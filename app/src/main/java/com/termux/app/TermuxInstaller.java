package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;

import androidx.annotation.MainThread;
import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Map;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The embedded zip, containing entries relative to the $PREFIX, is extracted to staging:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) Extract regular files and restore only the owner-execute bits listed by EXECUTABLES.txt.
 * (5.3) Create symlinks after extraction and permission restoration.
 *
 * One application-owned job writes the prefix. Its activity observer owns only the dialog and
 * completion callback, and detaches on stop/destruction without interrupting extraction.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";
    private final Context mContext;
    private BootstrapJob mJob;

    TermuxInstaller(Context context) {
        mContext = context.getApplicationContext();
    }

    /** Performs bootstrap setup if necessary. */
    @MainThread
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        TermuxInstaller installer = ((TermuxApplication) activity.getApplication()).getBootstrapInstaller();
        if (installer.mJob != null) {
            installer.mJob.attach(activity, whenDone);
            return;
        }

        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        switch (readBootstrapState()) {
            case INSTALLED:
                whenDone.run();
                return;
            case EMPTY_PREFIX:
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
                break;
            case PATH_NOT_DIRECTORY:
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
                break;
            case MISSING:
                break;
        }

        installer.mJob = installer.new BootstrapJob();
        installer.mJob.attach(activity, whenDone);
        new Thread(installer.mJob, "TermuxBootstrap").start();
    }

    /** Detaches only this activity; filesystem work belongs to the application. */
    @MainThread
    static void detachBootstrapObserver(Activity activity) {
        TermuxInstaller installer = ((TermuxApplication) activity.getApplication()).getBootstrapInstaller();
        if (installer.mJob != null) installer.mJob.detach(activity);
    }

    private static final class Observer {
        final Activity activity;
        final Runnable whenDone;
        android.app.Dialog dialog;

        Observer(Activity activity, Runnable whenDone) {
            this.activity = activity;
            this.whenDone = whenDone;
        }

        void dismiss() {
            if (dialog != null) {
                dialog.dismiss();
                dialog = null;
            }
        }
    }

    private final class BootstrapJob implements Runnable {
        private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        private Observer observer;
        private String failure;

        void attach(Activity activity, Runnable whenDone) {
            if (observer != null) detach(observer.activity);
            observer = new Observer(activity, whenDone);
            if (failure == null)
                observer.dialog = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
            else showFailure(observer);
        }

        void detach(Activity activity) {
            if (observer == null || observer.activity != activity) return;
            Observer previous = observer;
            observer = null;
            previous.dismiss();
        }

        private void fail(String message) {
            Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);
            sendBootstrapCrashReportNotification(mContext, message);
            handler.post(() -> {
                failure = message;
                if (observer != null) {
                    observer.dismiss();
                    showFailure(observer);
                }
            });
        }

        private void showFailure(Observer current) {
            current.dialog = new AlertDialog.Builder(current.activity)
                .setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                    if (observer != current) return;
                    detach(current.activity);
                    mJob = null;
                    current.activity.finish();
                })
                .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                    if (observer != current) return;
                    detach(current.activity);
                    mJob = null;
                    FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    setupBootstrapIfNeeded(current.activity, current.whenDone);
                }).show();
        }

        @Override
        public void run() {
            try {
                Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");
                Error error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                if (error != null) { fail(Error.getErrorMarkdownString(error)); return; }
                error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                if (error != null) { fail(Error.getErrorMarkdownString(error)); return; }
                error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                if (error != null) { fail(Error.getErrorMarkdownString(error)); return; }
                error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                if (error != null) { fail(Error.getErrorMarkdownString(error)); return; }

                Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");
                Map<File, String> symlinks = BootstrapArchive.extract(
                    new ByteArrayInputStream(loadZipBytes()), TERMUX_STAGING_PREFIX_DIR);
                for (Map.Entry<File, String> symlink : symlinks.entrySet())
                    Os.symlink(symlink.getValue(), symlink.getKey().getAbsolutePath());

                Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");
                if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR))
                    throw new RuntimeException("Moving termux prefix staging to prefix directory failed");

                Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");
                TermuxShellEnvironment.writeEnvironmentToFile(mContext);
                handler.post(this::complete);
            } catch (Exception e) {
                fail(Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));
            }
        }

        private void complete() {
            mJob = null;
            Observer current = observer;
            observer = null;
            if (current != null) {
                current.dismiss();
                current.whenDone.run();
            }
        }
    }

    /** What the prefix directory on disk says about the last install. */
    private enum BootstrapState {
        /** A usable prefix is installed. */
        INSTALLED,
        /** The prefix directory exists but holds nothing useful, so it must be installed again. */
        EMPTY_PREFIX,
        /** Nothing exists at the prefix path. */
        MISSING,
        /** A regular file occupies the prefix path, so installing must replace it. */
        PATH_NOT_DIRECTORY
    }

    private static BootstrapState readBootstrapState() {
        // A prefix directory that is a symlink to a valid directory counts as installed.
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true))
            return TermuxFileUtils.isTermuxPrefixDirectoryEmpty() ? BootstrapState.EMPTY_PREFIX : BootstrapState.INSTALLED;
        return FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false) ? BootstrapState.PATH_NOT_DIRECTORY : BootstrapState.MISSING;
    }

    private static void sendBootstrapCrashReportNotification(Context context, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(context),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

}
