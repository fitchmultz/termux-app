# App ownership and lifetimes

This reference describes who retains app resources, which APIs lend shared objects, and where cleanup occurs.
An owner controls release or replacement. A borrower can use a resource without acquiring that cleanup responsibility.
A copied container has independent membership, but its elements can still be shared mutable objects.

The downstream release inventory is [FORK_CHANGES.md](../FORK_CHANGES.md).

## Resource inventory

Paths in this table are relative to each module's `src/main/java`.
App paths without a package prefix are under `com/termux/app/`.
The named methods are the transfer or cleanup boundaries, not additional owners.

| Resource and source | Owner and transfer boundary | Terminal paths and thread obligations |
| --- | --- | --- |
| Sessions, tasks, and pending commands. `app`: `com/termux/app/TermuxService.java`; `termux-shared`: `com/termux/shared/termux/shell/TermuxShellManager.java` | The service manages membership. Dispatch moves an accepted command from pending to a session or task. A rejected request or reuse of an existing shell retires pending membership without creating a new owner. | Service shutdown takes pending commands before cancellation delivery. Session completion and explicit removal go through the service, independent of an attached activity. UI-observed session membership changes run on the main thread. Background task completion posts back through the service handler. |
| Activity and terminal callbacks. `app`: `TermuxActivity.java`, `terminal/TermuxTerminalSessionActivityClient.java`; `terminal-emulator`: `com/termux/terminal/TerminalSessionClientHandle.java` | The activity owns views and activity clients, not running sessions. Service attachment replaces session clients. A stable callback handle lets retained emulator buffers, images, and parser transfers use the replacement client. | Activity destruction and service unbind restore the service client and release activity references. Replacing the delegate does not cancel a callback already in progress. UI work remains on the main thread. |
| PTY descriptor, queues, and process workers. `terminal-emulator`: `com/termux/terminal/TerminalSession.java`, `TerminalPty.java` | One `ParcelFileDescriptor` owns the master. Reader and writer streams borrow its shared Java descriptor. The session owns input admission; a view borrows the session and can detach without terminating the child. | Before adoption, failed setup closes the raw native descriptor. After adoption, cleanup closes the Android owner and any exit-pipe descriptors, invalidating borrowed handles and signalling blocked Android I/O. The waiter reaps the child, clears its PID, closes input admission through `DRAINING`, and wakes the reader through a pipe. After the exit signal, the reader polls the master without waiting before each read, publishing ready bytes until no more input is readable. A quiet inherited slave does not delay completion; a descendant that keeps producing readable output can prolong draining. The waiter joins that reader off-main; the main thread drains the final queue before notifying completion. A kill request is not proof of completion. |
| Background process streams and output. `termux-shared`: `com/termux/shared/shell/command/runner/app/AppShell.java` | `AppShell` owns the child, stdin, and output readers. Readers append to command result buffers until they finish. | Stdin closes even when no input was supplied. Successful commands wait for the child and both readers' EOF before publishing results. Cancellation or execution failure closes the owned output streams before joining the readers, so an inherited pipe in a surviving descendant cannot withhold an Android cancellation result. The cancelled result contains output collected before stream closure, not future descendant output. Result suppression does not transfer worker cleanup. |
| Emulator buffers, presentation frames, and images. `terminal-emulator`: `com/termux/terminal/TerminalEmulator.java`, `TerminalBuffer.java`, `TerminalBitmap.java`, `TerminalSixel.java` | The emulator owns live buffers and the synchronized-output presentation state. Completed frames copy visible rows and palette entries while retaining shared published image pixels. The sixel decoder owns its mutable working bitmap. | Parsing, resize, drawing, and presentation-state acquisition are coordinated on the session's main thread. Frame completion releases the emulator's frozen-state reference. Placement removal drops references, not shared pixels through `recycle()`. Other retained references can keep pixels alive. |
| IME connection, key presses, and input reservations. `terminal-view`: `com/termux/view/TerminalView.java` | The view owns the served input connection and accepted physical-key press records. A reservation belongs to the session that admitted the press, not whichever pane is later focused. | Session retargeting and view detachment invalidate the connection and clear key records. Release consumes or cancels the matching reservation. Stale connections cannot target the replacement session. View and IME state changes run on the UI thread. |
| Selection handles, action mode, delayed toolbar, and cursor blink callbacks. `terminal-view`: `com/termux/view/TerminalView.java`, `textselection/TextSelectionCursorController.java` | The view owns selection UI and queued UI callbacks. The selection controller retains the active action mode and handles. | Retargeting, detachment, and action-mode destruction end selection ownership. `stopTextSelectionMode()` removes delayed toolbar work and finishes the controller. Gesture-dismissal grace does not delay terminal teardown. Cursor-blinker stop removes pending toggles. |
| Extra-key layout, repeat callbacks, modifiers, and popup. `termux-shared`: `com/termux/shared/termux/extrakeys/ExtraKeysView.java` | The view retains layout references and supplied configuration collections. An active press owns repeat and long-hold callbacks. The view owns the popup. | Main-thread handler callbacks end on release or cancellation. Reload and detachment cancel pending work and dismiss the popup. Reload clears old button references and retires modifiers absent from the new layout. |
| Evidence drafts, imported files, and import job. `app`: `com/termux/app/terminal/EvidenceStore.java`, `EvidenceFiles.java` | The application owns the store. The store retains live drafts and import work across activity replacement. An import copies granted content into app-owned files rather than retaining open provider handles. | Draft edits, listeners, and result publication run on the main thread. File copying runs on the import worker. Cursors and input streams close within `importFile()`. Copy failure deletes partial output and files copied by that failed batch. Process interruption leaves a persisted notice marker, not resumable work. Already copied files are not automatically added to a draft after process death. |
| Evidence UI, target dialog, and pending share. `app`: `com/termux/app/terminal/EvidenceDock.java` | The activity owns the dock and dialog. The store listener is borrowed only while the dock is started. A pending target choice retains session handles rather than trusting retained session objects. | Stop saves the draft, removes the listener, and dismisses the dialog. Start refreshes from the store and resumes pending target selection. Selection resolves the handle again and rejects exited targets. Delayed delivery also checks the current target. UI teardown does not cancel the application import worker. |
| Received-file input and name dialog. `app`: `com/termux/app/api/file/FileReceiverActivity.java` | The activity owns the pending input until saving consumes it. Each prompt is tied to its own input identity. | `saveStreamWithName()` closes input on success and validation or write failure. Dismissal, replacement, a new intent, dialog construction failure, and destruction release pending input. Stale callbacks cannot consume a replacement stream. Each admitted intent is handled once per activity instance, not again on every resume. |
| Bootstrap installation and observer. `app`: `com/termux/app/TermuxInstaller.java`, `TermuxApplication.java` | The application retains the one filesystem job. An attached activity observer owns its dialog and completion callback, not extraction. Joining a running job happens before checking prefix state, preventing a second staging-directory writer after recreation. | Stop or destruction detaches the observer without interrupting extraction. Success clears the job and notifies the current observer; with no observer, the next activity checks the installed prefix. Failure keeps a retryable job and posts a crash notification. Retry discards that failed job, removes the failed prefix and starts a new installation; abort discards it and finishes that activity. Process death has no resumable Java worker: the next launch uses the existing prefix/staging recovery checks. |
| Reports and backing files. `termux-shared`: `com/termux/shared/activities/ReportActivity.java` | The report activity consumes `ReportInfo` passed directly or through a cache-file path. A replacement activity needs that same backing file. | Destruction deletes the backing file except during configuration recreation. Age-based cleanup covers files left when destruction never completes. The report file is not an open descriptor retained by the caller. |
| Settings stores and report workers. `app`: `com/termux/app/fragments/settings/`; `activities/SettingsActivity.java` | Each preference fragment retains its data store, including context-dependent callbacks. These stores are not process singletons. About-report workers build report data and pass it to `ReportActivity`. | Fragment replacement releases its store when references disappear. A started report worker is not cancelled by fragment teardown. This ownership does not make preference objects safe for arbitrary cross-thread mutation. |
| Receivers, service requests, and help UI. `app`: `TermuxActivity.java`, `RunCommandService.java`, `event/SystemEventReceiver.java`, `TermuxOpenReceiver.java`, `activities/HelpActivity.java` | The activity owns its dynamic receiver registration. Android owns manifest receiver dispatch. `RunCommandService` hands valid execution requests to `TermuxService`. The help activity owns its WebView. | Activity start registers its receiver; stop unregisters it. `RunCommandService` releases foreground state and calls `stopSelf(startId)` on terminal request paths. Help destruction removes the WebView from its parent, clears its client, and destroys it. Receiver return is not an owner for later UI or worker state. |
| Local server and accepted sockets. `termux-shared`: `com/termux/shared/net/socket/local/` | The manager owns the server and listener thread. Acceptance transfers a client socket to the client handler. Server-side setup errors close the accepted socket before that handoff. | Server stop interrupts the listener, detaches the public descriptor and calls `shutdown()` to wake accept. The listener alone closes its captured descriptor after its final native call, preventing descriptor reuse during accept. Failed thread startup closes the untransferred descriptor. Starting a running manager is idempotent; a stopped manager is not restartable. Bulk JNI reads use only the remaining array capacity after each partial read. Accepted-client cleanup belongs to the handler; stopping the listener does not close every accepted client. Handler callbacks run on separate worker threads. |
| Service locks and bell audio. `app`: `com/termux/app/TermuxService.java`, `terminal/TermuxTerminalSessionActivityClient.java`; `termux-shared`: `com/termux/shared/termux/terminal/io/BellHandler.java` | The service owns its paired power/Wi-Fi wake locks. The activity client owns its SoundPool. The application-scoped bell handler borrows Android's vibrator service. | Explicit unlock and service destruction release wake locks. Activity stop releases SoundPool; resume can load it again. Terminal bell callbacks require a visible activity. None of these resources transfers to a terminal view or a published image. |
| Provider results. `app`: `com/termux/filepicker/TermuxDocumentsProvider.java`, `com/termux/app/TermuxOpenReceiver.java` | Provider methods transfer returned cursors and descriptors to Android's provider transport and the recipient. Thumbnail results wrap the returned descriptor in an `AssetFileDescriptor`. | A provider does not close a successfully returned cursor or descriptor before the recipient uses it. In contrast, app code acquiring a cursor, stream, or descriptor from a resolver owns closing that acquired handle. A URI or file path alone is not an acquired handle. |

