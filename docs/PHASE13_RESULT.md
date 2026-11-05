# PHASE 13 RESULT: polish (UI, UX, browser, bug sweep)

Run: 2026-10-23 00:30-14:00. Working tree, uncommitted (the manager commits).
Evidence root: `C:\Users\Asus\AppData\Local\Temp\opencode\p13`.
BEFORE pictures: `p13\before`; AFTER pictures: `p13\after`.

Every result carries one label: **phone**, **emulator**, **PC-only** or **code-only**. The
emulator is `emulator-5554` (Pixel_8_Pro, x86_64, 1344x2992 @480 dpi); the test phone
`2c0f6edc` left at 08:45 and was absent for the rest of the run. An emulator result is never
called phone-verified.

## 1. What shipped

- A single dark design system for the desktop shell, phone app and receiver: calm wallpaper,
  surfaces, accent, 4/8/12/16 spacing, 6/10/12 corners, vector controls, no developer words.
- Desktop shell: icon Start menu with search/pinned/All apps, icon taskbar above windows,
  centred and clamped windows, title-bar icons, double-click maximise, edge snap, drag-to-top,
  Win/Esc menus, Alt+F4, right-click desktop menu, remembered window places.
- Browser: the Chrome attempt is closed (B-003/B-111); the shipping browser is a full WebView
  browser with tabs, one Google search/address box, back/forward/reload, neutral start page,
  downloads to Files, Ctrl+T/W/L/Tab, F5, zoom. Start page has no Google logo (D19 note).
- Phone home: Material 3 dark/light, connection states, pairing code, first-run checklist,
  trusted PCs, quality/sound settings, running notification, real app name resource.
- Receiver GUI: looking/found/pairing/connecting/desktop/reconnect, plain firewall error,
  Exit and Reconnect; every CLI subcommand still works.
- D22: encoder-only thermal scaling, fixed desktop size, PC scales the stream back up.

## 2. AFTER walk-through (side by side)

### Desktop shell (emulator stream shown on the 1920x1200 PC)

| # | BEFORE | AFTER | Status | Evidence |
|---|---|---|---|---|
| D1 | `before/d0-desktop-empty.png` flat green | `after/a0-desktop.png`, `a6-desktop.png`, `a10-desktop.png` indigo/slate gradient; Settings picker | **DONE** | emulator |
| D2 | `before/d1-start-menu.png` Test row | `after/a1-start-menu.png`, `a3-start-menu-1728.png`; Test only behind harness | **DONE** | emulator |
| D3 | `before/d1-start-menu.png` plain list | `after/a1-start-menu.png`, `a3`, `a4-menu-current.png`, `a5-menu.png`, `a7-menu.png`: search box, pinned icon grid, All apps, Esc/Win | **DONE** | emulator |
| D4 | `before/d0`, `d5` text Start/clock, taskbar covered | `after/a0`, `a6`, `a10`: icon Start, icon window buttons, HH:MM + date, battery, always above windows | **DONE** | emulator |
| D5 | `before/d2-appcenter.png`, `d6-focus-over-max.png` off-screen/clipped | `after/a6`, `a8-two-windows.png`, `a10`: centred, clamped, places remembered | **DONE** | emulator |
| D6 | `before/d6` flat title bar | `after/a8`: icon/title/min/max/close; snap-left, drag-to-top, double-click restore, Win/Esc, Alt+F4 proven by `p6:rects` and `p6:key` logs | **DONE** | emulator |
| D7 | `before/d4-browser.png` VS Code carries address bar | `after/a9-vscode.png`, `a16-vscode.png`: VS Code fills its window; only Browser has the bar | **DONE** | emulator |
| D8 | `before/d0` 16:9 picture on 16:10 PC, green strip | `after/a2-desktop-1728.png`, `a10`, `e38-thermal3-final-pc.png`: 1728x1080 desktop fills 1920x1200 with no bars or green strip | **DONE** | emulator |
| D9 | `before/d1`, `d2` oversized text | `after/a0`, `a1`, `a10`: Windows-like density at 1080p | **DONE** | emulator |
| D10 | `before/d2` text list, clipped buttons | `after/a13-appcenter.png`, `a14-installed.png`, `a15-pick.png`, `a15-pick2.png`: cards, source chips, Install/Open/Uninstall, progress, file picker | **DONE** | emulator |
| D11 | `before/d3-settings.png` stale link/video | `after/a18-settings.png`, `a18-settings2.png`: real desktop size, live connection + RTT, wallpaper/quality; B-105 fixed in tree | **DONE** | emulator |
| D12 | nothing | none | **SKIP** | SKIPS row: desktop toasts not implemented |
| D13 | `before/d6` focused window behind maximised | `after/a8`, `a10`, `a14`: B-091 body paint/raise, B-085 crop, B-083/087/088/093 titles and one X11 window; B-105 live settings | **DONE** | emulator |
| D14 | `before/d0` empty desktop | none | **SKIP** | SKIPS row: welcome card/shortcuts not implemented |
| D15 | `before/d4` blank WebView, text buttons | `after/e33-browser-start3.png` neutral start, `e32-browser-tabs6.png` three tabs, `e28-files-downloads.png` downloaded zip, `e18-browser-search.png`; Chrome closed B-003/B-111 | **DONE** | emulator |
| D16 | Phase 12 before table | Phase 12 final phone: Browser **65.7/74.5**, Notepad++ **66.2/73.7**, VS Code **75.5/103.5** (urgent build), Test shell **87.0/97.0**; n=25 | **DONE with caveat** | phone (Phase 12); VS Code final-build re-run blocked by B-110; step 15 phone re-run SKIP |

