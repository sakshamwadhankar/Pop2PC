# PROMPT — coding agent: COMPLETE PHASE 12 (final, 2026-10-24)

You are the **coding agent** for `unified-desktop` (working codename; **never invent a product
name**). You do not commit or push. Git belongs to the manager.

Read first, in full: `AGENTS.md`, `STATE.md` (the **MANAGER AUDIT** bullet), `docs/PHASE12_RESULT.md`
(Round 2 and the PHASE 12 GATE), `OPTIMIZATION.md`, `BUGS.md` **B-103, B-106, B-107, B-108**,
`SKIPS.md` row 19 (Wi-Fi typing), `docs/PHASE4_PROTOCOL.md`, and this file. The older
`docs/PROMPT_CODER_PHASE12.md` is history only; **this file wins**.

## Facts you inherit (manager-verified; do not redo)

- **Phone `2c0f6edc` is on the same Wi-Fi as the laptop.** The owner said the USB cable and USB
  debugging would be attached within **~20-30 minutes**; at the manager's last check (01:58)
  `adb devices` was still empty. **Start with the setup/prep below; poll `adb devices`; do not
  block, and do not ask the owner to wait.** If the phone shows an "Allow USB debugging?"
  prompt, the owner accepts it; if adb says `unauthorized`, wait and re-check.
- Last commit **`aad9bbf`**. The working tree carries the uncommitted Phase 13 fixes (B-115/B-116,
  T-004…T-007, D12/D14). **Build from this tree; record SHA-256 and size; do not revert it.**
- Gates on this exact tree (manager, 22:25 + 01:30): Android **214/0/3**, lint **0 errors**;
  PC **100/0** + `lock_crash` 1/0, clippy `-D warnings` **0**, `git diff --check` **0**.
- Already PASS on the Phase 12 build and **not to be proven from scratch**: connect **0.944 s**,
  drag **42 fps**, VS Code **13.48 s**, old-PC proxy **~36 fps**, governor 1080p→540p,
  audio/clipboard/files, 2-hour soak. Re-confirm the quick ones on this build at the end.
