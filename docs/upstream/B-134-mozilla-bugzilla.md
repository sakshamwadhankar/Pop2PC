# GeckoView child processes crash-loop at bind on OnePlus CPH2619 (ColorOS / Android 16): NPE in ActivityThread.handleBindApplication reading PackageItemInfo.metaData

*Draft prepared for submission to bugzilla.mozilla.org (product: GeckoView).
Prepared 2026-11-07 by the PopToPC team. Text only — the owner submits.
No credentials, no personal data.*

## Environment

- GeckoView: `org.mozilla.geckoview:geckoview-arm64-v8a:159.0.20261102144711`
  (Firefox 159.0 release build, AAR sha256
  `003eff5dba9e53fe5a23ae454a5f9ce50ab631c4ea8aed33824957d8c756e745`).
- Device: OnePlus CPH2619, ColorOS `CPH2619_16.0.5.1300(EX01)`, Android 16 (API 36
  runtime; OEM framework, ActivityThread.java line numbers are OEM-specific).
- Host app: a plain app-context embedder (`GeckoRuntime.create(applicationContext)`),
  targetSdk 36, arm64-v8a only, no extra Gecko settings (consoleOutput/debugLogging off).
- The same app embeds Chromium WebView elsewhere; the WebView children bind fine.

## What happens

Every GeckoView child process dies at BIND, before any Gecko or app code in the child
runs. The stack is entirely inside the OEM's `ActivityThread`:

```
11-07 12:30:09.025 E/AndroidRuntime(12776): FATAL EXCEPTION: main
11-07 12:30:09.025 E/AndroidRuntime(12776): Process: dev.unifieddesktop.app:crashhelper_disable_art_image_, PID: 12776
11-07 12:30:09.025 E/AndroidRuntime(12776): java.lang.NullPointerException: Attempt to read from field 'android.os.Bundle android.content.pm.PackageItemInfo.metaData' on a null object reference in method 'void android.app.ActivityThread.handleBindApplication(android.app.ActivityThread$AppBindData)'
11-07 12:30:09.025 E/AndroidRuntime(12776): 	at android.app.ActivityThread.handleBindApplication(ActivityThread.java:8702)
11-07 12:30:09.025 E/AndroidRuntime(12776): 	at android.app.ActivityThread.-$$Nest$mhandleBindApplication(ActivityThread.java:0)
11-07 12:30:09.025 E/AndroidRuntime(12776): 	at android.app.ActivityThread$H.handleMessage(ActivityThread.java:2813)
11-07 12:30:09.025 E/AndroidRuntime(12776): 	at android.os.Handler.dispatchMessage(Handler.java:115)
11-07 12:30:09.025 E/AndroidRuntime(12776): 	at android.os.Looper.loopOnce(Looper.java:302)
11-07 12:30:09.025 E/AndroidRuntime(12776): 	at android.os.Looper.loop(Looper.java:412)
11-07 12:30:09.025 E/AndroidRuntime(12776): 	at android.app.ActivityThread.main(ActivityThread.java:9992)
11-07 12:30:09.025 E/AndroidRuntime(12776): 	at java.lang.reflect.Method.invoke(Native Method)
11-07 12:30:09.025 E/AndroidRuntime(12776): 	at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:613)
11-07 12:30:09.025 E/AndroidRuntime(12776): 	at android.os.ZygoteInit.main(ZygoteInit.java:1074)
```

The child process names come from GeckoView's own service declarations; all three die
the same way and are re-spawned in a loop:

