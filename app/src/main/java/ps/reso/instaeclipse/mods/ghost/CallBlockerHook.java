package ps.reso.instaeclipse.mods.ghost;

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
 * Incoming-call blocker. Instagram turns an incoming call into its ring (and full-screen screen) in
 * one method. On IG 451 that is
 * {@code LX/0kLJ->A01(Landroid/content/Context;LX/0qlq;Lcom/instagram/common/session/UserSession;Lcom/instagram/rtc/signaling/models/RtcConnectionEntity;Z)V}.
 * The obfuscated class ({@code 0kLJ}) changes per version, so the method is anchored by DexKit:
 * the one method that (a) uses the string {@code "rtc_call_activity_intent_action_incoming_call"},
 * (b) takes a {@code com.instagram.rtc.signaling.models.RtcConnectionEntity} parameter, and
 * (c) returns void. That resolves to exactly one method on 451/452.
 *
 * <p>In the hook, {@link CallBlocker#drop(Object)} inspects the {@code RtcConnectionEntity} arg's
 * {@code toString()} and decides by mode + list; when it says to block, {@code setResult(null)} skips
 * the ring (the method is void). Fails open on any error — the call rings.
 */
public final class CallBlockerHook {

    private static final String TAG = "(IE|CallBlocker) ";
    private static final String CACHE_KEY = "CallBlocker_ring";
    private static final String CACHE_KEY_ARG = "CallBlocker_ring_arg";

    /** The action an incoming call opens its screen with; only its ring loads it. */
    private static final String INCOMING_CALL_ACTION = "rtc_call_activity_intent_action_incoming_call";
    /** The call object Instagram rings with (a class it keeps by name, stable across versions). */
    private static final String CALL_ENTITY = "com.instagram.rtc.signaling.models.RtcConnectionEntity";

    private CallBlockerHook() {}

    public static void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            Method ring = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(CACHE_KEY, cl) : null;
            int argIndex = parseInt(DexKitCache.isCacheValid() ? DexKitCache.loadString(CACHE_KEY_ARG) : null);

            if (ring == null || argIndex < 0) {
                MethodData found = resolve(bridge);
                if (found == null) {
                    ModuleLog.line(TAG + "❌ incoming-call ring method not found");
                    if (FeatureFlags.callBlockMode != 0) FeatureStatusTracker.setBroken("CallBlocker");
                    return;
                }
                ring = found.getMethodInstance(cl);
                argIndex = entityArgIndex(ring);
                if (argIndex < 0) {
                    ModuleLog.line(TAG + "❌ no RtcConnectionEntity parameter on " + found.getClassName() + "." + found.getName());
                    if (FeatureFlags.callBlockMode != 0) FeatureStatusTracker.setBroken("CallBlocker");
                    return;
                }
                DexKitCache.saveMethod(CACHE_KEY, ring);
                DexKitCache.saveString(CACHE_KEY_ARG, String.valueOf(argIndex));
            }

            final int call = argIndex;
            XposedBridge.hookMethod(ring, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    try {
                        if (FeatureFlags.callBlockMode == 0) return;
                        if (param.args == null || call >= param.args.length) return;
                        if (CallBlocker.drop(param.args[call])) {
                            param.setResult(null); // void: the call never rings
                            ModuleLog.line(TAG + "blocked an incoming call");
                        }
                    } catch (Throwable ignored) {
                        // fail open: let the call ring
                    }
                }
            });
            FeatureStatusTracker.setHooked("CallBlocker");
            ModuleLog.line(TAG + "✅ " + ring.getDeclaringClass().getName() + "." + ring.getName() + " (arg " + call + ")");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
            if (FeatureFlags.callBlockMode != 0) FeatureStatusTracker.setBroken("CallBlocker");
        }
    }

    /** The one void method using the incoming-call string and taking an RtcConnectionEntity. */
    private static MethodData resolve(DexKitBridge bridge) {
        List<MethodData> candidates = DexKitQueryCache.findMethod(bridge, FindMethod.create()
                .matcher(MethodMatcher.create().usingStrings(INCOMING_CALL_ACTION)));
        MethodData match = null;
        for (MethodData m : candidates) {
            if (!"void".equals(String.valueOf(m.getReturnType()))) continue;
            boolean hasEntity = false;
            for (Object pt : m.getParamTypes()) {
                if (CALL_ENTITY.equals(normalizeType(String.valueOf(pt)))) { hasEntity = true; break; }
            }
            if (!hasEntity) continue;
            if (match != null) {
                ModuleLog.line(TAG + "⚠️ more than one candidate; using first: " + match.getClassName() + "." + match.getName());
                break;
            }
            match = m;
        }
        return match;
    }

    /** Normalizes a DexKit param-type string to a dotted class name ("Lp/kg;" and "p/kg" → "p.kg"). */
    private static String normalizeType(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("L") && s.endsWith(";")) s = s.substring(1, s.length() - 1);
        return s.replace('/', '.');
    }

    private static int parseInt(String s) {
        if (s == null) return -1;
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return -1; }
    }

    /** Index of the first parameter assignable to RtcConnectionEntity, or -1. */
    private static int entityArgIndex(Method ring) {
        Class<?>[] params = ring.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (params[i].getName().contains("RtcConnectionEntity")) return i;
        }
        return -1;
    }
}
