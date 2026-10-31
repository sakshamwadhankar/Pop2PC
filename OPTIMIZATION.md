# OPTIMIZATION: fast on a cheap phone, light on an old PC

**Rule zero: measure first.** Every optimization needs a before and an after number from a
real run on the phone. Changing performance settings without numbers has had to be undone
before (LESSONS.md).

## 1. The optimized design we use

| Idea | What it means | Why it is fast |
|---|---|---|
| **Two speeds** | The most-used tools (VS Code engine, terminal, Python, Git, browser) ship inside our app as native ARM code. Everything else (any `.exe`, x86 Linux apps) goes through the translator. | Native code runs at 100% phone speed. Only the long tail pays for translation. |
| **No VM** | Apps start directly, as normal processes and windows. | No guest OS to boot, no memory reserved for a guest, no extra layer on every memory access. |
| **Zero-copy video** | Window surfaces -> GPU compositor -> encoder input surface -> hardware encoder. | The CPU never touches pixels. |
| **Encode only on change** | No change, no frame (plus a keep-alive). | An idle desktop costs almost nothing. |
| **Input first** | Input has its own highest-priority channel and triggers an immediate frame. | Lowest typing latency. |
| **USB first** | Cable in: use USB. | Lower and steadier delay than Wi-Fi, and it charges the phone. |
| **Right-sized windows** | One virtual display per Android window, sized to the window; hidden windows do not render. | Fewer pixels to draw and encode. |
| **Best version per app** | App Center prefers full-speed, then Android, then web, then translated versions. | Users get the fastest working version automatically. |

## 2. Quality profiles (implemented and tuned in Phase 12)

| Profile | When | Desktop | Video |
|---|---|---|---|
| **Light** | software encoder, weak phone, thermal status >= 3, or Wi-Fi that is not 5 GHz | 1280x720, up to 30 fps | H.264, 6 Mbps |
| **Standard** | mid phone, USB or good 5 GHz Wi-Fi, thermal status 2 | 1920x1080, up to 30 fps | H.264, 14 Mbps |
| **Smooth** | strong phone on USB, thermal status <= 1 | 1920x1080, up to 60 fps | H.264, 24 Mbps |

`Phase12Profiles.choose()` is a pure function with JVM tests (`Phase12ProfilesTest`): a
software encoder, a non-strong phone or thermal status 3+ picks Light; thermal status 2 picks
Standard; a non-USB, non-5 GHz link picks Light; USB on a strong phone picks Smooth. The
session still starts from the existing 1080p/60 defaults and the governor's real
`LowerResolution` step rebuilds it at 960x540 when the thermal policy asks; applying a profile
at session start is follow-up work (the function is ready and tested).

Measured basis (phone rows are labelled; the rest are `emulator`, `proxy` or `PC-only`):

- 1080p hardware stream on the 2-core proxy: **35.65 fps average / 45.9 max**, `dropped=0`,
  decode **21.29 ms**, CPU **34.9 % of two cores**, working set **131 MB** (`p12/a4-hw-after.log`).
- Real 1920x1080 -> 960x540 governor rebuild on the emulator: desktop and the Settings
  window stay, input stays in the 1920x1080 link space, PC `dropped=0`
  (`p12/view-lowres.log`, `p12/lowres-after.png`).
- Idle 1080p receiver: **0.38 % of one core, 127 MB, 0 dropped** over 60 s
  (`p12/a1-after.log`).
- Software H.264 fallback after the B-104 fix: **36.68 fps average / 40.9 max**, 1070/1070
  frames presented, `dropped=0`, decode **16.75 ms**, CPU **33.3 % of two cores**, working set
  **~96 MB** (`p12/a4-sw-after3.log`). The fix allocates the output sample the software MFT
  asks for; the hardware path is unchanged.
- **Phone `2c0f6edc`, final app/receiver:** connect 0.944 s visible; typing p50 90.8 ms on the
  USB adb path (baseline 196.3 ms) and 91.2 ms on the USB-tether dev path (baseline 129.7 ms);
  round 2 (final build): Browser **65.7**, Notepad++ **66.6**, VS Code **75.5**, Test shell
  **87.0** ms (§5.1).
  drag 42.0 fps average / 55.9 max, `dropped=0` at the governor's 960x540; idle baseline
  0.62 % of one core / 123.9 MB / 0 dropped. The governor dropped to 960x540 automatically at
  thermal status 3 in 0.2 s with the desktop and windows kept; the Settings text stayed stale
  (B-105). Notepad++ 15.95 s baseline -> 11.95 s with `p12warm:wine` (28.23 s cold without it).
  Wi-Fi was OFF by owner request, so no Wi-Fi latency number exists (SKIPS.md).

