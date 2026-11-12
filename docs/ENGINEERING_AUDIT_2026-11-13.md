# ENGINEERING AUDIT — PopToPC (`unified-desktop`) — 2026-11-13

Author: senior Android performance/architecture + systems + Play-policy review (read-only audit; **no code changed, nothing committed**).
Evidence rules: every code claim cites `file:line` verified against the real tree; every measured number comes from the repo's own docs (STATE.md / OPTIMIZATION.md / TODO.md / docs/PHASE12_RESULT.md context); every external claim carries a URL; anything not directly verified is labeled **est.** (estimate) or **unverified**.
Position audited: Phases 1–13 complete and phone-verified (STATE.md, 2026-11-07); Phase 14 (rename to PopToPC + Play prep) opens next; T15-3 import feature is uncommitted in the working tree (TODO.md line 1: "done 3 of 5").
Owner decisions for this audit: **no backend for v1** (cloud = local export/import + sync over the encrypted PC link); deliverable = this one report + a chat summary.

---

## A. Method-level audit table

Verdicts: ✅ fine (keep) · ⚠️ minor problem · ❗ real problem. "Effort": S <1 day, M 1–3 d, L >3 d (coder-days, est.). Impact: what changes in a measured number or risk.

### A1. Link, transport, pairing (phone side)

| # | method/area | file | what it does today | verdict — why fine / what's wrong | best current replacement (2025-26) | effort | risk | impact |
|---|---|---|---|---|---|---|---|---|
| 1 | TLS server setup | Phase4LinkService.kt:576-631 | `SSLContext "TLS"`, server cert from app files, `wantClientAuth=true`, all-interfaces :47600, 30 s soTimeout | ✅ correct model; trust deliberately app-layer (B-115 gate + pin list). `want` (not `need`) lets cert-less clients finish the handshake, then get closed at :640-641 — wasted 1 RTT per scanner | Use platform `SSLServer` defaults is fine; optionally set an `X509ExtendedKeyManager` that rejects no-cert clients during handshake to fail scanners faster | S | low | hardens pre-pair surface (est. closes the "pairing-prompt spam" half of the door) |
| 2 | `say()` logger on hot path | Phase4LinkService.kt:354-360 | every `say()` = logcat + **appendText to `files/p4/p4.log`** + lastState. Called per keystroke (`Phase5Session.kt:473` keydown, `:492` keyup with typed text), per control line, per frame stats | ❗ file I/O + string build on the session/input path per keystroke; log grows unbounded (documented F15 in this audit, not yet in BUGS.md); **keyup logs the user's typed text** (privacy) | In-memory ring (e.g. 2k lines) + write on session end only; debug flag for live file logging; **never log text content** (length only) | S | low | removes per-keystroke I/O (est. 0.5–2 ms/keystroke on flash storage) + privacy fix |
| 3 | Decoder | Phase4Protocol.kt:137-176 | stateful byte-stream decoder, `feed()` avoids copy when buffer fully consumed, throws on bad version/len/channel | ✅ minimal-copy, validated; 1 copy socket→decoder→message is the floor for `InputStream` TLS | Optional future: `ByteQueue`-style ring to skip the final copy (marginal, est. <0.1 ms) | S | low | negligible; leave |
| 4 | PriorityWriter.emitOne | Phase4Protocol.kt:186-233 | per-64-KiB-chunk strict priority `input>control>audio>clip>video>cam>file>bench`; socket write outside `qLock` | ✅ the core QoS design; verified by protocol.rs tests on the PC twin | Keep; add a debug assert that emitOne runs on exactly one thread (contract currently documented only) | S | low | none (prevents future regression) |
| 5 | Gate / mayProcessControl | Phase4Protocol.kt:81-108 | pre-pair only empty keep-alives pass (B-115) | ✅ pure + unit-tested | keep | — | — | — |
| 6 | watchdogLoop | Phase4LinkService.kt:479-491 | 1 Hz force-close on no inbound (SO_LINGER 0) | ✅ measured to work (B-046 notes ~8.5 s overshoot in one 2026-10 run — accepted) | keep | — | — | — |
| 7 | governorLoop | Phase4LinkService.kt:298-335 | 2 s poll: MemoryInfo + `currentThermalStatus` (+ debug injection) | ⚠️ polls `trimLevel` via component callback anyway; polling is cheap but `onTrimMemory` is the idiomatic signal | Register `ComponentCallbacks2.onTrimMemory` for the trim rung; keep the 2 s thermal poll | S | low | negligible CPU; more idiomatic |
| 8 | FGS type fallback | Phase4LinkService.kt:493-506 | `connectedDevice` → fallback **`dataSync`** if startForeground throws; no `onTimeout()` handling anywhere (grep: none) | ❗ Android 15 (API 35) **kills `dataSync` FGS after 6 h per 24 h** via `Service.onTimeout()`; a long session on a device that refuses `connectedDevice` dies mid-session (PRD gate is 2 h charger survival — currently met, but this is a silent cliff on other OEMs) [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15), [FGS timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) | Override `onTimeout()`: promote to `mediaProjection`/`specialUse` path or post a "reconnect" notification and keep the session alive via the already-held wakelock + a `shortService` continuation; document type choice in a DECISIONS entry | M | medium | prevents a 6-h session death class on some OEMs |
| 9 | Pairing prompt | Phase4LinkService.kt:667-711 + Phase4PairingPrompt.kt | single pendingPairing CAS; notification on `!interactive||keyguard` (B-125, `VISIBILITY_PUBLIC` deliberate) | ⚠️ no rate limit: any LAN host can open repeated TLS connections and pop the public-visibility prompt (nuisance/DoS; B-125 chose PUBLIC for lock-screen visibility) | Per-fingerprint attempt cooldown (e.g. 3 prompts/5 min) + switch visibility to `VISIBILITY_PRIVATE` with the code only in the app UI (manager decision — B-125 recorded PUBLIC deliberately) | S | low | kills the prompt-spam vector |
| 10 | identity key storage | Phase4LinkService.kt:544-583 | PKCS8 Base64 in app files + in-memory PKCS12 pwd `"unified"` | ⚠️ unencrypted key in app storage; app-private protects vs other apps, not vs backup/root | `AndroidKeyStore` EC key (non-exportable) + self-signed cert via `KeyGenParameterSpec`; keep a fingerprint pin list (NDK/BlackBox path unaffected) | M | medium | hardware-backed identity |
| 11 | trust store JSON | Phase4Crypto.kt:233-273 | NDJSON with naive `jsonField` scan; forget by uppercase prefix | ⚠️ label with escaped quote round-trips wrong (display-only); ACTION_FORGET never force-closes the forgotten PC's live session (`Phase4LinkService.kt:228-239` — contradicts Phase4Crypto doc "cuts its live connection") | Parse with `org.json` (already a dep for tests); on forget, close matching `liveSessions` | S | low | correctness + closes documented contract gap |
| 12 | AOA path | Phase4Aoa.kt + Phase4LinkService.kt:363-372 | accessory fd adopted, never read/written ("item 8" explicitly open) | ⚠️ known open gap (USB = tethering only, per D17) | Either implement TLS-over-accessory bulk streams or delete the dead fd (it leaks on `stopAll` — never closed) | L / S(cleanup) | low | honest surface reduction now, real feature later |

### A2. Video pipeline (phone side)

