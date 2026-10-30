# Background sessions on Android

A terminal pane showing `[Process completed (signal 9) - press Enter]` means its shell was forcibly killed. It does not by itself identify the cause. Android can stop an individual child process even while the Termux app and its other panes are still running.

## Child-process restrictions

On Android 12 and later, Android may monitor app child processes and kill them when its system-wide limit or excessive-CPU policy is reached. The [AOSP default limit](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/am/ActivityManagerConstants.java#229) is 32, but device policies can differ. Pinning Termux in Recents, exempting it from battery optimization, and adding RAM or swap do not disable that policy. See the [Termux investigation](https://github.com/termux/termux-app/issues/2366) and Android's [process-monitor implementation](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/am/PhantomProcessList.java#407).

On the tested Galaxy Z Fold8 with Android 17 / One UI 9:

1. Open **Settings → Developer options**. If it is not available, open **About phone → Software information** and tap **Build number** seven times, then return to Settings.
2. Turn on **Disable child process restrictions**.
3. Resume the actual work and check whether the affected sessions remain alive.

This is an Android-wide setting, not a Termux-only exemption. Other apps' child processes are also unrestricted by this monitor, which can increase resource and battery use. Android can still terminate processes for low memory, an app crash, or other reasons; the setting is not a guarantee. The switch can be turned off again, although a separate device or ADB override can also affect the policy. Check it after changing Developer options or updating Android.

The Fold8 initially had the monitor enabled with a 32-process limit. After disabling it, the owner reported that the previously recurring kills appeared to stop during normal use; Android also reported 53 live child processes, 51 belonging to Termux, while both Pi sessions remained alive. The earlier kill log had already expired, so this observation does not prove what sent that particular signal.

If a kill recurs, capture the system log promptly: Android's log buffers roll over. From an authorized ADB connection, the following includes process-kill and low-memory messages without clearing the log:

```sh
adb logcat -d -b events -b system -v threadtime | grep -Ei 'am_kill|phantom|lmkd|termux'
```

Look for the recorded reason, such as `Trimming phantom processes`, rather than treating every signal 9 as the same problem.

## Temporary files on service shutdown

The current fork attempts to clear Termux's temporary directory when its [service is destroyed](../app/src/main/java/com/termux/app/TermuxService.java#L172-L175). That can remove files used by still-running background work; one observed shutdown also blocked the app's main thread long enough for Android to record an application-not-responding event. The existing [cleanup option](../termux-shared/src/main/java/com/termux/shared/termux/shell/TermuxShellUtils.java#L86-L110) can retain temporary files instead. Add this to `~/.termux/termux.properties`:

```properties
delete-tmpdir-files-older-than-x-days-on-exit = -1
```

Run `termux-reload-settings` while Termux is visible, or let the app load the file on its next start. Reloading can recreate the activity; save important work first. This avoids this cleanup path but does not prevent the service from stopping. Temporary files will accumulate, so remove them only after confirming that no running work needs them.
