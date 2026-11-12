TASK T16-2 — FINAL: rebuild the release AAB with every feature, full verification round, Play handoff

You are the coding agent for PopToPC (repo codename unified-desktop). AGENTS.md is
auto-loaded. No commits, no pushes. Do not edit any .md file except TODO.md. The manager
reviews raw evidence and updates BUGS.md / SKIPS.md / STATE.md / docs/PLAY_LISTING.md.

CONTEXT: every feature has landed — Import (Windows exe/msi + Android apps), the Linux
layer, Controller mode, the browser (GeckoView with WebView fallback), PC-only audio,
B-130/B-103/B-135/B-136/B-137/B-138 fixes. This is the FINAL task before the owner uploads
to Google Play.

GOAL: one signed release AAB containing everything, a full verification round of the whole
product on the real phone + PC, and the completed Play handoff checklist. Nothing else.

EVIDENCE DIR: C:\Users\Asus\AppData\Local\Temp\opencode\t16-2\ (create it; raw logs, PNGs).
CHECKPOINT RULE: after every item, rewrite TODO.md line 1: "T16-2: done X of 4. Updated
HH:MM. Now: <current item>." Blocked > 30 min -> record, move on, retry later.

CARRYOVER: HEAD = the manager's T16-1 commit (Controller mode landed). Phone 2c0f6edc.
If locked you may unlock (OWNER-AUTHORIZED: KEYCODE_WAKEUP; wm dismiss-keyguard;
coordinate-tap PIN bouncer 0-1-2-3; input keyevent 66). Never write the PIN anywhere.
Owner-use guard as always.

ITEM 1 [45 min] — gates + final signed release build
- gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:bundleRelease
  :app:assembleRelease; cargo test --release; cargo clippy --all-targets -- -D warnings;
  git diff --check; verify all arm64 libs 16 KB aligned (llvm-readelf); AAB < 200 MB;
  verify the upload-key signature.
- Record: AAB path, size, sha256 (this exact file is what the owner uploads).

ITEM 2 [120 min] — full product verification round on the phone + PC (real product path)
One fresh session covering the whole product, screenshot/log per step:
- Install the release APK; pair if asked; desktop streams at 1728x1080.
- Controller mode: touchpad move/click, phone keyboard typing, media keys, clipboard both
  ways, launch Notepad++ from the phone.
- Desktop: Notepad++ typing (marker `pop16final`), Browser google search (robot-wall
  honest note), VS Code opens (no white page), App Center shows Notepad++ installed.
- Import: import one .exe from the PC -> launches -> still listed after restart.
- Audio: YouTube plays -> phone silent, PC audible (the mute toggle ON).
- Exit: clean exit -> journal clean -> Default desktop restored.
- Cleanup: the full teardown recipe (0 processes, 0 receivers, 0 forwards, stay_on 0,
  timeout 1800000, Wi-Fi as found).

ITEM 3 [45 min] — Play handoff completion
- Update docs/PLAY_LISTING.md: add the Controller mode + Import + Linux-layer lines to the
  description; confirm the data-safety answers still hold (no collection, no sharing;
  local-network stream; the new controller channel changes nothing about data leaving the
  owner's devices).
- Confirm the store assets exist (docs/play-assets/icon-512.png, feature-base.png) and list
  exactly what the owner must do in the console (upload AAB, listing, data safety, content
  rating, internal testing track, testers).

ITEM 4 [30 min] — close-out
- Write Temp\opencode\t16-2\RESULT.txt: the AGENTS.md table, the verification checklist
  with pass/fail per step, the AAB details, the owner checklist, cleanup state.
- Cleanup verified. Final reply: table + failures + unknowns + cleanup yes/no + one line
  "T16-2: COMPLETE — READY FOR OWNER UPLOAD" or "T16-2: NOT COMPLETE — <exact list>".
  Then STOP. No commit, no push, no upload.

TIME BOX: 5 hours total. Work until genuinely complete or technically blocked.
