package ps.reso.instaeclipse.patcher;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.ExportBrand;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;

/**
 * No-root patcher screen. The user picks their Instagram APK(s), the pipeline patches and re-signs
 * them keeping Instagram's original signature, and the system installer is fired on success. All
 * heavy work runs off the UI thread; failures are shown, never crash the app. The determinate
 * progress bar tracks the pipeline, and the final install result (delivered asynchronously by the
 * system) flips the screen into an "installed" state with an Open Instagram shortcut.
 */
public class PatchInstagramActivity extends AppCompatActivity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView logView;
    private TextView selectionView;
    private TextView stepView;
    private TextView percentView;
    private MaterialButton runButton;
    private MaterialButton chooseButton;
    private MaterialButton getApkButton;
    private MaterialButton openButton;
    private MaterialButton fixLinksButton;
    private View progressRow;
    private View successCard;
    private LinearProgressIndicator progress;

    private final List<Uri> chosen = new ArrayList<>();
    private boolean busy;
    /** True between committing the install session and the system reporting its result. */
    private boolean awaitingInstall;

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::onPicked);

    private final PatchInstallReceiver.StatusCallback installStatus = (success, message) ->
            main.post(() -> onInstallFinished(success, message));

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patch_instagram);

        MaterialToolbar toolbar = findViewById(R.id.patch_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        logView = findViewById(R.id.patch_log);
        selectionView = findViewById(R.id.patch_selection);
        stepView = findViewById(R.id.patch_step);
        percentView = findViewById(R.id.patch_percent);
        runButton = findViewById(R.id.patch_run_button);
        chooseButton = findViewById(R.id.patch_choose_button);
        getApkButton = findViewById(R.id.patch_get_apk_button);
        openButton = findViewById(R.id.patch_open_button);
        fixLinksButton = findViewById(R.id.patch_fix_links_button);
        progressRow = findViewById(R.id.patch_progress_row);
        successCard = findViewById(R.id.patch_success_card);
        progress = findViewById(R.id.patch_progress);

        chooseButton.setOnClickListener(v -> {
            if (!busy) picker.launch(new String[]{"application/vnd.android.package-archive", "*/*"});
        });
        runButton.setOnClickListener(v -> {
            if (!busy) start();
        });
        getApkButton.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ExportBrand.TESTED_INSTAGRAM_APK)));
            } catch (Throwable t) {
                IeToast.makeText(this, getString(R.string.patch_failed, String.valueOf(t)), Toast.LENGTH_SHORT).show();
            }
        });
        openButton.setOnClickListener(v -> openInstagram());
        fixLinksButton.setOnClickListener(v -> AppLinkFixer.fix(this));

        PatchInstallReceiver.setCallback(installStatus);
    }

    private void onPicked(@NonNull List<Uri> uris) {
        chosen.clear();
        chosen.addAll(uris);
        if (chosen.isEmpty()) {
            selectionView.setText(R.string.patch_no_selection);
            runButton.setEnabled(false);
            return;
        }
        // A fresh pick clears any previous "installed" state so the user can patch again.
        successCard.setVisibility(View.GONE);
        runButton.setText(R.string.patch_run);
        StringBuilder names = new StringBuilder();
        for (Uri uri : chosen) {
            if (names.length() > 0) names.append(", ");
            names.append(displayName(uri));
        }
        selectionView.setText(getString(R.string.patch_selected, chosen.size(), names.toString()));
        runButton.setEnabled(true);
    }

    private void start() {
        if (chosen.isEmpty()) {
            IeToast.makeText(this, getString(R.string.patch_need_apk), Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        awaitingInstall = false;
        runButton.setEnabled(false);
        chooseButton.setEnabled(false);
        getApkButton.setEnabled(false);
        successCard.setVisibility(View.GONE);
        progressRow.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        updateProgress(0);
        logView.setText("");
        final List<Uri> inputs = new ArrayList<>(chosen);

        worker.execute(() -> {
            File work = new File(getCacheDir(), "iepatch/in");
            try {
                ApkPatcher.deleteRecursively(work);
                if (!work.mkdirs() && !work.isDirectory()) throw new Exception("Cannot create work folder");

                log(getString(R.string.patch_step_copy));
                uiLogger.progress(3);
                List<File> apks = materialize(inputs, work);
                if (apks.isEmpty()) throw new Exception(getString(R.string.patch_err_no_apk));
                uiLogger.progress(8);

                List<File> patched = ApkPatcher.patch(this, apks, uiLogger);
                log(getString(R.string.patch_step_install));
                PatchInstaller.install(this, patched, uiLogger);
                // The session is committed; the real result arrives later via installStatus.
                log(getString(R.string.patch_step_done));
                awaitingInstall = true;
                main.post(() -> stepView.setText(R.string.patch_step_waiting));
            } catch (Throwable t) {
                ModuleLog.line("(IE|Patch) failed", t);
                String reason = t.getMessage() != null ? t.getMessage() : t.toString();
                log(getString(R.string.patch_failed, reason));
                main.post(() -> {
                    stepView.setText(R.string.patch_state_failed);
                    IeToast.makeText(this, getString(R.string.patch_failed, reason), Toast.LENGTH_LONG).show();
                });
            } finally {
                ApkPatcher.deleteRecursively(work);
                main.post(() -> {
                    // When waiting on the installer, keep the UI locked until the result lands.
                    if (!awaitingInstall) {
                        busy = false;
                        progressRow.setVisibility(View.GONE);
                        progress.setVisibility(View.GONE);
                        chooseButton.setEnabled(true);
                        getApkButton.setEnabled(true);
                        runButton.setEnabled(true);
                    }
                });
            }
        });
    }

    /** Called on the main thread when the system reports the install result. */
    private void onInstallFinished(boolean success, String message) {
        awaitingInstall = false;
        busy = false;
        chooseButton.setEnabled(true);
        getApkButton.setEnabled(true);
        if (success) {
            updateProgress(100);
            stepView.setText(R.string.patch_installed_title);
            successCard.setVisibility(View.VISIBLE);
            runButton.setText(R.string.patch_installed);
            runButton.setEnabled(false);
        } else {
            progressRow.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
            runButton.setEnabled(true);
            String reason = message == null ? "" : message;
            log(getString(R.string.patch_install_failed, reason));
        }
    }

    private void openInstagram() {
        for (String pkg : CommonUtils.SUPPORTED_PACKAGES) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                startActivity(launch);
                return;
            }
        }
        IeToast.makeText(this, getString(R.string.not_installed_instagram), Toast.LENGTH_SHORT).show();
    }

    private void updateProgress(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        progress.setProgressCompat(clamped, true);
        percentView.setText(getString(R.string.patch_percent, clamped));
    }

    /** Logger handed to the pipeline: appends a step line and advances the determinate bar. */
    private final PatchLogger uiLogger = new PatchLogger() {
        @Override
        public void log(String line) {
            PatchInstagramActivity.this.log(line);
            main.post(() -> stepView.setText(line));
        }

        @Override
        public void progress(int percent) {
            main.post(() -> updateProgress(percent));
        }
    };

    /**
     * Copies each picked document to a cache file. A bundle (.apkm/.apks/.xapk, i.e. a zip whose
     * entries are .apk files) is unzipped into its contained APKs; a plain .apk is used as-is.
     */
    private List<File> materialize(List<Uri> uris, File work) throws Exception {
        List<File> result = new ArrayList<>();
        int index = 0;
        for (Uri uri : uris) {
            String name = sanitize(displayName(uri), index++);
            File copy = new File(work, name);
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("Cannot read " + name);
                writeTo(in, copy);
            }
            if (isBundle(copy)) {
                log(getString(R.string.patch_step_unbundle, copy.getName()));
                result.addAll(extractBundle(copy, work));
            } else {
                result.add(copy);
            }
        }
        return result;
    }

    private static boolean isBundle(File file) {
        String lower = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".apk")) return false;
        if (lower.endsWith(".apkm") || lower.endsWith(".apks") || lower.endsWith(".xapk")) return true;
        // Otherwise sniff: a zip that holds .apk entries is treated as a bundle.
        try (ZipInputStream zip = new ZipInputStream(new java.io.FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private List<File> extractBundle(File bundle, File work) throws Exception {
        List<File> apks = new ArrayList<>();
        File dir = new File(work, bundle.getName() + "-extracted");
        if (!dir.mkdirs() && !dir.isDirectory()) throw new Exception("Cannot unpack " + bundle.getName());
        try (ZipInputStream zip = new ZipInputStream(new java.io.FileInputStream(bundle))) {
            ZipEntry entry;
            int index = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String entryName = entry.getName();
                if (!entryName.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) continue;
                String flat = sanitize(new File(entryName).getName(), index++);
                File out = new File(dir, flat);
                writeTo(zip, out);
                apks.add(out);
            }
        }
        if (apks.isEmpty()) throw new Exception(getString(R.string.patch_err_no_apk));
        return apks;
    }

    private static void writeTo(InputStream in, File dest) throws Exception {
        try (OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (col >= 0) {
                    String name = cursor.getString(col);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "instagram.apk";
    }

    private static String sanitize(String name, int index) {
        String clean = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (clean.isEmpty()) clean = "input_" + index + ".apk";
        return index + "_" + clean;
    }

    private void log(String line) {
        ModuleLog.line("(IE|Patch) " + line);
        main.post(() -> {
            CharSequence current = logView.getText();
            logView.setText(current.length() == 0 ? line : current + "\n" + line);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PatchInstallReceiver.clearCallback(installStatus);
        worker.shutdownNow();
    }
}
