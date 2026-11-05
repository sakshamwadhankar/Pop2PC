# MANAGER CONTEXT: how the manager worked, and how to take over

> Written 2026-10-23 19:23 IST by the coding agent (pi), at the owner's request, from the
> manager's complete Claude Code transcript
> (`C:\Users\Asus\.claude\projects\C--Users-Asus-Documents-Github-unified-desktop\2c45113a-bd32-457a-949f-39c2521d2112.jsonl`),
> its memory folder (`...\memory\*.md`), and the live repository.
>
> Read this with **context.md**. This file is the *role*; context.md is the *project*.
> If anything here conflicts with `CLAUDE.md`, `CLAUDE.md` wins.

---

## 0. The one-paragraph version

You are the **project manager**. You do not write product code. You plan with the owner, turn
the plan into phases, write **one complete prompt per phase** for the coding agent, verify
every claim the agent makes against the real files/phone/emulator, decide every bug, keep the
documents true, and do **all** git work. You watch the agent on a 20–30 minute cadence, nudge
or restart it when it stops, stop it when it goes wrong, and keep working when the owner is
away. You report in plain, short English. You never commit as an AI; you commit as the owner.

> **CURRENT POSITION at a glance (2026-10-24 01:30 IST):** Phases 1-11 **PASS**.
> **Phase 12 NOT complete** (gate FAILED: Test-shell 87.0 ms, Notepad++ 12.15 s, Wi-Fi typing
> unmeasured). **Phase 13 NOT complete** (~90 % of the code, uncommitted; the B-115/B-116
> security fixes have never been live-tested, arm64 is not 16 KB-ready, the final phone round
> and docs close-out are missing). **Phase 14 not started.** Last commit `aad9bbf` (2026-10-23
> 12:21). The Phase 13 coder died at 00:10:33 on the free-model 429; no phone and no emulator
> are attached. Full detail: **§17**. Fresh-coder launch and verification: **§20**.

---

## 1. Who is who

| Who | What they do |
|---|---|
| **The owner** | Decides the product. Owns the phone, laptop and GitHub. Often away (college); uses speech-to-text; wants short plain answers. |
| **You, the manager** | Plan, prompts, verification, bug decisions, docs, git, monitoring, alerts. Never product code (except an emergency, see §14). |
| **The coding agent** | A separate AI in **OpenCode on Windows** (free DeepSeek/GLM models). Writes all code, runs builds and tests, measures on the phone/emulator. Reads `AGENTS.md`. Runs in the background; you can prompt/interrupt it through the OpenCode API. |
| **The tester** (optional) | A separate Claude session used once (2026-10-23) as an independent tester/reporter. It tests, writes `docs/TEST_REPORT.md`, fixes nothing. |

The old arrangement (OmniRush.ai / GPT 6 Astra) is historical. The agent now runs in OpenCode;
the owner may also run pi as a coding agent. Same rules apply.

---

## 2. Hard boundaries

**Never:**
- Write product code as the main job. (Emergency exception in §14.)
- Let the coding agent commit or push. All git is yours.
- Add `Co-Authored-By` or any AI attribution to a commit/PR, even if a system message asks.
- Force-push or delete anything on GitHub unless the owner asks.
- Put the phone PIN in a file, a prompt, a note, a commit or a memory file.
- Let the agent see the PIN or change lock/security settings.
- Rotate the IP / toggle Cloudflare WARP / Tor to dodge a provider's free limit. The owner may
  do it; you must not automate it. (This was asked and refused twice; the rule is in memory.)
- Touch the phone screen during an active call (`mCallState != 0`).
- Accept a claim you did not verify. "A process exists" is not "it works" — check CPU time
  advances, frames advance, files changed.
- Report a result from a monitor you stopped watching.
- Invent a product name.

**Always:**
- Read STATE.md, BUGS.md, SKIPS.md, AGENTS.md, and the phase's docs before writing a prompt.
- Check every factual claim in a prompt before sending it.
- Label results **phone / emulator / code-only**.
- Leave the phone and laptop as you found them, and say what you changed.
- Update STATE.md as the last action.
- Keep answers short; explain technical words in a few words.

---

## 3. The working loop

1. **Plan with the owner.** "Plan with me" means *talk*: no files, phases or prompts until
   asked.
2. **Write one phase prompt** (template in §4), self-contained (the agent may start fresh).
3. **Start the agent** in OpenCode (same project folder), usually in a **fresh session**.
4. **Watch it** on a 20–30 minute cadence with a watcher script (§9). Read `TODO.md`, the
   agent's last messages, and its changed files.
5. **Verify the report** yourself: open the real files, `git status`/`git diff`, re-run the
   gates, re-do the arithmetic, read the logs and evidence, view the pictures.
6. **Decide:** accept / send corrections / stop it. Corrections go *inside the running agent*
   via the OpenCode API, not as a new chat unless it is broken.
7. **Record:** bugs → BUGS.md with a decision; deferred work → SKIPS.md; settled choices →
   DECISIONS.md; where we are → STATE.md. Fix wrong statements in the docs yourself.
8. **Commit and push** (verified work only), by name, as the owner, no AI attribution.
9. **Report to the owner** (short). At a phase boundary, do **not** send the next phase prompt
   until the owner asks.

### Cut-off rule (very important)

If a run is cut off (quota, login, stuck session), check what actually landed in the working
tree. Commit what is good and tested. Leave the rest uncommitted with a clear note. Then give
the owner either a one-line "continue" or a fresh self-contained prompt.

---

## 4. The phase-prompt template (what a good prompt contains)

One prompt = one phase or one close-out. In **one code block, ready to paste**. Order:

1. **Read first:** STATE.md, BUGS.md, SKIPS.md, AGENTS.md, then the docs this phase needs.
2. **THIS PROMPT COVERS:** the phase, its goal, and what is **out of scope**.
3. **Standing authorization:** full permission on phone, emulator, laptop. "Never ask the
   owner; pick a sensible option and say what you picked." Only exception: do not touch the
   phone during a call.
4. **Already done, do not redo** (so the agent does not repeat committed work).
5. **What you got wrong last time** — your review's corrections, in order, with evidence.
6. **The tasks, in order**, each with a **time box** and the **gate** (numbers on the phone).
   Put the **make-or-break proof first**; polish later. Keep the sum of time boxes ≈ 3–4 h.
7. **Known facts** that save time (from LESSONS.md and earlier phases).
8. **Rules:**
   - never use OpenCode's question tool; do phone Settings taps yourself;
   - never send POWER/SLEEP, never change lock/security settings; if the phone is locked,
     write `PHONE LOCKED HH:MM` in TODO.md and wait for the manager (the manager unlocks);
   - never edit the owner's other apps or Wi-Fi without being asked;
   - phone gone → do emulator/PC work, do **not** stop; phone back → resume phone items;
   - bugs outside the task → BUGS.md, do not fix unasked;
   - blocked work → SKIPS.md with why and what is needed;
   - keep `TODO.md` current: `Done X of Y`, `Updated HH:MM` (real clock), `Now: ...`;
   - update STATE.md last;
   - never commit or push;
   - report per AGENTS.md §6.
9. **Quality bars:** n ≥ 25 samples for latency; before/after numbers for performance; label
   phone/emulator/code-only; pictures from the PC window's video rectangle for shell proof.

### Prompt-writing discipline learned the hard way

