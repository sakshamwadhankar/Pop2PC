# PROMPT — coding agent: finish Phase 13 (Part 1 now; Part 2 when the phone is back)

You are the **coding agent** for `unified-desktop` (working codename; **never invent a product
name**). You do not commit or push; git belongs to the manager.

Read first, in full: `AGENTS.md`, `STATE.md` (top two bullets), `docs/HANDOVER_2026-10-24.md`,
`BUGS.md` rows **B-109 B-110 B-112 B-113 B-115..B-121**, `docs/TEST_REPORT.md` T-001..T-007,
`SKIPS.md` rows 15-18, `docs/UX_AUDIT.md`, `docs/PHASE13_RESULT.md`, `TODO.md`,
`docs/PROMPT_CODER_NEXT.md`, and this file. If a doc disagrees with the live tree, the tree wins;
report the stale doc instead of guessing.

## Verified state you inherit (manager re-checked 2026-10-24 13:10; do not redo this)

- Last commit `4ffa297` (docs-only recovery audit, 2026-10-24 02:41; `aad9bbf` before it). The
  Phase 13 work is **uncommitted** in the tree.
- Android gate re-checked by the manager at **12:57** on these exact sources (build 02:29; no
  Android source changed since 02:28): **214 tests / 0 failures / 3 skipped**, lint **0 errors /
  142 warnings**, `assembleDebug` OK. PC gate re-run 12:57: `cargo test --release` **100/0** +
  `lock_crash` **1/0**, `cargo clippy --all-targets --release -- -D warnings` **exit 0**,
  `git diff --check` **0**.
- In the tree, with unit tests: **B-115** (pairing gate hole), **B-116** (one-sided trust +
  reconnect backoff), **B-109** (App Center honest open), **B-110** (client→desktop mapping),
  **B-112** (GUI lock after forced kill), **B-113** (duplicate probe branch), **T-004/B-121**
  (Back on Settings), **T-005/B-118** (desktop-drawn Files delete modal), **T-006/B-119**
  (rename/+ prompts, folder select), **T-007/B-120** (no 2 s focus steal, no synthetic Alt),
  **T-003/Dobby** (16 KB aligned), **D12** (toasts), **D14** (welcome card + shortcuts).
- ELF check of the built APKs: every x86_64 `lib*.so` has LOAD `p_align 0x4000`; **arm64
  `libbox64.so` is still `0x1000`**. The 16 KB candidate `android/win86/vendor/libbox64-16kb.so`
  (22,390,072 B) is built and ELF-checked but **not shipped**.
- The previous coder (`ses_e963…`) died at **00:10:33** on the free-model 429 limit. It produced
  **no** Files/rename/Back/focus/16 KB evidence; `p13fix/` has receiver logs only. It was chasing
  an out-of-scope "receiver exits by itself" issue. Do not repeat that.
- The pi coder `01a16a89` died at **03:02** on the same limit. It left a **Phase 12-only**
  candidate in the tree (`p12nppwarm`, default OFF, switch `p12nppwarm:1`); leave it alone — it
  is not part of this task.
- No phone (`2c0f6edc`) and no emulator are attached (13:10). Free laptop RAM was **2.4 GB**
  after `gradlew --stop`; the AVD asks 6 GB. If the emulator will not start, say so with the
  numbers, do the docs close-out that needs no device, and stop — do not close the owner's apps.

## Hard rules for this task

