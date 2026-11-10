package com.termux.app.event;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxService;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.shell.TermuxShellManager;

import java.io.File;
import java.util.Arrays;

public class SystemEventReceiver extends BroadcastReceiver {

    private static SystemEventReceiver mInstance;

    private static final String LOG_TAG = "SystemEventReceiver";

    /** The Termux:Boot plugin's scripts directory, {@code ~/.termux/boot}. */
    private static final File BOOT_SCRIPTS_DIR = new File(TermuxConstants.TERMUX_DATA_HOME_DIR, "boot");

    public static synchronized SystemEventReceiver getInstance() {
        if (mInstance == null) {
            mInstance = new SystemEventReceiver();
        }
        return mInstance;
    }

    @Override
    public void onReceive(@NonNull Context context, @Nullable Intent intent) {
        if (intent == null) return;
        Logger.logDebug(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));

        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
                onActionBootCompleted(context, intent);
                startBootScripts(context);
                break;
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                startBootScripts(context);
                break;
            case Intent.ACTION_PACKAGE_ADDED:
            case Intent.ACTION_PACKAGE_REMOVED:
            case Intent.ACTION_PACKAGE_REPLACED:
                onActionPackageUpdated(context, intent);
                break;
            default:
                Logger.logError(LOG_TAG, "Invalid action \"" + action + "\" passed to " + LOG_TAG);
        }
    }

    public synchronized void onActionBootCompleted(@NonNull Context context, @NonNull Intent intent) {
        TermuxShellManager.onActionBootCompleted(context, intent);
    }

    /**
     * Start each regular file in {@link #BOOT_SCRIPTS_DIR} as a background {@link TermuxService}
     * task, in name order, as the Termux:Boot plugin does. Android kills every Termux process on
     * reboot and on app update, so both events start the scripts. A script that keeps running
     * keeps the service in the foreground without a terminal session or wake lock.
     */
    private static void startBootScripts(@NonNull Context context) {
        File[] scripts = BOOT_SCRIPTS_DIR.listFiles(File::isFile);
        if (scripts == null || scripts.length == 0) return;
        Arrays.sort(scripts);

        StringBuilder names = new StringBuilder();
        for (File script : scripts) {
            // Termux:Boot runs scripts whose owner forgot to make them executable.
            if (!script.canRead()) script.setReadable(true);
            if (!script.canExecute()) script.setExecutable(true);

            Uri scriptUri = new Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE)
                .path(script.getAbsolutePath()).build();
            Intent executeIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, scriptUri)
                .setClass(context, TermuxService.class)
                .putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(executeIntent);
            else
                context.startService(executeIntent);

            names.append(names.length() == 0 ? "" : ", ").append(script.getName());
        }
        Logger.logInfo(LOG_TAG, "Started boot scripts: " + names);
    }

    public synchronized void onActionPackageUpdated(@NonNull Context context, @NonNull Intent intent) {
        Uri data = intent.getData();
        if (data != null && TermuxUtils.isUriDataForTermuxPluginPackage(data)) {
            Logger.logDebug(LOG_TAG, intent.getAction().replaceAll("^android.intent.action.", "") +
                " event received for \"" + data.toString().replaceAll("^package:", "") + "\"");
            if (TermuxFileUtils.isTermuxFilesDirectoryAccessible(context, false, false) == null)
                TermuxShellEnvironment.writeEnvironmentToFile(context);
        }
    }



    /**
     * Register {@link SystemEventReceiver} to listen to {@link Intent#ACTION_PACKAGE_ADDED},
     * {@link Intent#ACTION_PACKAGE_REMOVED} and {@link Intent#ACTION_PACKAGE_REPLACED} broadcasts.
     * They must be registered dynamically and cannot be registered implicitly in
     * the AndroidManifest.xml due to Android 8+ restrictions.
     *
     *  https://developer.android.com/guide/components/broadcast-exceptions
     */
    public synchronized static void registerPackageUpdateEvents(@NonNull Context context) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        intentFilter.addDataScheme("package");
        context.registerReceiver(getInstance(), intentFilter);
    }

    public synchronized static void unregisterPackageUpdateEvents(@NonNull Context context) {
        context.unregisterReceiver(getInstance());
    }

}
