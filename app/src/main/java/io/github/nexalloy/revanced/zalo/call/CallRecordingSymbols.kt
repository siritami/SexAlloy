package io.github.nexalloy.revanced.zalo.calls

/**
 * Stable ZRTC symbols used by auto call recording.
 *
 * Only names that Zalo keeps unobfuscated across releases live here. Obfuscated
 * app-layer symbols (peer manager singleton, concrete CallCallback subclass,
 * in-call activity "controls ready" accessors) are intentionally NOT listed —
 * they change every build. Fallback paths discover what they need at runtime
 * from these anchors:
 *
 *  - Peer metadata / start / stop / callback binding: [PEER_JNI] native methods.
 *  - Callback lifecycle: the non-obfuscated [CALL_CALLBACK] base, plus the
 *    concrete subclass seen in `zrtc_peer_register_callback`.
 *  - Secondary UI trigger: stable Android lifecycle methods on [CALL_ACTIVITIES].
 */
internal object CallRecordingSymbols {
    const val PEER_JNI = "com.vng.zing.vn.zrtc.PeerJNI"
    const val CALL_CALLBACK = "com.vng.zing.vn.zrtc.CallCallback"
    val CALL_ACTIVITIES = arrayOf("zm.voip.ui.incall.ZmInCallActivity")
}