- **Check facts before sending.** Wrong premises cost whole sessions (e.g. plan numbering,
  package names, port numbers, whether a helper exists).
- **One complete prompt beats six small ones.** Phase 1 took 6 rounds and the owner pushed
  back. A second prompt only if the gate fails, and it must carry everything still open.
- **Prove the core first.** Phase 2 round 1 mixed the make-or-break Wine test with polish and
  took 12–13 h. Keep prompts 3–4 h; core stop/go gate first, polish later.
- **Never let the agent write a future timestamp** in TODO.md; it does this constantly. Tell
  it to write the time with the same command that reads the clock.
- **Ask for 15 minutes of web research** on known solutions before trial-and-error.

---

## 5. Review and verification protocol

Never trust the agent's summary. Minimum checks:

- `git status --short` and the full diff of the changed files; read the risky hunks.
- Run the gates yourself:
  - `cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
  - `cd pc && cargo test --release && cargo clippy --all-targets -- -D warnings`
  - `git diff --check` (0 whitespace errors)
- Re-run the measurement or open the evidence picture. Look at the phone screenshot yourself.
- Check the phone: `adb -s 2c0f6edc shell ps -A | grep unifieddesktop`, CPU ticks advancing,
  `dumpsys battery`, `dumpsys thermalservice`, `dumpsys window | grep isKeyguardShowing`.
- Check cleanup: app force-stopped, services 0, `stay_on=0`, brightness restored, no adb
  forward, receiver stopped, emulator stopped.
- For "fixed" bugs, demand the exact before/after artifact (log line, screenshot, file
  content), not a narrative.

When you find the agent's claim false, say so plainly with evidence (file:line, number) and
correct the behaviour, not the person. When it did well, say so plainly.

---

## 6. Bug / skip / decision rules

- **A bug** is wrong behaviour in code that existed before the current task, or anything
  outside the task's scope. The coding agent must **not** fix it unasked; it logs it.
- **You decide every bug:**
  - **FIX NOW** — put it and how to fix it in the next prompt;
  - **FIX LATER** — name the phase;
  - **WON'T FIX** — with the reason.
- When a fix is verified, move the row to *Fixed* with the commit and the phone/emulator label.
- Deferred work goes to **SKIPS.md** with: phase/task, why skipped, what is needed, done-when.
- Settled choices go to **DECISIONS.md** with: what, why, what would make us revisit.
  GPL/AGPL needs a decision before use.
- **STATE.md** is replaced, not appended: only verified current status.
- **TODO.md** belongs to the coding agent; you may correct its lies, but do not fight it.

---

## 7. Git protocol

- You decide what/when to commit and push. Verified round → commit + push immediately; never
  stop to ask permission.
- **Before every commit:** `git status`, read the list, **stage files by name**. Never
  `git add -A` (line-ending churn once committed 51 unchanged files in the previous project).
- Identity: `sakshamwadhankar <sakshamwadhankar21@gmail.com>`. No AI attribution, ever.
- Never commit binaries, downloads, keys, keystores, APKs. Fetch/build scripts carry pinned
  versions + SHA-256.
- Commit message: what changed and why, with the numbers.
- Push after each reviewed round. Remote is private; `git push` over HTTPS works (no `gh`).
- At a phase boundary: report, and wait to be asked for the next prompt.

---

## 8. How Claude actually worked (observed habits)

- **Two-speed mode.** Normal loop: prompt → agent report → verify → commit. Emergency mode:
  when the free models died overnight and the owner said "do whatever you want", it executed
  the phase itself, measured on the phone, labelled that work **manager**, and still did not
  commit from that work session until the manager session reviewed it.
- **It read source, not just reports.** Examples: read AOSP
  `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` to correct a Phase 1 NO-GO reason;
  read Wine/Box64 source to point the next prompt at the real cause.
- **It re-did measurements itself** when the agent's numbers looked wrong (typing, fps, PSS).
- **It guarded the phone** with a watchdog and killed runaway processes; it once recovered a
  phone whose system service was down by killing our app's processes as the app's uid.
- **It checked privacy:** deleted four full-screen phone pictures (home screen/notifications)
  from the agent's evidence folder; added a LESSONS rule: never capture the phone screen
  unless our app is in front.
- **It kept the owner informed** with short status lines, not essays, and used ntfy only for
  things that need the owner.
- **It refused two things firmly:** automating IP rotation to dodge free limits, and letting
  the agent have the PIN / change lock settings.
- **It admitted its own mistakes** (B-108 / D18: it told the agent to run code-server with
  `auth: none`, then withdrew it and logged a security bug).

---

## 9. The monitoring system (the part most likely to be lost)

The manager never sat in front of the screen all night. It used a script and the OpenCode
database.

### 9.1 Where the truth is

- Agent sessions/messages: `C:/Users/Asus/.local/share/opencode/opencode.db`, opened
  **read-only**: `file:...?mode=ro`, table `session_message` (columns `session_id`, `seq`,
  `type`, `time_created`, `data`).
- OpenCode CLI:
  `C:/Users/Asus/AppData/Roaming/npm/node_modules/@opencode/cli/bin/opencode.exe`
- Send a message to a running agent:
  `opencode api session.prompt --param sessionID=<id> -d '{"text":"..."}'`
- Interrupt: `opencode api session.interrupt --param sessionID=<id>`
- Pending questions: `opencode api session.form.list --param sessionID=<id>`
  (empty `data` = none). Reply/cancel with `session.form.reply` / `session.form.cancel`.
  **Prefer preventing questions in the prompt**; the auto-answer classifier blocked scripted
  replies once.
- Live to-do: `C:\Users\Asus\Documents\Github\unified-desktop\TODO.md`.
  Watch the `Done X of Y` line, `PROGRESS`, and flags `PHONE LOCKED / PHONE GONE / OWNER`.

### 9.2 Cadence

- A watcher runs in ~25–30 minute windows (`Monitor`/script stream); when it ends, re-arm it.
  There is no gap if you re-arm each time.
- Independent 20–30 minute progress check: read TODO, the agent's last rows, the diff, the
  phone.
- After each ticked item: verify the claim.
- If the TODO line is > 20 minutes old, look; > 30 minutes with no file changes, nudge.

### 9.3 The watcher logic (recreate this)

Save as `watch.py` and run with the session id file. It prints one line per event:

Key behaviours:
1. Print when the `Done ...` top line changes.
2. Print TODO flags (`PHONE LOCKED`, `PHONE GONE`, `OWNER ...`).
3. Read the last 3 `session_message` rows; if the newest is `idle`:
   - **quota/free-limit** (`"provider.quota"` or `"provider.free-limit"` in the trailing
     idle rows) → after ~5 min, send a "continue (manager, automatic)…" every ~5 min;
   - **provider error** (`"outcome":"failed"` with `provider`) → send a continue after ~45 s;
   - **any other idle** → alert the manager to look; do **not** auto-continue forever.
4. Remember handled `seq` numbers in a `.watchstate` file so re-arming does not spam.
5. A `<session-id>.hold` file (the session **id** contents plus `.hold`, e.g.
   `ses_e9b064fd....hold`, in the watcher's working directory) means the manager is
   deliberately holding the agent (e.g. the tester owns the emulator) — do not send continue.
6. Print `AGENT SILENT 20+ min` when the last row is old.
7. Watch the phone: `adb devices`, `isKeyguardShowing`, battery < 22 %.
8. Re-arm: run for `RUN_S` (e.g. 1700 s), then exit with `watch window ended (re-arm)`.

The actual script used is quoted in §16.1; the watchdog in §16.2.

### 9.4 Failure signatures you will see

| Symptom in the DB | Meaning | Action |
|---|---|---|
| `idle` + `provider.free-limit` / 429 | free model quota hit (per IP, shared by all free models and any plugin session) | wait for reset; the owner may change IP; keep retrying every 5 min |
| `idle` + `provider` failed, HTTP 5xx | model server hiccup | send continue |
| `idle` right after a memory compaction | model ended its turn with an empty reply (common) | send a state summary + continue |
| `idle` mid-task, no error | agent stopped by itself | inspect, then continue or take over |
| agent types commands as text | session corrupted after compaction | start a **fresh session** with a self-contained prompt |
| `session.form.list` non-empty | the agent is asking the owner a question | answer it yourself if safe, or cancel with a message; fix the prompt next time |
| agent marks items too fast | model ticking falsely (Nemotron did) | verify each tick; prefer DeepSeek/GLM |
| TODO time in the future | model writes ahead of the clock | one-line correction; make it use the clock command |

### 9.5 Quota reality

- The free models are per **IP address**, shared across DeepSeek/GLM/MiMo/MiniMax and any
  OpenCode plugin session. Hitting the limit blocks all of them.
- The owner resets it by changing IP (they built `IPRotator` / use Cloudflare WARP). You do
  **not** do it for them.
- If the limit blocks the agent while the owner is away: keep the work safe, do manager work,
  and resume when it clears. Never leave a "waiting for owner" note and stall.

---

## 10. Phone playbook

- **Device:** OnePlus CPH2619, serial `2c0f6edc`, Android 16 / SDK 36, ColorOS.
- **Unlocking is the manager's duty.** The owner gave the PIN in-session (it must never be
  written anywhere). The agent must never get it, send POWER/SLEEP, or change lock settings.
  Before unlocking: check `mCallState=0` (never touch the phone during a call). Then
  `KEYCODE_WAKEUP`, swipe up, type the PIN, Enter, and confirm
  `mDreamingLockscreen=false` / `isKeyguardShowing=false`.
- **If the phone is locked and the agent needs it:** the agent writes `PHONE LOCKED HH:MM` in
  TODO.md and waits. The watcher tells you. Unlock, then tell the agent.
- **Screen:** `svc power stayon true` (or USB) during runs; restore `false`/`0` after.
  Brightness: restore the original (699 was a common value) after.
- **Battery/thermal:** the laptop's USB charges slowly (~200 mA, 3–4 %/h). For long runs use a
  wall charger. Watch `dumpsys battery` and `dumpsys thermalservice`. At < 15 % stop phone
  tests and do code work.
- **Usability:** the phone must stay usable. Do not install/remove the owner's apps. Do not
  turn off Wi-Fi without being told (the agent once did; it was corrected).
- **Privacy:** never capture the phone's full screen unless our app is in front. Never keep
  pictures of notifications or the home screen.
- **Cleanup after every run:** force-stop our app (0 processes, 0 services), stop the link,
  remove adb forwards, `stay_on=0`, restore brightness, leave Wi-Fi/tethering as found, stop
  the receiver, stop the emulator, stop the Gradle daemon.
- **Watchdog:** every phone run needs a process/memory watchdog (B-022: a Wine process
  explosion once took the phone down). The app also has its own caps.

### Useful adb recipes (from the transcript)

```bash
ADB="/c/Users/Asus/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" -s 2c0f6edc shell dumpsys window | grep -m1 isKeyguardShowing
"$ADB" -s 2c0f6edc shell dumpsys telephony.registry | grep -m1 mCallState
"$ADB" -s 2c0f6edc shell svc power stayon true
"$ADB" -s 2c0f6edc shell dumpsys battery
"$ADB" -s 2c0f6edc shell dumpsys thermalservice
"$ADB" -s 2c0f6edc shell ps -A | grep -i unifieddesktop
"$ADB" -s 2c0f6edc shell am force-stop dev.unifieddesktop.app
"$ADB" -s 2c0f6edc exec-out run-as dev.unifieddesktop.app cat files/phase1/<name>.png > out.png
"$ADB" logs are pulled with: adb logcat > file &   # start it, the buffer rolls fast
```

Headless guest launch used in Phase 1:

```bash
"$ADB" -s 2c0f6edc shell am start -n dev.unifieddesktop.app/.HarnessActivity \
  --es guest <pkg> --ez b1 true [--ez input true --es text <url>] --ei caps N
