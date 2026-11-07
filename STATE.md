# STATE: where the work stands

Rules: replace stale lines, never append history, never record a status that was not
verified this session. The coding agent updates this as its last action; the manager
corrects it after review.

## Now

- **Position (2026-11-07, manager review): PHASE 13 IS COMPLETE.** T13-10 closed the last two
  items with live proof: B-130 fixed (ChangePropertyWire), B-103 fixed END-TO-END (focus-gain
  re-read + wine conversion; marker `FOCUSDROP1` pasted into Notepad++ through the real
  product path), and the D22 hot-stage paired-proof captured via the manager-approved
  debug-only thermal injection (release-guarded, 5 unit tests). Gates: Android 247/0/3,
  cargo 103/0, clippy 0, diff-check 0. Evidence: `Temp\opencode\t13-10\RESULT.txt` +
  `D22-RESULT.txt`. **Phase 14 opens next: the product name is `PopToPC` (DECISIONS D12,
  owner 2026-11-07) — rename + Play preparation.** Everything below is `phone`-verified on
  `2c0f6edc` unless marked otherwise.
- **Phase 12 round 4 (2026-11-01, final tree): 6 pass, 2 miss.**
  - PASS: connect 0.944 s; Wi-Fi typing **p50 107.1 / p90 119.6 ms** (n=100, 5 GHz, no adb
    forward); moving-window fps 42.0; VS Code 13.48 s cold; old-PC 1080p proxy; governor drop.
  - MISS: **Test-shell USB typing p50 81.7 ms** (n=74, target 80; `drawn>encoded` 41.8 ms is the
    hardware encoder floor) and **Notepad++ start 10.45 s** (n=5, target 10; 9.87 s of it is
    Notepad++ under Wine/box64). Both are in **SKIPS.md** with the exact "done when" condition.
  - **B-122 FIXED** (typing probe fires in the product path). Encoder knob A (operating rate
    120) measured worse and was reverted; knob B (8 Mbps) failed a separate-session control and
    was reverted (`Quality.kt` is back to 12 Mbps, `QualityPlanTest` fixed, gate command green).
  - **B-124 (new, minor):** the NPP prewarm shows a ghost window and can leave two Notepad++
    processes when a real open races it. The switch stays **default OFF**; do not enable it
    before the race is fixed.
  - Full numbers, stages and evidence: `docs/PHASE12_RESULT.md` "PHASE 12 GATE" and
    `OPTIMIZATION.md` 5.3.
- **Phase 13 (2026-11-02, manager review).** B-115 is phone-proven; **B-116 is fixed** from
  T13-2 plus the B-126 closure; B-125 is fixed with screen-off/keyguard notification proof; and
  **B-126 is fixed** in T13-3b. On phone
  `2c0f6edc`, normal pairing, reverse one-sided re-pair accept, and deny all passed. Both sides
  showed code `983053`; before Yes, VSTAT stayed 0 for 27 s and reconnects stayed 0; after Yes,
  the phone logged `REPAIR stored` and `presented` advanced 0 -> 22. The re-pair gate stayed
  closed until Yes. Evidence: `Temp\opencode\t13-3b\RESULT6.txt`. Thermal status was 3, so
  the performance precondition was not met; this does not affect the security proof. The phone
  was left locked and clean.
- **T13-6 / B-117 (2026-11-04):** the 16 KB Box64 artifact is verified in the APK and on the
  phone. All 15 arm64 APK libraries, including `libblackbox.so` and `libdobby.so`, have
  `PT_LOAD p_align=0x4000`. Wine/Xvfb and Notepad++ ran on phone `2c0f6edc`; 767 frames were
  presented with 0 dropped, and real PC input typed `b117ok`. Thermal Status stayed 3, so no
  performance number is claimed; the phone is 4 KB-page and the 16 KB result is ELF/APK
  verified rather than physical-16-KB-device verified. Evidence: `Temp\opencode\t13-6\RESULT5.txt`.
- **T13-8 overnight (2026-11-05/06, manager-verified from raw evidence):** Files
  delete/rename/new-folder/select/open, B-121 Back, B-120 focus (66 s, 33/33, 0 reclaims), D12
  toasts and the D14 welcome card all PASS live; connect 0.104 s window / 1.139 s first frame;
  drag fps 45.4/54.0 dropped 0; NPP start 12.60 s median and typing p50s 96.4-126.9 ms recorded
  with honest caveats (locked/screen-off phone, owner PC load) - the Phase 12 records (81.7 ms /
  10.45 s) stay the formal numbers. D22 live hot-stage NOT PROVEN: the framework override never
  reaches the governor (**B-127**, new), and the VS Code editor typing gate is blocked by
  **B-128** (new). Evidence: `Temp\opencode\t13-8\RESULT.txt`.
