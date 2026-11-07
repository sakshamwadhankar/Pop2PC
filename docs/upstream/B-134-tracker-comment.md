# Comment for Google issuetracker 460828007 (same NPE shape at bind)

*Draft prepared for posting as a comment on Google issuetracker 460828007.
Prepared 2026-11-07 by the PopToPC team. Text only — the owner submits.
No credentials, no personal data.*

---

Same NPE reproduced on a OnePlus CPH2619 running ColorOS `CPH2619_16.0.5.1300(EX01)`
(Android 16), with GeckoView 159.0.20261102144711 as the embedder (Firefox-derived
engine, MPL-2.0) — so the affected process model is not Chromium-specific: it is the
generic `:process` child-service bind path.

```
java.lang.NullPointerException: Attempt to read from field
'android.os.Bundle android.content.pm.PackageItemInfo.metaData' on a null object
reference in method 'void android.app.ActivityThread.handleBindApplication(android.app.ActivityThread$AppBindData)'
  at android.app.ActivityThread.handleBindApplication(ActivityThread.java:8702)   <- OEM build
  at android.app.ActivityThread$H.handleMessage(ActivityThread.java:2813)
  at android.os.Looper.loopOnce(Looper.java:302)
  ...
```

Details that may help triage:

- It is deterministic and affects ONLY child processes bound as a service
  (`GeckoChildProcessServices$gpu`, `$tab…`, and a `CrashHelper` service in our case);
  the same app's main process and its Chromium WebView children bind fine.
- Each child dies before any app code runs (the frame is inside the framework's bind
  path), so no manifest or app-side change can guard it; the children then respawn and
  crash in a loop.
- Mozilla's Fenix tracker carries the same shape as issue #27006.
- Raw logcat excerpt (spawn → FATAL → died → respawn; two windows,
  12:30:08–12:31:04 and 13:19:20–13:20:07, 2026-11-07) available on request.

If it helps the Chrome-side fix: on this OEM build the bind path appears to
dereference a null `PackageItemInfo` for `:process` child services on Android 16.
