package io.github.nexalloy.revanced.zalo.calls

import io.github.nexalloy.morphe.findMethodListDirect

/**
 * Lifecycle methods declared on concrete `CallCallback` subclasses.
 *
 * Hooking only `com.vng.zing.vn.zrtc.CallCallback` misses the real callbacks:
 * Zalo's production subclass (e.g. `l92.z1` on 26.08.02) overrides every
 * lifecycle method and does not call super, so Xposed never runs a base-class
 * hook. Discover the overrides by name — names themselves are stable ZRTC
 * symbols — so this works on any Zalo release without pinning class names.
 */
val concreteCallbackLifecycleMethods = findMethodListDirect {
    val base = "com.vng.zing.vn.zrtc.CallCallback"
    val observed = listOf(
        "onIncomingCall",
        "onMakeCall",
        "onPreConnectSuccessful",
        "onCallConfirmed",
        "onCallAudioState",
        "onCallState",
        "onCallErr",
        "onCallEnd",
        "onCallAutoHangup",
    )
    buildList {
        for (name in observed) {
            val matches = findMethod { matcher { this.name = name } }
            for (method in matches) {
                if (method.className != base) add(method)
            }
        }
    }
}