### Phone app (phone `2c0f6edc`; emulator cross-check)

| # | BEFORE | AFTER | Status | Evidence |
|---|---|---|---|---|
| P1 | `before/d0-phone-app.png` harness buttons | `after/a20-home-final.png`, `a23-connected-final.png`, `a28-pairing.png`, `a28-paired.png`; emulator `e1-home.png`-`e6-paired.png`: Not connected / Looking / Pairing code / Connected / Problem, one Stop | **DONE** | phone |
| P2 | none | `after/a20-home-final.png`, `a27-home-dark-default.png`: Notifications, Background activity, Sound checklist with ticks and one-line reasons | **DONE** | phone |
| P3 | none | `after/a26-settings-forget.png`, `a26-forget-after.png`: trusted PC row + Forget | **DONE** | phone |
| P4 | none | `after/a21-settings.png`, `a21-settings-connected.png`, `a27-settings-light.png`: quality Auto/Sharp/Smooth, sound, About/version/licences/privacy | **DONE** | phone |
| P5 | none | running notification "Desktop running on <PC>" with Stop; proof was text-only (`dumpsys notification --noredact`); no screen image retained to protect the owner's other notifications | **DONE** | phone |
| P6 | harness title | `after/a20-home-final.png`: only the app-name string resource; no "harness"/phase words; `HarnessActivity` no LAUNCHER in release | **DONE** | phone |

### PC receiver (laptop, PC-only)

| # | BEFORE | AFTER | Status | Evidence |
|---|---|---|---|---|
| R1 | CLI only | `after/e7-gui-found.png`, `e10-gui-state.png`: no-argument GUI opens, dark tokens, app icon; CLI still works | **DONE** | PC-only |
| R2 | none | `after/e7`, `e8-gui-pairing.png`, `e10`, `e12-gui-reconnect.png`: looking/found/pairing/connecting/desktop/Reconnect, trusted phone remembered | **DONE** | PC-only |
| R3 | none | `after/e14-gui-retry.png`: "Windows Firewall blocked the connection" with a next step; no console window | **DONE** | PC-only |
| R4 | none | desktop Exit/Reconnect path proven in the GUI no-lock run (`e11-chrome-desktop.png`, `e12`); normal lock path was proven earlier, forced-kill restart is B-112 | **DONE with caveat** | PC-only |

### Agent rows (A1-A6)

| # | Finding | AFTER | Status | Evidence |
|---|---|---|---|---|
| A1 | Taskbar covered by a window | `after/a0`, `a6`, `a10`: taskbar is the top layer; clock always visible | **DONE** | emulator |
| A2 | No window buttons | `after/a8`: icon + title buttons for each open window, focused underline | **DONE** | emulator |
| A3 | `[max]` in title | `after/a8`, `a10`: no internal state word | **DONE** | emulator |
| A4 | Start menu clipping/version suffix | `after/a1`, `a3`, `a5`: content-sized panel, no versions, rows reachable | **DONE** | emulator |
| A5 | Phone title under status icons | `after/a20-home-final.png`, `a27`: edge-to-edge with correct insets | **DONE** | phone |
| A6 | App Center action row below work area | `after/a13`, `a14`, `a15`: actions inside the window and work area | **DONE** | emulator |

## 3. Browser (D15/D19)

The manager's 11:08 note is implemented: the local start page is neutral (`after/e33-browser-start3.png`)
with one search box and the plain words "Search with Google"; it has no Google logo or colour
marks. `https://www.google.com` is loaded only when the user navigates there. DECISIONS D19
records the choice.

