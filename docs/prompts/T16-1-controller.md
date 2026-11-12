TASK T16-1 — CONTROLLER MODE: control the PC from the phone (the deferred feature, now unlocked by launch)

You are the coding agent for PopToPC (repo codename unified-desktop). AGENTS.md is
auto-loaded. No commits, no pushes. Do not edit any .md file except TODO.md. The manager
reviews raw evidence and updates BUGS.md / SKIPS.md / STATE.md / PLAY_LISTING.md.

CONTEXT: PLAN.md deferred Controller mode until after the first launch — that condition is
met. Scope recorded there: the PHONE controls the PC — touchpad, keyboard, app control,
explicit clipboard transfer, media/system controls. The full-screen video view is NOT part
of controller mode (the phone IS the controller surface).

GOAL: from the phone app, the owner connects to a TRUSTED PC and gets: a touchpad surface
(pointer move, tap-click, two-finger scroll), a phone keyboard that types on the PC, media
keys, a clipboard bridge both ways, and a launcher for .exe apps installed on the PC.
Nothing else.

EVIDENCE DIR: C:\Users\Asus\AppData\Local\Temp\opencode\t16-1\ (create it; raw logs, PNGs).
CHECKPOINT RULE: after every item, rewrite TODO.md line 1: "T16-1: done X of 5. Updated
HH:MM. Now: <current item>." Blocked > 30 min -> record, move on, retry later.

CARRYOVER: HEAD = the manager's T15-4/T15-5/T15-6 commits (Import Windows/Android + Linux
layer landed). Phone 2c0f6edc has the current release. The receiver (pc/udreceiver) already
synthesizes real input for the desktop path — reuse its SendInput plumbing for controller
mode. Only TRUSTED/paired PCs may be controlled (the B-115 gate pattern applies: nothing
before Yes, plain refusals, no reconnect storms). If locked you may unlock (OWNER-AUTHORIZED:
KEYCODE_WAKEUP; wm dismiss-keyguard; coordinate-tap PIN bouncer 0-1-2-3; input keyevent 66).
Never write the PIN anywhere. Owner-use guard as always.

RULES (hard):
- Security first: controller mode connects ONLY to trusted PCs; the pairing gate applies
  exactly like view mode; a deny is a plain refusal with no reconnect storm. Unit-test the
  gate decision.
- Real SendInput for every PC-side proof; never fake movement.
- Pure decisions separated for unit tests (gesture→pointer deltas, scroll mapping, key
  mapping, clipboard round-trip state), like KeyRoute/ChangePropertyWire.
- Protocol additions go through the existing PriorityWriter channel order (control is above
  audio/video) and the versioned decoder — extend, do not break, the PC twin's tests.

ITEM 1 [90 min] — protocol + receiver: controller connection mode
- New connection kind ("controller") in the handshake: trusted-only, no video, control+
  clipboard+file channels only. Receiver: pointer move/click/scroll handlers (SendInput),
  text-commit handler, media-key handler, app-launch handler (launch a registered .exe by
  name from the receiver's installed-apps list), clipboard push/pull handlers.
- PC cargo tests for every handler decision. Receiver CLI: `udreceiver controller` mode
  (and the GUI gains a "Controller" button beside Find phone).

ITEM 2 [90 min] — phone side: the Controller surface
- Phone app: "Controller" card on the home screen (trusted PCs listed) -> the controller
  UI: touchpad surface (move, tap=left, two-finger=scroll, long-press=right), keyboard
  toggle (IME -> commit text to the PC), media/system keys row (vol-, vol+, mute, play/
  pause, prev/next), clipboard share (send phone clipboard to the PC) and copy-from-PC
  (pull), app launcher list (the PC's registered apps).
- Unit tests for gesture→deltas, scroll mapping, key mapping, the clipboard round-trip
  state machine.

ITEM 3 [120 min] — live proofs (real product path, PC + phone)
- Touchpad: PC cursor moves with phone swipes (screen capture of the PC before/after
  cursor position; pointer travels proportional to gesture).
- Tap opens an app on the PC; two-finger scroll scrolls a page.
- Phone keyboard types `ctrl16ok` into the PC's Notepad++ (already installed).
- Media keys: play/pause + volume change reflected on the PC (log + observable state).
- Clipboard: copy on the phone -> paste on the PC lands; copy on the PC -> pull on the
  phone lands.
- App launch: launch Notepad++ from the phone's controller list -> its window opens.
- Deny/security proof: an untrusted PC attempting controller mode is refused plainly; a
  trusted PC works first-try. No reconnect storms (0 reconnects in the log).
- Screenshots + raw logs for every proof; thermal/battery recorded.

ITEM 4 [60 min] — hardening + tests
- The controller channel rate-limits pointer events (coalesce to <=120 Hz); disconnect/
  kill paths restore the PC state exactly like Exit does (journal clean, Default desktop).
  Unit tests for the coalescing and the teardown decisions.

ITEM 5 [60 min] — close-out
- Full gates: gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug; cargo test
  --release; cargo clippy --all-targets -- -D warnings; git diff --check. AAB size.
- Write Temp\opencode\t16-1\RESULT.txt: the AGENTS.md table, every proof with raw-log
  path, proposed BUGS.md rows, proposed PLAY_LISTING.md addition (Controller mode is a
  listing headline), cleanup state.
- Cleanup always: stay_on=0, force-stop app, forwards removed, 0 receivers, Wi-Fi as found,
  no call, gradle daemon stopped, screen_off_timeout 1800000.
- Final reply: table + failures + unknowns + cleanup yes/no + one line "T16-1: COMPLETE" or
  "T16-1: NOT COMPLETE — <exact list>". Then STOP. No commit, no push, no upload.

TIME BOX: 10 hours total. Work until genuinely complete or technically blocked.