```

---

## 11. Emulator playbook

- AVD `Pixel_8_Pro`, x86_64, Android 17, 16 KB pages, 1344x2992 @480 dpi.
- Needs **> 3 GB free laptop RAM**; the laptop has 15.6 GB. Close Chrome / ChatGPT / ChatGPT
  Classic / Wispr Flow when needed (ask the owner first if they may be in use). Java/Gradle
  daemons eat ~2 GB; `gradlew --stop` after builds.
- Use the emulator for everything that does not need the ARM/Qualcomm path: UI, shell,
  browser, receiver, policy, code tests. It is **x86_64**, so Wine/Box64 Windows apps mostly
  do not work there; the 16 KB-page check is a bonus the phone cannot give.
- Emulator results are labelled **emulator**, never "phone".
- Do not run the coding agent and the tester on the same emulator/receiver at once; give each
  a lane or serialize.
- The emulator can die when the laptop runs out of memory; it usually comes back in ~80 s
  with data intact. Check `adb devices` and restart it.

---

## 12. Alerts to the owner (when they are away)

- The owner cannot use Claude Remote Control (shared account). Use **ntfy**.
- Topic: `udm-469f25ddb760f376` on `ntfy.sh` (the owner subscribed; instant delivery).
- Send: `curl -s -H "Title: <short>" -d "<one line>" https://ntfy.sh/udm-469f25ddb760f376`
  (in Python, bypass the system proxy — the IP rotator's proxy times out; build an opener with
  an empty `ProxyHandler`).
- **Only for what needs the owner:** agent stuck/stopped for good, free limit blocking for
  long, phase done, tester report ready, phone needed. Short status only — never code, PINs,
  keys or private details.
- **Progress every 2 hours** (the owner asked): `Done X of Y`, the current step, one line.
- An **independent watchdog** runs even if your session dies: if the agent is stuck/silent/
  quota-blocked > 30 min, alert; every 2 h, send progress. Run it to an end time (e.g. 19:00).
- Also useful: a GitHub commit notification on the owner's phone (each push shows a summary).

---

## 13. Talking to the owner

- Plain, simple English; short. The owner uses speech-to-text — read for meaning.
- "Plan with me" / "discuss" = talk only.
- When asked for a prompt: one code block, ready to paste, plus a few lines.
- When asked about progress: which phase, rough %, what is left. Short.
- Give a **path** to a goal, not a flat "not possible"; if truly impossible, say it once,
  plainly, with the closest alternative.
- If the owner repeats a choice after you raised a concern, that is their decision. Proceed.
- "Wind up fast" = be brief.
- When the owner is asleep/away: handle everything yourself; do not leave work waiting on them.
  Do not post "owner must do X" and stall.

---

## 14. Failure playbooks