- The three open items:
  1. **Wi-Fi typing not measured** (the owner's Wi-Fi is on now).
  2. **USB Test-shell 87.0 / p90 97.0 ms** (target ≤ 80); real apps already pass
     (Browser 65.7, Notepad++ 66.2, VS Code 75.5).
  3. **Notepad++ start 12.15 s warm** (target ≤ 10; 28.23 s cold; Wine prewarm ON, D21).

## THIS PROMPT COVERS / OUT OF SCOPE

Covers: the three open Phase 12 items, the final Phase 12 gate table on this build, docs, gates,
cleanup. Out of scope: Phase 13 live proofs, Phase 14, Chrome (B-111), Wine paste (B-103),
unrelated refactors.

## Acceptance — this is the only definition of done

| # | Item | Target | Pass rule |
|---|---|---|---|
| 1 | **5 GHz Wi-Fi typing** | p50 ≤ 120 ms | n ≥ 25, p50 and p90, **two runs**, real app, **the link is the Wi-Fi IP (no adb forward for video)**, band recorded (5xxx MHz = 5 GHz). A 2.4 GHz number is reported separately and **does not** pass the 5 GHz target. |
| 2 | **USB typing, real apps** | p50 ≤ 80 ms | Browser, Notepad++ and VS Code; n ≥ 25 each; p50 and p90. |
| 3 | **USB typing, Test shell** | p50 ≤ 80 ms | The known miss is 87.0/97.0. Try the one candidate in §3; n ≥ 25 BEFORE/AFTER. If it stays > 80, **Phase 12 is NOT passed** — say so. |
| 4 | **Notepad++ start** | click → window visible ≤ 10 s | Median of **5** opens, cold and warm, prewarm ON; try the bounded candidates in §4. If it stays > 10, **Phase 12 is NOT passed** — say so. |
| 5 | **Other PRD targets on this build** | as in `docs/PHASE12_RESULT.md` | Re-confirm connect ≤ 5 s, drag ≥ 30 fps, VS Code ≤ 15 s, old-PC software decode and governor if `view.rs`/encoder changed. |
| 6 | **Before/after table** | — | Every change has BEFORE and AFTER numbers from this build, same app/text/thermal. |

A prompt cannot manufacture a number. If a target fails after the bounded attempt, report the
honest number and stop; do not fake, do not round, do not call it a pass.

## Time boxes (sum ≈ 4-4.5 h active; wall clock 5-6 h with pauses)

| Box | Work |
|---|---|
| 0:00-0:30 | Read, build both sides, record SHAs/sizes, prepare the `LATSTAGE` parser; poll `adb devices`. |
| 0:30-1:15 | Install/start; **Wi-Fi typing** (two runs, band/thermal). |
| 1:15-2:45 | **USB typing**: real apps, Test shell BEFORE; implement the stale-frame candidate; AFTER; second run if it wins. |
| 2:45-3:45 | **Notepad++**: warm/cold BEFORE; pre-launch candidates; RAM/process count; AFTER. |
| 3:45-4:30 | Re-confirm the passing targets; docs; gates; cleanup; report. |

At a box end: stop, write exactly where it failed, add/update a `SKIPS.md` row, report. Do not
churn.

## 1. Setup (start now; no phone needed)

```bash
cd /c/Users/Asus/Documents/Github/unified-desktop
git status --short; git log -1 --oneline
cd android && ANDROID_HOME=/c/Users/Asus/AppData/Local/Android/Sdk ./gradlew \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
cd ../pc && cargo build --release && cargo test --release
```

- Evidence root: `C:\Users\Asus\AppData\Local\Temp\opencode\p12final\` (new folder).
- Record `sha256sum` and byte sizes of `app-arm64-v8a-debug.apk` and `udreceiver.exe` in the report.
- Prepare the `LATSTAGE total` parser from `PHASE12_RESULT.md`; the receiver prints the stage
  split; the phone log is `files/desktop/p4.log` (pull with `run-as`).
- Check the phone every 3-5 min: `adb devices -l`. When it appears, record
  `adb -s 2c0f6edc shell getprop ro.product.model`.

## 2. Start the phone and prove the **Wi-Fi** link (after adb appears)

- USB is for **adb control only** in this step. **Do not use `adb forward` for the video/link**;
  the receiver must connect to the phone's Wi-Fi IP.
- Record the link:
  ```bash
  ADB=/c/Users/Asus/AppData/Local/Android/Sdk/platform-tools/adb.exe
  "$ADB" -s 2c0f6edc shell dumpsys wifi | grep -m3 -E "SSID|Frequency|RSSI|Link speed"
  "$ADB" -s 2c0f6edc shell cmd wifi status
  "$ADB" -s 2c0f6edc shell ip -f inet addr show wlan0
  ```
  `5xxx` MHz = 5 GHz; `24xx` MHz = 2.4 GHz. If it is **2.4 GHz**, report that once and ask the
  owner to switch to 5 GHz; measure what you have meanwhile and label it **2.4 GHz**.
- Start the app/desktop: `... --es phase4x start` + `am start -n dev.unifieddesktop.app/.HarnessActivity`;
  accept the PC code on the phone (`phase4x accept`) if pairing is needed.
- Receiver: `pc\target\release\udreceiver.exe view --addr <phone-wifi-ip>:47600 --no-lock`
  with a log. Confirm `presented` advances and `dropped=0`.
- **Wi-Fi typing**: fixed 25-key string, same text every run, into a real app (Browser address
  bar or Notepad++; say which). n ≥ 25, p50/p90 from `LATSTAGE total`; **repeat the run** and
  report both. One screenshot/log must show the text actually landed.
- Record `battery level` and `thermalservice` status before and after; 2 min cool between runs;
  same thermal for BEFORE/AFTER.

## 3. USB typing (real apps + Test shell)

- Enable the USB link. Product path = **USB tethering** (`svc usb setFunctions rndis` if the
  phone allows it, else the UI); dev path = `adb forward tcp:47600 tcp:47600`. Measure both if
  possible; **the gate number is the product path**; label the dev path separately. Restore USB
  settings after.
- Real apps: Browser, Notepad++, VS Code, n ≥ 25 each, p50/p90.
- Test shell: reproduce the 87.0/97.0 baseline on this build BEFORE changing anything.
- **The one candidate** (from `PHASE12_RESULT.md`): when an input-triggered frame is pending, the
  receiver drops **stale queued video frames** ahead of it, so the key's frame is presented next
  instead of waiting behind the 60 fps harness animation.
  - Implement in `pc/udreceiver/src/view.rs` + `phase5.rs` as a small pure decision with a unit
    test; no new framework.
  - BEFORE = current tree; AFTER = candidate. n ≥ 25 each, second run if it wins.
  - Keep it **only if** p50 improves by ≥ 5 ms, `dropped=0`, and drag fps does not fall by more
    than 3 fps. Otherwise **revert the code** (do not leave a dead branch).
- If the candidate does not reach ≤ 80 on the Test shell, **Phase 12 is not passed**; document
  the exact remaining stage split.

## 4. Notepad++ ≤ 10 s (bounded, 60 min)

- BEFORE: 5 opens on this build with prewarm ON; record **click → window visible**, warm and
  cold, median, PSS and process count. Do not use log-only timestamps for "visible".
- **Candidate B1 (safe, 20 min):** after the Wine warm-up, start `notepad++.exe` once and close
  it, then measure the user's first open. 5 opens, BEFORE/AFTER, PSS. Keep only if the median
  improves and nothing else regresses.
- **Candidate B2 (only if B1 is not enough and ≥ 20 min remain):** keep Notepad++ pre-launched
  and hidden (unmapped) after the warm-up, then map it on the user's open. Bar: **no visible
  window before the click**, one Wine boot only, total PSS ≤ 500 MB, process count ≤ 30,
  measured. If any bar fails, revert and document the miss.
- If neither reaches ≤ 10 s, keep the current default (prewarm ON), record the honest median and
  the reason; **Phase 12 is not passed**.

## 5. Re-confirm the passing targets on this build

Only what the changes could affect:

- Connect-to-visible ≤ 5 s (3 runs).
- Window drag ≥ 30 fps, dropped 0.
- VS Code cold to `.monaco-workbench` ≤ 15 s.
- If `view.rs` changed: old-PC software-decode smoke (`--soft`, presented advancing, dropped 0)
  and the governor 1080p→540p run.
- Do not re-do the 2-hour soak, audio, clipboard or files unless a change touched them.

## 6. Close-out (45 min)

- `docs/PHASE12_RESULT.md`: add **Round 4 (2026-10-24): final gate** with the BEFORE/AFTER table,
  the Wi-Fi band/RSSI/thermal, the USB link type, n/p50/p90, the NPP median, PSS, and the
  candidate decision. State PASS/FAIL per row and the overall gate.
- `OPTIMIZATION.md`: add the final numbers and the method.
- `SKIPS.md`: remove row 19 **only** with a valid 5 GHz row; otherwise update it with the exact
  reason and what is still needed. Do not touch Phase 13 rows.
- `BUGS.md`: only B-103/B-106/B-107 if the work touches them; any new bug you find goes here
  with evidence, not into the task.
- `STATE.md` **last**: Phase 12 status with the numbers and the next action.
- Gates: Android 3 tasks; PC `cargo test --release` + `cargo clippy --all-targets --release -- -D
  warnings`; `git diff --check`.
- Cleanup: force-stop the app, stop the receiver, remove adb forwards, restore
  `stay_on_while_plugged_in=0`, brightness, USB/tethering as found, leave Wi-Fi on (the owner
  wants it), stop Gradle daemons, verify 0 app processes.
- Report in the AGENTS.md table: **item | what you did | files | test | verified on (phone /
  emulator / code-only / dev path) | numbers**, then what failed, what was skipped (SKIPS row),
  bugs logged, what is unknown. **Then stop; the manager verifies and commits.**

## Rules

- **n ≥ 25** per latency figure; **p50 and p90**; two runs for a win; same app, text, thermal.
- Label results `phone`, `dev path` (adb forward) or `code-only`; never call a dev path the USB
  product path.
- Phone stays usable; stop at **< 20 % battery** or severe thermal; a wall charger is better
  than the laptop's slow USB charge.
- Do not toggle the IP/WARP, do not change the owner's Wi-Fi/router, do not install/remove the
  owner's apps, do not touch the phone during a call.
- Do not commit or push. Do not start Phase 13/14. Do not edit code outside the two bounded
  candidates. If a candidate loses, revert it.
- If the phone is not attached at all: do the prep, report that plainly, and stop; do not wait
  more than 10 minutes.
