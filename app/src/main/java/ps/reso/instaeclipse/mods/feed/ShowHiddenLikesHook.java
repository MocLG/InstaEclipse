package ps.reso.instaeclipse.mods.feed;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Show hidden like counts: reveal the exact like count when a poster hid it but Instagram's server
 * still sent the number.
 *
 * <p>IG's feed like-row, reel config, grid and likes-text all ask ONE decider method whether to hide
 * a post's count, passing (UserSession, …, posterId String, boolean hidden) and getting back a
 * boolean. Hooking the Media "like_and_view_counts_disabled" getter does nothing, because the rows
 * read that flag straight off the Pando data tree (by field-hash) and hand it to the decider — the
 * getter is never consulted for the drawn text. So we hook the decider itself and force it to answer
 * "not hidden" while the switch is on. IG's own rows then draw a number only when the post's count is
 * above zero, so nothing is fabricated.
 *
 * <p>The decider is anchored version-independently: find every method that uses the field-name hash
 * of {@code like_and_view_counts_disabled} (the rows' tree read) and take the boolean
 * (UserSession, …, String, boolean) method they invoke — on IG 450-452 that resolves to exactly one.
 */
public class ShowHiddenLikesHook {

    private static final String TAG = "(InstaEclipse | ShowHiddenLikes): ";
    private static final String CACHE_KEY_DECIDER = "ShowHiddenLikes_decider_v2";
    private static final int FLAG_KEY = "like_and_view_counts_disabled".hashCode(); // -1301662067
    private static final String USER_SESSION = "com.instagram.common.session.UserSession";

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            Method decider = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(CACHE_KEY_DECIDER, classLoader) : null;
            if (decider == null) {
                decider = findDecider(bridge, classLoader);
                if (decider != null) DexKitCache.saveMethod(CACHE_KEY_DECIDER, decider);
            }
            if (decider == null) {
                ModuleLog.line(TAG + "❌ like-count decider not found");
                return;
            }
            final int flagIndex = decider.getParameterTypes().length - 1; // last param = the hidden flag
            XposedBridge.hookMethod(decider, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.showHiddenLikes) return;
                    try {
                        if (flagIndex >= 0 && flagIndex < param.args.length
                                && Boolean.TRUE.equals(param.args[flagIndex])) {
                            // Answer "not hidden"; IG still draws a number only when the count > 0.
                            param.setResult(Boolean.FALSE);
                        }
                    } catch (Throwable ignored) {
                        // Fail open: leave IG's own decision.
                    }
                }
            });
            ModuleLog.line(TAG + "✅ Hooked decider " + decider.getDeclaringClass().getName() + "."
                    + decider.getName() + " (flag arg " + flagIndex + ")");
            FeatureStatusTracker.setHooked("ShowHiddenLikes");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    /**
     * The boolean {@code (UserSession, …, String, boolean)} method that the flag-reading like-rows
     * invoke most — the shared count-hide decider.
     */
    private static Method findDecider(DexKitBridge bridge, ClassLoader classLoader) {
        List<MethodData> readers = bridge.findMethod(FindMethod.create().matcher(
                MethodMatcher.create().usingNumbers(FLAG_KEY)));
        Map<String, Integer> tally = new HashMap<>();
        Map<String, MethodData> byKey = new HashMap<>();
        for (MethodData reader : readers) {
            List<MethodData> invokes;
            try {
                invokes = reader.getInvokes();
            } catch (Throwable t) {
                continue;
            }
            for (MethodData inv : invokes) {
                try {
                    if (!"boolean".equals(inv.getReturnTypeName())) continue;
                    List<String> p = inv.getParamTypeNames();
                    int n = p.size();
                    if (n < 3 || n > 4) continue;
                    if (!USER_SESSION.equals(p.get(0))) continue;
                    if (!"boolean".equals(p.get(n - 1))) continue;
                    if (!"java.lang.String".equals(p.get(n - 2))) continue;
                    String key = inv.toString();
                    byKey.put(key, inv);
                    tally.merge(key, 1, Integer::sum);
                } catch (Throwable ignored) {
                }
            }
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : tally.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        if (best == null) return null;
        try {
            return byKey.get(best).getMethodInstance(classLoader);
        } catch (Throwable t) {
            return null;
        }
    }
}
