package io.github.nexalloy.revanced.zalo.calls

import android.app.Notification
import android.content.Context
import app.morphe.extension.shared.Logger
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Auto-record Zalo one-to-one call audio, ported from the Zalo Patch
 * `CallRecordingFeature` — with **zero hardcoded obfuscated symbols**.
 *
 * ## How it captures
 * Hooks the bundled ZRTC native bridge `com.vng.zing.vn.zrtc.PeerJNI` (all method
 * names are stable JNI names) and the concrete `CallCallback` subclass (resolved
 * structurally by its non-obfuscated superclass — see [callCallbackSubclassFingerprint]).
 * When a call's audio streams register it calls the native
 * `zrtc_peer_start_record_audio(peer, true, path)` to capture a WAV into Zalo's
 * cache; on teardown it stops and hands the WAV to [CallRecordingOutput], which
 * repairs the header, transcodes to M4A and writes it to the shared MediaStore
 * (`Recordings/Zalo Call Recordings`).
 *
 * ## Why it survives Zalo updates
 * Everything the recorder needs is either non-obfuscated or resolved by shape:
 *   - PeerJNI methods (`zrtc_peer_*`) — stable native names.
 *   - The callback class — found by `superClass == CallCallback` via DexKit, never
 *     by its obfuscated name.
 *   - Lifecycle method names (`onCallState`, `onCallAudioState`,
 *     `onPreConnectSuccessful`, `onCallAutoHangup`, `onCallErr`) — the native ZRTC
 *     callback contract, stable across builds.
 * The Zalo Patch original also carried an obfuscated peer-manager singleton and
 * in-call-activity field/method for *fallback* start/stop triggers. Those are
 * intentionally dropped: start is driven by the audio-stream registration and
 * stop by the peer-termination natives + callback terminal states, so no
 * per-version symbol table is needed.
 *
 * ## In-process, not cross-process
 * NexAlloy patch code runs inside `com.zing.zalo`, which already holds RECORD_AUDIO
 * and MediaStore access, so the whole pipeline runs in-process. The Zalo Patch
 * module-process pieces (broadcast import protocol, notifier, recordings UI,
 * ConfigProvider) are dropped.
 *
 * ## Legal
 * Call recording often requires the consent of all parties. Default off.
 */
val AutoRecordCalls = patch(
    name = "Auto-record call audio",
    description = "Records Zalo one-to-one voice/video call audio to " +
        "Recordings/Zalo Call Recordings as M4A. Runs the native ZRTC recorder. " +
        "Default off. Recording calls may require the consent of all parties " +
        "where you live — check local law before enabling.",
    use = false,
) {
    CallRecorder.install(this)
}

private object CallRecorder {
    private const val PEER_JNI = "com.vng.zing.vn.zrtc.PeerJNI"
    private const val PARTNER_ID_METHOD = "zrtc_call_config_set_partner_id"
    private const val MAKE_CALL = "zrtc_peer_make_call"
    private const val INCOMING_CALL = "zrtc_peer_incoming_call"
    private const val REGISTER_CALLBACK = "zrtc_peer_register_callback"
    private const val START_RECORD = "zrtc_peer_start_record_audio"
    private const val IS_IN_CALL = "zrtc_peer_is_in_call"

    private val SESSIONS: MutableMap<Any, Session> =
        Collections.synchronizedMap(WeakHashMap())
    private val SESSIONS_BY_PEER = ConcurrentHashMap<Long, Session>()
    private val CONFIG_PARTNERS = ConcurrentHashMap<Long, String>()
    private val PEER_PARTNERS = ConcurrentHashMap<Long, String>()
    private val HOOKED_CALLBACKS: MutableSet<String> =
        Collections.synchronizedSet(HashSet())

    /** Most recently seen live peer handle; the fallback when a callback isn't mapped. */
    @Volatile private var lastPeerHandle: Long = 0L

    @Volatile private var recordMethod: Method? = null
    @Volatile private var appContext: Context? = null

