package ps.reso.instaeclipse.mods.feed;

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
 * Show hidden like counts: reveal the exact like count when Instagram would otherwise render
 * "liked by <user> and others" (because the viewer turned on "Hide like and view counts", or the
 * post carries the hidden flag) while the server still sent the real number.
 *
 * Mechanism (mirrors HushGram's approach, re-implemented as a runtime getter hook): the feed
 * like-row rendering asks the Media model whether like/view counts are disabled via a no-arg
 * Boolean getter that reads the "like_and_view_counts_disabled" field (IG 451: Media.A4L). When it
 * returns true, IG draws the "and others" text instead of the number. We hook that getter and, only
 * when a real count actually arrived (the no-arg Integer "like_count" getter — IG 451: Media.A6U —
 * returns a positive value), force it to report false so IG renders the number it already has. The
 * count itself is never fabricated: posts whose count the server withheld (count null / 0) are left
 * untouched, so nothing shows "0 likes".
 *
 * Anchors are version-independent: both getters are located by their exact field-name string on the
 * stable, unobfuscated com.instagram.feed.media.Media (fallback: LiveTreeMediaDict on IG <= 446).
 */
public class ShowHiddenLikesHook {

    private static final String TAG = "(InstaEclipse | ShowHiddenLikes): ";
    private static final String CACHE_KEY_DISABLED = "ShowHiddenLikes_disabledGetter";
    private static final String CACHE_KEY_COUNT = "ShowHiddenLikes_countGetter";

    // Owners to try, in order; both getters must resolve on the same owner so thisObject matches.
    private static final String[] OWNERS = {
            "com.instagram.feed.media.Media",
            "com.instagram.feed.media.LiveTreeMediaDict"};

    private static volatile Method countGetter;

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            Method disabledGetter = null;

            if (DexKitCache.isCacheValid()) {
                disabledGetter = DexKitCache.loadMethod(CACHE_KEY_DISABLED, classLoader);
                countGetter = DexKitCache.loadMethod(CACHE_KEY_COUNT, classLoader);
                if (disabledGetter == null || countGetter == null) {
                    disabledGetter = null;
                    countGetter = null;
                }
            }

            if (disabledGetter == null) {
                for (String owner : OWNERS) {
                    Method d = findGetter(bridge, classLoader, owner, "like_and_view_counts_disabled", "java.lang.Boolean");
                    if (d == null) continue;
                    Method c = findGetter(bridge, classLoader, owner, "like_count", "java.lang.Integer");
                    if (c == null) continue;
                    disabledGetter = d;
                    countGetter = c;
                    DexKitCache.saveMethod(CACHE_KEY_DISABLED, d);
                    DexKitCache.saveMethod(CACHE_KEY_COUNT, c);
                    break;
                }
            }

            if (disabledGetter == null || countGetter == null) {
                ModuleLog.line(TAG + "❌ like-count getters not found");
                return;
            }

            final Method count = countGetter;
            disabledGetter.setAccessible(true);
            count.setAccessible(true);

            XposedBridge.hookMethod(disabledGetter, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.showHiddenLikes) return;
                    // Only act when IG intends to hide the count.
                    if (!Boolean.TRUE.equals(param.getResult())) return;
                    try {
                        Object c = count.invoke(param.thisObject);
                        // Reveal only when a real count was sent (never fabricate "0 likes").
                        if (c instanceof Integer && (Integer) c > 0) param.setResult(Boolean.FALSE);
                    } catch (Throwable ignored) {
                        // Fail open: leave IG's original decision untouched.
                    }
                }
            });

            ModuleLog.line(TAG + "✅ Hooked " + disabledGetter.getDeclaringClass().getName() + "."
                    + disabledGetter.getName() + " (count via " + count.getName() + ")");
            FeatureStatusTracker.setHooked("ShowHiddenLikes");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    private static Method findGetter(DexKitBridge bridge, ClassLoader classLoader,
                                     String owner, String fieldString, String returnType) {
        try {
            List<MethodData> found = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(
                    MethodMatcher.create()
                            .declaredClass(owner)
                            .usingEqStrings(List.of(fieldString))
                            .returnType(returnType)
                            .paramCount(0)));
            if (found.size() != 1) return null;
            return found.get(0).getMethodInstance(classLoader);
        } catch (Throwable t) {
            return null;
        }
    }
}