Proven on the final browser build (emulator): tabs/new/close/switch with independent titles
(`e32-browser-tabs6.png`), search-or-URL (`e18-browser-search.png`), back/forward/reload,
download to `files/desktop/Downloads` and visibility in Files (`e28-files-downloads.png`),
Ctrl+T/W/L/Tab, F5 (keycode 135), zoom +/-/0. F1 (131) is correctly unhandled.

## 4. D22 thermal scaling (encoder path only)

Implementation: `Phase5Encoder` captures the fixed desktop in its SurfaceTexture and configures
MediaCodec + GL viewport at 3/4 per axis; `Phase5Session.governorLowerResolution` rebuilds the
encoder, rebinds the display to the new capture surface and requests a keyframe. Input mapping
and the VirtualDisplay never change.

Measurement (emulator, Android thermal status overridden to 3; **not a phone thermal run**):

| Metric | BEFORE (thermal 0) | AFTER (thermal 3, 1296x810) |
|---|---|---|
| Encoder | 1728x1080, 12 Mbps, 60 fps | 1296x810, 6 Mbps, 30 fps |
| Encoded frames | ~53 fps | **28.6 fps** (172 / 6.006 s) |
| PC presented | 53 fps | **29.0 fps** |
| Decode | 1.45 ms | **1.11-1.20 ms** |
| Dropped | 0 | **0** |
| Receive rate | ~1.7 Mbps | **~0.4-0.7 Mbps** |
| Picture | full desktop | full desktop + taskbar, no crop (`e38-thermal3-final-pc.png`, `e39-thermal3-final-phone.png`) |

A real-phone thermal-3 run is in SKIPS.md; the phone was absent.

## 5. Bugs found or touched (see BUGS.md)

- B-003/B-111: Chrome first-run blank; missing `NativeOnlySandboxedProcessService0`; browser
  fallback shipped.
- B-105: Settings live values fixed in the working tree.
- B-112: receiver GUI lock child fails after a forced kill; no-lock path works.
- B-113: duplicate `p6:probe` harness branch (page probe unreachable).
- B-114: 1920x1200 encoder start unsupported on the test phone; 1728x1080 used.

## 6. Gates and limits

Final Android gate (`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`) **PASSED**
(2026-10-23 11:24): **206 tests / 0 failures / 3 skipped**, lint **0 errors / 137 warnings**,
`assembleDebug` BUILD SUCCESSFUL in 1m42s (`p13/gate-final.log`). PC build/tests: see
`p13/pc-test-final.log` / `p13/pc-build-final.log` and `STATE.md`. The working tree is
uncommitted.

Limits: D12 and D14 are SKIPS; D16's VS Code final-build re-run is blocked by B-110; the
phone thermal-3 and step-15 phone re-run are SKIPS; the GUI forced-kill lock restart is B-112.

## 7. Phase 13 follow-up proofs (2026-11-01, phone `2c0f6edc`)

These close the two tester bugs that blocked the phase (docs/TEST_REPORT.md T-001/T-002) and
record the one UX gap the proof exposed. All runs are `phone`, thermal 0, USB adb forward,
desk 1728x1080, unattended (harness `accept`/`deny`, no `input tap`).