    fun install(executor: PatchExecutor) {
        val classLoader = executor.classLoader
        appContext = executor.appContext

        val peerClass = classLoader.loadClass(PEER_JNI)
        recordMethod = peerClass.getDeclaredMethod(
            START_RECORD, Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, String::class.java
        ).apply { isAccessible = true }
        // Presence check only; not stored (used for staleness assurance).
        peerClass.getDeclaredMethod(IS_IN_CALL, Long::class.javaPrimitiveType)

        var hooks = 0
        hooks += hookPeerMetadata(peerClass)
        hooks += hookAudioStreamRegistration(peerClass)
        hooks += hookPeerTermination(peerClass)
        hooks += hookCallbackRegistration(peerClass)
        hooks += hookCallbackClass(resolveCallbackClass(executor))
        hooks += hookNotifications(classLoader)

        check(hooks > 0) { "No call lifecycle hooks installed" }
        Logger.printInfo { "[Zalo] Auto-record installed $hooks hooks" }

        appContext?.let { CallRecordingOutput.recoverPending(it, null) }
    }

    /** The concrete CallCallback subclass, resolved by structure (never by name). */
    private fun resolveCallbackClass(executor: PatchExecutor): Class<*>? = with(executor) {
        runCatching { ::callCallbackSubclassFingerprint.clazz }.getOrElse {
            Logger.printException({ "[Zalo] CallCallback subclass not resolved by fingerprint" }, it)
            null
        }
    }

    // --- Peer metadata: partner UID + call direction ---------------------------

    private fun hookPeerMetadata(peerClass: Class<*>): Int {
        var count = 0
        count += hookAllByName(peerClass, PARTNER_ID_METHOD) {
            before { param ->
                val args = param.args
                if (args != null && args.size >= 2 && args[0] is Long && args[1] is Int) {
                    val uid = (args[1] as Int).toLong() and 0xffffffffL
                    if (uid > 0L) CONFIG_PARTNERS[args[0] as Long] = uid.toString()
                }
            }
        }
        val bindPeer: HookScope.() -> Unit = {
            before { param ->
                val args = param.args
                if (args != null && args.size >= 2 && args[0] is Long && args[1] is Long) {
                    val peerHandle = args[0] as Long
                    val configHandle = args[1] as Long
                    CONFIG_PARTNERS[configHandle]?.takeIf { it.isNotEmpty() }?.let {
                        PEER_PARTNERS[peerHandle] = it
                    }
                    val session = sessionForPeer(peerHandle)
                    session.direction =
                        if (param.method.name == MAKE_CALL) "outgoing" else "incoming"
                }
            }
        }
        count += hookAllByName(peerClass, MAKE_CALL, bindPeer)
        count += hookAllByName(peerClass, INCOMING_CALL, bindPeer)
        return count
    }

    // --- Audio stream registration -> confirmed + connected -> start -----------

    private fun hookAudioStreamRegistration(peerClass: Class<*>): Int {
        var count = 0
        for (methodName in arrayOf(
            "zrtc_peer_register_in_audio_stream",
            "zrtc_peer_register_out_audio_stream"
        )) {
            count += hookAllByName(peerClass, methodName) {
                after { param ->
                    val args = param.args ?: return@after
                    if (args.isEmpty() || args[0] !is Long) return@after
                    val session = sessionForPeer(args[0] as Long)
                    synchronized(session) {
                        session.confirmed = true
                        session.audioConnected = true
                    }
                    start(session, methodName)
                }
            }
        }
        return count
    }

    // --- Peer termination -> stop ----------------------------------------------

    private fun hookPeerTermination(peerClass: Class<*>): Int {
        var count = 0
        for (methodName in arrayOf(
            "zrtc_peer_end_call", "zrtc_peer_force_stop", "zrtc_peer_delete"
        )) {
            count += hookAllByName(peerClass, methodName) {
                before { param ->
                    val args = param.args ?: return@before
                    if (args.isEmpty() || args[0] !is Long) return@before
                    stopForPeer(args[0] as Long, methodName)
                }
            }
        }
        return count
    }

    // --- Callback registration binds a callback instance to a peer handle ------

    private fun hookCallbackRegistration(peerClass: Class<*>): Int =
        hookAllByName(peerClass, REGISTER_CALLBACK) {
            before { param ->
                val args = param.args ?: return@before
                if (args.size < 2 || args[0] !is Long || args[1] == null) return@before
                val peerHandle = args[0] as Long
                val session = sessionForPeer(peerHandle)
                SESSIONS[args[1]!!] = session
            }
        }

