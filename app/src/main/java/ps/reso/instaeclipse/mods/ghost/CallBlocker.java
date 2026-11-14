package ps.reso.instaeclipse.mods.ghost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;

/**
 * Decides whether an incoming Instagram call rings (incoming-call blocker).
 *
 * <p>Instagram turns an incoming call into its ring in one method (see {@link CallBlockerHook}),
 * which this is asked first with the call object. A yes skips the ring, so the phone stays quiet and
 * nothing is sent to Instagram: the caller hears a call nobody picks up.
 *
 * <p>The call object is one of Instagram's own data classes; only its {@code toString()} is read,
 * which looks like {@code RtcCallConnectionEntity(state=Incoming, callerId=..., callerName=...,
 * displayUri=...)}. Only a ringing call to you counts; everything else (ended calls, live invites,
 * calls moved from another device) rings as usual.
 *
 * <p>Four modes: 0 everyone (off), 1 nobody, 2 only the list, 3 everyone except the list. The list
 * holds caller names (matched case-insensitively) or numeric user ids, one per line. Fails open:
 * anything unexpected lets the call ring, and a ringing call whose caller can't be read also rings,
 * so a list can never silence someone you meant to hear.
 */
public final class CallBlocker {

    public static final int EVERYONE   = 0; // off
    public static final int NOBODY     = 1;
    public static final int ONLY_LIST  = 2;
    public static final int EXCEPT_LIST = 3;

    /** How a ringing call's text starts; the class name that says it's a call at all. */
    private static final String RINGING = "RtcCallConnectionEntity(state=Incoming,";

    private static final String CALLER_ID   = ", callerId=";
    private static final String CALLER_NAME = ", callerName=";
    private static final String AFTER_NAME  = ", displayUri=";

    private CallBlocker() {}

    private static boolean usesList(int mode) {
        return mode == ONLY_LIST || mode == EXCEPT_LIST;
    }

    /** Asked first with each call Instagram is about to ring. True means don't ring. Never throws. */
    public static boolean drop(Object call) {
        try {
            int mode = FeatureFlags.callBlockMode;
            if (call == null || mode == EVERYONE) return false;
            String text = String.valueOf(call);
            if (!text.startsWith(RINGING)) return false;
            return drops(mode, text, usesList(mode) ? accounts(FeatureFlags.callBlockAccounts) : Collections.emptyList());
        } catch (Throwable failure) {
            return false; // fail open: let the call ring
        }
    }

    /** Whether a ringing call with this text stays quiet. A caller the list can't be checked against rings. */
    static boolean drops(int mode, String text, List<String> list) {
        if (mode == NOBODY) return true;
        String rawName = between(text, CALLER_NAME, AFTER_NAME);
        String name = rawName == null ? null : rawName.toLowerCase(Locale.ROOT);
        String id = between(text, CALLER_ID, ", ");
        boolean known = name != null || id != null;
        if (!known) return false; // caller unreadable → let it ring
        boolean listed = (name != null && list.contains(name)) || (id != null && list.contains(id));
        return mode == ONLY_LIST ? !listed : listed;
    }

    /** What sits between two markers in {@code text}, or null when there's nothing there or it says null. */
    static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        if (from < 0) return null;
        from += start.length();
        int to = text.indexOf(end, from);
        if (to <= from) return null;
        String value = text.substring(from, to).trim();
        return value.isEmpty() || "null".equals(value) ? null : value;
    }

    /** Usernames/ids from the stored list: one per line (commas work too), trimmed of a leading @, lower-cased, deduped. */
    static List<String> accounts(String stored) {
        if (stored == null || stored.trim().isEmpty()) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (String piece : stored.split("[,\\n\\r]")) {
            String name = normalize(piece);
            if (name != null && !names.contains(name)) names.add(name);
        }
        return names;
    }

    private static String normalize(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        while (trimmed.startsWith("@")) trimmed = trimmed.substring(1).trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