| # | method/area | file | what it does today | verdict | best current replacement | effort | risk | impact |
|---|---|---|---|---|---|---|---|---|
| 13 | GL compositor init | Phase5Encoder.kt:330-400 | EGL14 + GLES2 on `p5-gl` HandlerThread, `EGL_RECORDABLE_ANDROID`, external-OES texture | ✅ standard Grafika pattern, zero CPU copies | keep | — | — | — |
| 14 | frame pacing | Phase5Encoder.kt:433-448 | `Handler.postDelayed` to next fps slot; forced draws bypass | ⚠️ `postDelayed` granularity ≈ handler-thread jitter, not vsync-aligned; the pacing *concept* is right and measured (200 ms backlog fixed, LESSONS §3) | Drive draws from `Choreographer` on the GL thread (frame callback → draw), keep keep-alive timer; **est.** 1–2 ms pacing jitter reduction feeding the 41.8 ms `drawn>encoded` floor | M | low | typing p50 est. −1–2 ms |
| 15 | per-frame log | Phase5Encoder.kt:487 | `Log.i("P5PROBE draw …")` string build + logd IPC **every drawn frame** | ❗ hot-path logging at up to 60 fps. Note: B-122's decision says "Kept P5PROBE logs" — they were needed for diagnosis. Gate behind `BuildConfig.DEBUG` (debug keeps them; release loses nothing — `lat` reports don't depend on the log line) | `if (BuildConfig.DEBUG) Log.i(...)` | S | low | **est. 1–3 ms/frame** on SD695-class; directly attacks the 41.8 ms `drawn>encoded` floor |
| 16 | updateTexImage latch-all | Phase5Encoder.kt:450-456 | latches every queued buffer per draw | ✅ the measured fix for backlog | keep | — | — | — |
| 17 | per-frame GL state re-issue | Phase5Encoder.kt:467-478 | viewport/useProgram/attrib pointers every frame | ⚠️ 5 redundant GLES calls/frame (state unchanged) | Set once at init (viewport only changes with size); keep matrix upload per frame | S | low | est. <0.5 ms/frame |
| 18 | encoder optional-key ladder | Phase5Encoder.kt:180-245 | CBR, baseline, realtime priority, no B-frames, `KEY_LOW_LATENCY`, QTI low-latency, prepend-header; degrade ladder (B-056) | ✅ matches 2025 best practice ([AOSP low-latency media](https://source.android.com/docs/core/media/low-latency-media), [QMediaExtensions](https://docs.qualcomm.com/doc/80-56386-10/topic/classqti_1_1video_1_1QMediaExtensions.html), [Videon best practices](https://support.videonlabs.com/hc/en-us/articles/31279433435923-Low-Latency-Configuration-Best-Practices)) | **Add `KEY_INTRA_REFRESH` probe** (row/period tuned so one I-frame never lands whole in a frame — smooths bitrate spikes; Sunshine/NVENC practice [issue #3323](https://github.com/LizardByte/Sunshine/issues/3323)); probe with the same degrade ladder; A/B measure per OPTIMIZATION rule zero | M | medium | est. 2–5 ms on keyframe-heavy moments; smoother fps |
| 19 | toAnnexB | Phase5Encoder.kt:593-610 | per-AU: `ByteArrayOutputStream` + a 4-byte array per NAL + final `toByteArray()` — 2 full copies + n+2 allocs per frame | ⚠️ at 12 Mbps / 60 fps ≈ 2×~25 KB copies + garbage per frame | Preallocate one scratch buffer sized to max AU; write start-codes in place; return a slice view or copy once | S | low | est. <1 ms/frame + less GC churn |
| 20 | output loop | Phase5Encoder.kt:507-570 | dequeue 10 ms timeout, AVCC→Annex-B, csd merge, listener callback | ✅ fine | combine with #19 | S | low | — |
| 21 | D22 thermal step | Phase5Encoder.kt:96-103 + Phase5Session.kt:260-327 | fixed desktop 1728x1080 → encoder input 3/4 axis (1296x810), keyframe on rebuild | ✅ implemented per D22; emulator-verified (28.6 enc fps, dropped 0) | **Add de-escalation**: when pressure clears for N min, rebuild back to 100% + keyframe (today `displayScale` stays 75 until session end; `Phase10Governor` has no recovery rung — `Phase10Governor.kt:77-123`) | M | low | quality restored after cool-down |
| 22 | video queue policy | Phase5Session.kt:351-407 | drop-until-keyframe at ≥2 queued; 400 ms keyframe request limit | ✅ correct congestion shape | keep | — | — | — |
| 23 | statsLoop clock sync | Phase5Session.kt:599-640 | 1 Hz ping/pong + csr/lat reports | ✅ fine | keep | — | — | — |
| 24 | Quality wiring | Quality.kt:38-47 + Phase5Session.kt:129 | AUTO=12 Mbps/60; SHARP/SMOOTH map to STANDARD/SMOOTH constants; re-read per encoder build | ✅ wired; Settings copy honest ("when the desktop starts") | **`Phase12Profiles.choose()` is dead code** (zero production callers, only its test; LIGHT dims conflict with D22) — delete it or wire it; decision for manager (AUDIT row, not a BUGS row) | S (delete) / M (wire) | low | removes confusion |
| 25 | adaptive bitrate | ALGORITHMS.md §3 promises ~250 ms ABR; **not implemented** — PC sends `vq` stats (view.rs `PcVideoStats`) but no code feeds encoder bitrate from RTT/loss (grep: nothing consumes `vq` phone-side) | ❗ biggest missing piece for Wi-Fi p90 (119.6 vs target 120 — margin 0.4 ms) | Consume `vq` in `Phase5Session.onControlMessage`: exponential-up/multiplicative-down bitrate trim ±20% steps with a floor (4 Mbps), request keyframe after big drops; unit-test the pure policy; measure per rule zero | M | medium | Wi-Fi p90 est. 119.6→≤110; removes stall stalls like OPTIMIZATION §5.3's "transport stall enc>pc_rx 27.9" |
| 26 | key URGENT path | Phase5Encoder.kt:62-77 + Phase5Session.kt:455-470 | 150 ms urgent window skips fps pacing on key/button | ✅ measured win (Browser 220→70 ms) | keep | — | — | — |

### A3. Desktop shell + input dispatch (phone side)

| # | method/area | file | what it does today | verdict | best current replacement | effort | risk | impact |
|---|---|---|---|---|---|---|---|---|
| 27 | window model | Phase6ShellModel.kt (233 L) | pure z-order/focus/resize/snap, JVM-tested | ✅ exemplary | keep | — | — | — |
| 28 | per-click logging | Phase6Shell.kt:1039-1046 | **every mouse click** builds a long string + `P7Log.say` on the UI thread | ❗ P7Log.say (P7Log.kt:17-24) = SimpleDateFormat.format + logcat + **appendText to p7.log per call**, un-gated; this is per-click file I/O on the UI thread (B-139 diagnostic, rate-limit was applied in Phase6Window but NOT here) | Gate behind `BuildConfig.DEBUG` + rate-limit (mirror Phase6Window's 24-log rule); cache the `SimpleDateFormat` (or use epoch ms) | S | low | click latency hygiene; est. 0.3–1 ms/click + no UI-thread disk I/O |
| 29 | P7Log itself | P7Log.kt | every call = logcat + file append, `@Synchronized` | ❗ shared by X11 frame loop error paths and guest logs → serialized file I/O | Ring buffer + flush timer; release builds keep logcat only | S | low | removes contention risk |
| 30 | input fan-out | Phase6Shell.kt:977-1203 | moveTo hover, button bookkeeping, `dispatchTouchEvent` with real MotionEvents, IME-commit key route (B-135) | ✅ correct: no injection APIs, real Android input path | keep | — | — | — |
| 31 | syncViews | Phase6Shell.kt:757-782 | model→views mirror; `bringChildToFront` (keeps drags alive) | ✅ deliberate (comment) | keep | — | — | — |
| 32 | taskbar ticker | Phase6Views.kt:128-133 | 1 Hz invalidate | ✅ fine | keep | — | — | — |
| 33 | Home ticker | HomeActivity.kt:836-844 | 500 ms refresh of card states | ⚠️ cheap but constant | Drive state changes from service broadcasts instead of polling (minor battery/usage win; invisible to user) | S | low | negligible |
| 34 | failure classification | Phase4LinkService.kt:170-174 + HomeActivity | substring-match on log text for "PROBLEM" state | ⚠️ brittle (a reworded log silently kills the UI state) | Typed failure enum on `lastFailure` | S | low | robustness |
| 35 | keyboard shortcuts / Esc / Alt+F4 | Phase6Shell.kt:1145-1203 | modal-owns-keys, Win opens start menu | ✅ | keep | — | — | — |

### A4. X11 / Wine / Box64 (phone side)

| # | method/area | file | what it does today | verdict | best current replacement | effort | risk | impact |
|---|---|---|---|---|---|---|---|---|
| 36 | X11 window feed | Phase2X11.kt:670-685 + Phase7Windows.kt:119-155 | **full-root `GetImage` every poll** — even unchanged grabs download the whole 1280×720×4 B (≈3.7 MB) over TCP; the byte-compare (`x11ImageUnchanged`) happens **after** the transfer; after a key it polls every 8 ms | ❗ the single biggest waste in the codebase: during typing bursts this moves hundreds of MB/s over loopback TCP and does a 3.7 MB memcmp each grab; per changed frame it allocates ByteArray (3.7 MB) + `IntArray(w*h)` + Bitmap (3.7 MB) (`Phase2X11.kt:687-698`) | **XDamage/XFixes**: subscribe to root damage events, fetch only damaged rects (typically a few KB after a keystroke), compose into a reused bitmap; short-term patch: reuse one ByteArray + `Bitmap.copyPixelsFromBuffer` and skip the IntArray; medium-term: rootless per-window buffers like Termux:X11. This is the structural fix for the X11 typing floor (Notepad++ p50 66.6 ms measured after the byte-compare fix — the remaining `dispatch→drawn` 6.7 ms is mostly this copy chain) | L (XDamage) / S (buffer reuse) | medium (new X extension path) | typing in X11 apps est. −3–6 ms + large CPU drop |
| 37 | X11 client protocol | Phase2X11.kt:545-1091 | hand-rolled pure-Java X11 client, XTEST, QueryKeymap verify + release retries (B-140) | ✅ battle-tested with phone-verified wire quirks (LESSONS §7 round 5c) | keep; add damage only | — | — | — |
| 38 | right/middle click | Phase7Windows.kt:243-260 | only button 1 + wheel 4/5 reach Xvfb; `mouseButton` no-op | ❗ X11 apps needing right-click (context menus) cannot be used | Map PC button 2/3 → XTEST button 3/2 through the existing serialized input executor | S | low | user-visible feature gap closes |
| 39 | Xvfb lifecycle | Phase2X11.kt:265-405 | byte-patched Xvfb, `-nolisten unix`, two-gate readiness, 6 retries, socket-probe liveness (B-084) | ✅ proven under seccomp constraints (MIT-SHM disabled, /tmp impossible — LESSONS) | keep | — | — | — |
| 40 | wine boot | Phase2Wine.kt:831-890 | runtime staging → reaper → Xvfb → **drop hives every boot** → patch → farm → wineserver → `wineboot -i` | ✅ the B-020/B-022 family fix; MSI state survives via export/import dance (B-094) | keep; add a loud comment + a test that no new caller ever calls `boot()` directly (B-086 `ensureLivePrefix` contract) | S | low | prevents a silent future footgun |
| 41 | wineserver supervision | Phase2Wine.kt:624-741 | app starts `wineserver -p 3600` itself; LocalSocket probe + lock fallback; /proc comm pid scan | ✅ correct under box64 posix_spawn death (B-020) | keep | — | — | — |
| 42 | safety cap / reaper | Phase2Wine.kt:371-546 | 40 procs / 600 MB cap, 5 s sampler; reaper spares app family | ✅ the phantom-process-killer answer (LESSONS) | keep | — | — | — |
| 43 | downloads | Phase2Data.kt:259-495 | pins + SHA-256, manual redirect follow, Range resume, 30 attempts, stall detection, mirror ladder (B-133/B-137) | ✅ strong; better than most production apps | keep | — | — | — |
| 44 | tar/deb reader | Phase2Data.kt:536-758 | GNU long names, name sanitize, symlink-escape check, ELF peek | ✅ | keep | — | — | — |
| 45 | sleeps in launchers | Phase2X11.kt:415-475 (`startXeyes/Xcalc/Xmessage` sleep 3000 on caller) | ⚠️ blocking sleeps inside dispatch paths | Replace with the existing readiness loops (`waitForCatalogWindow` L1164) | S | low | snappier catalog opens |
| 46 | box64 strategy | android/win86/fetch-box64.sh (654 L) | upstream box64 pinned 27860e6, 9 patch blocks, 16 KB-linked `libbox64.so` | ✅ keep Box64: Jan-2026 low-end-ARM benchmarks put Box64 (~2.4–2.5× native penalty) ahead of FEX (~3.2×) on small cores (reported in web research; exact article URL not captured — **unverified**); FEX ARM64EC still in development ([FEX wiki](https://fex-emu.com/), [FEX GitHub](https://github.com/FEX-Emu/FEX)) | Add **per-app DYNAREC profiles** (BOX64_DYNAREC_BIGBLOCK/STRONGMEM/SAFEFLAGS per app, the Winlator approach) stored next to the catalog rating — targets the NPP 9.87 s guest time directly | M | medium | NPP start est. −0.5–1.5 s |
| 47 | NPP prewarm | Phase2Wine.kt:1160-1207 + Phase12Preload | launches+closes one NPP copy; guarded vs real open (B-124: ghost window + duplicate process risk → default OFF) | ⚠️ the 0.97 s win (10.45→9.48 measured) is locked behind an unfixed race | Fix the race properly: prewarm marks its process as a placeholder the real open *adopts* (same pid reuse) instead of kill-then-launch | M | medium | NPP 10.45→est. ~9.5 s with prewarm default ON |

### A5. Android guests (BlackBox)

| # | method/area | file | verdict / note | replacement | effort | risk | impact |
|---|---|---|---|---|---|---|---|
| 48 | B1 decor reparent | GuestPresentationHost.java:367-438 | detach guest decor → Presentation on private display; DetachedWindowCallback swallows attr changes; Compose owner tags copied | ✅ **confirmed correct choice**: cross-app ActivityEmbedding requires the *target* app to be export-embedded ([Jetpack WindowManager docs](https://developer.android.com/develop/ui/views/layout/activity-embedding)) and launching onto app-made virtual displays stays blocked (LESSONS, phone-proven) — B1 remains the only viable route in 2025-26; `SurfaceControlViewHost` (B2 path, `GuestSurfacePresentationHost.java`) exists as fallback but received no guest frames (Phase-1 evidence) | keep B1; keep B2 as the API-30+ experiment | — | — | — |
| 49 | guest input socket | GuestPresentationHost.java:131-245 + AndroidWindowContent.kt:202-222 | abstract LocalSocket, kernel-uid check from `/proc/self/status` (B-073), 500 ms latch per event | ✅ | batch touch moves per frame slice (est. fewer socket round-trips during drags) | M | low | drag smoothness |
| 50 | guest close | AndroidWindowContent.kt:152-159 + `C <pkg>` | best-effort close, EngineDaemon refcount | ⚠️ busy guest UI thread drops the reply → window shows closed but guest lives | Add an EngineBridge-side `killPackage` fallback (engine already has one) after a 1 s unconfirmed close | S | low | guest leak prevention |
| 51 | Phase7Dispatch divergence | Phase7Dispatch.kt:128-144 | headless display 1280x720 @320 dpi, writes to `files/phase1` | ⚠️ harness-only inconsistency | align with product geometry or rename dir | S | low | hygiene |

### A6. App Center / installers

| # | method/area | file | verdict / note | replacement | effort | risk | impact |
|---|---|---|---|---|---|---|---|
| 52 | picker | Catalog.kt:60-93 + app_catalog.json (14 apps) | rating → kind order (D7) → numeric version; "Not yet" never offered while usable exists | ✅ pure + tested | ratings are static strings — wire them to the runtime "did it open" result so the catalog learns per device (see E-features) | M | low | long-term UX |
| 53 | winget manifests | Installers.kt:42-160 | YAML subset reader, x64 MSI preferred, SHA-256 + version verify refuses changed manifests | ✅ trust model is right ([winget manifests](https://learn.microsoft.com/windows/package-manager/winget/)) | keep; cache manifest commits to survive upstream re-pins (B-133 policy already documented in Catalog.kt:13-25) | S | low | — |
| 54 | MSI product code | Installers.kt:248-310 | regex-free GUID detect + reg query parse | ✅ (ICU regex crash avoided deliberately) | keep | — | — | — |
| 55 | T15-3 import | ImportedApps.kt (new) + Phase11Files kind byte + files.rs KIND_IMPORT | PC→phone exe/msi/zip import, sha dedupe, traversal-safe zip | ✅ new, tested | finish the remaining 2/5 tasks (phone proofs per TODO.md) | S | low | ships the feature |

### A7. Tools (Node/code-server)

| # | method/area | file | verdict / note | replacement | effort | risk | impact |
|---|---|---|---|---|---|---|---|
| 56 | D20 auth | Phase3Server.kt:75-117, 218-333 | 32-byte SecureRandom password per start, `--config` only, loopback bind, cookie pre-injection, redacted status | ✅ strong (D18 withdrawal lesson applied) | keep | — | — | — |
| 57 | login loop | Phase3Server.kt:117-155 | 3 attempts POST /login, blocking on caller thread | ✅ bounded | keep | — | — | — |
| 58 | process supervision | Phase3Server.kt:344-436 | /proc cmdline scan by app-private root path, TERM→KILL | ✅ | keep | — | — | — |
| 59 | ELF filter | Phase3Data.kt:102-114, 117 | name filter + magic-byte guard on every deb member | ✅ the Play code-loading defense in depth | keep | — | — | — |
| 60 | native modules | Phase3Data.kt:251-276 | symlinks from unpacked tree into `nativeLibraryDir`, `--preserve-symlinks` | ✅ clever + verified (`/proc/<pid>/exe` gate, Phase3Native.kt:159-160) | keep | — | — | — |
| 61 | CodeServerWarm gate | CodeServerWarm.kt:24-99 | single `@Synchronized` "is up" gate; stale `Phase3CodeActivity.kt:13` comment says `auth:none` | ⚠️ stale comment only | fix comment | S | low | docs truth |

### A8. Channels (files/clip/audio/camera)

| # | method/area | file | verdict / note | replacement | effort | risk | impact |
|---|---|---|---|---|---|---|---|
| 62 | audio capture | Phase11Audio.kt:139-218 | 48 kHz stereo, 960-sample packets, nanoTime stamps, `ShortArray(PACKET_SAMPLES)` reused? — allocated per read loop iteration (line 183) | ⚠️ small per-packet alloc (960 shorts ≈ 2 KB @100 pkt/s ≈ 200 KB/s garbage) | reuse one buffer | S | low | GC hygiene |
| 63 | sink race | Phase11Audio.kt:103-113 vs Phase11Files.kt:204-220 | `bindSink/unbindSink` unconditional; Files sender guarded by `currentLive === live` | ❗ reconnect race: old session's finally unbinds the new session's capture → PC audio dies for the session (new finding, not in BUGS.md) | copy the Files guard pattern into Audio | S | low | fixes a real audio-loss bug |
| 64 | camera shim | Phase6Apps.kt:517, 542-560, 995-1006 | main-frame HTML re-served from `shouldInterceptRequest` with shim prepended + relaxed CSP | ✅ works (webcamtests 640x360/11 fps proven) | androidx.webkit `WebViewAssetLoader` would be cleaner but adds a dependency for zero measured gain — keep | — | — | — |
| 65 | clipboard hub | Phase11Clip.kt + Phase11X11Clip.kt | 3-way with FNV-1a echo guard; X11 owner via PropertyNotify timestamps; B-130 fixed `ChangePropertyWire`; B-103 closed end-to-end (STATE 2026-11-07) | ✅ | keep | — | — | — |
| 66 | file transfer | Phase11Files.kt:31-178 | kind byte (back-compat), streaming SHA-256, `.part` verify-then-rename | ✅ | keep | — | — | — |

### A9. PC receiver (Rust)

| # | method/area | file | verdict / note | replacement | effort | risk | impact |
|---|---|---|---|---|---|---|---|
| 67 | TLS verifier per connection | crypto.rs:181-190 | `verify_server_cert` builds a fresh `RootCertStore` + `WebPkiServerVerifier` **per connection** | ⚠️ verifier construction is not free (webpki cert parsing) | Build the verifier once at client-config creation (it's caller-agnostic here — it trusts only the presented cert); ~1-line-per-connection saved | S | low | connect path est. −1–5 ms |
| 68 | reader thread | session.rs:193-197 | blocking reads → 16 KiB buffer → `sync_channel(4)`; tick semantics (B-067/B-072) | ✅ measured design (512 KB queue added ~27 ms — documented) | keep | — | — | — |
| 69 | reconnect backoff | session.rs:567-580 | 0.5→10 s, terminal errors stop (B-116) | ✅ live-proven | keep | — | — | — |
| 70 | input coalescing | view.rs:216-235 | moves coalesce, keys never drop, queue cap 8192 | ✅ | keep | — | — | — |
| 71 | file pacing | view.rs:1416-1460 | ≤8 CH_FILE msgs per loop turn so 20 MB file never starves input | ✅ | keep | — | — | — |
| 72 | decode path | view.rs:2197-2283, 2386-2445 | MFT + DXGIDeviceManager (IUnknown-pointer subtlety documented), stream-change in place, IDR wait | ✅ delicate but correct; B-104 output-sample alloc handled via ManuallyDrop | keep; note the B-104 **software decode black screen remains** (software path drops every frame on the 125% laptop) — see B-057 research (§B.3) | — | — | — |
| 73 | crop math | phase5.rs:344-350 + view.rs:2491-2593 | 1088→1080 visible-rect crop, unit-tested | ✅ | keep | — | — | — |
| 74 | keyboard hook | lock.rs:929-931, 1524-1528 | per-key GetAsyncKeyState + atomics, both edges swallowed | ✅ | keep | — | — | — |
| 75 | status file cadence | lock.rs:793-800 | rewrites `lock-status.txt` every 200 ms while RUNNING | ⚠️ trivial file I/O ×5/s | write on state change + every 1 s | S | low | negligible; hygiene |
| 76 | audio pump | audio.rs:540-567, 331-399 | tops up to 2880 frames (60 ms), one packet per write, per-sample f32/i16 loop | ⚠️ per-sample loop is fine at 48 kHz (48k samples/s) but not vectorized | chunk-copy via `copy_from_slice` per channel pair where format allows | S | low | est. <1% CPU |
| 77 | jitter buffer | audio.rs:54-120 | 6-packet target, 12 cap, drop-late, one-underrun-per-gap | ✅ B-101-tuned | keep | — | — | — |
| 78 | camera | cam.rs:247-408 | 15 fps pace, one-pass downscale, JPEG q70; **MFEnumDeviceSources activate array never CoTaskMemFree'd** (cam.rs:42-50) | ⚠️ bounded per-stream leak | free the array after source selection | S | low | resource hygiene |
| 79 | file sink | files.rs:97-161 | basename sanitize, sha verify, unique names; START-during-transfer silently drops previous `Incoming` (`.part` stays) | ⚠️ malformed peer can leak `.part` files | delete the orphan `.part` on supersede | S | low | hygiene |
| 80 | mdns probing | mdns.rs | probes each local IPv4 as socket source (WARP workaround) | ✅ measured necessity | keep | — | — | — |
| 81 | build profile | pc/Cargo.toml:5-10 | `panic="abort"`, `strip`, `lto="thin"` at workspace root (B-050) | ✅ | consider `lto="fat"` + `codegen-units=1` for the receiver (size/speed) — measure binary + bench before adopting | S | low | est. −5–10% binary |

### A10. Build system & repo

| # | method/area | file | verdict / note | replacement | effort | risk | impact |
|---|---|---|---|---|---|---|---|
| 82 | R8/minify | android/app/build.gradle.kts:56 `isMinifyEnabled=false` | no code/resource shrinking | ⚠️ enabling **R8 full mode** shrinks the app and speeds startup ([Reddit case: startup >50% faster with R8+Baseline](https://www.reddit.com/r/androiddev/comments/1hd6coy/reddit_improved_app_startup_speed_by_over_50)) — but BlackBox + FreeReflection + GeckoView rely on reflection: needs careful keep rules + regression pass | enable with a tested keep-rule set; measure APK size + startup before/after | M | **high** (reflection breaks silently) | APK size est. −20–40%; startup est. −10–20% |
| 83 | Baseline Profiles | absent | none | ❗ cheapest startup win available: [official ~30%](https://developer.android.com/topic/performance/baselineprofiles/overview), [Duolingo −30%](https://blog.duolingo.com/slashed-android-startup-time-baseline-profiles), [Meta 2025](https://engineering.fb.com/2025/10/01/android/accelerating-our-android-apps-with-baseline-profiles) | add `androidx.baselineprofile` module + macrobenchmark cold-start test; profile TLS/pairing/display/shell-init paths | M | low | connect est. 0.944→~0.75-0.85 s (est.) |
| 84 | lint config | none (`grep lint` empty) | only default lintVital | ⚠️ | add `lint {}` severity config matching the gate | S | low | gate hygiene |
| 85 | gradlew | hand-rolled curl+sha (no wrapper dir) | ✅ sha-verified 9.8.1 | keep | — | — | — |
| 86 | legacy packaging | app/build.gradle.kts `useLegacyPackaging=true` | required: libbox64 must be *extracted* to be executable | ✅ deliberate | keep | — | — | — |
| 87 | 16 KB alignment | CMake `-Wl,-z,max-page-size=16384` + fetch scripts verify `0x4000` per file (T13-6, phone-verified) | ✅ ahead of the Nov 1 2025 Play deadline ([16 KB requirement](https://developer.android.com/guide/practices/page-sizes), [Play blog](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html)) | keep | — | — | — |
| 88 | repo junk | `undefined/` (tracked!), `Modelfile` (untracked Ollama config), `.freebuff/` (exclude only in .git/info/exclude) | ❗ hygiene | `git rm -r undefined/`; delete/ignore Modelfile; move .freebuff into .gitignore | S | low | clean repo |
| 89 | tracking-doc drift | BUGS.md | 30 rows resolved-in-place never moved to the Fixed table; B-135 duplicated (fixed+open); B-103/B-130 in both tables | ⚠️ audit found STATE.md (2026-11-07) newer than BUGS.md rows | manager triage pass: move fixed rows, dedupe | S | low | docs truth |

### A11. Security summary rows (detail in §F)

| # | area | verdict | note |
|---|---|---|---|
| 90 | exported HarnessActivity (AndroidManifest.xml:32, no permission) | ❗ | release-build surface: any app can start it with `phase3x=node:<js>` → JS execution via libnode.so with app privileges (verified chain: manifest :32 → HarnessActivity.kt:235-243 → Phase3Dispatch → Phase3Native.node). HarnessReceiver is correctly DUMP-gated + debug-only; the activity is the unguarded remainder. Not in BUGS.md — **proposed B-138**. Must fix before Play. |
| 91 | pairing code = deterministic SAS of both certs | ✅ (documented property) | authenticates *binding* (both screens agree), user's Yes is the trust decision (TOFU) — same class as BLE numeric comparison; upgrade options in §B.9 |
| 92 | TLS key not in AndroidKeyStore | ⚠️ | row 10 |
| 93 | clipboard transmission to paired PC | ⚠️ (policy) | data-safety declaration + listing disclosure needed (§F) |

---

## B. Research report — current best methods (2025-26), with sources

### B.1 Low-latency video encoding from Android

**Our state** (`Phase5Encoder.kt:180-245`): H.264 hardware via `createInputSurface`, CBR, baseline, no B-frames, `KEY_I_FRAME_INTERVAL=10`, `KEY_LOW_LATENCY=1` + `vendor.qti-ext-enc-low-latency.enable=1` + realtime priority + prepend-headers, degrade ladder for emulator rejection. 12 Mbps default (knob B 8 Mbps was measured worse and reverted — OPTIMIZATION §5.3).
**Verdict: current config matches published best practice.** The remaining wins are (a) **KEY_INTRA_REFRESH** to smooth I-frame cost (NVIDIA/Sunshine practice: intra-refresh distributes I-cost over frames, avoiding latency spikes — [Sunshine #3323](https://github.com/LizardByte/Sunshine/issues/3323); SunShine docs recommend client-driven bitrate with fast presets [docs](https://docs.lizardbyte.dev/projects/sunshine/master/md_docs_2configuration.html), [advanced](https://docs.lizardbyte.dev/projects/sunshine/v0.23.1/about/advanced_usage.html)); (b) **adaptive bitrate** (absent here — see row 25); (c) pacing via Choreographer (row 14). AOSP documents the low-latency decode/encode keys we already use ([AOSP low-latency media](https://source.android.com/docs/core/media/low-latency-media)); QTI vendor keys are discoverable per device via `getSupportedVendorParameters()` ([Qualcomm blog](https://www.qualcomm.com/developer/blog/2023/07/building-media-rich-android-apps-mediacodec-and-vendor-extensions), [QMediaExtensions](https://docs.qualcomm.com/doc/80-56386-10/topic/classqti_1_1video_1_1QMediaExtensions.html)). Community reference for sub-10 ms H.264 pipelines: [LiveVideo10ms](https://github.com/Consti10/LiveVideo10ms). 1080p60 LAN bitrate guidance ~15-25 Mbps matches our 12-24 range ([Moonlight optimization guide](https://joltfly.com/optimize-moonlight-game-streaming-for-ultra-low-latency)).
**Recommendation:** keep H.264 CBR + low-latency; add intra-refresh probe + ABR; revisit AV1/HEVC only when target-device encoder coverage justifies it (H.264 remains the only universally hardware-decoded option on old PCs — our PRD R10).

### B.2 Compositing model

**Our state:** one private VirtualDisplay → SurfaceTexture → GL → encoder input surface (zero-copy, Phase5Encoder/Phase5Desktop), guests via B1 Presentation reparenting, WebView windows in the shell.
**Verdict: the model is right and still the only viable one.** `VirtualDisplay`+`Presentation` are not deprecated ([VirtualDisplay reference](https://developer.android.com/reference/android/hardware/display/VirtualDisplay)); the modern `SurfaceControlViewHost` route needs a WindowContext and is for same-trust embedding ([SCVH source](https://android.googlesource.com/platform/frameworks/base.git/+/refs/heads/main/core/java/android/view/SurfaceControlViewHost.java)); Jetpack `ActivityEmbedding` can only embed activities that opt in / your own ([activity embedding docs](https://developer.android.com/develop/ui/views/layout/activity-embedding), [blog](https://medium.com/androiddevelopers/leverage-multi-window-and-activity-embedding-4951f44a8992)) — cross-app freeform embedding of arbitrary Play apps onto app-made displays remains blocked (our Phase-1 NO-GO, phone-proven). Vulkan for the compositor would buy nothing: we draw one textured quad (rows 13-17), and GLES is universally supported.
**Recommendation:** keep the architecture; invest in the X11 damage path (row 36) — that is where real compositor-side waste is, not in the GL layer.

### B.3 The black-screen bug class (B-057/B-104 family)

Facts in repo: emulator `c2.android.avc.encoder` rejects the optional key set and then **ignores sync-frame (IDR) requests**; the receiver correctly renegotiates stream-change in place and waits for IDR after any gap (view.rs:1810-1818); B-104 = software MFT returns null sample → every frame dropped on the 125% laptop (hardware path fine).
**Industry analysis:** this is the classic *"software/quirky encoder + IDR policy"* failure class — the same reason remote-desktop products force periodic IDR on suspicious decoders and gate software encoders to lower resolution. Best practice (WebRTC/Moonlight): never rely on the encoder honoring on-demand IDR alone; send a short periodic keyframe (e.g. 5-10 s) as a safety net and treat a no-IDR encoder as a degraded mode with lower resolution — exactly ALGORITHMS §2's "periodic keyframes only as a safety net", which the phone **does not yet do** (`KEY_I_FRAME_INTERVAL=10` exists but no forced periodic IDR probe on emulator-class encoders).
**Recommendation:** add a small periodic-IDR safety net (10 s) + on emulator/software-encoder detection force LIGHT profile. Sources: [Moonlight/Sunshine optimization](https://www.reddit.com/r/MoonlightStreaming/comments/1d2ihd8/moonlightsunshine_optimisation_and_common_issues), [AOSP MediaCodec](https://android.googlesource.com/platform/frameworks/base.git/+/master/media/java/android/media/MediaCodec.java).

### B.4 Input latency design

**Our state:** input channel highest priority per 64 KiB chunk (both sides), moves coalesced / keys never dropped, urgent-frame bypass, 8 ms X11 echo polling, `TCP_NODELAY` tuning knob, release-all on focus loss; measured stage budget 10.9 / 2.3 / 9.8 / 41.8 / 6.3 / 4.2 ms (PC→phone / recv→dispatch / dispatch→drawn / drawn→encoded / enc→PC / decode-present; OPTIMIZATION §5.3).
**Verdict:** the design already implements everything the low-latency streaming industry does (WebRTC/Moonlight/Parsec-style: priority input path, no coalescing of releases, input-triggered frames). Remaining levers: rows 15 (per-frame log), 14 (Choreographer), 36 (X11 damage), 28/2 (per-click/keystroke logging), and the encoder floor itself (intra-refresh). One phone-side idea from current practice: `main.post` per event is correct for latency; batching *moves only* across one frame would cut handler traffic without delaying keys ([Android input pipeline notes](https://source.android.com/docs/core/interactive/input) — general reference).

### B.5 Networking: UDP vs QUIC vs TCP on LAN

**Our state:** TCP + TLS 1.3, framed 8-channel multiplexing with strict priority, keep-alive 5 s / dead 15 s, measured 8.7-11.5 MB/s Wi-Fi, RTT p50 ~15-16 ms (PHASE4_RESULT).
**2025-26 evidence:** QUIC (via UDP) beats TCP+TLS on connection setup (0-RTT) and multiplexed streams, and 2025 studies report ~30% lower latency vs WebRTC-style stacks and better behavior under loss ([arXiv 2504.10054](https://arxiv.org/html/2504.10054v2), [arXiv 2505.22132](https://arxiv.org/html/2505.22132v1), [HAProxy transport guide](https://www.haproxy.com/blog/choosing-the-right-transport-protocol-tcp-vs-udp-vs-quic), [CACM QUIC critique](https://cacm.acm.org/research/taking-a-long-look-at-quic)); AWS DCV ships QUIC for 4K60 remote workstations ([AWS blog](https://aws.amazon.com/blogs/gametech/stream-a-remote-qa-or-press-environment-with-nice-dcv-over-quic-udp-for-a-4k-monitor-at-60-fps)); Rust stack: [quinn](https://github.com/quinn-rs/quinn)/[docs](https://docs.rs/quinn) supports unreliable datagrams (ideal for video) + reliable streams (input/control) — the exact split we emulate manually today (protocol.rs tests prove input waits ≤1 chunk behind a 1 MiB drain).
**Recommendation:** **stay on TCP+TLS for v1** (it works, it is measured, USB/Wi-Fi LAN losses are low; a transport rewrite mid-Phase-14 violates the project's change discipline) and buy the same wins cheaper with **adaptive bitrate (row 25)** + keyframe-on-loss. Record QUIC as the designated post-launch transport upgrade (Android side would use Cronet or a Java QUIC lib — new dependency, new license entries per THIRD_PARTY rule). Sources as above.

### B.6 Foreground services, background survival, OEM limits (targetSdk 36)

**Our state:** FGS `connectedDevice` (+`mediaProjection` when consented) with `dataSync` fallback; partial wakelock; `START_NOT_STICKY`; onboarding to the ColorOS "Allow background activity" toggle (the only reliable anti-freezer, LESSONS §2 — no public API).
**2025-26 rules:** API 35 kills `dataSync`/`mediaProcessing` FGS at **6 h/24 h** via `onTimeout()` ([behavior changes 15](https://developer.android.com/about/versions/15/behavior-changes-15), [FGS timeout](https://developer.android.com/develop/background-work/services/fgs/timeout), [FGS changes overview](https://developer.android.com/develop/background-work/services/fgs/changes)); targetSdk 36 changes: orientation/resizability restrictions ignored on ≥600dp screens ([behavior changes 16](https://developer.android.com/about/versions/16/behavior-changes-16)); Play target-API deadline for 36 is Aug 31 2026 ([Play target API](https://support.google.com/googleplay/android-developer/answer/11926878)).
**Recommendation:** implement `onTimeout()` (row 8); keep the connectedDevice-first ladder; keep the onboarding flow (it is the honest, policy-safe answer); document the FGS-type strategy in DECISIONS.

### B.7 Native perf: Box64/FEXCore/Wine/DXVK + build flags

**Our state:** upstream Box64 pinned + 9 patch blocks + 16 KB link flag; Wine 11.19 Kron4ek; DXVK/VKD3D absent (D22/Q8: investigated, not feasible yet — Turnip/ICD loader missing).
**2025-26 evidence:** Box64 vs FEX on low-end ARM favors Box64 (Jan-2026 bench: ~2.4-2.5× vs ~3.2× on Cortex-A53; reported in search results, Phoronix FEX-2512 coverage — exact URL not captured, **unverified**); FEX 2512 shipped Dec 2025 with ongoing improvements ([Phoronix via search](https://www.phoronix.com/search/FEX) — index link, **unverified**); Hangover dropped QEMU for FEX/Box64 backends and has a Termux/Android port (search-reported, **unverified**); Wine's Android driver was revived in Wine 11.6 (search-reported, **unverified**). DXVK on Adreno 619 still requires a Turnip ICD stack we do not have (ARCHITECTURE Q8 stands).
**Recommendation:** keep Box64; add per-app DYNAREC profiles (row 46); leave DXVK gated behind a dedicated future phase with phone GPU measurements; 16 KB alignment already done everywhere (row 87). R8/Baseline-Profile/native flags are the build-level wins (rows 82-83).

### B.8 Startup: tap → usable desktop

**Our state:** connect 0.944 s visible / 1.139 s first frame (STATE.md T13-8); prewarm starts 3 s after desktop (D21) so it doesn't compete; code-server fetch is on-demand.
**Best practice 2025:** Baseline Profiles (~25-50% cold-start gains in case studies: [Duolingo](https://blog.duolingo.com/slashed-android-startup-time-baseline-profiles), [Meta](https://engineering.fb.com/2025/10/01/android/accelerating-our-android-apps-with-baseline-profiles), [Reddit](https://www.reddit.com/r/androiddev/comments/1hd6coy/reddit_improved_app_startup_speed_by_over_50), [overview](https://developer.android.com/topic/performance/baselineprofiles/overview)) + R8 full mode + macrobenchmark `StartupMode.COLD` gates ([2026 practice writeup](https://mvpfactory.io/blog/android-app-startup-profiling-in-2026-baseline-profiles-r8-full-mode-and-the)).
**Recommendation:** add macrobenchmark + baseline profiles; parallelize TLS handshake and display/encoder creation where dependency-safe; measure with the existing gate recipe. Est. connect 0.94→0.75-0.85 s (est.).

### B.9 Security/pairing

**Our state:** TLS 1.3 both directions pinned; pairing = deterministic 6-digit SAS of both cert fingerprints (Kotlin/Rust golden-vector locked) + user Yes; re-pair flow (B-126); forget; NDJSON trust stores.
**2025-26 practice:** this is the BLE "numeric comparison" class; consumer device pairing has moved to SPAKE2+ (Matter PASE, [analysis paper](https://eprint.iacr.org/2025/1268), RFC 9383) or QR/NFC onboarding (Matter 1.4.1 NFC; Wi-Fi Easy Connect/DPP binds credentials cryptographically via QR — [Wi-Fi Alliance DPP](https://www.wi-fi.org/discover-wi-fi/wi-fi-easy-connect), official page, not fetched this session).
**Recommendation (minimal upgrade, keep the UX):** (1) rate-limit pairing prompts (row 9); (2) show the code in-app only + `VISIBILITY_PRIVATE` (manager decision vs B-125); (3) v1.x upgrade path: QR shown by the *phone*, scanned/displayed by the PC GUI (we control both ends; receiver GUI already exists) — removes digit-misread risk and the MITM-replay window without SPAKE2 complexity; (4) move the phone key into AndroidKeyStore (row 10). Full SPAKE2 is not warranted for a LAN/USB peer-to-peer product where the Yes button is the trust anchor.

### B.10 Android 33-36 APIs we should use (or already do)

| API | Status in repo | Action |
|---|---|---|
| 16 KB page sizes | ✅ done, verified (T13-6) | keep CI check |
| Edge-to-edge enforcement (35) | ✅ HomeActivity does manual insets (:101-115); opt-out attr not used | none — compliant; opt-out dies in 36 anyway ([behavior changes 16](https://developer.android.com/about/versions/16/behavior-changes-16)) |
| Predictive back | ✅ `OnBackInvokedDispatcher` on 33+ (HomeActivity.kt:89-97) + legacy fallback | none |
| FGS types + `onTimeout` | ❗ fallback dataSync lacks timeout handling | row 8 |
| WebView UA reduction (Android 16) | ❗ `Phase14BrowserPolicy` WebView fallback spoofs a Chrome UA ([UA reduction blog](https://android-developers.googleblog.com/2024/12/user-agent-reduction-on-android-webview.html), [Privacy Sandbox](https://privacysandbox.google.com/blog/user-agent-reduction-webview-update)) | rework fallback UA: use the real reduced UA + `GeckoView` desktop UA already covers the main path |
| `MediaProjection` FGS type (34) | ✅ added before `getMediaProjection` (:517-540) | none |
| Partial MediaProjection / screen-recording detection | n/a | none |
| Photo picker | n/a | none |
| Baseline profiles / macrobenchmark | ❗ absent | row 83 |
| `org.json`/AndroidX style APIs | partially (androidx.media used despite "no AndroidX" comment) | row 34 / docs fix |

---

## C. Distribution the right way

1. **Format:** Android App Bundle (mandatory for new apps), **Play App Signing** enrolled at first upload (upload key RSA ≥2048; Google keeps the signing key) — [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756), [sign your app](https://developer.android.com/studio/publish/app-signing). The release keystore currently comes from gitignored `android/keystore.properties` (verified) — that becomes the *upload* key; never commit it (already the rule).
2. **Size:** arm64 split ≈55 MB debug (LESSONS Phase 12) — comfortably under the 150 MB base limit; Play Asset Delivery not needed ([bundle rules](https://twinr.dev/blogs/google-play-bundle-size-regulation)). Keep ABI splits; consider dropping the universal APK from release tracks (dev-only).
3. **Target/compat:** targetSdk 36 already set (deadline Aug 31 2026 for new/updates) — [Play target API](https://support.google.com/googleplay/android-developer/answer/11926878). minSdk 29 excludes pre-Android-10 devices deliberately (virtual-display rules).
4. **Permissions & data safety:** declare: notifications, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_DATA_SYNC` (if the fallback stays), `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `RECORD_AUDIO` (genuinely required by `AudioPlaybackCapture` — Phase11Audio.kt:20), clipboard transfer to the user's **own paired PC** (declare as data shared off-device to user-controlled destination; no third-party collection), no device IDs, no ads/analytics. [Data safety form](https://support.google.com/googleplay/android-developer/answer/10787469).
5. **Rollout:** internal → **closed** (if the owner's account was created after Nov 13 2023: **12 testers × 14 consecutive days** required before production) → production staged rollout with halt capability — [staged rollout](https://support.google.com/googleplay/android-developer/answer/6346149), [testing requirement](https://support.google.com/googleplay/android-developer/answer/14151465).
6. **In-app install pipeline (Wine/deb/winget):** keep as-is — the mechanics are right (pinned URL + SHA-256 + manifest-commit pinning + verify-before-run, Installers.kt:141-160). The Play-policy framing of *what* gets downloaded is the sensitive part (see §F rows 1-2), not the mechanics.
7. **Receiver distribution:** not Play-governed; ship from the site/GitHub with signed releases + the GUI already built (gui.rs). Add an auto-update check later (signed manifest, not self-replacing binary — keeps user trust and simplicity).

---

## D. Performance plan tied to our measured targets

Measured bases (all `phone 2c0f6edc`, from OPTIMIZATION.md §5 / STATE.md): connect 0.944 s; typing stage budget 10.9 / 2.3 / 9.8 / **41.8** / 6.3 / 4.2 ms (USB, Test shell, n=74); Wi-Fi typing p50 107.1 / p90 119.6 (n=100, margin 0.4 ms); moving-window fps 42.0; NPP 10.45 s (9.87 s is NPP itself under Wine/box64); VS Code cold 13.48 s. Session spread is 8-20 ms — every gate needs n≥74 medians across ≥3 sessions (OPTIMIZATION §5.3 rule).

| Rank | Change (audit rows) | Target it serves | Expected (est.) | Effort | Risk |
|---|---|---|---|---|---|
| 1 | Gate per-frame `P5PROBE` log (15) + per-keystroke `say` (2) + per-click P7Log (28/29) | USB typing 81.7→≤80; fps | p50 −2–5 ms (three logging sources removed from the input/draw path) | S | low |
| 2 | X11 buffer reuse + `copyPixelsFromBuffer` (36 short-term) | typing in X11 apps; CPU | dispatch→drawn −2–4 ms; big GC/CPU drop | S | low |
| 3 | Choreographer pacing (14) + GL state hoist (17) | typing; fps smoothness | −1–3 ms pacing jitter | M | low |
| 4 | `KEY_INTRA_REFRESH` probe behind degrade ladder (18) | typing spikes on keyframes | −2–5 ms on keyframe moments | M | medium |
| 5 | Adaptive bitrate from `vq` (25) | **Wi-Fi p90 119.6→≤115**; fps stability | p90 −5–10 ms; kills transport-stall outliers | M | medium |
| 6 | XDamage rect fetching (36 long-term) | X11 typing floor; CPU | dispatch→drawn est. −3–6 ms | L | medium |
| 7 | Box64 per-app DYNAREC profiles (46) | **NPP 10.45→≤10** | NPP guest time −0.5–1.5 s | M | medium |
| 8 | Fix B-124 race + prewarm default ON (47) | NPP | −0.9 s (measured prewarm win: 9.48 vs 10.45) | M | medium |
| 9 | Baseline profiles + macrobenchmark (83) | connect; general cold start | connect 0.94→0.75-0.85 (est.); every activity path gains | M | low |
| 10 | R8 full mode (82) | APK size; startup | size −20–40% (est.); startup −10–20% (est.) | M | high |
| 11 | Verifier cache (67) + crypto micro | connect | −1–5 ms | S | low |
| 12 | Governor de-escalation (21) + quality restore | quality after heat | restores 75%→100% automatically | M | low |

Ordering = impact/effort. Items 1-3 are the honest path to the 80 ms USB target (the remaining floor is genuinely the encoder: `drawn>encoded` 41.8 ms with no knob that moved it — OPTIMIZATION §5.3; if after items 1-4 the floor still holds, the manager decision is a LIGHT-by-default-for-Test-shell style profile or accepting 81.7 with a documented miss, as Phase 12 did).

---

## E. Feature shortlist (cloud = none for v1)

| # | Feature | Why it fits | Scope | Play risk |
|---|---|---|---|---|
| 1 | **Finish T15-3 import** (PC exe/msi/URL → desktop) | already 3/5 done; the marquee "install anything" promise | finish proofs | framed like GameHub's policy-compliant import (see F) — must keep ratings + "not for piracy" positioning |
| 2 | **Local backup & restore** ("cloud widget" v1 = no backend): settings + app list + desktop layout + installed-registry export/import as a file; auto-sync between phone↔PC over the existing encrypted link | offline-first (owner decision); zero new permissions; the PC link is already a trusted channel | S-M | none |
| 3 | **Catalog ratings that learn**: record open-success/duration per installed app on this device; show "tested on your phone" instead of static ratings | turns App Center from a list into a trusted advisor | M | none |
| 4 | **Multi-monitor receiver** (`--x/y/w/h` exists; lock covers every monitor; video is single-rect today) | real desktop users have 2 screens | M (PC side) | none |
| 5 | **Wi-Fi↔USB live handoff** (session survives link-type switch) | plug/unplug without losing the desktop | M | none |
| 6 | **PC-trackpad gestures** (two-finger scroll already; add pinch-zoom → desktop scale, three-finger swipe → task switch) | laptop-native UX | M | none |
| 7 | **Screenshot/share from PC** (PrintWindow already proven in tests) | support + user delight | S | none |
| 8 | **Controller mode** | already planned in PLAN.md as post-launch — keep deferred, reuse pairing/session | L | moderate (needs its own policy review: accessibility-adjacent claims) |
| 9 | **3D/DXVK+Turnip evaluation phase** | unlocks games | L, gated on GPU probes (Q8) | none |
| 10 | **Cloud relay (connect from anywhere)** | explicitly deferred — RustDesk-style broker; privacy/security burden | XL | high (abuse surface, data-safety, infrastructure) — do not put in v1 |

---

## F. Play Store compliance checklist

| # | Policy area | Status | What must be done |
|---|---|---|---|
| 1 | **Device and Network Abuse — downloaded executable code** | ⚠️ defensible, must be documented | The policy bans downloading dex/JAR/.so for execution; the **interpreter/VM exception** covers code run "in a virtual machine or interpreter with indirect access to Android APIs" ([policy](https://support.google.com/googleplay/android-developer/answer/16559646), [Developer Program Policy](https://support.google.com/googleplay/android-developer/answer/18265346)). Our model: only APK-shipped `lib*.so` executes (libbox64/libnode); downloaded Wine/Xvfb/apps are **data** translated by Box64; downloaded JS runs on shipped Node. This is the strongest possible reading of the exception, but it is unusual — write a one-page policy memo (DECISIONS entry) citing the exception text, and be ready to answer review questions. Precedent: interpreter apps (Lua/Python/JS OTA) are allowed; the 2025 policy update that threatened emulators was narrowed in practice ([Time Extension coverage](https://www.timeextension.com/news/2025/08/google-could-be-killing-android-emulation-with-its-new-policy-update)) |
| 2 | **Emulation precedent** | ✅ viable | GameHub (Windows-emulator + Steam) shipped on Play Nov 2025 **with arbitrary-PC-game import blocked by policy** ([Android Authority](https://www.androidauthority.com/gamehub-windows-emulator-play-store-3614337), [Gizmochina](https://www.gizmochina.com/2025/11/12/windows-pc-emulation-for-android-is-now-official-with-a-catch), [r/EmulationOnAndroid](https://www.reddit.com/r/EmulationOnAndroid/comments/1otclxr/gamehub_is_now_available_on_google_play)). Our Import feature (any exe) is broader than what GameHub was allowed — expect scrutiny: keep compatibility ratings, age rating, and anti-piracy framing in the listing; consider gating the Import UI with a clear "install software you own" notice. Winlator itself is **not** on Play ([winlator.org](https://winlator.org), [GitHub](https://github.com/brunodev85/winlator)) — we must be cleaner than the sideload world: no links to pirated content, no bundled installers |
| 3 | **App virtualization / cloning** | ✅ precedent | Parallel Space family is live on Play ([listing](https://play.google.com/store/apps/details?id=com.lbe.parallel.intl)); app-level virtualization frameworks are academically studied as a Play-populated category ([analysis](https://www.researchgate.net/publication/341733269)). Disclose "runs apps in a private workspace" in the listing; never claim to be the cloned app (no Chrome branding — D19 already handles this) |
| 4 | **FGS policy** | ⚠️ | `connectedDevice` genuinely matches (a PC accessory session); `dataSync` fallback needs the 6-h `onTimeout` story (row 8); `mediaProjection` requires user consent + the type (done, :517-540). Declare types in the manifest + listing ([FGS changes](https://developer.android.com/develop/background-work/services/fgs/changes)) |
| 5 | **Permissions / data safety** | ⚠️ todo | RECORD_AUDIO (required by AudioPlaybackCapture — verify once on device that capture works with only the projection grant; if RECORD_AUDIO proves unnecessary, drop it), clipboard-to-own-PC declaration, privacy policy URL, no device IDs, no analytics ([data safety](https://support.google.com/googleplay/android-developer/answer/10787469)) |
| 6 | **Security — the audit's P1** | ❗ | exported HarnessActivity (row 90) must be fixed before any Play upload; also the FORGET/session-cut gap and audio-sink race (rows 11, 63) — small diffs, big trust signal |
| 7 | **16 KB / targetSdk / edge-to-edge / predictive back** | ✅ | all done and verified (rows 87, B.10) |
| 8 | **Deceptive behavior / impersonation** | ✅ mostly | no Chrome branding (D19); "PopToPC" naming pending Phase 14 rename; keep the neutral start page (already decided) ([Developer Policy Center](https://play.google/developer-content-policy)) |
| 9 | **Testing + rollout** | ⚠️ todo | 12-tester/14-day closed test for new personal accounts; staged rollout ([testing requirement](https://support.google.com/googleplay/android-developer/answer/14151465), [staged rollout](https://support.google.com/googleplay/android-developer/answer/6346149)) |
| 10 | **Ecosystem context** | info | Google blocked 1.75M violating apps + 80k accounts in 2025 — review quality expectations are rising ([Google security blog](https://blog.google/security/keeping-google-play-android-app-ecosystem-safe-2025)); Epic v. Google injunction changes are reshaping Play policies ([policy update page](https://support.google.com/googleplay/android-developer/answer/15582165)) |
| 11 | **2026 developer-verification** | watch | Sep 2026 verified-developer requirements will also affect sideloading ecosystems; irrelevant to a Play listing but relevant to the receiver's distribution channel (search-reported, [r/androiddev](https://www.reddit.com/r/androiddev/comments/1n0gj1l/) — **unverified details**) |

---

## G. Plain-language summary (for the owner)

**What is genuinely good:** the product's hardest parts are done and proven on your phone — the PC lock with its four ways to always exit, the Play-safe "downloaded programs run as data" model, the encrypted pairing, and the measured culture (every change has before/after numbers). The research confirms two big architectural choices are still the *right* choices in 2026: showing Android apps through the Presentation trick (Google still blocks every alternative), and keeping Box64 (it currently beats FEXCore on modest phone chips).

**What is holding the last two targets:** three small habits — the app writes a log line to a file on *every keystroke and every drawn frame*, and the Windows-app path re-downloads the whole screen picture over a local socket even when nothing changed. Removing those is a few days of work and should get typing from 81.7 to under 80 ms and noticeably lighten the phone. The Wi-Fi p90 number (119.6 vs 120 target) is one transport stall away from failing: the missing piece is an honest adaptive-bitrate loop the design docs already promise but the code never got.

**The one thing I would not ship to Play without fixing:** a leftover test screen ("HarnessActivity") is reachable by other apps in a release build, and through it a malicious app could run code inside PopToPC. It is a one-line manifest fix plus a small guard. There are three smaller trust/robustness fixes in the same family (Forget should cut the live connection; audio shouldn't die on reconnect; pairing prompts should rate-limit).

**Play risk, honestly:** nothing in the design is forbidden, but our category (running Windows programs) is rare on Play — the one comparable app (GameHub) was allowed only after removing "install anything" features. Expect questions from Google review about downloading programs; the answer ("programs are data for the translator we ship, like a Python interpreter") is defensible and written down in the checklist. Budget one review cycle for it.

**Features worth building next (no backend):** finish the Import feature, add one-tap backup/restore of your desktop setup as a file (and sync it over the existing encrypted PC cable/Wi-Fi), make the App Center learn what actually runs on each phone, add second-monitor support on the PC, and let a session survive switching between cable and Wi-Fi.

**Proposed BUGS.md rows (manager triage):** B-138 exported HarnessActivity RCE surface (blocker for Play) · B-139b per-click/per-keystroke/per-frame logging on hot paths · B-140b X11 full-root GetImage + per-frame allocations · B-141b dataSync FGS 6-h timeout unhandled · B-142 FORGET doesn't cut live session · B-143 audio sink unbind race on reconnect · B-144 pairing prompt unthrottled + public visibility (revisit B-125 decision) · B-145 cam.rs MF activate leak · B-146 orphan `.part` on superseded transfer · B-147 right/middle click missing in X11 windows · B-148 governor never de-escalates. (Existing already-covered: B-057/B-104 black-screen class, B-124 prewarm race, B-109/B-110/B-111/B-112/B-113, B-008.)

---

## Sources (all external claims)

- [Device and Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/16559646) · [Developer Program Policy](https://support.google.com/googleplay/android-developer/answer/18265346) · [Developer Policy Center](https://play.google/developer-content-policy)
- [16 KB page sizes](https://developer.android.com/guide/practices/page-sizes) · [Play 16 KB blog](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html)
- [Behavior changes 15](https://developer.android.com/about/versions/15/behavior-changes-15) · [FGS timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) · [FGS changes](https://developer.android.com/develop/background-work/services/fgs/changes) · [Behavior changes 16](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Play target API levels](https://support.google.com/googleplay/android-developer/answer/11926878)
- [AOSP low-latency media](https://source.android.com/docs/core/media/low-latency-media) · [MediaCodec source](https://android.googlesource.com/platform/frameworks/base.git/+/master/media/java/android/media/MediaCodec.java) · [QMediaExtensions](https://docs.qualcomm.com/doc/80-56386-10/topic/classqti_1_1video_1_1QMediaExtensions.html) · [Qualcomm vendor-extensions blog](https://www.qualcomm.com/developer/blog/2023/07/building-media-rich-android-apps-mediacodec-and-vendor-extensions) · [Videon low-latency config](https://support.videonlabs.com/hc/en-us/articles/31279433435923-Low-Latency-Configuration-Best-Practices) · [LiveVideo10ms](https://github.com/Consti10/LiveVideo10ms) · [Sunshine config docs](https://docs.lizardbyte.dev/projects/sunshine/master/md_docs_2configuration.html) · [Sunshine advanced](https://docs.lizardbyte.dev/projects/sunshine/v0.23.1/about/advanced_usage.html) · [Sunshine intra-refresh #3323](https://github.com/LizardByte/Sunshine/issues/3323) · [Moonlight optimization](https://joltfly.com/optimize-moonlight-game-streaming-for-ultra-low-latency) · [r/MoonlightStreaming guide](https://www.reddit.com/r/MoonlightStreaming/comments/1d2ihd8/moonlightsunshine_optimisation_and_common_issues)
- [arXiv QUIC study 2504.10054](https://arxiv.org/html/2504.10054v2) · [arXiv QUIC vs WebRTC 2505.22132](https://arxiv.org/html/2505.22132v1) · [HAProxy TCP/UDP/QUIC](https://www.haproxy.com/blog/choosing-the-right-transport-protocol-tcp-vs-udp-vs-quic) · [CACM on QUIC](https://cacm.acm.org/research/taking-a-long-look-at-quic) · [AWS DCV over QUIC](https://aws.amazon.com/blogs/gametech/stream-a-remote-qa-or-press-environment-with-nice-dcv-over-quic-udp-for-a-4k-monitor-at-60-fps) · [quinn](https://github.com/quinn-rs/quinn) · [quinn docs](https://docs.rs/quinn) · [MoQ context (Fraunhofer)](https://publica.fraunhofer.de/bitstreams/e876619e-d65d-4acc-a76b-e44207eb2f54/download) · [DiVA QUIC streams thesis](https://www.diva-portal.org/smash/record.jsf?pid=diva2:2063794)
- [VirtualDisplay reference](https://developer.android.com/reference/android/hardware/display/VirtualDisplay) · [SurfaceControlViewHost source](https://android.googlesource.com/platform/frameworks/base.git/+/refs/heads/main/core/java/android/view/SurfaceControlViewHost.java) · [Activity embedding](https://developer.android.com/develop/ui/views/layout/activity-embedding) · [Embedding blog](https://medium.com/androiddevelopers/leverage-multi-window-and-activity-embedding-4951f44a8992)
- [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview) · [Duolingo −30%](https://blog.duolingo.com/slashed-android-startup-time-baseline-profiles) · [Meta 2025](https://engineering.fb.com/2025/10/01/android/accelerating-our-android-apps-with-baseline-profiles) · [Reddit >50%](https://www.reddit.com/r/androiddev/comments/1hd6coy/reddit_improved_app_startup_speed_by_over_50) · [2026 startup deep-dive](https://mvpfactory.io/blog/android-app-startup-profiling-in-2026-baseline-profiles-r8-full-mode-and-the)
- [Matter security analysis (SPAKE2+/PASE)](https://eprint.iacr.org/2025/1268) · [Wi-Fi Easy Connect (DPP)](https://www.wi-fi.org/discover-wi-fi/wi-fi-easy-connect)
- [Android Authority: GameHub on Play](https://www.androidauthority.com/gamehub-windows-emulator-play-store-3614337) · [Gizmochina](https://www.gizmochina.com/2025/11/12/windows-pc-emulation-for-android-is-now-official-with-a-catch) · [r/EmulationOnAndroid](https://www.reddit.com/r/EmulationOnAndroid/comments/1otclxr/gamehub_is_now_available_on_google_play) · [Time Extension on the 2025 policy](https://www.timeextension.com/news/2025/08/google-could-be-killing-android-emulation-with-its-new-policy-update) · [Winlator site](https://winlator.org) · [Winlator GitHub](https://github.com/brunodev85/winlator) · [Parallel Space listing](https://play.google.com/store/apps/details?id=com.lbe.parallel.intl) · [Virtualization frameworks analysis](https://www.researchgate.net/publication/341733269) · [FEX-Emu](https://github.com/FEX-Emu/FEX)
- [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756) · [Sign your app](https://developer.android.com/studio/publish/app-signing) · [Bundle size rules](https://twinr.dev/blogs/google-play-bundle-size-regulation) · [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469) · [Staged rollout](https://support.google.com/googleplay/android-developer/answer/6346149) · [Closed-testing requirement](https://support.google.com/googleplay/android-developer/answer/14151465)
- [WebView UA reduction blog](https://android-developers.googleblog.com/2024/12/user-agent-reduction-on-android-webview.html) · [Privacy Sandbox UA](https://privacysandbox.google.com/blog/user-agent-reduction-webview-update) · [GeckoView](https://mozilla.github.io/geckoview) · [WebView memory](https://developer.android.com/develop/ui/views/layout/webapps/manage-webview-memory) · [GeckoView vs WebView load tests](https://www.reddit.com/r/firefox/comments/98babz/good_news_on_android_performance_front)
- [Google 2025 enforcement blog](https://blog.google/security/keeping-google-play-android-app-ecosystem-safe-2025) · [Policy update page (Epic injunction)](https://support.google.com/googleplay/android-developer/answer/15582165) · [winget manifests](https://learn.microsoft.com/windows/package-manager/winget/) · [Android input docs](https://source.android.com/docs/core/interactive/input)

*Unverified items (flagged where used): the Jan-2026 Box64-vs-FEX low-end benchmark numbers, Hangover-termux status, Wine 11.6 Android driver revival, Sep-2026 developer-verification details — found in web-search summaries without directly captured article URLs; verify before citing outside this document.*
