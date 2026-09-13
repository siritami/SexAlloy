package io.github.nexalloy.revanced.zalo.calls

import io.github.nexalloy.morphe.findClassDirect

/**
 * The concrete `com.vng.zing.vn.zrtc.CallCallback` subclass (obfuscated to
 * `l92.z1` in Zalo 26.08.02, but its NAME is never used here).
 *
 * It is resolved purely by structure: the one app class whose superclass is the
 * non-obfuscated `com.vng.zing.vn.zrtc.CallCallback`. Zalo can rename the class
 * every release and this still resolves, so nothing needs updating on a Zalo
 * update. DexKit caches the result per host build.
 *
 * We hook this class rather than the base `CallCallback` because the subclass
 * overrides every call-lifecycle method (`onCallState`, `onCallAudioState`,
 * `onPreConnectSuccessful`, `onCallAutoHangup`, `onCallErr`, ...), so a hook on
 * the base's declared methods would never fire. The lifecycle method names
 * themselves are stable (they come from the native ZRTC callback contract), so
 * they are matched by name at hook time — see [CallRecordingLifecycle].
 */
val callCallbackSubclassFingerprint = findClassDirect {
    findClass {
        matcher { superClass = "com.vng.zing.vn.zrtc.CallCallback" }
    }.first()
}