1. **One live run per claim.** A code read is `code-only`; never write "verified" for it.
2. Evidence in `C:\Users\Asus\AppData\Local\Temp\opencode\p13done\` with clear names. Keep
   pictures and logs; do not delete evidence.
3. Desktop pictures come from **`p6:shot NAME`** then
   `adb exec-out run-as dev.unifieddesktop.app cat files/shot-*.png > p13done\NAME.png`.
   `adb screencap` shows the harness, **not** the desktop.
4. PC input: use `C:\Users\Asus\AppData\Local\Temp\tester\pcdrv.py` (it refuses to send unless a
   `udreceiver` window is in front) or `C:\Users\Asus\AppData\Local\Temp\udmlive\click.ps1`.
   Screenshot the receiver with `python pcdrv.py shot NAME`.
5. **Do not chase a receiver that exits on its own.** If it reproduces, add a new `BUGS.md` row
   with the log, relaunch once, and continue. It is not part of Part 1.
6. Do not edit product code unless a live check proves the tree is broken in the items below.
   Anything older/outside scope goes to `BUGS.md`, not into this task.
7. Time boxes: Part 1 **60 min**, Part 2 **90 min**, final phone round **45 min**, docs/gates
   **45 min**. At a box: stop, write exactly where it failed, add a `SKIPS.md` row, report.
8. Do not loop on a 429. Stop and report; the manager restarts you.
9. Do not commit or push. Do not delete `undefined/` yourself; the manager removes it before the
   commit.

---

## Part 1 — emulator live proof (do this first; 60 min)

Emulator: AVD `Pixel_8_Pro` (x86_64, Android 17, 16 KB pages), SDK
`C:\Users\Asus\AppData\Local\Android\Sdk`. Build the APK from this tree, record its **sha256 and
size** in the report, install `app/build/outputs/apk/debug/app-x86_64-debug.apk`.

1. Start the app and the desktop (this exact recipe):
   - `adb shell am broadcast -n dev.unifieddesktop.app/.HarnessReceiver --es phase4x start`
   - `adb shell am start -n dev.unifieddesktop.app/.HarnessActivity`
   - `adb forward tcp:47600 tcp:47600`
   - start `pc\target\release\udreceiver.exe view --addr 127.0.0.1:47600 --no-lock` with a log
   - when the log prints `PC code:`, run
     `... --es phase4x accept`
   - open Files: `... --es phase4x "p6:open:files"`
2. **Files delete (B-118), both outcomes:** click a file, click the trash icon; the modal must be
   drawn on the desktop. **Cancel** keeps the file; repeat and **Delete** removes it. The desktop
   clock must keep ticking after both (two `p6:shot` 5 s apart show a later time), and `p4.log`
   must have **no** `BadTokenException` and the app **no ANR** (`dumpsys activity processes` /
   `logcat` check).
3. **Rename (B-119):** pencil on a file -> a name field appears; type a new name **with an
   extension** (e.g. `notes.v2.txt`) and confirm it is kept exactly. Rename a folder too.
4. **"+" (B-119):** it must ask for a name. Empty and `../x` must be refused with a plain error
   and create nothing. A normal name creates the folder.
5. **Folder click (B-119):** one click selects (status line says selected), a double click opens.
6. **Back on Settings (B-121):** start `.HomeActivity`, open Settings, `adb shell input keyevent 4`;
   a screenshot must show the **home page**, not the launcher.
7. **B-120 focus:** with the view running, put another Windows window in front and leave it there
   **>= 60 s**. The view log must contain **0** `keyboard focus reclaimed` lines and no synthetic
   Alt may reach the other window (its menu bar must not open). Then focus our view
   (`python pcdrv.py focus`) and type 8 characters into a Files rename field or the Browser box;
   they must land on the desktop.
8. **B-115 live (security):** `udreceiver trust forget <phone-prefix>` on the PC so it is
   **unpinned**; `phase4x forget-all` on the phone; start the phone desktop; start
   `view --addr 127.0.0.1:47600 --no-lock`; **do not tap Yes**. The phone shows the 6-digit code;
   the PC must show **no video** (window stays black, `presented=0`), the phone log must show
   `DROP control "video-on"` / `display` with `gate=PAIRING`, and no desktop picture may appear
   on the PC. Then `phase4x deny`: the PC must end with a plain message and the phone desktop must
   go down. Then repeat and `phase4x accept`: video starts normally.
9. **B-116 live (one-sided trust):** while the PC **still pins** the phone, `phase4x forget-all`
   on the phone, start the phone desktop, then start the receiver (`--window` GUI path and the
   `view --lock` path, one after the other; end a lock run with the Exit button or `lock-exit`).
   Expected: the PC shows the code and **does not lock**
   before Yes; after Yes it pairs and locks. Refusal case: phone desktop live for another PC and
   the forgotten PC connects -> the PC must show the plain "already showing a desktop" message
   and **stop**, not reconnect every ~250 ms; the log must show the backoff 500/1000/2000 ms, not
   250 ms.
10. **16 KB (B-117):** install the x86_64 APK on the 16 KB AVD; the "isn't 16 KB compatible"
    dialog must be gone (screenshot). Run the ELF check on the APK contents and list every
    `lib*.so`; x86_64 all `0x4000`; **arm64 `libbox64.so` is still `0x1000`** until Part 2.

Write `PROGRESS` + `Done X of Y` in `TODO.md` after each numbered item.

---

## Part 2 — phone `2c0f6edc` (90 min, only when `adb devices` shows it)

If the phone is not attached: say so plainly, do Part 1 and the honest docs close-out, and stop.

1. **box64 16 KB swap (B-117), carefully:**
   - Back up the shipped lib: copy `android/app/src/main/jniLibs/arm64-v8a/libbox64.so` to
     `android/win86/vendor/libbox64-4kb-shipped.so` (both paths are gitignored).
   - Copy `android/win86/vendor/libbox64-16kb.so` over `jniLibs/arm64-v8a/libbox64.so`, build the
     **arm64** APK, install it, boot Wine and open Notepad++ **with typing**.
   - If Wine boots and the window renders and typing works: keep the candidate, rebuild, and say
     so. If it storms or does not boot (B-026): restore the backup, rebuild, log the exact failure
     and keep B-117's box64 half **open**.
   - ELF check both APKs: every `lib*.so` must be LOAD `0x4000`; report every file.
2. **B-115/B-116 on the phone:** repeat Part 1 items 8-9 with the same expected logs; label them
   `phone`.
3. **First-time walk-through (the Phase 13 gate):** fresh install state with no trusted PCs ->
   open the app -> pair (compare the code) -> desktop -> open one app -> Exit -> PC back to the
   normal desktop. Pictures of every screen; measure Exit (click -> PC usable) and connect
   (start -> first desktop frame).
4. **Final-build phone round (SKIPS row 17 + UX D16/step 15):** typing in Browser, Notepad++ and
   VS Code, **n >= 25 each**, p50/p90 with the `LATSTAGE` probe; connect-to-visible; a drag VSTAT
   (fps). This is the Phase 13 APK's own row, not the Phase 12 numbers.
5. **D22 real thermal-3 run (SKIPS row 18):** if the phone reaches thermal 3 (or the same
   override), 30 s with an animated window: encoder/PC fps, dropped frames, decode ms, and a
   no-crop picture. If the phone cannot reach it, keep the SKIPS row; do not fake it.
6. **Cleanup:** force-stop the app, stop the receiver, remove adb forwards, restore
   `stay_on_while_plugged_in=0`, brightness, Wi-Fi and tethering exactly as found, stop Gradle
   daemons. Verify 0 app processes.

---

## Part 3 — docs + gates + report (45 min; after Part 1, and after Part 2 if the phone was there)

- `BUGS.md`: update **B-109, B-110, B-112, B-113, B-115..B-121** with status **and** the evidence
  file names + the label (`emulator` / `phone` / `code-only`). B-118/B-119/B-120/B-121 may be
  called FIXED only with the live proof above. B-117 says exactly which half is fixed and which
  file is still `0x1000`. B-103 stays OPEN (FIX LATER); B-111 is the decided WebView fallback;
  B-107 needs one clean PC test run, not a fix.
- `SKIPS.md`: remove rows 15/16 (D12/D14) **only** if their live proof exists; update rows 17/18
  with the phone result or leave them with the honest reason; add any new deferral.
- `docs/PHASE13_RESULT.md`: add the Part 1/Part 2 tables (what, evidence, label, numbers) and the
  final gate verdict; correct §5/§2 where they still call D12/D14 SKIP or B-112/B-113 open.
- `docs/UX_AUDIT.md` §7: D12/D14 statuses and anything the live run changes.
- `TODO.md`: update the `Done X of Y`, `PROGRESS`, and the final line.
- `STATE.md` **last**, replacing stale lines: Phase 12, Phase 13, Phase 14 status, what is still
  unproven, and the next action.
- Gates: Android `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`; PC
  `cargo test --release` + `cargo clippy --all-targets --release -- -D warnings`;
  `git diff --check`. Record the counts, lint errors and APK sha256/size.
- Report in the AGENTS.md format: **item | what you did | files | test | verified on | numbers**,
  then what failed, what was skipped (with SKIPS row), bugs logged, and what is unknown.
  Then **stop**; the manager verifies and commits.

## What not to do

- Do not start Phase 14 (Google Play): the product name comes from the owner, and arm64 is not
  16 KB-ready yet.
- Do not re-open Chrome (B-111) or Wine paste (B-103).
- Do not touch the Phase 12 speed work here; it has its own prompt
  (`docs/PROMPT_CODER_PHASE12_FINAL_2026-10-24.md`, which supersedes `PROMPT_CODER_PHASE12.md`),
  handed after this task.
- Do not add a new framework, a new dependency, or a product name.
- Do not commit binaries, APKs, images, keystores, or evidence.