    // --- Callback lifecycle on the resolved subclass ---------------------------

    private fun hookCallbackClass(callbackClass: Class<*>?): Int {
        callbackClass ?: return 0
        var count = 0
        var current: Class<*>? = callbackClass
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                val name = method.name
                if (Modifier.isAbstract(method.modifiers) ||
                    !CallRecordingLifecycle.observes(name)
                ) continue
                val signature = current.name + "#" + method.toGenericString()
                if (!HOOKED_CALLBACKS.add(signature)) continue
                try {
                    method.isAccessible = true
                    hookMember(method, callbackHook(method))
                    count++
                } catch (t: Throwable) {
                    HOOKED_CALLBACKS.remove(signature)
                    Logger.printException({ "[Zalo] callback hook failed: $signature" }, t)
                }
            }
            current = current.superclass
        }
        return count
    }

    private fun callbackHook(method: Method): HookScope.() -> Unit = {
        before { param ->
            val methodName = method.name
            val s = SESSIONS[param.thisObject] ?: currentLiveSession() ?: return@before
            if (s.deleted) return@before
            when (methodName) {
                "onIncomingCall" -> { s.direction = "incoming"; return@before }
                "onMakeCall" -> { s.direction = "outgoing"; return@before }
            }
            val state = firstInt(param.args)
            if (CallRecordingLifecycle.isVideoState(methodName)) return@before
            if (CallRecordingLifecycle.shouldStopAudio(methodName, state)) {
                // ZRTC ignores recordAudio(false, ...) after its controller leaves the
                // confirmed state, so stop synchronously in this before-hook.
                stop(s, methodName)
                return@before
            }
            var shouldStart: Boolean
            synchronized(s) {
                if (CallRecordingLifecycle.confirmsCall(methodName)) s.confirmed = true
                if (CallRecordingLifecycle.connectsAudio(methodName, state)) s.audioConnected = true
                shouldStart = CallRecordingLifecycle.shouldStartAudio(s.confirmed, s.audioConnected)
            }
            if (shouldStart) start(s, methodName)
        }
    }

    // --- Notification observer feeds caller identity ---------------------------

    private fun hookNotifications(classLoader: ClassLoader): Int {
        val nmClass = runCatching {
            classLoader.loadClass("android.app.NotificationManager")
        }.getOrNull() ?: return 0
        return hookAllByName(nmClass, "notify") {
            before { param ->
                param.args?.forEach { arg ->
                    if (arg is Notification) CallRecordingMetadataStore.observe(arg)
                }
            }
        }
    }

    // --- Native start / stop ---------------------------------------------------

    private fun start(session: Session, trigger: String) {
        synchronized(session) {
            if (session.deleted || session.started ||
                !CallRecordingLifecycle.shouldStartAudio(session.confirmed, session.audioConnected)
            ) return
            val app = appContext ?: return
            val native = recordMethod ?: return
            if (session.tempFile == null) {
                session.startedAt = System.currentTimeMillis()
                session.pendingName = CallRecordingOutput.newPendingName(
                    session.startedAt, session.direction
                )
                session.tempFile = File(CallRecordingOutput.tempDirectory(app), session.pendingName)
            }
            try {
                native.invoke(null, session.peerHandle, true, session.tempFile!!.absolutePath)
                session.started = true
                Logger.printInfo { "[Zalo] start record dir=${session.direction} trigger=$trigger" }
            } catch (t: Throwable) {
                session.tempFile?.delete()
                Logger.printException({ "[Zalo] native start failed" }, t)
            }
        }
    }

    private fun stop(session: Session, trigger: String) {
        val tempFile: File?
        val startedAt: Long
        val direction: String
        val peerUid: String
        val observed = CallRecordingMetadataStore.current()
        synchronized(session) {
            if (!session.started) {
                session.confirmed = false
                session.audioConnected = false
                return
            }
            try {
                recordMethod?.invoke(
                    null, session.peerHandle, false, session.tempFile?.absolutePath ?: ""
                )
            } catch (t: Throwable) {
                Logger.printException({ "[Zalo] native stop failed" }, t)
            } finally {
                session.started = false
            }
            val mapped = PEER_PARTNERS[session.peerHandle]
            peerUid = if (!mapped.isNullOrEmpty()) mapped
            else session.peerUid ?: observed.peerUid
            tempFile = session.tempFile
            startedAt = session.startedAt
            direction = session.direction
            CallRecordingMetadataStore.clear()
            // A ZRTC peer handle can survive across calls. Clear per-call state.
            session.confirmed = false
            session.audioConnected = false
            session.tempFile = null
            session.pendingName = null
            session.startedAt = 0L
            session.direction = "unknown"
        }
        val app = appContext ?: return
        if (tempFile == null) return
        CallRecordingOutput.finalizeRecording(
            app, tempFile, startedAt, direction, peerUid,
            observed.displayName, observed.phoneNumber, null
        )
    }

    private fun stopForPeer(peerHandle: Long, methodName: String) {
        val session = SESSIONS_BY_PEER[peerHandle] ?: return
        synchronized(session) {
            if (methodName == "zrtc_peer_delete") session.deleted = true
            stop(session, "PeerJNI#$methodName")
            if (session.deleted) SESSIONS_BY_PEER.remove(peerHandle, session)
        }
    }

    // --- session helpers (no obfuscated peer-manager needed) -------------------

    private fun sessionForPeer(peerHandle: Long): Session {
        lastPeerHandle = peerHandle
        return SESSIONS_BY_PEER.getOrPut(peerHandle) {
            Session(peerHandle, PEER_PARTNERS[peerHandle])
        }
    }

    /** The current active call's session, used when a callback instance isn't mapped. */
    private fun currentLiveSession(): Session? {
        val handle = lastPeerHandle
        if (handle != 0L) {
            SESSIONS_BY_PEER[handle]?.takeIf { !it.deleted }?.let { return it }
        }
        return SESSIONS_BY_PEER.values.firstOrNull { !it.deleted }
    }

    private fun firstInt(args: Array<Any?>?): Int {
        args?.forEach { if (it is Int) return it }
        return Int.MIN_VALUE
    }

    private class Session(val peerHandle: Long, val peerUid: String?) {
        var direction: String = "unknown"
        var startedAt: Long = 0L
        var pendingName: String? = null
        var tempFile: File? = null
        var confirmed: Boolean = false
        var audioConnected: Boolean = false
        var started: Boolean = false
        @Volatile var deleted: Boolean = false
    }
}