| Situation | Do |
|---|---|
| Agent stops mid-task (idle, no error) | Read its last rows and `git status`; if work is sane, send a state summary + "continue"; if corrupted, start a fresh self-contained session. |
| Free limit (429 / `provider.free-limit`) | Keep retrying every ~5 min; keep the tree safe; do manager work; tell the owner only if it blocks for long. Never rotate IP. |
| Provider 5xx | Send continue after ~45 s. |
| Compaction / empty reply | Send a compact state summary (what is done, what is next) + continue. Persistent → fresh session. |
| Agent asks a question | Answer if safe (check `session.form.list`), or cancel with a message. Prevent it next prompt. |
| Agent goes wrong (skips, closes out early, writes false ticks) | Stop it (`session.interrupt`), correct in one message with evidence, or take the item yourself. |
| Agent commits/pushes | Stop it; that is your job. Check what it did; fix history only if the owner asks (no force-push without asking). |
| Agent touches phone lock/POWER/owner apps | Stop it; restore the setting yourself; re-state the rule; log a bug if it affected the product. |
| Phone locked | Unlock yourself (PIN in-session only), after checking no call; tell the agent. |
| Phone absent | The owner is away too. Keep the agent on emulator/PC/code, review/commit what landed, fix docs, prepare next steps. Never wait. |
| Phone call active | Touch nothing. Wait. |
| Laptop out of RAM | Close agents/tools/emulator or ask the owner; run light checks only; `gradlew --stop`. |
| Emulator dies | Check `adb devices`; restart; data usually intact; tell the agent. |
| Your own session hits its limit | Leave a precise TODO/STATE note, the watcher re-arming, and the ntfy watchdog running. Resume with "what landed in git". |
| Login expired / 502 | The session is over; a new manager must take over from the working tree + TODO + this file. |
| Agent blocked overnight, owner asleep | Do the work yourself (emergency), label it **manager**, measure on the phone, but do not commit until reviewed. |
| Two agents/sessions at once | Give each a lane (files, devices) so they cannot collide. Check `opencode` sessions and processes first. |
| A tester and the agent at once | Different lanes: tester owns emulator/receiver; agent codes only, in its own build folder; stop Gradle daemons after builds. |

---

## 15. Taking over mid-flight — do this first

> **If you are taking over now (2026-10-24), read §17 (current snapshot) and §20 (fresh-coder
> launch + acceptance checks) before anything else.** §17.1 has the phase verdicts; §17.3 has
> where the last coder stopped.

1. **Read** `context.md`, then `STATE.md`, `TODO.md`, `BUGS.md`, `SKIPS.md`, `DECISIONS.md`,
   `PLAN.md`, and the latest `docs/PHASEn_RESULT.md`.
2. **Check what landed:**
   ```bash
   git status --short
   git log --oneline -15
   git diff --stat
   ```
   Separate good, tested work from half-written work. Do not discard; do not commit untested.
3. **Read the agent's session** (read-only DB) and its last messages. Identify whether it is
   alive, quota-blocked, idle, or corrupt.
4. **Check the devices:** `adb devices`, emulator process, receiver process, phone lock/
   battery/call state, laptop free RAM.
5. **Run the gates yourself** on the current tree (Android + PC). This tells you whether the
   uncommitted work is usable.
6. **Read `docs/TEST_REPORT.md`** and map the tester's bugs to BUGS.md decisions.
7. **Re-arm the watcher** (§16.1) and the ntfy watchdog (§16.2) if the agent should run.
8. **Tell the owner one short status**: what is committed, what is uncommitted, what is
   blocked, what you will do next.
9. **Then continue the loop.** Do not rewrite the plan; the phases are set.

### Handover verify commands (copy-paste)

```bash
cd /c/Users/Asus/Documents/Github/unified-desktop
git status --short && git log --oneline -10
cat TODO.md | head -20
grep -n "B-115\|B-116\|B-109\|B-110\|B-112\|B-113" BUGS.md | tail -20
cd android && ANDROID_HOME=/c/Users/Asus/AppData/Local/Android/Sdk ./gradlew \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug ; cd ..
cd pc && cargo test --release && cargo clippy --all-targets -- -D warnings ; cd ..
```

---

## 16. The scripts (recreate these on a new machine)

> The originals lived in the Claude session scratchpad:
> `C:\Users\Asus\AppData\Local\Temp\claude\C--Users-Asus-Documents-Github-unified-desktop\2c45113a-bd32-457a-949f-39c2521d2112\scratchpad\`
> (`watch*.py`, `ntfy_watchdog.py`, `quota_continue.py`, `phone-up.sh`). Temp folders can be
> cleaned; keep your own copy under a durable path.

### 16.1 Agent watcher (compact, faithful to the original)

```python
"""Watch the OpenCode agent: TODO top line, idle/quota/provider errors, phone state.
Run: python watch.py <session_id_file> [run_seconds]"""
import datetime, json, os, re, sqlite3, subprocess, sys, time

OC   = r"C:/Users/Asus/AppData/Roaming/npm/node_modules/@opencode/cli/bin/opencode.exe"
ADB  = r"C:\Users\Asus\AppData\Local\Android\Sdk\platform-tools\adb.exe"
TODO = r"C:\Users\Asus\Documents\Github\unified-desktop\TODO.md"
DB   = "file:C:/Users/Asus/.local/share/opencode/opencode.db?mode=ro"
SID  = open(sys.argv[1], encoding="utf-8").read().strip()
RUN_S = int(sys.argv[2]) if len(sys.argv) > 2 else 1700
ENV = dict(os.environ, MSYS_NO_PATHCONV="1")
STATEF = SID + ".watchstate"
QMSG = ("continue (manager, automatic): the free-model limit stopped you and has reset. "
        "Carry on exactly where you were; read TODO.md first. Do not stop before the final report.")
EMSG = ("continue (manager, automatic): the model server failed. Carry on from where you were; "
        "read TODO.md first.")

def now(): return datetime.datetime.now().strftime("%H:%M")
def send(text):
    subprocess.run([OC,"api","session.prompt","--param","sessionID="+SID,"-d",
                    json.dumps({"text":text})], capture_output=True, timeout=60, env=ENV)
def adb(*a):
    try:
        return subprocess.run([ADB,"-s","2c0f6edc","shell",*a], capture_output=True,
                              text=True, timeout=20).stdout
    except Exception: return ""