## 3. Phone side

- **Translator:** dynamic recompilation on, wrapped native libraries wherever possible,
  per-app settings profiles, FEXCore where Box64 is weak.
- **3D in Windows apps** (later): DirectX -> Vulkan through DXVK/VKD3D, on Turnip for Adreno.
- **Memory:** a budget for the desktop, so the user's own phone apps keep working. Stop
  rendering hidden windows, pause background desktop apps when memory runs low.
- **Heat and battery:** read the thermal headroom; step fps and resolution down before the
  phone throttles. On USB the phone charges.
- **Startup:** reconnecting resumes the running session (no cold start), aiming at <= 5 s.

## 4. PC side (old PCs)

- **Hardware H.264 decode** (Windows' built-in decoder, GPU-accelerated on almost every PC
  since about 2010). Software decode at 720p as a fallback.
- **Present immediately:** no extra frame buffering; a low-latency swap chain.
- A small native program: little RAM, no browser engine, no runtime to install.
- The receiver stays responsive even if the phone stalls (the Exit button always works).

## 5. Link

- **USB:** AOA bulk transfer, or tethering as fallback. High bitrate ceiling.
- **Wi-Fi:** 5 GHz recommended; adaptive bitrate; keyframe on loss; input never queued
  behind video.
- Separate channels so a big video frame never delays a key press.
- **Input wakes the pipeline, not the next frame.** The input path must never wait for a
  video frame before it is flushed: on a still desktop the encoder posts one keep-alive
  frame per second, and a key must not inherit that delay.
- **No timed reads on the TLS socket.** A reader thread does fully blocking socket reads
  into a small channel and the TLS loop waits on the channel (5 ms when it has input to
  flush). A timed-out blocking `recv` on Windows leaves the connection in an indeterminate
  state (B-067, B-072); the socket itself never sees a read timeout.

### 5.1 What the input fix measured (Phase 12 round 2, phone)

- The old receiver only flushed input when a frame arrived. Browser on a still desktop:
  **p50 220.1 ms, only 8 of 25 keys reported**, against 65.3 ms with an animated window on
  the same link. VS Code 81.6 ms, test shell 85.6 ms.
- After the wake tick + urgent frames: Browser **70.6 ms** (n=25), VS Code **75.5 ms**,
  test shell **83.8 ms** (the harness animates at 60 fps, so its encode+present stage is
  37-44 ms; the real apps pass).
- **A picture that did not change must not become a video frame.** The X11 window used to
  push the Xvfb root into the shell every 120 ms whether or not anything had changed. Each
  push became a frame in the encoder, so a key flash from Notepad++ queued behind one of
  them: `encoded>pc_rx` 35 ms and p50 105-109 ms, while a window with no X11 frame behind it
  reached the PC in 5 ms. The loop now compares the raw GetImage bytes and posts only a
  changed picture; after a key it polls every 8 ms for 300 ms so the echo does not wait for
  the 120 ms idle tick. Measured on the final build: Notepad++ **66.6 ms** (was 104.8-108.8),
  Browser with an X11 window behind **67.5 ms** (was 108.1), both n=25, `encoded>pc_rx` 5.0
  and 5.6 ms. A fresh app and receiver also cleared the static Browser's earlier bimodal
  half (76.5 -> **65.7 ms**, p90 109.7 -> 74.5): `drawn>encoded` fell 44.4 -> 36.0 ms, so
  that half was app-process/encoder state, not the network.
- Final budget (n=25 each, final APK): Browser still 12.7 / 2.5 / 6.5 / 36.0 / 4.9 / 2.0;
  Notepad++ 12.2 / 1.5 / 6.7 / 38.0 / 5.0 / 2.1; Browser with X11 behind 12.2 / 1.2 / 7.2 /
  36.7 / 5.6 / 1.9 (PC->phone / receive->dispatch / dispatch->drawn / drawn->encoded /
  encoded->PC / decode-present). The dominant stage is the phone's canvas-to-encoder.
- The one remaining typing miss is the Test shell: p50 **81.7 ms** on the shipped 12 Mbps
  build (n=74, thermal 0, 2026-11-01), 1.7 ms over the 80 ms target. Its permanent 60 fps
  animation is gone (see 5.3), so what is left is the encoder stage itself: `drawn>encoded`
  median **41.8 ms** is 51 % of the whole budget, and no encoder knob tested so far moves it.

### 5.3 Round 4 (2026-11-01, phone): encoder knobs, session spread, Wi-Fi gate, NPP start