| Item | What was run | Result | Evidence |
|---|---|---|---|
| **B-115** security gate (blocker) | No trusted PC (`pins=0` both sides), unpaired receiver `view --no-lock`, code `983053` on both sides, two full 300 s waits, then deny, then accept | **PASS (functional).** While pairing: `DROP control "display 1728 1080" (gate=PAIRING)`, `DROP control "video-on" (gate=PAIRING)`, `P5 VSTAT sent=0 ... shell=down`. Both 300 s waits ended `VIEW FAILED: pair confirm timed out` with **0** `VSTAT` lines. Deny: `PAIR_NO cut` + `VIEW FAILED: phone answered No; nothing was stored`, no video. Accept: `PAIR_YES stored fpr=... pins=1` -> `P5 gate open, waiting for video-on` -> **181** `VSTAT` lines up to `presented=166`, desktop visible. | `Temp\opencode\t13-1\RESULT3.txt`, raw `view-usb.log`, `view-usb-run2.log`, `view-deny2.log`, `view-accept.log`, `p4-tail.txt` |
| **B-125** locked phone shows no pairing prompt (new, major) | The proof ran with the phone screen off; `p4.log` shows `PAIRING prompt code=983053` immediately followed by `PAIRING notification skipped: app in front`; `screencap` is black | **FIXED 2026-11-01 - see the next row for the fix and the live proof.** The code was only visible on the app's home screen; a locked phone gave the user no notification, no sound and no code while the PC waited. | `Temp\opencode\t13-1\phone-pair*.png`, `p4.log` slice |
| **B-125 fix** (T13-3 + T13-3p/T13-3p2) | `Phase4PairingPrompt.wanted()` + `showPairingNotification` changed; the APK was installed and the phone locked; two runs: screen off, then screen on with the keyguard showing and `HomeActivity` resumed behind it; unpaired receiver each time | **FIXED (code + unit + live keyguard proof; one limit).** Unit tests 4/4; full gate green (`:app:testUnitDebugUnitTest :app:lintDebug :app:assembleDebug` BUILD SUCCESSFUL 10:14). Run 1: `PAIRING notification posted fg=false interactive=false keyguard=true`. Run 2: `posted fg=false interactive=true keyguard=true`. Both codes **983053** (PC `PC code: 983053`, notification `Does your PC show 983053?`), `vis=PUBLIC` (`vis=1`, id 47611), deny -> `PAIR_NO cut`, notification gone. **Limit:** the pre-fix skip state needs `AppForeground > 0` behind a non-occluded keyguard; on this phone the keyguard marks the activity `isSleeping=true`/`mVisible=false`, so the counter reads 0 and that state was not reproduced live. The unit test `foregroundKeyguardLockedIsWanted` covers that input; T13-1's `skipped: app in front` is the observed pre-fix state. | `Temp\opencode\t13-3\gate-t13-3.log`, `Temp\opencode\t13-3p\RESULT5p.txt` + `lock1.png`/`lock2.png`/`notif.txt`, `Temp\opencode\t13-3p2\RESULT5p2.txt` + `p4.txt`/`notif.txt` |
| **B-116** one-sided trust (blocker) | Phone forgot the PC (PC still pins it): no lock before trust, reconnect backoff, desktop-live refusal, deny, and the reverse one-sided case | **FIXED (T13-2 + T13-3b, phone).** Before trust, `input-desktop=Default` and `lock-state=unknown`; live reconnect backoff was `500/1000/2000/4000/8000/10000 ms` with 0 reconnect storms; desktop-live refusal and deny were plain. The reverse case then passed with matching code `983053`, VSTAT=0 for 27 s before Yes, `REPAIR stored` after Yes, and no PC pin after deny. | `Temp\opencode\t13-2\RESULT4.txt`, `Temp\opencode\t13-3b\RESULT6.txt` |
| **B-126** reverse one-sided trust (new, major) | PC trust list emptied, phone still pins the PC; unpaired receiver `view --no-lock`; then normal pairing, reverse accept, and deny | **FIXED (T13-3b, code + unit + live phone proof).** The PC reads the phone greeting before showing a code; `pair-request` forces a fresh phone prompt even when the phone still pins the PC; and the phone's gate remains `PAIRING` until Yes. Normal pairing, reverse accept, and deny all passed. Reverse: both sides showed `983053`; before Yes, VSTAT=0 for 27 s and reconnects=0; after Yes, `REPAIR stored ... pins=1` and `presented` advanced 0 -> 22. Deny: `PAIR_NO cut`, exact plain failure, exit 1, VSTAT=0, reconnects=0, no PC pin. Thermal status was 3, so the performance precondition was not met. | `Temp\opencode\t13-3b\RESULT6.txt` |
| **B-117** 16 KB native-library alignment (Play blocker) | Replaced the arm64 Box64 artifact with the existing 16 KB candidate, rebuilt the APK, verified its contents, and booted Wine/Notepad++ on phone `2c0f6edc` | **FIXED (T13-6, code-only alignment + phone functional proof).** The Box64 candidate has SHA-256 `69f5c12d...d585f1d`, size 22,390,072 B, and all three `PT_LOAD` segments at `0x4000`; the APK and on-device copy match. All 15 arm64 APK libraries, including `libblackbox.so` and `libdobby.so`, are `0x4000` aligned. Wine/Xvfb and Notepad++ ran from `nativeLibraryDir`, 767 frames were presented with 0 dropped, and real PC input typed `b117ok` visibly into Notepad++. Thermal Status stayed 3, so no performance number is claimed; the phone is 4 KB-page, so alignment was verified from the ELF/APK rather than a physical 16 KB device. | `Temp\opencode\t13-6\RESULT5.txt`, `apk-all-arm64`, `typing-b117v2.png` |

Limits of the B-115 proof: the code match is proven from both logs (phone `PAIRING prompt
code=983053` / PC `PC code:  983053`), not from a photograph, because the locked screen
captures black (that is B-125). B-116 is proven in T13-2 ((a) and (b) pass, (c) partial -
the reverse one-sided case is B-126); its `pc-pairing-state.png` caught the terminal rather
than the receiver window, so (a) rests on `udreceiver lock-status` (`input-desktop=Default`)
and the receiver's own log, not on a picture. Both B-116 runs used the locked phone driven
only by harness broadcasts (no `input tap`), and the phone was left clean afterwards
(0 processes, 0 receivers, 0 forwards, `stay_on=0`, input desktop Default).