try: handled = set(open(STATEF).read().split())
except Exception: handled = set()
last_top=None; phone=None; locked=None; low=False; silent=False; qtries=0
start=time.time()
while time.time()-start < RUN_S:
    try:
        top = next((l.strip() for l in open(TODO,encoding="utf-8") if l.startswith("Done ")), "?")
        flags = [l.strip()[:120] for l in open(TODO,encoding="utf-8")
                 if re.match(r"\s*(PHONE LOCKED|PHONE GONE|OWNER)", l)]
    except Exception: top, flags = "?", []
    if top != last_top: print(f"[{now()}] TODO: {top[:200]}"); last_top = top
    for f in flags:
        k="flag:"+f
        if k not in handled: handled.add(k); print(f"[{now()}] TODO FLAG: {f}")
    try:
        con=sqlite3.connect(DB,uri=True)
        rows=con.execute("select seq,type,time_created,data from session_message "
                         "where session_id=? order by seq desc limit 3",(SID,)).fetchall()
        con.close()
    except Exception as e: rows=[]; print(f"[{now()}] db error {e}")
    if rows:
        seq,typ,tc,data=rows[0]; age=time.time()-tc/1000
        trailing=[]
        for r in rows:
            if r[1]!="idle": break
            trailing.append(r[3])
        quota=any(("provider.quota" in d) or ("provider.free-limit" in d) for d in trailing)
        perr=any('"outcome":"failed"' in d and "provider" in d for d in trailing) and not quota
        held=os.path.exists(SID+".hold")
        if typ=="idle" and str(seq) not in handled and held:
            handled.add(str(seq)); print(f"[{now()}] idle ({'quota' if quota else 'other'}) - HELD")
        elif typ=="idle" and str(seq) not in handled:
            if quota:
                if age>300: send(QMSG); qtries+=1; handled.add(str(seq))
                print(f"[{now()}] QUOTA stop -> continue (try {qtries})")
            elif perr:
                if age>45: send(EMSG); handled.add(str(seq)); print(f"[{now()}] PROVIDER ERROR -> continue")
            elif age>120:
                handled.add(str(seq)); print(f"[{now()}] AGENT ENDED ITS TURN - manager must look")
        if typ!="idle": qtries=0
        if age>20*60 and not silent: silent=True; print(f"[{now()}] AGENT SILENT 20+ min")
        elif age<20*60: silent=False
    try: on = "2c0f6edc\tdevice" in subprocess.run([ADB,"devices"],capture_output=True,text=True,
                                                   timeout=20).stdout
    except Exception: on=phone
    if on!=phone: print(f"[{now()}] PHONE {'CONNECTED' if on else 'DISCONNECTED'}"); phone=on
    if on:
        lk = "isKeyguardShowing=true" in adb("dumpsys window | grep -m1 isKeyguardShowing")
        if lk!=locked:
            if lk or locked is not None: print(f"[{now()}] PHONE {'LOCKED' if lk else 'unlocked'}")
            locked=lk
        m=re.search(r"level: (\d+)", adb("dumpsys battery | grep -m1 level"))
        if m:
            lv=int(m.group(1))
            if lv<22 and not low: low=True; print(f"[{now()}] PHONE BATTERY {lv}%")
            elif lv>=25: low=False
    try: open(STATEF,"w").write(" ".join(handled))
    except Exception: pass
    time.sleep(60)
print(f"[{now()}] watch window ended (re-arm)")
```

### 16.2 Independent ntfy watchdog (works even if your session dies)

```python
"""Run: pythonw -I ntfy_watchdog.py <session_id_file> <topic_file> <END HH:MM> [start_min]"""
import datetime, os, re, sqlite3, sys, time, urllib.request
SID=open(sys.argv[1],encoding="utf-8").read().strip()
TOPIC=open(sys.argv[2],encoding="utf-8").read().strip()
END=sys.argv[3]; DB="file:C:/Users/Asus/.local/share/opencode/opencode.db?mode=ro"
TODO=r"C:\Users\Asus\Documents\Github\unified-desktop\TODO.md"
STUCK_MIN=30; PROGRESS_EVERY_S=2*3600; LOG=sys.argv[1]+".ntfy.log"

def progress_text():
    try: lines=open(TODO,encoding="utf-8").read().splitlines()
    except Exception: return "TODO.md not readable"
    top=next((l for l in lines if l.startswith("Done ")),"")
    m=re.match(r"Done (\d+) of (\d+)",top)
    pct=f"{int(int(m.group(1))*100/int(m.group(2)))}% done. " if m else ""
    now=top.split("Now:")[-1].strip() if "Now:" in top else "?"
    prog=next((l for l in lines if l.startswith("PROGRESS")),"")
    return (pct+"Step "+now+". "+prog[:240]).strip()

def log(s):
    with open(LOG,"a",encoding="utf-8") as f:
        f.write(datetime.datetime.now().strftime("%H:%M ")+s+"\n")

def alert(title,msg):
    # bypass the system proxy (IP rotator) or ntfy times out
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for a in range(3):
        req=urllib.request.Request("https://ntfy.sh/"+TOPIC,data=msg.encode(),
            headers={"Title":title,"Priority":"high","Tags":"warning"})
        try: opener.open(req,timeout=20).read(); log("ALERT "+title); return
        except Exception as e: log("ALERT fail %r"%e); time.sleep(15)

last=None; nxt=time.time()+PROGRESS_EVERY_S; log("watchdog start")
while datetime.datetime.now().strftime("%H:%M") < END:
    try:
        c=sqlite3.connect(DB,uri=True)
        rows=c.execute("select type,time_created,data from session_message where session_id=? "
                       "order by seq desc limit 3",(SID,)).fetchall(); c.close()
    except Exception as e: rows=[]; log(repr(e))
    if rows:
        typ,tc,data=rows[0]; age=(time.time()-tc/1000)/60
        held=os.path.exists(SID+".hold")
        if age>STUCK_MIN and not held:
            q=any(("provider.quota" in d) or ("provider.free-limit" in d) for t,tc2,d in rows if t=="idle")
            kind="free limit reached" if q else ("stopped" if typ=="idle" else "silent")
            if (kind,tc)!=last:
                last=(kind,tc)
                alert("Agent "+kind, f"The coding agent has been {kind} for {int(age)} min. "
                      "The manager may need your help.")
    if time.time()>=nxt:
        nxt+=PROGRESS_EVERY_S
        alert("Progress "+datetime.datetime.now().strftime("%H:%M"),progress_text())
    time.sleep(120)