- **T13-9 (2026-11-06/07, manager-verified from raw evidence): B-128 FIXED and committed** -
  `KeyWebView.onTouchEvent` restarts the IME input on every pointer UP (pure `ImeRebind` +
  `KeyRoute.decide`, 9 unit tests); `probe` lands in the Monaco editor while the Chat path
  still works; gates 233/0/3, cargo 103/0, clippy 0. **The VS Code editor typing gate is
  CLOSED**: 3x25 keys in-editor, on screen and on disk, p50 103.6-116.8 at thermal 0.
  Screen-on re-take: connect 0.130/1.158 s; typing best p50 82.2/84.7 vs 80 (best on this
  tree, still a miss); NPP start 21.03 s (screen-on contention); drag 42.3/49.0 fps dropped 0.
  **B-103 not fixed but isolated**: serve path proven to wine; blockers are the Android
  background-clipboard-read policy (phone-side) and a paste conversion that never starts
  (wine-side; our malformed format=32 TARGETS replies - new **B-130** - are the prime
  suspect). New **B-129** (Monaco Ctrl+A inserts). D22 real heat not reached again (204
  drags / 45.2 min on USB power, thermal 0). Evidence: `Temp\opencode\t13-9\RESULT.txt`.
- **Next Phase 13 work:** T13-10 - fix **B-130** (format=32 ChangeProperty length), add the
  manager-approved focus-gain clipboard re-read for B-103's phone-side half, retest the
  B-103 paste end-to-end, and close the D22 paired-proof via the debug-only thermal injection
  (B-127 decision) with a real-heat multi-window attempt alongside. Phase 14 follows Phase 13
  and needs the product name (**DECISIONS D12 — decided 2026-11-07: `PopToPC`**).
- **Deferred post-launch plan (2026-11-04):** `PLAN.md` now records an optional Controller mode
  for controlling the PC from the phone, including app control, touchpad/keyboard, explicit
  clipboard transfer, media/system controls, capture and signature features. It is explicitly
  deferred until Phases 1-14 are complete and the first release is launched; it does not change
  the current scope or gates.
- **How to measure (recipe in `docs/prompts/T12-3.md` and `T12-4.md`):** cool to Thermal Status
  <= 1 (screen off, poll every 2 min), USB `adb forward tcp:47600 tcp:47600` (or the real Wi-Fi
  socket for Wi-Fi runs), `udreceiver view --addr ... --no-lock`, `p6:open:test`, 25 keys per run
  with the SendInput runner, count `LATSTAGE` from the receiver log. Desk must be **1728x1080**;
  never measure at thermal >= 2; one session never proves a 1-2 ms change (session spread is
  8-20 ms).
- **Rules that bit us this round:** the coder labels PASS/FAIL against the wrong criterion, so
  the manager re-derives every number from the raw `LATSTAGE` lines before recording it; the
  coder may only touch `TODO.md` in docs; the phone must be left clean after every task
  (`phase4x stop`, force-stop app, `forward --remove-all`, `stay_on=0`, 0 receivers).
- **Watch chain:** `Temp\opencode\p12full\watchdog.py <session-id>` wakes the manager the moment
  the coder dies or stalls (no worker child for 6 min); `picheck.py 20min` sends the periodic
  ntfy progress note. Restart both after any OpenCode server restart - a server restart kills
  the timers and the coder's child receivers (that caused a false "parallel coder" alarm).
- **Next:** continue the remaining Phase 13 phone proofs in the order above, then complete Phase
  14. The deferred Controller mode is not part of the current release plan.

## Done (verified on the phone, 2026-10-14)

- **T1 engine** — `docs/PHASE1_ENGINE.md`: pick **Blacks-BlackBox** (Apache-2.0).
- **T2 build (G6)** — the whole `android/` project now **builds**: Java, Kotlin, AIDL, the
  native Dobby+BlackBox engine, dex, APK. Fixes made this session: library `targetSdk`
  removed, AGP-9 `sourceSets` DSL, `local.properties` path, submodule names
  (`:black-reflection`/`:compiler`), dropped the bad kotlin-stdlib pin, Bcore manifest
  wired in, Dobby re-pinned to self-consistent `e9fe7fb` + NDK 26, two Dobby/Bcore source
  patches (case-collision, `Logger::Shared` inline) applied reproducibly in `fetch-source.sh`.
- **T3 virtualization** — BlackBox runs a real Play app (Google Tasks) inside our app
  (ProxyActivity, uid 10189, process `:p0`); it renders. **But on the real screen, not our
  display.** Evidence in `evidence/phase1/`.
- **T5 numbers** — 2 processes (host + `:black`), TOTAL PSS ≈ 43.7 MB. No frame ever lands
  on our display.
- **T6** — `docs/PHASE1_PLAY_POLICY.md` (REQUIRE_SECURE_ENV, avoid QUERY_ALL_PACKAGES).

## Round 2 results (emulator; phone unavailable)

- **T0:** `evidence/phase1/round2_placement.txt` explains the activity-record versus
  actual-window display contradiction: the proxy task record is under the requested private
  display, while the guest WindowState is on built-in display 0.
