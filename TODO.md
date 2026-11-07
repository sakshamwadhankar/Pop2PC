T14-1: done 6 of 6. Updated 11:14. Now: manager review (owner backs up keystore + uploads AAB; NPP sha-mismatch bug filed in Temp\opencode\t14-1\RESULT.txt §3).

# Night run: Phase 12 finish + Phase 13 polish

## T13-2 — B-116 live proof (2026-11-01 02:13-02:20, coder)

Done 10 of 14. Updated 11:10. Now: T13-3p2 B-125 exact-state proof=no (fg=false keyguard=true; keyguard half posts, fg=true+keyguard=true not reachable this way on phone), next manager decision.

- B-116 proven live on 2c0f6edc: (a) no lock before trust - lock-status input-desktop stayed Default at
  2s/10s while the receiver sat in the pairing state showing the code (983053 on both sides); the lock
  engages only after connect_and_pair returns. (b) reconnect backoff live 500/1000/2000/4000/8000/10000ms
  (cap 10s), 0 reconnects in every one-sided/pairing/deny/reverse run (old bug: 115 per 30s).
  (c) PARTIAL: the desktop-live refusal is a plain quoted reason on both sides (PASS), but the reverse
  one-sided case (PC forgot, phone still trusts) has NO refusal message: the phone says "trusted", then
  closes via SESSION dead-timeout (~15s); the PC shows a stray pairing code then "VIEW FAILED: phone closed
  the connection while pairing". Recorded in RESULT4.txt for the manager. No code changed.

## Run 3 — 2026-10-31 00:33-… (coder, final Phase 12 + Phase 13 combined)

Done 7 of 14. Updated 01:35. Now: T12-6 npp median=10.45s (p12nppwarm:1 warm=9.48s), top gap=9.87s Wine/box64 NPP start+paint, next manager decision.

- Item 1 B-122 FIXED in code + proven on the phone: root cause was that in shell mode the only
  flash source was the harness probe chip (`Phase6Shell.kt:195-199` returns early unless armed;
  armed only by `p6:probe-chip`, `Phase6Dispatch.kt:76`). Fix: `ShellRoot.flashFrame()` +
  `dispatchDraw` report the frame that carries a key-down (`Phase6Shell.kt`), key path calls it.
  25-key run at 1728x1080 in the Browser: 27 LATSTAGE, p50 **84.9** / p90 **107.1**, text visible
  in the shot (`p12full/b122c.png`). Second run p50 97.2 / p90 134.2.
- Item 2 Wi-Fi typing (5 GHz 5220 MHz, RSSI -58, thermal 0, battery 46 %): run 1 p50 84.9 / p90
  107.1, run 2 p50 97.2 / p90 134.2 — both p50 <= 120 (PASS), p90 over in run 2.
- Item 3 Test shell: BEFORE on this build (animation on, 1728x1080) p50 239.0 / p90 259.9 n=25;
  AFTER removing the 60 fps self-invalidation: 1728x1080 p50 198.9 n=14, 1296x810@30fps p50 103.1 /
  p90 144.3 n=26. Still over 80 — gate NOT met so far; drawn>encoded 45-63 ms dominates.
- PHONE GONE 07:07 (adb device vanished; receiver got "link dead" at the same moment).

## Checkpoint 1 — B-122 (2026-10-31 00:17-00:31, coder)

Done 4 of 4. Updated 00:31. Now: manager decision on B-122 (harness step, not a code
regression) and on the new Wi-Fi typing number.

- **B-122 root cause found (phone):** in shell mode the latency probe's only flash source is the
  harness probe chip (`Phase6Shell.kt:1056`); it is disarmed by default and logs
  `P5PROBE chip flash SKIPPED (armed=false)`. The 2026-10-24 run never armed it (`p7.log`
  13:20-13:45: 868 `P6DISPATCH`, 0 `probe-chip`), so `probe.tDrawUs` stayed 0 and
  `Phase5Session.kt:383` never called `sendLatency`. Reproduced unarmed today at 1728x1080.