```
11-07 12:30:08.609 I/ActivityManager: Start proc 12776:dev.unifieddesktop.app:crashhelper_disable_art_image_/u0a480 for service {dev.unifieddesktop.app/org.mozilla.gecko.crashhelper.CrashHelper}
11-07 12:30:09.506 I/ActivityManager: Start proc 12882:dev.unifieddesktop.app:gpu_disable_art_image_/u0a480 for service {dev.unifieddesktop.app/org.mozilla.gecko.process.GeckoChildProcessServices$gpu}
11-07 12:30:10.057 I/ActivityManager: Start proc 12952:dev.unifieddesktop.app:sandboxed_tab_disable_art_image_20/u0a480 for service {dev.unifieddesktop.app/org.mozilla.gecko.process.GeckoChildProcessServices$tab20}
11-07 12:30:09.025 E/AndroidRuntime(12776): FATAL EXCEPTION: main        (crashhelper: NPE above)
11-07 12:30:09.777 E/AndroidRuntime(12882): FATAL EXCEPTION: main        (gpu: same NPE)
11-07 12:30:10.361 E/AndroidRuntime(12952): FATAL EXCEPTION: main        (sandboxed_tab: same NPE)
11-07 12:30:27.380 I/ActivityManager: Process dev.unifieddesktop.app:gpu_disable_art_image_ (pid 12882) has died: fg BTOP
11-07 13:19:38.692 I/ActivityManager: Process dev.unifieddesktop.app:crashhelper_disable_art_image_ (pid 1868) has died: fg BTOP
```

Two windows in our log, 12:30:08–12:31:04 and 13:19:20–13:20:07, each a
spawn → FATAL → died → respawn loop (32 occurrences of the exact NPE line in the
captured `logcat-full.log`). The main process never crashes; only the `:…` children.
Because the children die before the Gecko IPC is up, GeckoView never associates the
crash with a session: `ContentDelegate.onCrash` never fires and the page sits in
"loading" forever. The embedder cannot catch or work around it (it is framework code
running before the child's app classloader does anything).

## Why we believe it is the OEM framework, not the embedder

- The failing frame is `ActivityThread.handleBindApplication(ActivityThread.java:8702)`
  reading `PackageItemInfo.metaData` on a null object reference — i.e. the framework
  dereferences a null `PackageItemInfo` while binding, before any app code runs. No
  manifest change, content-provider declaration, or GeckoView setting can reach that.
- The host app process (same APK, same manifest, same uid) binds fine; only the
  GeckoView child processes crash. The one thing that distinguishes them is that they
  are separate `:process` services bound by GeckoView — and on this ColorOS build the
  bind path for them hits the null `metaData`.
- The same shape is known elsewhere: Google issuetracker 460828007, Mozilla Fenix
  issue #27006, and HONOR/Xiaomi reports in the same family. We are filing here
  because GeckoView children hit it deterministically on this device, which makes the
  case reproducible in one run.
- An OEM framework logger also prints unrelated warnings from the same bind path
  (`BootstrapClass`, `WorkManagerProxy` at `ActivityThread.java:8614`) seconds before
  the crash, on the same child pid — the bind path itself is heavily OEM-patched.

## Reproduction

1. OnePlus CPH2619, ColorOS `CPH2619_16.0.5.1300(EX01)` (Android 16), stock settings.
2. Any app embedding `geckoview-arm64-v8a` 159.0.20261102144711 that opens a
   `GeckoSession` in a `GeckoView` (our code: `GeckoRuntime.create(applicationContext)`,
   `GeckoSession` with `usePrivateMode(false)`, `USER_AGENT_MODE_DESKTOP`).
3. Open the window. Watch `adb logcat`: every child
   (`:crashhelper…`, `:gpu…`, `:sandboxed_tab…`) dies at bind with the NPE above and is
   re-spawned; no page ever paints.

## Consequence for embedders

On affected devices GeckoView is unusable (never paints), so apps must keep a WebView
fallback for OEM-skinned phones. We would welcome any embedder-side mitigation
GeckoView can offer (e.g. skipping the crashhelper service on these builds), but the
fix belongs in the OEM's `ActivityThread` bind path.

## Related

- Google issuetracker 460828007 (same NPE shape at bind).
- Mozilla Fenix #27006.
- Our raw log excerpts: `logcat-full.log` lines around 12:30:08–12:31:04 and
  13:19:20–13:20:07 (device-local timestamps), available on request.
