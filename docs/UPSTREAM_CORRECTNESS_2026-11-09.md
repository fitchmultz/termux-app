# Selective upstream correctness integration — 2026-11-09

This is the correctness portion of the Fold16-based upstream audit, separate from
the inline-image capability in PR35. It does not rebase/synchronize upstream,
refresh dependencies/packages, change version/signing identity, or install an APK.
The source base is `792d527882cbc85bbd8aa5c2de140d5e94fe4f29`.

Every proposal below was open/unmerged when inspected; adopting a reviewed hunk
is not a claim of upstream maintainer approval or upstream test completion.

| Source | Inspected head | Adaptation |
| --- | --- | --- |
| [#5325](https://github.com/termux/termux-app/pull/5325) | `c8d0e4ea0b402c6432e32aa10c1d19b3f30a8777` | Valid empty writes are no-ops; retain closed/range checks and overflow-safe bounds. Covers sanitized control-only paste. |
| [#5230](https://github.com/termux/termux-app/pull/5230) | `e2bd0ca26d43a10450d30482429e1ce7907b821d` | Coalesce PTY Looper wakeups; retain queue/read-buffer invariant and Fold watchdog early return. |
| [#5352](https://github.com/termux/termux-app/pull/5352) | `ca7c4a817e3c4225e8b17a1163501ceeb1c76f89` | Bound SU work by scroll-region height. |
| [#5353](https://github.com/termux/termux-app/pull/5353) | `e73357fc89f40aa4e19710117cb0771c78cdb3e7` | Reverse index above a scrolling region moves the cursor only. |
| [#5354](https://github.com/termux/termux-app/pull/5354) | `1e65e0fc3db17070bf4f453ea324e9879af41751` | Correct cursor movement at vertical margins and in origin mode. |
| [#5355](https://github.com/termux/termux-app/pull/5355) | `9fb4ab6cd602fb53b15974d6f862f51adb9b8744` | VPA/VPR/HPA preserve absolute versus origin-relative coordinates. |
| [#5356](https://github.com/termux/termux-app/pull/5356) | `40fa56b74141332d907dd261d04354ab0b7368aa` | LR block-copy scrolling does not move transcript rows or selections. |
| [#5357](https://github.com/termux/termux-app/pull/5357) | `7dd6e3fb1baee13641b08cf129b0f949c0d0e3d7` | Ignore DEL; CAN/SUB cancel controls without inserting DEL into copied text. |
| [#5424](https://github.com/termux/termux-app/pull/5424) | `2f38925de3a4e2d984526835c1582ca52a0ddabb` | Snapshot overlapping source styles before same-row copies. |
| [#5378](https://github.com/termux/termux-app/pull/5378) | `c2ead99f328824af002d011234b1a0dc4765b650` | Wrapped-word selection crosses the transcript/visible boundary. |
| [#5295](https://github.com/termux/termux-app/pull/5295) | `9e3bf41f5df1beddb7b88c637e1084c3273dd692` | Unmatched Ctrl+Alt reaches the active pane; recognized Fold shortcuts remain. |
| [#5294](https://github.com/termux/termux-app/pull/5294) | `aecb81e7b5e53f81904489272d6575d13261a0b2` | Partial arrays, UTF/localrefs, failed duplication, preserved exceptions and PTY setup/fork cleanup; preflight PID output and use one-int region copy. |
| [#5246](https://github.com/termux/termux-app/pull/5246) | `ffcda3bf28e276c5c0d008d71b7c71aaaeafb13e` | Connected-client FD and exactly one AM failure result only. |

Do not import #5246's unconditional kill: current reflective PID lookup can return
`-1`, which has process-selection semantics in `kill`. Main shared-source repairs
do not change the separately pinned API companion producer. Closing a PTM after a
post-fork PID-copy exception is not proof of child termination/reaping.

## Build transport repair

Initial PR35 runs `42570544832` and `42570628841` failed before Java tests when the
locked snapshot's browser URL returned HTTP504. The public same-repository REST
asset delivered the exact existing 63,866,880-byte archive with SHA-256
`b1b8e301fe39d65a8269bd3bc3183e329a0d9dfd67a5abd46065030b136e1d1e`.
The transport fallback reads the fixed release-tag endpoint, selects one exact
filename/positive asset ID, and constructs the same-repository raw-asset endpoint.
It introduces no credentials, alternate contents, lock changes or integrity retry.
Archive/member/profile/cache checks remain mandatory. Mocked transport negative
controls and the existing real online/offline bootstrap gate qualify this path.

## Verification boundaries

Focused tests exercise production queue/handler routing, terminal sequences,
style/history and active-pane input. Included-source C host fault injection uses
fake JNI callbacks, tracked allocators and stubbed PTM/fork boundaries; Java socket
tests shadow only native availability/AM execution. These do not establish Android
ART, real child lifecycle, native socket/AM execution or physical Fold acceptance.
Full exact-head CI, actual independent Pi Durable and Cursor review, addressed
findings and refreshed revisions are required before this correctness PR merges.
Graphics remains separate rather than holding these fixes behind that capability.

## Observed second transport failure and narrow correction

The first push bootstrap run `42572582066` passed all 17 runner/transport tests and
restored all 127 snapshot inputs through the public API, then failed at the
**separate custom lsof browser URL** with HTTP504. Its downloaded
`bootstrap-first.log` SHA-256 is
`2cdda8dae5da361402dc882ebb1c5c8fd42b08a85b85a0cded27324938ebe3e8`.
A green repeat does not explain away that observed transport defect.

The follow-up reuses the existing API-only helper only for a failed curl of the
exact `LSOF_BACKPORT` URL **and** SHA pair. Browser bytes with bad hashes are never
retried. The helper still selects one exact filename from the same fixed public
release endpoint and constructs the same-repository raw asset URL from a positive
integer ID. API bytes pass the common checksum check before publication. No
package versions, source hashes, artifact contents, lock/snapshot pins, credentials,
URLs or environment policies change.

Fourteen new fully mocked fetch tests and the eleven unchanged snapshot tests
passed in the isolated authoring worktree. Actual current-head M1 CI/live transport
remains a separate gate; this result is not a full Android/bootstrap build claim.
