# Target-device tethering API shapes

These signatures were read from the Infinix X6816's actual Android 11
(API 30) framework by a short-lived, read-only shell-UID-2000 reflection
probe. It inspected classes and methods only: it did not construct a
manager or invoke network controls. The temporary device probe was removed.
A newer compile SDK is not evidence that its class layout exists here.

## Observed runtime signatures

```text
probe_uid=2000
method=public void android.net.TetheringManager.startTethering(int,java.util.concurrent.Executor,android.net.TetheringManager$StartTetheringCallback)
method=public void android.net.TetheringManager.startTethering(android.net.TetheringManager$TetheringRequest,java.util.concurrent.Executor,android.net.TetheringManager$StartTetheringCallback)
request_class=android.net.TetheringManager$TetheringRequest
builder_class=android.net.TetheringManager$TetheringRequest$Builder
constructor=public android.net.TetheringManager$TetheringRequest$Builder(int)
build=public android.net.TetheringManager$TetheringRequest android.net.TetheringManager$TetheringRequest$Builder.build()
method=public void android.net.TetheringManager.stopTethering(int)
method=public void android.net.TetheringManager.stopTethering(android.net.TetheringManager$TetheringRequest,java.util.concurrent.Executor,android.net.TetheringManager$StopTetheringCallback)
```

The request and builder are nested classes. The initial adapter's newer
`android.net.TetheringRequest` assumption produced a real
`ClassNotFoundException` during the first live Start attempt.

## Adapter requirements

- Resolve the request, builder, and callback from the same compatible
  method signature. Never pass a request object to the int overload.
- Verify the builder's int constructor and its build return type.
- Keep all hidden-framework work in the authorized UID-2000 helper.
  The ordinary app uses public Android APIs and typed IPC only.
- A discovered method is not proof of successful control. Observe actual
  hotspot state after a request, and verify client internet separately.
- Missing or incompatible shapes are unavailable, not fabricated support.

## Shizuku startup is a separate compatibility issue

Official Shizuku 13.6.0 fails in this ROM's `LoadedApk.makeApplication`
before the app helper loads. An explicitly approved test with official
13.5.4, matching the installed manager's signing certificate, successfully
starts the helper as UID 2000. No root, unofficial APK, global hidden-API
bypass, or SELinux change was used. See `enhanced-access.md` for scope and
limitations; older-version compatibility is not automatic downgrade policy.