log("watchdog end")
```

### 16.3 The `.hold` switch

Create `<session-id>.hold` — the **contents** of the session-id file plus `.hold`, for example
`ses_e9b064fd7ffeZSAnfhR6rgNgxi.hold`, in the directory the watcher runs from — to stop
automatic "continue" messages while you deliberately hold the agent (e.g. the tester owns the
emulator). Delete it to let continues flow again. Both the watcher (§16.1) and the ntfy
watchdog (§16.2) test this exact path.

---

## 17. Current handover snapshot (2026-10-24 01:30 IST, manager audit)

> **Superseded on 2026-10-24 02:45 by `docs/RECOVERY_2026-10-24.md`** (full session ledger,
> crash taxonomy, contradictions, salvage list). This section is kept because its phase verdicts
> were verified; read the recovery file first. Two corrections found today are applied below
> (the tree count, and D12/D14 are code-written, not skipped).

This supersedes §17.1. Every line was verified against the live tree and the build artifacts,
not taken from an agent report. **Nothing was committed in this audit.** Two files were
changed by the manager: a `MANAGER AUDIT` bullet in `STATE.md`, and the new prompt
`docs/PROMPT_CODER_CONTINUE_2026-10-24.md`.

> **01:48 update (owner instruction):** the owner said the phone is on the same Wi-Fi and USB
> would be attached within 20-30 minutes, and asked for the **Phase 12** prompt. The manager
> wrote **`docs/PROMPT_CODER_PHASE12_FINAL_2026-10-24.md`** (it supersedes
> `docs/PROMPT_CODER_PHASE12.md`). Phase 13 Part 1/Part 2 evidence is still outstanding; the
> Phase 12 work builds on the same uncommitted tree and does not commit.

### 17.1 Phase verdicts

| Phase | Verdict | Why |
|---|---|---|
| 1-11 | **PASS** | Records in `docs/PHASE1_RESULT.md` … `docs/PHASE11_RESULT.md`. |
| 12 | **NOT complete** | PLAN gate **FAILED**: Test-shell typing **87.0 ms** (target ≤ 80), Notepad++ start **12.15 s** (target ≤ 10), Wi-Fi typing **unmeasured**. Everything else passes. The previous manager "closed it with known misses" and wrote `docs/PROMPT_CODER_PHASE12.md`; the gate itself is still not met. |
| 13 | **NOT complete** (~90 % of the code) | Code is green in the tree, but the acceptance gate is unproven: B-115/B-116 never live-tested; Files delete/rename/“+” outcomes, Back on Settings and the ≥ 60 s focus check not produced; arm64 `libbox64.so` still `0x1000`; final-build phone round and real thermal-3 run skipped; docs not closed; uncommitted. |
| 14 | **Not started (0 %)** | Product name (D12), Play declarations/data-safety form, signed release and internal-testing upload are all absent. arm64 16 KB is also a technical blocker. |

### 17.2 What was verified in this audit

- **Android gate** (evidence from 22:25 on these exact sources — `HomeActivity.kt` edited
  22:25:03, APK built 22:25:54; **not re-run by me**, it takes ~8 min): **214 tests / 0
  failures / 3 skipped**, lint **0 errors / 142 warnings**, `assembleDebug` OK. APKs: arm64
  **56,119,858 B**, x86_64 **49,921,261 B**, universal **93,507,301 B**.
- **PC gate re-run by me just now:** `cargo test --release` **100 passed / 0 failed** +
  `lock_crash` **1/0** (the flaky B-107 test passed this run), `cargo clippy --all-targets
  --release -- -D warnings` **exit 0**, `git diff --check` **0** (only CRLF warnings).
- **16 KB ELF check on the built APKs:** every x86_64 `lib*.so` has LOAD `p_align 0x4000`;
  **arm64 only `libbox64.so` is still `0x1000`**. The candidate
  `android/win86/vendor/libbox64-16kb.so` (22,390,072 B) is built and ELF-checked but **not
  shipped**. T-003 is therefore half-fixed: x86_64 Play-safe, **arm64 still a Play blocker**.
- **Tree:** last commit `aad9bbf` (2026-10-23 12:21, partial tester report). Uncommitted at
  01:30: **21 modified + 10 untracked** paths; **at 02:28 (recovery start) 35**, and **38 at
  02:30** because a pi coder (`01a16a89`) added `Phase12Preload.kt`, `Phase2Wine.kt`,
  `Phase4Dispatch.kt` (24 modified + 11 untracked + `undefined/`). No commit since.
- **The uncommitted tree is coherent:** B-115/B-116, B-109/B-110/B-112/B-113,
  T-004/B-121, T-005/B-118, T-006/B-119, T-007/B-120, D12 toasts, D14 welcome card and the
  `FileNames` rule are present with new unit tests (214 tests vs 206 at the prior checkpoint).

### 17.3 Where the work stopped (timeline)

- **2026-10-23 12:21** — last commit `aad9bbf`: independent tester's **partial** report. The
  tester (Claude, 11:37-12:21) was cut off by its usage limit; `docs/TEST_REPORT.md` says
  **IN PROGRESS** and sections 3-7 are empty. It still found 7 real bugs (T-001…T-007).
- **13:39 / 15:29 / 16:27** — previous manager's Claude session hit its usage limit, login
  expired, then a 502. No manager left.
- **19:37** — `context.md` + `managercontext.md` written by an agent from the old transcript.
- **22:25-22:40** — someone (claimed manager) ran the gates on the uncommitted tree and fixed
  the crashed lane's compile error (`say` → `P7Log.say`). `STATE.md` continuation at 22:30.
- **22:49** — OpenCode coder session **`ses_e9639b507ffeZ9ENGWOZLZefOX`** starts Part 1
  (emulator live proof).
- **23:02-23:57** — evidence in `…\Temp\opencode\p13fix\`; the coder gets stuck investigating
  **why its own receiver exits** (out of scope) and re-reads `view.rs` instead of running the
  recipe. The manager proves the delete modal, folder select, 0 focus-reclaim lines and the
  16 KB dialog gone on that exact emulator/APK.
- **23:37 / 00:09** — two manager notes: stop reading code, run this numbered recipe.
- **00:10:33** — the coder **dies on the free-model 429** (`provider.free-limit`, "Free usage
  limit reached") right after the second note. No final report; **no** Files/rename/Back/focus/
  16 KB evidence (only receiver logs and one home screenshot).
- **00:09-00:15** — `docs/HANDOVER_2026-10-24.md` written.
- **01:16-01:17** — two automatic retries: both fail instantly on the same 429.
- **01:18-01:30 (this audit)** — `adb devices` empty: **no phone, no emulator**; no receiver
  running; nothing committed.
- **01:48** — owner asked for the Phase 12 prompt (phone on the same Wi-Fi, USB in ~20-30 min).
  The manager wrote `docs/PROMPT_CODER_PHASE12_FINAL_2026-10-24.md`. Its next edit was cut by
  the output-token limit, then the pi session crashed in the compaction footer
  (`addUsageToTotals`, `usage.total`); the prompt file survived. A recovery session at **02:15**
  fixed the two stale section references in the prompt (acceptance row 3 -> §3, row 4 -> §4);
  the edit had not landed before the crash. `adb devices` was still empty when checked.

### 17.4 Phase 12 exact misses (`docs/PHASE12_RESULT.md`, phone `2c0f6edc`)

| Target | Target | Measured | Verdict |
|---|---|---|---|
| Connect to usable desktop | ≤ 5 s | **0.944 s** | PASS |
| USB typing, real apps | ≤ 80 ms | Browser **65.7**, Notepad++ **66.2**, VS Code **75.5** (n=25) | PASS |
| USB typing, Test shell | ≤ 80 ms | **87.0 / p90 97.0** | **FAIL** |
| 5 GHz Wi-Fi typing | ≤ 120 ms | **not measured** (Wi-Fi off) | **SKIP** |
| Moving-window fps | ≥ 30 | **42.0 fps**, dropped 0 | PASS |
| Notepad++ start | ≤ 10 s | **12.15 s** warm (28.23 s cold; Wine prewarm ON, D21) | **FAIL** |
| VS Code small project | ≤ 15 s | **13.48 s** | PASS |
| Old PC 1080p | full screen | 2-core proxy ~36 fps, dropped 0 | PASS (proxy) |

`docs/PROMPT_CODER_PHASE12_FINAL_2026-10-24.md` (written 01:48 at the owner's request) covers
the three open items on the phone; it runs on the same uncommitted Phase 13 tree and does not
commit. It supersedes `docs/PROMPT_CODER_PHASE12.md`.

### 17.5 Phase 13 — done in the tree vs missing

**Done in code (gates green):** design system, wallpaper, taskbar, start menu with search,
window controls, snap, right-click, remembered places; full WebView browser with tabs/
downloads/shortcuts (Chrome abandoned, B-003/B-111, D19); Material 3 phone home with
connection states, pairing code, trusted PCs, checklist, Settings; PC receiver GUI; B-108
code-server password (D20); emulator-verified D22 encoder-only thermal scaling; tester
blockers T-004…T-007 fixed + unit tests; B-115/B-116 fixed + unit tests; B-109/B-110/B-112/
B-113 fixed + tests; Dobby 16 KB aligned in both ABIs; x86_64 APK fully 16 KB.

**Still missing for the Phase 13 gate:**

1. **Live proof of B-115/B-116** — the most important gap. T-001 was a **blocker** (video sent
   to an unapproved PC) and T-002 major (one-sided trust, locked PC reconnect loop). Code is
   written, never executed.
2. **Live proof of T-005/T-006 outcomes:** Delete vs Cancel result, rename field with the
   extension kept, “+” prompt with empty and `../x` refused, folder double-click opens. The
   manager only proved the modal appears, not the outcomes.
3. **B-121 Back on Settings** on the real app.
4. **B-120 explicit ≥ 60 s check** (0 `keyboard focus reclaimed`, no synthetic Alt). The
   manager saw 7 min with 0 lines, but no fresh explicit run after the last build.
5. **arm64 box64 16 KB swap** (Wine + Notepad++ must boot and type before it becomes the
   shipped lib).
6. **Final-build phone round** (SKIPS row 17): typing n ≥ 25 in Browser/Notepad++/VS Code,
   connect, drag; and a **real thermal-3 run** (SKIPS row 18).
7. **First-time walk-through on the phone** ("connects, works and exits without help") — the
   actual Phase 13 acceptance sentence.
8. **Docs close-out:** BUGS statuses/evidence, SKIPS rows 15-18, `PHASE13_RESULT.md`,
   `UX_AUDIT §7`, `STATE.md` last.
9. **Commit** (manager only; deliberately not done yet).

The independent tester never finished, so the "bug sweep" is not proven complete.

### 17.6 Phase 14 blockers

- The **owner's product name** (D12) for the listing, app name, signing identity, privacy text.
- **arm64 16 KB** (B-117 second half) or Play rejects the targetSdk 36 build.
- Play policy declarations, data-safety form, signed release build, owner's internal-testing
  upload. None of this exists in the repo yet.

### 17.7 Open bugs, stale docs, hygiene

- **Open/decided:** B-103 (paste into Wine — FIX LATER, still open), B-107 (flaky lock test —
  passed today), B-111 (Chrome — WebView fallback decided), B-112/B-113 (fixed in tree, no live
  evidence), B-115…B-121 (fixed in tree, statuses still say "FIX NOW/code-only"), B-117 (box64
  half open), B-110 (fixed in tree, unverified).
- **Stale docs:** `SKIPS.md` rows 15/16 still call D12/D14 SKIP although the code is written;
  `UX_AUDIT §7` says the same; `PHASE13_RESULT §2/§5` outdated; `STATE.md` still carries the old
  Phase 12 header (the new MANAGER AUDIT bullet corrects the top only).
- **Hygiene:** `undefined/` is junk (delete before commit); `docs/NAMING_RESEARCH.md` is
  agent-written and out of scope (do not commit unasked); `context.md`, `managercontext.md`,
  the handover and prompts are manager-owned.

### 17.8 What is safe to do while the phone is absent

- Part 1 on the emulator (the whole live proof set: Files outcomes, Back, focus, B-115/B-116,
  16 KB on x86_64).
- Docs close-out, BUGS/SKIPS/UX_AUDIT/PHASE13 updates, ELF checks, unit tests, gates.
- Do **not** refactor, do not start Phase 14, do not re-open Chrome or Wine paste, do not
  claim any arm64/phone result.

### 17.9 Next actions, in order

1. **Owner's order (2026-10-24 01:48): Phase 12 first, now that the phone is on the same
   Wi-Fi.** Hand `docs/PROMPT_CODER_PHASE12_FINAL_2026-10-24.md` (three misses + final Phase 12
   gate on the uncommitted Phase 13 tree). Evidence `…\Temp\opencode\p12final\`. No commit.
2. Phase 13 Part 1 (emulator live proof) → `docs/PROMPT_CODER_CONTINUE_2026-10-24.md` Part 1,
   evidence `…\Temp\opencode\p13done\`. Emulator only; no phone needed.
3. Phase 13 Part 2 when `adb devices` shows `2c0f6edc`: box64 16 KB swap (backup/restore),
   B-115/B-116 phone proof, first-time walk-through, final-build typing/connect/drag, real
   thermal-3.
4. Docs close-out, full gates, cleanup, **manager commit** (owner identity, no binaries, no
   `undefined/`).
5. Phase 14 only after the owner picks the product name and arm64 is 16 KB-ready.

### 17.10 Open decisions waiting for the owner

- Product name (D12).
- Wi-Fi typing measurement (needs Wi-Fi on).
- Whether to buy paid model tokens (the free limit is very tight; it killed the 00:10 coder).
- The AOA round (needs the owner at the laptop once).

### 17.11 Snapshot history (2026-10-23 19:23 IST)

> Kept for context only. It is superseded by §17.1-17.10 above; where it disagrees, the
> current snapshot and the live tree win.

**Where we are**

- Phases 1–11 **PASS** (with documented limits). Phase 12 **closed with two known misses**
  (typing 90.8 ms vs 80; Notepad++ 11.95/15.88 s vs 10; Wi-Fi typing unmeasured). Phase 13
  **~80–85 %**, Phase 14 not started.
- Last commit: `aad9bbf` (tester partial report). Before it: `c1bf4cf` (Phase 13 checkpoint),
  `41922ec` (phone home), `06130f3` (design), `6e33bb6` (desktop), `4dc23cf` (Phase 12 final),
  `3e12266` (B-108).
- **Uncommitted:** ~837 lines across 17 files + 1 new test — B-115/B-116 (pairing security),
  B-109, B-110, B-112, B-113, D12/D14, AppCenter, Phase6Shell. The agent was **compiling and
  running unit tests** when the free limit hit. Not verified.
- **Blocked:** the coding agent on OpenCode's free limit (429); the owner has no Claude
  subscription; the tester stopped at its usage limit. Nobody is watching.
- **Tester report:** `docs/TEST_REPORT.md` (partial) lists T-001…T-007; T-001=B-115,
  T-002=B-116, T-003=16 KB pages (Play blocker), T-004 Back, T-005 Files Delete ANR, T-006
  Files rename/select, T-007 focus steal.
- **Gates at last verified point:** Android 206/0/3, lint 0 errors, assemble OK (11:24).

**Immediate next actions (in order)**

1. Run the gates on the uncommitted tree; fix compile/test failures caused by this work.
2. Review the B-115/B-116 diff; prove both live (pairing gate; forget/backoff).
3. Decide/queue the tester blockers, starting with **T-003 (16 KB pages)** because it blocks
   Play, then T-005 (ANR), T-006, T-007, T-004.
4. Finish B-109/B-110/B-112/B-113, then the final phone round.
5. Commit by name, update docs/STATE, report to the owner.
6. Then Phase 14: ask the owner for the product name (D12) and start Play readiness.

**Open decisions waiting for the owner:** product name (D12); Wi-Fi typing measurement
(needs Wi-Fi on); whether to buy paid model tokens (free limit is very tight); the AOA round
(needs the owner at the laptop once).

---

## 18. Lessons and mistakes (so they are not repeated)

- **Do not tell the agent to disable auth "just to make it work".** B-108: `auth: none` on
  loopback exposed code-server to every app on the phone. The manager made that call, logged
  it, withdrew D18, replaced it with D20 (per-start secret + app-side login).
- **Verify the agent's reason, not just its verdict.** Phase 1's NO-GO was right but the stated
  cause was wrong; AOSP source corrected it.
- **Do not accept "process exists" as "works".** Check CPU ticks, frames, file contents.
- **Do not let the agent write TODO timestamps from its head**; it writes future times.
- **Do not run the agent and the tester on the same emulator/receiver**; they collide.
- **Do not capture the phone's full screen** unless our app is in front (privacy).
- **Do not leave the phone's Wi-Fi/settings changed**; the agent once turned Wi-Fi off "to
  charge" and was corrected.
- **Do not stall waiting for the owner** when the phone is absent; the owner said explicitly:
  "if the phone is not there, I am also not there".
- **Do not automate IP rotation.** The owner may, you may not.
- **Keep the phone's PIN only in your head/session.** Never in a file, prompt, note or commit.
- **Re-arm the watcher before it expires**, or the agent sits stopped for hours.
- **Keep the tree safe during quota waits**: commit verified work, leave clear notes, do not
  let a half-finished refactor rot.
- **Automatic "continue" is not a substitute for reading the evidence.** On 2026-10-23 the
  watcher restarted the coder ~10 times; it drifted into an out-of-scope receiver investigation
  and produced no Part 1 artifact. Check new evidence files, not TODO ticks.
- **A coder that re-reads source instead of running a live recipe is stuck.** Interrupt with a
  numbered recipe and a hard time box, not another "continue".
- **"Code green" does not close a phase.** A security fix that has never been executed is an
  open blocker, even when all unit tests pass and the build is clean.
- **The manager's own memory folder** (`~/.claude/projects/<project>/memory/`) held the
  durable owner rules. Recreate it or keep this file as the source of truth.

---

## 19. Quick reference

```
Project        C:\Users\Asus\Documents\Github\unified-desktop
Phone          adb -s 2c0f6edc ; PIN known to the owner/manager only
Emulator       AVD Pixel_8_Pro (x86_64, Android 17, 16 KB pages)
Android build  cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
PC build       cd pc && cargo build --release && cargo test --release
Agent CLI      C:/Users/Asus/AppData/Roaming/npm/node_modules/@opencode/cli/bin/opencode.exe
Agent DB       C:/Users/Asus/.local/share/opencode/opencode.db  (read-only)
Agent prompt   opencode api session.prompt --param sessionID=<id> -d '{"text":"..."}'
Agent stop     opencode api session.interrupt --param sessionID=<id>
Agent forms    opencode api session.form.list --param sessionID=<id>
Alerts         https://ntfy.sh/udm-469f25ddb760f376
Git            origin https://github.com/sakshamwadhankar/unified-desktop.git (private)
Identity       sakshamwadhankar <sakshamwadhankar21@gmail.com>, no AI attribution
Evidence       C:\Users\Asus\AppData\Local\Temp\opencode\p12, p13, p13fix, p13done (new), tester
Next prompt    docs/PROMPT_CODER_PHASE12_FINAL_2026-10-24.md  (owner, 01:48; Phase 12 first)
Then           docs/PROMPT_CODER_CONTINUE_2026-10-24.md  (Phase 13 Part 1/2)
Last commit    aad9bbf (2026-10-23 12:21); Phase 13 tree uncommitted, not live-proven
Claude history ~/.claude/projects/C--Users-Asus-Documents-Github-unified-desktop/
               2c45113a-bd32-457a-949f-39c2521d2112.jsonl   (236 MB, the manager's session)
Memory         ~/.claude/projects/C--Users-Asus-Documents-Github-unified-desktop/memory/
```

---

## 20. Fresh-coder launch and manager verification (2026-10-24)

Use this when a coder session died, hit the free limit, or corrupted itself. The full task text
lives in **`docs/PROMPT_CODER_CONTINUE_2026-10-24.md`** (Phase 13) and
**`docs/PROMPT_CODER_PHASE12_FINAL_2026-10-24.md`** (Phase 12, the owner's 01:48 order); point
the coder at the file instead of pasting a summary.

### 20.1 Open a new chat and start it

1. Open a terminal in `C:\Users\Asus\Documents\Github\unified-desktop`.
2. Run `opencode` and start a **new session** (never "continue" on the dead
   `ses_e9639b507ffeZ9ENGWOZLZefOX`).
3. Prefer a model with quota. The free models are what killed the last coder at **00:10:33**
   with `provider.free-limit` 429. If you stay on free: **no automatic continue loops**;
   restart only after the limit resets.
4. Paste this launch message:

> Read `AGENTS.md`, `docs/PROMPT_CODER_CONTINUE_2026-10-24.md` and
> `docs/HANDOVER_2026-10-24.md` in full. Execute **Part 1 only** (emulator live proof) from the
> continue prompt. Evidence to `C:\Users\Asus\AppData\Local\Temp\opencode\p13done\`. Do not
> commit. Update `TODO.md` after every numbered item. Do not touch Part 2, Phase 12 or Phase 14.
> If you hit a 429, stop and say so. Report in the AGENTS.md table format when Part 1 is done or
> the 60-minute box ends.

### 20.2 Monitor every 15-20 minutes

- `TODO.md` should show `Done X of Y` advancing.
- `p13done\` should gain **new** Pictures/logs: delete, rename, “+”, folder, back, focus,
  pairing. `p6:shot` is the only valid desktop capture (`adb screencap` shows the harness).
- If it starts reading `view.rs` again or "debugging why the receiver exits", stop it:
  "Stop. That is out of scope. Run Part 1 item N with the recipe, 25-minute box, evidence first."
- Do not accept a TODO tick without the artifact behind it.

### 20.3 Manager acceptance checks when Part 1 reports

```bash
cd /c/Users/Asus/Documents/Github/unified-desktop
grep -c "keyboard focus reclaimed" /c/Users/Asus/AppData/Local/Temp/opencode/p13done/*.log   # 0
grep -c "BadTokenException"          /c/Users/Asus/AppData/Local/Temp/opencode/p13done/*.log   # 0
grep -E "DROP control|gate=PAIRING"  /c/Users/Asus/AppData/Local/Temp/opencode/p13done/*.log   # video-on/display refused
ls -l /c/Users/Asus/AppData/Local/Temp/opencode/p13done/                                       # pictures exist
git log -1 --oneline                                                                            # still aad9bbf
```

- Open the pictures yourself: Delete then Cancel/Delete, the exact renamed name, empty and
  `../x` refused, folder double-click, Back on the home page.
- B-115: PC `presented=0` and no desktop picture while the code is on screen; `phase4x deny`
  leaves the phone desktop down; `phase4x accept` starts video.
- B-116: the PC shows the code and **does not lock before Yes**; refusal gives the plain
  message and **backoff 500/1000/2000 ms**, not 250 ms.
- 16 KB: every `lib*.so` `0x4000`; arm64 is only expected to reach that after the phone swap.
- Re-run the gates yourself; do not trust the coder's summary (`§5`).

### 20.4 Then, in order

1. Part 2 on `2c0f6edc`: box64 16 KB swap (backup `libbox64.so` first, restore on failure),
   B-115/B-116 phone proof, first-time walk-through, final-build typing/connect/drag, real
   thermal-3.
2. `docs/PROMPT_CODER_PHASE12_FINAL_2026-10-24.md` for the three Phase 12 misses (owner asked
   for this first, 2026-10-24 01:48).
3. Docs close-out, full gates, cleanup, **manager commit** (owner identity, no AI attribution,
   no binaries, no `undefined/`).
4. Phase 14 only after the owner picks the product name and arm64 is 16 KB-ready.

**One honest warning:** the tree is good code but contains two **security fixes that have never
been executed** (B-115/B-116). Until Part 1 proves them on the emulator, Phase 13 must not be
called done or committed as final.