- **Session-to-session spread was bigger than any knob.** The same 12 Mbps code measured
  p50 101-103 ms (T12-2), 82.9 ms (T12-3 baseline) and 81.7 ms (T12-4 S3) in different
  receiver sessions at thermal 0. One session can never prove a 1-2 ms change; every gate
  number below is a median of a stated n and session.
- **Encoder knob A (operating rate 120)** made it **worse**: 85.9 ms vs 82.9 baseline (n=74
  each). Reverted.
- **Encoder knob B (bitrate 12 -> 8 Mbps)** looked like a win in one session (75.0 ms, n=50),
  but the separate-session control killed it: 8 Mbps S1 87.5 / S2 101.2 (S2 had a transport
  stall, `enc>pc_rx` 27.9 ms) vs 12 Mbps S3 81.7. The real encoder-time effect was only
  ~1.6 ms. **Reverted to 12 Mbps**, and the unit test that still asserted the old literal was
  fixed so `:app:testDebugUnitTest` is green again.
- **Stage budget on the shipped build (USB, Test shell, n=74):** PC->phone 10.9 /
  receive->dispatch 2.3 / dispatch->drawn 9.8 / **drawn->encoded 41.8** / encoded->PC 6.3 /
  PC-rx->presented 4.2 ms. The encoder is the floor; the phone-side canvas work is already
  ~10 ms.
- **5 GHz Wi-Fi typing PASSES:** n=100 (4 runs x 25 keys), **p50 107.1 / p90 119.6 ms** on
  5220 MHz, RSSI -23..-25, thermal 0, desk 1728x1080, no adb forward. `drawn>encoded` rises to
  **60.1 ms** on Wi-Fi because the encoder waits on a slower output drain; `pc>phone` stays
  ~10.7 ms. p90 margin is 0.4 ms, so this row is close to the edge.
- **Notepad++ start is 10.45 s** (n=5, thermal 0, shipped default), 0.45 s over the 10 s
  target. A single open: broadcast->spawn 0.155 s, spawn->window 0.177 s, then **9.87 s of
  Notepad++ under Wine/box64** - the guest, not our plumbing. The NPP prewarm reaches
  **9.48 s** (n=3) but maps a ghost window and leaves a duplicate process when a real open
  races it (BUGS B-124), so it stays default OFF (SKIPS.md row).

### 5.2 Round 3 (2026-10-31, phone): the probe fix and the real Wi-Fi number

- **The typing probe had no draw source in the product path.** In shell mode the only flash
  was the harness chip, armed by hand; with a real window focused `tDrawUs` stayed 0 and no
  `lat` report was ever sent (B-122). `ShellRoot` now reports the shell frame that carries the
  key-down (`flashFrame()` + `dispatchDraw`), so the probe works with no harness step and no
  extra pixels. 1728x1080, Browser, 25 PC keys: 27 `LATSTAGE`, p50 **84.9 / p90 107.1 ms**.
- **5 GHz Wi-Fi typing (first numbers on a real link): 5220 MHz, 11ac, RSSI -58 dBm, thermal 0,
  battery 46 %.** Two runs, 25 keys each through SendInput into the receiver window, no adb
  forward: p50 **84.9** (p90 107.1) and **97.2** (p90 134.2) ms. The target is p50 <= 120:
  passed. Wi-Fi costs about 10-20 ms over the 66-75 ms USB figures.
- **Test window animation:** the Test window self-invalidated at 60 fps forever, which at
  1728x1080 kept the encoder queue full. Removing it took the Test shell from p50 239.0
  (animation on, n=25) to 198.9 (n=14) at 1728x1080 and to **103.1 ms (n=26)** at the
  low-power 1296x810@30 fps encoder - still over the 80 ms target, with `drawn>encoded`
  45-63 ms as the whole gap. At 1080p the phone encodes a 1728x1080 frame too slowly for the
  80 ms budget; the earlier 87.0 ms was recorded at the governor's 960x544, which this phone
  would not drop to on 2026-10-31 (thermal 0).

## 6. How we measure (every phase that touches speed)

| What | How |
|---|---|
| Typing latency | The app flashes the screen on each key; the test presses a key on the PC and times until the receiver window brightens. Also measure each stage separately: input send, app reaction, encode, transport, decode. |
| Frame rate | Frames presented per second on the PC while dragging a window. |
| CPU / RAM / heat | Phone `/proc` and thermal readings during a fixed 5-minute workload. |
| App start | Click to first window drawn. |
| Phone stays usable | WhatsApp and a call during a session: no freezes, no kills (`am_kill`). |
| Survival | 2 hours on charger: CPU time advancing the whole time. |
