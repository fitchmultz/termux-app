# Background sessions on Android

A terminal pane showing `[Process completed (signal 9) - press Enter]` means its shell was forcibly killed. It does not by itself identify the cause. Android can stop an individual child process even while the Termux app and its other panes are still running.

## Child-process restrictions

On Android 12 and later, Android may monitor app child processes and kill them when its system-wide limit or excessive-CPU policy is reached. The [AOSP default limit](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/am/ActivityManagerConstants.java#229) is 32, but device policies can differ. Pinning Termux in Recents, exempting it from battery optimization, and adding RAM or swap do not disable that policy. See the [Termux investigation](https://github.com/termux/termux-app/issues/2366) and Android's [process-monitor implementation](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/am/PhantomProcessList.java#407).

On the tested Galaxy Z Fold8 with Android 17 / One UI 9:

1. Open **Settings → Developer options**. If it is not available, open **About phone → Software information** and tap **Build number** seven times, then return to Settings.
2. Turn on **Disable child process restrictions**.
3. Resume the actual work and check whether the affected sessions remain alive.

This is an Android-wide setting, not a Termux-only exemption. Other apps' child processes are also unrestricted by this monitor, which can increase resource and battery use. Android can still terminate processes for low memory, an app crash, or other reasons; the setting is not a guarantee. The switch can be turned off again. Check it after changing Developer options or updating Android.

The Fold8 initially had the monitor enabled with a 32-process limit. After disabling it, the owner reported that the previously recurring kills appeared to stop during normal use; Android also reported 53 live child processes, 51 belonging to Termux, while both Pi sessions remained alive. The earlier kill log had already expired, so this observation does not prove what sent that particular signal.

If a kill recurs, capture the system log promptly: Android's log buffers roll over. From an authorized ADB connection, the following includes process-kill and low-memory messages without clearing the log:

```sh
adb logcat -d -b events -b system -v threadtime | grep -Ei 'am_kill|phantom|lmkd|termux'
```

Look for the recorded reason, such as `Trimming phantom processes`, rather than treating every signal 9 as the same problem.

## Start programs at boot

Android stops every Termux process when the phone restarts and when the Termux APK is updated. After either event, this fork starts each regular file in `~/.termux/boot` as a background task, in name order. After a restart, that happens once the phone is unlocked for the first time. Nothing starts if the directory is missing or empty.

No separate app is needed. The upstream [Termux:Boot](https://github.com/termux/termux-boot#readme) plugin must be signed with the same key as Termux, so its public builds cannot be installed beside this fork. Scripts written for Termux:Boot work unchanged, with one difference: this fork also starts them after an APK update.

For example, `~/.termux/boot/start-services` starts the services enabled through `termux-services`, such as `sshd`:

```sh
#!/data/data/com.termux/files/usr/bin/sh
export SVDIR="$PREFIX/var/service" LOGDIR="$PREFIX/var/log"
service-daemon start >/dev/null 2>&1
exec tail -s 5 --pid="$(cat "$PREFIX/var/run/service-daemon.pid")" -f /dev/null
```

The last line keeps the script running, which matters. Termux stays a foreground service, with its notification, only while a session, a background task, or the wake lock exists. When a boot script exits and nothing else holds the service, [the service stops](../app/src/main/java/com/termux/app/TermuxService.java#L855-L862) and Android treats Termux as a cached app. On an Android 17 emulator, Android froze every Termux process 30 seconds later and `sshd` stopped answering. The example therefore waits for as long as the service supervisor runs. It starts the supervisor the same way a login shell does, so opening a session later does not start a second one. `termux-wake-lock` also holds the service, but it keeps the CPU awake.

The notification's **Exit** action ends sessions and stops the service. It does not end boot scripts or the programs that they started, but Termux stops tracking them, so they no longer hold the service. On the same emulator, Android froze them ten seconds after **Exit**, and they resumed when Termux was opened again. A restart or an APK update starts the scripts as tracked tasks again. Android delivers neither event to an app that was force-stopped and not opened since.

## Temporary files on service shutdown

The current fork attempts to clear Termux's temporary directory when its [service is destroyed](../app/src/main/java/com/termux/app/TermuxService.java#L172-L175). In one observed shutdown on the Fold, that cleanup blocked the app's main thread long enough for Android to record an application-not-responding event. The existing [cleanup option](../termux-shared/src/main/java/com/termux/shared/termux/shell/TermuxShellUtils.java#L86-L110) can retain temporary files instead. Add this to `~/.termux/termux.properties`:

```properties
delete-tmpdir-files-older-than-x-days-on-exit = -1
```

Run `termux-reload-settings` while Termux is visible, or let the app load the file on its next start. Reloading can recreate the activity; save important work first. This avoids this cleanup path but does not prevent the service from stopping. Temporary files will accumulate, so remove them only after confirming that no running work needs them.