/**
 * Tiny before/after DSL wrapper so the ported code reads like the original
 * `XpHooks.Before` / `XpHooks.After` and hooks every overload of a method name.
 */
private class HookScope {
    var before: ((de.robv.android.xposed.XC_MethodHook.MethodHookParam) -> Unit)? = null
    var after: ((de.robv.android.xposed.XC_MethodHook.MethodHookParam) -> Unit)? = null
    fun before(f: (de.robv.android.xposed.XC_MethodHook.MethodHookParam) -> Unit) { before = f }
    fun after(f: (de.robv.android.xposed.XC_MethodHook.MethodHookParam) -> Unit) { after = f }
}

private fun hookMember(member: java.lang.reflect.Member, block: HookScope.() -> Unit) {
    val scope = HookScope().apply(block)
    member.hookMethod {
        scope.before?.let { b -> before { b(it) } }
        scope.after?.let { a -> after { a(it) } }
    }
}

/** Hooks every method overload named [name] on [clazz]/its supers; returns how many were hooked. */
private fun hookAllByName(clazz: Class<*>, name: String, block: HookScope.() -> Unit): Int {
    var count = 0
    var current: Class<*>? = clazz
    val seen = HashSet<String>()
    while (current != null && current != Any::class.java) {
        val here = current
        for (method in here.declaredMethods) {
            if (method.name != name) continue
            if (!seen.add(method.toGenericString())) continue
            try {
                method.isAccessible = true
                hookMember(method, block)
                count++
            } catch (t: Throwable) {
                Logger.printException({ "[Zalo] hook failed: ${here.name}#$name" }, t)
            }
        }
        current = here.superclass
    }
    return count
}