- **T1:** the final smoke run received 97 ImageReader frames in 10.684 s (**9.08 fps**),
  first frame **833 ms**; samples stayed in app-private storage.
- **T2 B1:** public Presentation decor reparenting failed with
  `IllegalArgumentException: ...DecorView... not attached to window manager`.
- **T2 B2:** delayed public SurfaceControlViewHost reparenting reached
  `B2_SURFACE_PACKAGE_SHOWN`, but the ImageReader received no guest frame. No B3 public
  alternative was found without the same ViewRoot reparenting problem or forbidden APIs.
- **T3/T4:** not run because T2 did not produce guest content. Deferred rows are in
  `SKIPS.md`.
- **G6:** `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` pass.

## The blocker (why NO-GO)

A Play-installed app cannot launch ANY activity (not even its own) onto a virtual display
it creates. Android's check (`ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay`,
AOSP main, read by the manager 2026-10-15): an app-made display is untrusted, so the
activity needs `allowEmbedded="true"` AND the caller needs `ACTIVITY_EMBEDDING`
(signature|privileged) or an activity of its uid already on that display (impossible for
the first one). Trusted displays need a system permission; PUBLIC displays need a
MediaProjection grant. Phone evidence: the SecurityException in
`evidence/phase1/selftest_launchDisplayId_denial.txt`. Details and the manager's
corrections in `docs/PHASE1_RESULT.md`.

## Open

| Item | Notes |
|---|---|
| Bets: Phases 1-4 | ARCHITECTURE.md section 6: Q1-Q4 answered (records in `docs/PHASE1_RESULT.md`...`PHASE4_RESULT.md`); Q5 NOT PROVEN - AOA round deferred (SKIPS.md row); Q6 answered (custom framed protocol over TCP + TLS 1.3, not QUIC/WebRTC). |
| Phone round (Phase 6 item 8) | **RUN 2026-10-19 08:02-08:31, PASS** except the typing-latency samples (SKIPS.md residual: no `P5 LAT`/`LATSTAGE` during PC typing - needs the probe path explained). Evidence: `docs/PHASE6_RESULT.md` section 1b + the phone PNGs, all captured from the PC window's video rectangle. |
| Phase 9 (2026-10-21) | **PASS (review rework + manager gate).** Private desktop lock + Exit, 8/8 blocked keys, strict PID journal + PID-aware guard, session mutex before recovery, fail-closed start/recovery, verified Default exit. Phone: second view refused 42 ms; lock-recover refused while locked; Exit 291 ms; frozen/key click 641/984 ms (lock paths 100/211); phone gone 1134 ms (lock 76); guard 12 ms/426 wall; 8/8; selftest PASS; clean/unreadable leftover desktop 75/59 ms; T17 next view after guard+lock kill recovered the dirty journal and removed it. Gates: 149 Android/0/3 skipped, 55 PC/0 release, clippy 0, diff check 0. Skips: Windows 10 (no hardware), policies (not-admin), multi-monitor (code-only). Record: `docs/PHASE9_RESULT.md`. |
| Phase 8 (2026-10-20) | **Round 6 complete; gate PASS on the phone with fresh 10/10.** Catalog 14, picker 11/11; B-083/B-085..B-092 fixed and phone-verified; Zoom's missing package routes to the official Browser Play page (B-092). Fresh content 10/10 (VS Code, Tasks, Zoom Play, Excalidraw, Photopea, xeyes, xcalc, Notepad++, 7-Zip, Minesweeper). Gates at the time: 148 Android tests / 0 failures / 3 skipped, lint+assemble green; 45 PC tests / 0; clippy exit 0. B-093 was fixed in Phase 9 (phone: `X11 desktop — winemine`). Evidence: `docs/PHASE8_RESULT.md` Round 6, `p8r6_*`. |
| Phase 7 round 4 (2026-10-20) | **PARTIAL - gate not met.** B-070/B-071/B-073/B-074/B-075/B-076 fixed + proven; gate picture `p8/gate_npp.png`; `npp7` typed; `code7` not typed (B-078, Explorer/Quick Open). Record: `docs/PHASE7_RESULT.md` "Round 4". |
| B-056 / B-057 / B-058 (Phase 6) | Open in BUGS.md awaiting the manager's decision: B-056 emulator encoder configure rejection (mitigated in code); B-057 PC black window on the **emulator** stream only (FIX NOW per the manager; the phone round did NOT reproduce it; its PC half is now written + tested - cargo 42/42); B-058 title-bar drag lost the y delta on the phone (a real SendInput drag this round moved nothing at all - still open). |
| B-062..B-065 (Phase 7) | Mostly closed by round 4: PC keys reach Notepad++ (`npp7` landed, B-062), xeyes is placed by `place` (B-063), the harness handles multi-part commands (B-064), the VS Code window loads code-server (B-065). Open from Part A: B-077 (X11 flood after a lost key-up) and B-078 (VS Code Explorer/Ctrl+P from the PC). |