Android can kill the process without completing lifecycle callbacks.
These cleanup paths describe normal completion, cancellation, replacement, and reported failure, not a guarantee that `onDestroy()` runs.
Persisted drafts, report-file age cleanup, and filesystem installation state have separate recovery roles.

## Borrowed API reference

### Renderer buffers and pixels

[`TerminalRendererState`](../terminal-emulator/src/main/java/com/termux/terminal/TerminalRendererState.java) is read-only by caller contract, not by Java type enforcement.
`getScreen()` returns a mutable `TerminalBuffer`, and `getPalette()` returns an array.
[`TerminalRenderer`](../terminal-view/src/main/java/com/termux/view/TerminalRenderer.java) acquires both for rendering without copying.

For a live emulator, those objects can change as output is parsed, colors change, the screen switches, or geometry changes.
Their useful validity window is the current render pass on the owning thread.
A retained reference is not a frozen frame.
An independent text capture or color snapshot requires a copy made while the owner is not changing the data.

The synchronized-output state has copied rows and a copied palette.
Its image pixels are shared with live-buffer placements.
`TerminalBitmap.getBitmap()`, `TerminalBuffer.getSixelBitmap()`, and the pixels reachable through `getTerminalBitmap()` are borrowed published pixels.
Callers do not mutate or recycle them, even after live-buffer eviction.
Ordinary rendering needs no pixel copy. Writable image processing needs a separate bitmap.

