package ps.reso.instaeclipse.patcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.toast.IeToast;

import rikka.shizuku.Shizuku;

/**
 * Shared "enable Instagram link handling" flow used by the patch success screen and the Help tab.
 * A re-signed build fails Android App Links verification, so its supported web addresses are off
 * and a non-root app can't approve them itself. This tries Shizuku first (runs the shell-only
 * {@code pm set-app-links-user-selection} to auto-enable every domain, no manual toggle); if
 * Shizuku isn't running or is denied, it opens the system "Open by default" screen as a fallback.
 */
public final class AppLinkFixer {

    private AppLinkFixer() {}

    /** The installed Instagram-family package, or null if none is installed. */
    public static String installedHost(Activity a) {
        for (String pkg : CommonUtils.SUPPORTED_PACKAGES) {
            if (a.getPackageManager().getLaunchIntentForPackage(pkg) != null) return pkg;
        }
        return null;
    }

    /** True if an Instagram-family app is installed (so the action is worth showing). */
    public static boolean hostInstalled(Activity a) {
        return installedHost(a) != null;
    }

    public static void fix(Activity activity) {
        String pkg = installedHost(activity);
        if (pkg == null) {
            IeToast.makeText(activity, activity.getString(R.string.not_installed_instagram), Toast.LENGTH_SHORT).show();
            return;
        }
        if (ShizukuLinks.hasPermission()) { enableViaShizuku(activity, pkg); return; }
        if (ShizukuLinks.available()) { requestThenEnable(activity, pkg); return; }
        openSettings(activity, pkg);
    }

    private static void requestThenEnable(Activity activity, String pkg) {
        final Shizuku.OnRequestPermissionResultListener[] holder = new Shizuku.OnRequestPermissionResultListener[1];
        holder[0] = (requestCode, grantResult) -> {
            if (requestCode != ShizukuLinks.PERMISSION_REQUEST_CODE) return;
            try { Shizuku.removeRequestPermissionResultListener(holder[0]); } catch (Throwable ignored) {}
            if (grantResult == PackageManager.PERMISSION_GRANTED) enableViaShizuku(activity, pkg);
            else openSettings(activity, pkg);
        };
        try { Shizuku.addRequestPermissionResultListener(holder[0]); } catch (Throwable ignored) {}
        ShizukuLinks.requestPermission();
    }

    private static void enableViaShizuku(Activity activity, String pkg) {
        IeToast.makeText(activity, activity.getString(R.string.patch_links_enabling), Toast.LENGTH_SHORT).show();
        new Thread(() -> ShizukuLinks.enable(pkg, (ok, message) -> activity.runOnUiThread(() -> {
            if (ok) {
                IeToast.makeText(activity, activity.getString(R.string.patch_links_enabled), Toast.LENGTH_LONG).show();
            } else {
                openSettings(activity, pkg);   // fall back to the settings screen if the command failed
            }
        })), "ie-applinks").start();
    }

    private static void openSettings(Activity activity, String pkg) {
        Uri uri = Uri.parse("package:" + pkg);
        try {
            Intent intent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, uri)
                    : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri);
            activity.startActivity(intent);
        } catch (Throwable t) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri));
            } catch (Throwable ignored) {}
        }
    }
}
