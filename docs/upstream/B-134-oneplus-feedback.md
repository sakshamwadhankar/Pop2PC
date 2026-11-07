# OnePlus / ColorOS feedback: GeckoView child processes crash-loop at bind — NPE in ActivityThread.handleBindApplication (PackageItemInfo.metaData null)

*Draft prepared for the OnePlus feedback / bug-report channel (ColorOS 16).
Prepared 2026-11-07 by the PopToPC team. Text only — the owner submits.
No credentials, no personal data.*

## Device and build

- Model: OnePlus CPH2619
- OS version: ColorOS `CPH2619_16.0.5.1300(EX01)` (Android 16)
- App: a normal (non-privileged) user-installed app, targetSdk 36

## Summary

Every child process that GeckoView (the Firefox engine used by many Android apps,
version 159.0.20261102144711) spawns dies at process BIND with a NullPointerException
inside the ColorOS framework — before any app code runs:

```
java.lang.NullPointerException: Attempt to read from field
'android.os.Bundle android.content.pm.PackageItemInfo.metaData' on a null object
reference in method 'void android.app.ActivityThread.handleBindApplication(android.app.ActivityThread$AppBindData)'
  at android.app.ActivityThread.handleBindApplication(ActivityThread.java:8702)
  at android.app.ActivityThread.-$$Nest$mhandleBindApplication(ActivityThread.java:0)
  at android.app.ActivityThread$H.handleMessage(ActivityThread.java:2813)
  at android.os.Handler.dispatchMessage(Handler.java:115)
  at android.os.Looper.loopOnce(Looper.java:302)
  at android.os.Looper.loop(Looper.java:412)
  at android.app.ActivityThread.main(ActivityThread.java:9992)
```

The affected children are the multi-process services GeckoView declares
(`:crashhelper…` → `org.mozilla.gecko.crashhelper.CrashHelper`, `:gpu…` →
`GeckoChildProcessServices$gpu`, `:sandboxed_tab…` → `GeckoChildProcessServices$tab…`).
They spawn, crash at bind, are re-spawned, and crash again in a loop (we captured
32 occurrences across two windows in one session). The app's main process and every
Chromium WebView child of the same app bind fine — only GeckoView's `:process`
services crash.

## Why this looks like a ColorOS framework defect

- The crash frame is inside `ActivityThread.handleBindApplication` (line 8702 on this
  build), dereferencing a null `PackageItemInfo` while reading `metaData`. That code
  runs before the child's app code and cannot be reached or guarded by an app.
- The same APK binds its own main process and WebView children without issue.
- The same NPE shape is reported by other OEMs' Android 16 builds (Google issuetracker
  460828007; Mozilla Fenix #27006; HONOR/Xiaomi variants), which suggests a common
  Android 16 vendor-patch regression in the bind path.

## Reproduction

1. On the CPH2619 above, install any app embedding GeckoView 159
   (e.g. any Firefox-derived browser or an app that embeds the engine).
2. Open the GeckoView page.
3. `adb logcat`: each child (`:crashhelper…`, `:gpu…`, `:sandboxed_tab…`) dies at bind
   with the NPE above and is re-spawned in a loop; no page ever renders.

## Impact

Apps that embed GeckoView cannot render any web content on this device and must fall
back to another engine. For Firefox-derived browsers this means the browser cannot run
on the device at all. A fix in the ColorOS bind path (or a whitelisting of GeckoView's
child services) would restore them.

## Notes for triage

- The full logcat excerpt (spawn → FATAL → died → respawn, 12:30:08–12:31:04 and
  13:19:20–13:20:07 device-local time, 2026-11-07) is available on request.
- The OEM framework also logs from the same bind path on the same pids just before the
  crash (`BootstrapClass`, `WorkManagerProxy` warnings at `ActivityThread.java:8614`).