`TerminalSixel.getBitmap()` differs: it exposes the decoder's working bitmap.
Parsing can mutate or replace that bitmap before image publication.
Retaining it does not produce a completed image.

### Extra-key arrays and collections

[`ExtraKeysInfo.getMatrix()`](../termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysInfo.java) returns the parsed backing matrix.
`ExtraKeysView.reload()` retains the matrix and its button objects.
`TermuxActivity` reads its row count, and `ExtraKeysActivity` reads a parsed button.
These readers do not need copies. Independent array edits require copies of both the outer array and each affected row.

[`ExtraKeysView`](../termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysView.java) retains the list passed to `setRepetitiveKeys()` and the map passed to `setSpecialButtons()`.
The default constructor supplies the repetitive-key constants and a new default modifier map.
The modifier-name set is derived when the map is set, not maintained as a live view of later map edits.
Independent configuration edits use a separate collection and the setter.

`getRepetitiveKeys()` and `getSpecialButtonsKeys()` return detached containers of names.
`getSpecialButtons()` returns a detached map with shared `SpecialButtonState` values.
The view updates those states and their button references during interaction and layout reloads.
A shallow map copy does not create independent modifier state. View-related access remains on the UI thread.

### Android preference sets

[`SharedPreferenceUtils`](../termux-shared/src/main/java/com/termux/shared/settings/preferences/SharedPreferenceUtils.java) preserves Android's native set contract.
`getStringSet()` does not copy and can return the supplied default itself.
The returned set is not caller-writable.
An editable or stable independent value requires a copy of a non-null result.

