package ps.reso.instaeclipse.mods.ghost;

import android.app.Activity;
import android.app.Notification;
import android.widget.Toast;

import java.util.Set;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;

/**
 * Block incoming Instagram calls (voice/video DM calls), hidden but still a missed call in history.
 *
 * <p>When blocked, the incoming-call screen and ring never appear. The call is not declined and
 * nothing is sent to the caller — it rings out on their side and lands in your chat as a missed
 * call, exactly as if you had not picked up. The missed-call record is written by Instagram's
 * signaling layer, which we never touch, so history is intact.
 *
 * <p>Scope:
 * <ul>
 *   <li>Master {@link FeatureFlags#blockIncomingCalls} on, "specific chats only" off → every call.
 *   <li>Master on + {@link FeatureFlags#blockCallsSpecificOnly} on → only calls whose caller is a
 *       chat on {@link FeatureFlags#blockCallsThreads}. That set holds chat display <b>names</b>
 *       (added from each chat's top-left menu), because — on IG 452 — the incoming call exposes the
 *       caller's display name but no DM thread id (the thread id is in neither the call activity's
 *       intent nor the ring notification; the rsys {@code RingNotification.createFromMcfType} hook
 *       never fires). The notification's {@code android.title}/{@code android.callPerson} name is the
 *       one caller identifier available at call time, and it equals the chat header title.
 * </ul>
 *
 * <p>Two guarded hooks:
 * <ol>
 *   <li>{@code NotificationManager.notify}: the incoming ring is a full-screen CATEGORY_CALL
 *       notification. We read the caller name from it; if blocked we drop the notification and note
 *       the time (the "a blocked call just rang" signal). Active/outgoing calls have no full-screen
 *       intent, so they're never touched.
 *   <li>{@code RtcCallActivity.onCreate}: the in-app call screen. With the master switch on and
 *       "specific only" off we finish() it for every incoming call; with "specific only" on we
 *       finish() it only when a blocked ring was just seen by hook 1.
 * </ol>
 *
 * Default OFF; guarded throughout — on any doubt Instagram behaves normally.
 */
public final class BlockIncomingCallsHook {

    private static final String TAG = "(IE|BlockCalls) ";
    private static final long RING_WINDOW_MS = 12_000L;

    /** When a ring that should be blocked was last seen (the signal the call screen waits for). */
    private static volatile long blockedRingAt;
    private static boolean loggedActivity, loggedNotif, loggedMenu;

