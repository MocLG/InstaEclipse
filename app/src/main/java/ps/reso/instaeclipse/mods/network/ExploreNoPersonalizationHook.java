package ps.reso.instaeclipse.mods.network;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Explore without personalization: ask Instagram for the non-personalized Explore grid on every
 * fetch. Instagram otherwise only serves that grid where the EU's Digital Services Act requires it.
 *
 * <p>The topical_explore request (discover/topical_explore/) is assembled by one static builder
 * that adds the form param {@code is_nonpersonalized_explore} from a {@code Boolean} argument. On IG
 * 451/452 that is the builder's 2nd {@code java.lang.Boolean} parameter (right after
 * {@code enable_adaptive_grid}); it holds the user's own choice, defaulting to not-nonpersonalized.
 * DexKit finds the builder by the literal {@code is_nonpersonalized_explore}; the hook flips its 2nd
 * Boolean argument to {@code TRUE} before the request is built, so the param goes out as true.
 *
 * <p>Fails open: with the switch off, the builder not found, an unexpected shape, or anything
 * thrown, Instagram's own value stands. This is a request-param injection, not a URL drop, so it
 * lives outside {@link IGNetworkInterceptor} (which rewrites the resolved URI, not the POST body).
 */
public final class ExploreNoPersonalizationHook {

    private static final String TAG = "(IE|ExploreNoPersonalization) ";
    private static final String CACHE_KEY = "ExploreNoPersonalization_builder";
    private static final String ANCHOR = "is_nonpersonalized_explore";

    private ExploreNoPersonalizationHook() {}

    public static void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            Method found = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(CACHE_KEY, cl) : null;
            if (found == null) {
                for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(
                        MethodMatcher.create().usingStrings(ANCHOR)))) {
                    Method m = md.getMethodInstance(cl);
                    // The builder carries at least two Boolean params (adaptive-grid + nonpersonalized).
                    if (booleanParamCount(m) >= 2) {
                        found = m;
                        break;
                    }
                }
                if (found == null) {
                    ModuleLog.line(TAG + "❌ explore request builder not found for \"" + ANCHOR + "\"");
                    return;
                }
                DexKitCache.saveMethod(CACHE_KEY, found);
            }
            final Method builder = found;
            // The index of the 2nd Boolean parameter: the nonpersonalized flag on IG 451/452.
            final int flagArg = secondBooleanIndex(builder);
            if (flagArg < 0) {
                ModuleLog.line(TAG + "❌ no 2nd Boolean param on " + builder.getName());
                return;
            }
            XposedBridge.hookMethod(builder, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    try {
                        if (!FeatureFlags.exploreNoPersonalization) return;
                        if (param.args != null && flagArg < param.args.length) {
                            param.args[flagArg] = Boolean.TRUE;
                        }
                    } catch (Throwable ignored) {
                        // A malformed request must never crash IG's Explore load.
                    }
                }
            });
            FeatureStatusTracker.setHooked("ExploreNoPersonalization");
            ModuleLog.line(TAG + "✅ " + builder.getDeclaringClass().getName() + "." + builder.getName()
                    + " (arg " + flagArg + ")");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    private static int booleanParamCount(Method m) {
        int n = 0;
        for (Class<?> p : m.getParameterTypes()) if (p == Boolean.class) n++;
        return n;
    }

    /** Index of the 2nd {@code java.lang.Boolean} parameter (static method: args align with params), or -1. */
    private static int secondBooleanIndex(Method m) {
        Class<?>[] types = m.getParameterTypes();
        int seen = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == Boolean.class && ++seen == 2) return i;
        }
        return -1;
    }
}