`setStringSet()` passes the supplied set directly to `Editor.putStringSet()`.
Android's contract forbids modifying that set after the call.
A caller that continues editing its original passes a separate set.
Choosing `commit()` rather than `apply()` does not change this obligation.
There are no production callers of these wrappers in the traced tree; the wrappers do not impose extra copying on other preference APIs.

The platform contracts are [SharedPreferences.getStringSet](https://developer.android.com/reference/android/content/SharedPreferences#getStringSet(java.lang.String,%20java.util.Set%3Cjava.lang.String%3E)) and [Editor.putStringSet](https://developer.android.com/reference/android/content/SharedPreferences.Editor#putStringSet(java.lang.String,%20java.util.Set%3Cjava.lang.String%3E)).

### Service and manager lists

[`TermuxService.getTermuxSessions()`](../app/src/main/java/com/termux/app/TermuxService.java) returns the manager's actual list.
`TermuxActivity` passes that live list to `TermuxSessionsListViewController` so adapter updates reflect service membership.
`EvidenceDock` and the activity session client also traverse it.
This is intentional borrowing, not a missing defensive copy.

Membership and cleanup remain service-owned.
The synchronized getter does not lock later iteration.
Session-list observation and membership changes use the main thread, followed by adapter notification where applicable.
A stable membership snapshot requires a list copy under the same owner-thread or lock discipline as updates.
The copied entries are still live sessions, whose running state can change before a later action.

The manager's public task and pending-command lists are also live owner registries.
Manager methods govern placement. Direct list access is not permission to retire commands, publish results, or clean up their processes independently.

### Evidence drafts

[`EvidenceStore.get(handle)`](../app/src/main/java/com/termux/app/terminal/EvidenceStore.java) returns the cached mutable draft, not a deserialized copy on every call.
The application store retains it across activity replacement.
`EvidenceDock` intentionally edits that object and calls `save(handle)` to persist it.
An import captures the target draft before its worker starts and publishes changes on the main thread.

Draft access, mutation, import publication, and listeners share the main thread.
An independent editor or background consumer needs copied field values and a separate file list.
Copying file paths does not copy the files or transfer deletion rights.
Saved preferences are the process-recovery representation, not automatic persistence of every in-memory edit.