    public void install(ClassLoader cl) {
        // The per-chat entry is added to the DM thread menu from ConversationMenuHook's per-thread
        // scan (see injectMenu), which runs reliably whenever the thread header is laid out.
        boolean any = false;

        // 1) the incoming ring notification — also our caller identity + "a call just rang" signal
        try {
            Class<?> nm = XposedHelpers.findClass("android.app.NotificationManager", cl);
            XposedBridge.hookAllMethods(nm, "notify", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!enabled()) return;
                    try {
                        Notification n = lastNotification(param.args);
                        if (n == null || !isIncomingCallRing(n)) return;
                        String caller = callerName(n);
                        if (!isBlocked(caller)) return;
                        param.setResult(null);                      // drop the ring
                        blockedRingAt = System.currentTimeMillis(); // tell the call screen to finish
                        if (!loggedNotif) {
                            loggedNotif = true;
                            ModuleLog.line(TAG + "✅ dropped an incoming-call ring (caller \"" + caller + "\")");
                        }
                    } catch (Throwable ignored) {}
                }
            });
            any = true;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ notification hook unavailable: " + t);
        }

        // 2) the in-app incoming-call screen
        try {
            Class<?> act = XposedHelpers.findClass("com.instagram.rtc.activity.RtcCallActivity", cl);
            XposedBridge.hookAllMethods(act, "onCreate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!enabled()) return;
                    try {
                        // Global: finish every incoming call. Per-chat: finish only when hook 1 just
                        // saw a blocked caller's ring (the call screen carries no caller identity).
                        boolean block = !FeatureFlags.blockCallsSpecificOnly || recentBlockedRing();
                        if (!block) return;
                        if (param.thisObject instanceof Activity a) {
                            a.finish();
                            if (!loggedActivity) {
                                loggedActivity = true;
                                ModuleLog.line(TAG + "✅ blocked an incoming call screen");
                            }
                        }
                    } catch (Throwable ignored) {
                        // never break the call UI
                    }
                }
            });
            any = true;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ call screen unavailable: " + t);
        }

        if (any) {
            FeatureStatusTracker.setHooked("BlockIncomingCalls");
            ModuleLog.line(TAG + "✅ installed");
        } else {
            ModuleLog.line(TAG + "❌ no hook point attached");
        }
    }

    /** Adds (or refreshes) the per-chat "Block/Unblock incoming calls" entry. True once placed. */
    static boolean injectMenu(Activity a) {
        try {
            int headerId = a.getResources().getIdentifier("direct_thread_header", "id", a.getPackageName());
            if (headerId == 0 || a.findViewById(headerId) == null) return false;
            String name = chatKey(a);
            boolean blocked = name != null && FeatureFlags.blockCallsThreads.contains(name);
            int label = blocked ? R.string.ie_unblock_calls_this_chat : R.string.ie_block_calls_this_chat;
            boolean added = ThreadHeaderMenu.add(a, "blockcalls", I18n.t(a, label), () -> toggleBlock(a));
            if (added && !loggedMenu) {
                loggedMenu = true;
                ModuleLog.line(TAG + "✅ added per-chat entry to the thread menu");
            }
            return added;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ menu inject: " + t);
            return true;
        }
    }

    /** Flips this chat's block state (keyed on its display name), persists it, keeps the label in sync. */
    static void toggleBlock(Activity a) {
        String name = chatKey(a);
        if (name == null) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(
                    a, I18n.t(a, R.string.ig_hide_chat_no_thread), Toast.LENGTH_SHORT).show();
            return;
        }
        Set<String> set = FeatureFlags.blockCallsThreads;
        boolean nowBlocked;
        if (set.contains(name)) { set.remove(name); nowBlocked = false; }
        else { set.add(name); nowBlocked = true; }
        ps.reso.instaeclipse.utils.core.SettingsManager.saveAllFlags();
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(a,
                I18n.t(a, nowBlocked ? R.string.ie_block_calls_chat_on : R.string.ie_block_calls_chat_off),
                Toast.LENGTH_SHORT).show();
        ThreadHeaderMenu.add(a, "blockcalls",
                I18n.t(a, nowBlocked ? R.string.ie_unblock_calls_this_chat : R.string.ie_block_calls_this_chat),
                () -> toggleBlock(a));
    }

    /** The open chat's display name (its header title), the key we match incoming calls against. */
    private static String chatKey(Activity a) {
        String t = ConversationMenuHook.chatTitle(a);
        return t == null || t.trim().isEmpty() ? null : t.trim();
    }

    /** The feature is active when the master switch is on. */
    private static boolean enabled() {
        return FeatureFlags.blockIncomingCalls;
    }

    /**
     * Master off → nothing blocked. Master on, "specific chats only" off → every call. Master on,
     * "specific chats only" on → only callers whose display name is on {@link
     * FeatureFlags#blockCallsThreads}.
     */
    private static boolean isBlocked(String caller) {
        if (!FeatureFlags.blockIncomingCalls) return false;
        if (!FeatureFlags.blockCallsSpecificOnly) return true;
        Set<String> list = FeatureFlags.blockCallsThreads;
        return caller != null && list != null && list.contains(caller.trim());
    }

    private static boolean recentBlockedRing() {
        return blockedRingAt > 0 && System.currentTimeMillis() - blockedRingAt < RING_WINDOW_MS;
    }

    /** The caller's display name from the CallStyle ring notification. */
    private static String callerName(Notification n) {
        try {
            if (n.extras != null) {
                CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                if (t != null && t.length() > 0) return t.toString().trim();
                Object p = n.extras.get("android.callPerson");
                if (p instanceof android.app.Person per && per.getName() != null) return per.getName().toString().trim();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** notify(int, Notification) / notify(String, int, Notification): the Notification is the last arg. */
    private static Notification lastNotification(Object[] args) {
        if (args == null) return null;
        for (int i = args.length - 1; i >= 0; i--)
            if (args[i] instanceof Notification) return (Notification) args[i];
        return null;
    }

    /** The incoming ring: a call notification with a full-screen intent, not a foreground-service one. */
    private static boolean isIncomingCallRing(Notification n) {
        if (!Notification.CATEGORY_CALL.equals(n.category)) return false;
        if (n.fullScreenIntent == null) return false;
        return (n.flags & Notification.FLAG_FOREGROUND_SERVICE) == 0;
    }
}