Limit of the B-125 proof: the fix and its unit test are in the tree and the gate is green,
but the exact pre-fix state - `AppForeground` counting a resumed activity while the keyguard
covers it - was not reproduced live. With the keyguard showing, this phone marks the activity
`isSleeping=true`/`mVisible=false`, so the counter reads 0; the notification still posts via
the keyguard condition. The unit test `foregroundKeyguardLockedIsWanted` covers the counter>0
input, and T13-1's `PAIRING notification skipped: app in front` is the observed pre-fix state.
The two live runs that did run are quoted in the B-125 fix row.

## 8. T13-8 overnight closure run (2026-11-05/06, manager-verified)

Run: 2026-11-05 23:36 - 2026-11-06 03:55, phone `2c0f6edc`, HEAD `f3dfadb`, no product code
changed. Evidence: `Temp\opencode\t13-8\` (RESULT.txt, pics\, raw receiver logs, conn/lat/npp
JSONs). All performance numbers thermal 0, desk 1728x1080, USB adb forward, real SendInput
product path. The manager re-derived the headline numbers from the raw logs before recording
(NPP median 12.60 s from `nppstarts.json`; drag 45.4/54.0 fps from `drag-vstat.json`; connect
0.104 s window / 1.139 s first frame from `conntimes.json`; the real-thermal-3 GOVERNOR ladder
is verbatim in `logcat-full.log` at 00:01:00-09).

| Item | Result | Evidence |
|---|---|---|
| Files proofs (B-118/B-119 follow-up) | PASS - delete modal + Cancel + Delete, rename keeps the typed extension, 5 invalid rename names refused with a message, new-folder prompt with refusals, folder single-click selects / double-click opens | `pics\i2-*`, logcat `FILES ...` lines |
| B-121 Back regression | PASS - Settings -> Back returns to the app home, app stays resumed | `pics\i3-1`, `i3-2` |
| B-120 focus regression | PASS - 66 s zero-input poll, 33/33 foreground, 0 reclaim lines (old bug: 171 reclaims in ~6 min) | `focus-poll-i3.log`, `view5.out.log` |
| D12 toasts | PASS - "Copied" via the real clipboard path (no focus steal, auto-dismiss ~2 s); "Rename failed" also captured | `pics\i4-1`, `i4-2`, `i2-11` |
| D14 welcome card | PASS - appears once on a fresh session, Files shortcut opens the app, dismissal persists across a reconnect (`welcome_done` pref) | `pics\i4-3..i4-8` |
| Connect time | PASS - window 0.104 s / first frame 1.139 s median (5 fresh sessions; target 5 s) | `conntimes.json`, `conn1-5.log` |
| Typing LATSTAGE (3x25 keys each) | test shell p50 96.4-126.9 ms (target 80 MISS; Phase 12 recorded 81.7); Browser 109.8-122.0; NPP 96.5-124.2; VS Code editor FAIL (B-128: keys reach the Chat input, not the editor) | `lat-*.json`, `typing-testshell.log` |
| Notepad++ start | 12.60 s median (n=5, fresh session + closed window each; target 10 MISS; Phase 12 recorded 10.45) | `nppstarts.json` |
| Drag fps | 45.4 avg / 54.0 max, rx 9694 kbit/s, decode 4.25 ms, dropped 0 (target >= 30 PASS) | `drag-run.log`, `drag-vstat.json` |
| D22 hot-stage (T13-7) | NOT PROVEN - the framework override never reaches the governor (B-127) and the 35-min real-heat box did not reach thermal 3; the real-heat ladder exists verbatim in `logcat-full.log` (00:01:00-09) and `files/p4/p4.log` (19:11) | `RESULT.txt` §4 |

Honest caveats (recorded, not tuned away): the phone was locked / screen-off during the typing
runs, the laptop was under owner interactive load, and two receiver windows were closed
externally mid-run. Tonight's p50s sit 15-45 ms above Phase 12's same-build numbers, so the
Phase 12 records (81.7 ms / 10.45 s) remain the formal numbers until a screen-on re-take.
Cleanup verified by the manager: override 0, screen_off_timeout 1800000 (system table),
stay_on 0, 0 app processes, 0 wine/Xvfb, 0 receivers, 0 forwards, Wi-Fi off (as found), no
call, git tree clean apart from TODO.md.