- **Armed re-run at 1728x1080, Browser focused:** 27 `LATSTAGE`, p50 **81.7 ms**, p90 **98.4 ms**
  (min 67.6, max 111.0), Wi-Fi 5220 MHz / RSSI -58 / 11ac, dropped=0. Evidence
  `Temp\opencode\p12live\` (`RESULT.txt`, `viewB.log`, `p4-new.log`, `p7-new.log`).
- Harness trap: `--es phase4x p6:probe-chip on` is parsed as `pkg=on` and does nothing; use
  `--es phase4x p6:probe-chip`.
- No product code changed. Cleanup done (receiver killed, `phase4x stop`, force-stop,
  `stay_on=0`, 0 forwards, 0 app processes).

## Manager-takeover phone round (2026-10-24 13:20-13:46, coder; evidence `Temp\opencode\p12final\`)

Stopped at **13:46**: the owner came back and was actively using phone 2c0f6edc (ChatGPT on
screen at 13:44; the owner's tap also answered the B-115 pairing prompt 4.5 s in). Phone work
ended there; items 4 (full), 5 and B-116 were NOT run. Cleanup done: receiver killed,
`phase4x stop`, app force-stopped, `stayon false`, no adb forwards, 0 app processes,
`gradlew --stop`.

| Item | Result (label `phone` unless said) | Evidence |
|---|---|---|
| 1 build+install | arm64 APK **56,122,610 B**, sha256 `cb3aa692caa4fd6cd721242fdf770be1fa94c39a82dea76d9897442b1df56f4a` (the manager-verified 02:29 build, `assembleDebug` up-to-date), installed OK; Wi-Fi link to 192.168.1.16:47600, no adb forward, stream 1728x1080 dropped=0 | `apk-sha256.txt`, `wifi-view1.log` |
| 2 Wi-Fi typing | **NOT MEASURED — B-122.** 25-key string typed via the PC input path landed in the Browser (screenshot), phone logged every keyup, but in this state the build sent 0 `lat` reports (0 LATSTAGE; the same build had sent them at 02:51, at the governor's 960x540), so n=0. Two runs pointless without the probe; run 1 only, honestly recorded. Link/band context: 5 GHz 5240 MHz RSSI -30 (manager 13:18), battery 37->35 %, thermal 3, mCallState=0. Stray PC-side clicks (13) also landed in the view from the owner's PC use — noise noted. Manager confirmed the right log (`files/p4/p4.log`, 16.3 MB: 0 LATSTAGE, 1,334 `P5 LAT`, last 02:51:30.934; `files/desktop/` empty). | `wifi-run1-textproof.png`, `wifi-lat-run1.txt` (sent=25 n=0), `wifi-view1.log`, BUGS **B-122** |
| 3 Notepad++ <=10 s | BEFORE (Wine warm-up default ON, `p12nppwarm` OFF): 13.37/12.45/12.26/12.65/12.32 -> **median 12.45 s**. AFTER (app restarted, `p12nppwarm:1`): 17.83/10.88/16.21/11.73/10.96 -> **median 11.73 s**. PSS 125,015 KB after (147-201 MB in the before state) — no regression; procs 3 (Xvfb, wineserver, notepad++.exe resident hidden, no visible window before the click). Manager re-check from independent timestamps (`P6 Starting Notepad++` log line -> `npp-visible.png` write time): 13.30/12.40/12.22/12.61/12.29 -> **median 12.40 s** and 17.52/10.82/16.17/11.66/10.92 -> **median 11.66 s**, so the numbers hold. Honest caveat: spread widened (10.9-17.8 s), the win is 0.7 s on the median, still **1.7 s over the 10 s target**; first AFTER open was the slowest, so the prelaunched N++ was not visibly reused, and the `P12NPPPREWARM` line did not survive, so the cause is plausible, not proven. Verdict (manager): **keep the code, default OFF (FIX LATER), do not enable**; `p12nppwarm` is runtime-only (no prefs write), false at every app start. | `b3-before-1..5/`, `b3-after-1..5/`, `npp-after5.png` |
| 4 B-115 live | **PARTIAL.** PC unpinned (`trust forget` 2B3F10C2 + 2 stale pins), `forget-all` (removed=1, pins=0), desktop restarted, unpaired connect: code 983053 shown on BOTH sides; **zero video frames between the pairing prompt and Yes**; video only after `PAIR_YES` -> `gate open` -> display -> video-on. The Yes came from the owner at +4.46 s, so the >=300 s no-video wait, the `DROP control gate=PAIRING` lines and the deny path are **unproven** (BUGS B-123). Bonus (B-116 refusal path, one sample): while the old desktop was still live, the forgotten PC got `SESSION refused different PC while desktop is live`, the receiver printed one plain `VIEW FAILED` and exited — no reconnect storm. | `b115-gate-evidence.log`, `b115-view1.log`, `b115-view2.log`, `b115-phone-after-pairyes.png` |
| 5 Files/Back/focus | **NOT RUN** (owner on the phone; the Back-on-Settings check needs phone-side input anyway). | — |
| 6 cleanup | Done, see the stop note above. Battery 35 % at the end; Wi-Fi left on; Wi-Fi SSID/band untouched. | — |

Skips to record: item 2 number (B-122, probe regression), item 4 full protocol + item 5 +
B-116 (owner on the phone, B-123). Gates: no code changed this round (BUGS/TODO/STATE docs
only), so the 12:57 gate results stand.

Manager verification (2026-10-24 13:50): item 1 APK hash/size match the 02:29 build output;
item 2 accepted as unmeasured after checking the real log path; item 3 numbers reproduced from
independent log/file timestamps (12.40 / 11.66 s medians vs the coder's 12.45 / 11.73 s); item 4
accepted as partial only (B-115 needs the 300 s wait, `DROP gate=PAIRING`, deny path); cleanup
re-checked by hand (0 app processes, 0 udreceiver, no forwards, `stay_on=0`, unlocked, no call).
No code changed, nothing committed; HEAD still `4ffa297`.

# Night run: Phase 12 finish + Phase 13 polish (continued)

PROGRESS 12:32 CODE-ONLY LANE: B-115/B-116 (tester T-001/T-002) written, B-109/B-110/B-112/B-113/D12/D14 written, now compiling + unit tests. Emulator + receiver still the independent tester's until 14:10, so no adb, no APK install, no udreceiver start/stop, no PC desktop. Rust uses only `C:\Users\Asus\AppData\Local\Temp\p13rust`; Android builds run `gradlew --stop` right after. Live emulator proof for 2-6 starts at 14:10.
Done 13 of 16 | Failed or skipped 3 (Chrome closed with WebView fallback; D12/D14 no longer SKIP - done in code, live proof pending; step-15 phone re-run SKIPS) | Updated 12:32 | Now: compile + tests
PHONE LEAVING 08:45 - restored; owner returns ~15:30-16:40.
PHONE CONNECTED 07:24 - 2c0f6edc; stay_on_while_plugged_in set to USB.
PHONE GONE 06:59 - adb devices empty; the owner took the phone. Do not wait for it.
ORDER NOTE 05:31: step 10 (phone home) runs before step 9 (Chrome) because the home screen is the first thing the owner sees and it was still the old harness screen; Chrome is a longer, riskier engine experiment. Step 9 follows step 10 immediately, then the 30-min keyboard/snap box, then the 45-min encoder-only thermal resolution box (measure one thermal-3 run, then update D22), then steps 11-16.

PHONE 2c0f6edc CONNECTED 00:27. stay_on_while_plugged_in=2 (usb) set 01:37 per manager note 01:37; reset to 0 at cleanup. Wi-Fi OFF. Battery 40 percent, thermal 3, mCallState=0, unlocked.
B-108 DONE 00:41 (phone): per-start 32-byte secret + auth password; app POSTs /login and gives only its WebView the cookie. uid 2000 shell curl / -> 302 to /login (old build: full workbench). VS Code 14.04 s cold / 10.24 s warm. Secret: 0 logcat hits, not in cmdline/environ. Unit tests + build green. BUGS B-108 Fixed, DECISIONS D20.
TYPING 02:49: X11 frame-loop fix IN (skip byte-identical Xvfb grabs + 8 ms poll 300 ms after a key; Phase7X11Frames.kt + unit test Test). Winner verified on the final build, fresh app+receiver: Notepad++ 66.2/73.7 n=25 (was 104.8-108.8, text confirmed in the editor); Browser with an X11 window behind 67.5/84.4 n=25 (was 108.1); Browser alone 65.7/74.5 n=25 (fresh receiver, was 76.5/109.7). enc>rx 4.8-5.6 on all X11 cases (was 31-35): the codec no longer carries identical X11 frames. Test shell still 87.0/97.0 n=25 (>80: the harness ball animation keeps the codec busy; different mechanism). Soak still PASS (350 keys, 0 errors, dropped=0).
PREWARM 03:06 (phone): Wine warm-up shipped ON by default (DECISIONS D21; Phase12Preload default WINE). P12PREWARM wine ok=true ms=6687 and 5249; click-to-visible 12.15 s with the prewarm done (11.2 s in the first run) vs 28.23 s cold without it; still 2.1 s over the 10 s target (Notepad++'s own paint under Box64/Wine). A click 2 s into a 5.2 s boot waited on one monitor and reused it (13.83 s, ONE boot only, NPP running). Boots serialized (ensureLivePrefix/bootAndWait @Synchronized; AppCenter.runExe now goes through ensureLivePrefix). Cost: app PSS 111.9 MB on the empty warmed desktop, Xvfb 62.4 + wineserver 18.1 MB (~192 MB total); with NPP open 418.7 MB total. Connect: receiver window up in 330 ms, first frame <1 s.
PART 1 - FINISH PHASE 12
1 [45 min] DONE B-108 SECURITY (FIX NOW).
2 [20 min] DONE Typing BEFORE in the real apps (shell test field, Browser address bar, VS Code editor, Notepad++), n >= 25 each, p50/p90; table into docs/PHASE12_RESULT.md "Round 2".
3 [90 min] DONE Typing AFTER (X11 frame-loop fix won): cut `drawn>encoded`; one candidate at a time, BEFORE/AFTER each; keep only what helps; winning settings default.
4 [40 min] DONE Notepad++ <= 10 s (prewarm default ON; 12.15 s, miss): Wine prewarm default ON if it does not hurt connect/RAM; BEFORE/AFTER.
5 [15 min] DONE Phase 12 docs: Round 2 table + OPTIMIZATION.md numbers.

PART 2 - PHASE 13: POLISH (follow docs/UX_AUDIT.md when it appears)
6 [30 min] DONE Walk-through BEFORE: 8 pictures in p13/before/ (d0-d6), Agent walk-through + A1-A6 in docs/UX_AUDIT.md §6.
7 [45 min] Quick wins: remove Test from user paths; new wallpaper + 4-6 choices; taskbar on top + windows inside work area + remember place; address bar only in Browser; desktop fills PC screen (receiver tells phone its size/aspect).
8 [120 min] Design system + shell: colours, type, spacing, radius, vector icons, taskbar, start menu with search, title bars, snap, double-click maximize, right-click menus, keyboard shortcuts.
9 [90 min] DONE Browser -> Chrome (D19): Chrome closed at the 10:40 box (B-003/B-111); full WebView browser shipped and proven.
10 [90 min] DONE Phone app home screen (Material 3, dark/light, connection card, Start/Stop, pairing code, trusted PCs, checklist, How to connect, Settings; HarnessActivity stays, no LAUNCHER in release).
11 [90 min] DONE PC receiver window (GUI, find phone/pair/connect/Exit, plain errors, no console window, CLI subcommands keep working).
12 [60 min] DONE User-facing bugs: B-091, B-085, B-105, B-097, B-083/B-087/B-088/B-093 - one at a time, proof each.
13 [30 min, then stop] DONE B-103 paste into Notepad++: no new Wine-source idea this round; B-103 stays OPEN.
14 [30 min] DONE Walk-through AFTER into p13/after/, side by side in docs/PHASE13_RESULT.md; every UX_AUDIT item done/SKIPS/decided.
15 [20 min] DONE with caveat Typing AFTER on the final build: Phase 12 phone numbers used; final-build phone re-run is SKIPS (phone absent), VS Code caveat B-110.
16 [in progress] CLOSE-OUT from 12:30 or when done: gates, docs, cleanup, STATE.md last, final report.