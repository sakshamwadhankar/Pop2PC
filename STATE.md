# STATE: where the work stands

Rules: replace stale lines, never append history, never record a status that was not
verified this session. The coding agent updates this as its last action; the manager
corrects it after review.

## Now

- **Position (2026-11-02, manager takeover): Phase 12 is measured and recorded; Phase 13 is
  running; Phase 14 not started.** Pairing/reconnect security checkpoint committed locally as
  `c893f3e` (not pushed). The remaining Phase 12/13 work is still uncommitted and must be split
  into later reviewed commits. Everything below is `phone`-verified on `2c0f6edc` unless marked
  otherwise.
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
- **Phase 13 (in progress, 2026-11-01 02:10).** **B-115 PASS** (T13-1, phone): unpaired PC got
  only the pairing exchange - `DROP control "display 1728 1080" (gate=PAIRING)` and
  `DROP control "video-on" (gate=PAIRING)`, `P5 VSTAT sent=0`, two full 300 s waits with 0 video
  frames, deny stored nothing, accept opened the gate (181 VSTAT, presented=166). Evidence
  `Temp\opencode\t13-1\RESULT3.txt`, recorded in `docs/PHASE13_RESULT.md` section 7.
  **B-125 found (major):** with the phone screen off/locked the pairing code is invisible and the
  notification is suppressed (`PAIRING notification skipped: app in front`); decision FIX NOW in
  Phase 13. **T13-2 done (phone):** B-116 (a) PASS (lock desktop never engaged before trust -
  `input-desktop=Default` at ~4 s and ~10 s, codes matched), (b) PASS (live backoff
  500/1000/2000/4000/8000/10000 ms, **0** reconnects vs the old 115 per 30 s), (c) PARTIAL:
  desktop-live refusal and deny are plain on both sides, but the **reverse** one-sided case is
  **B-126** (new, major): the PC forgets, the phone still pins it, the PC shows a code the phone
  never shows, the phone answers `trusted`, then `SESSION dead-timeout` ~15 s and the PC prints
  `VIEW FAILED: phone closed the connection while pairing`. FIX NOW, after T13-3. Evidence
  `Temp\opencode\t13-2\RESULT4.txt`, recorded in `docs/PHASE13_RESULT.md` section 7.
- **T13-3 (B-125) FIXED** (code + 4 unit tests + full gate green 10:14). Live on the phone:
  screen off -> `posted fg=false interactive=false keyguard=true`; screen on + keyguard ->
  `posted fg=false interactive=true keyguard=true`; code **983053** matched the PC and the
  notification text, `vis=PUBLIC`, deny removed it. **Limit:** the pre-fix skip state
  (`AppForeground > 0` behind the keyguard) is not reachable on demand - the system marks the
  activity sleeping/invisible, so the counter reads 0; that input is covered by the unit test.
  Recorded in BUGS.md, `docs/PHASE13_RESULT.md` section 7, SKIPS.md.
- **Running now: T13-3b, the B-126 fix** (PC reads the phone's greeting before showing a code;
  phone shows a real prompt for `pair-request` even when it pins the PC; tests both sides;
  live reverse-case re-proof on the locked phone). The phone is on its lock screen and must
  stay locked.
- After that: D12 toasts, D14 welcome card, Files/Back/focus, box64 16 KB swap + Wine proof,
  D22 thermal-3, final phone round.
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
- **Next:** finish T13-1, verify its raw logs, then T13-2 (B-116 live proof), then the remaining
  Phase 13 phone items in the order above. Phase 14 follows Phase 13, not before.

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
