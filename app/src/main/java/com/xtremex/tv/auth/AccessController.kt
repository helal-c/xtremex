package com.xtremex.tv.auth

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.xtremex.tv.BuildConfig
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

class AccessController(context: Context, private val deviceType: String,
    private val playing: () -> Boolean,
    private val changed: (AuthState, String, Boolean) -> Unit) {
    private val sessions = SessionStore(context)
    private val client = AuthClient(BuildConfig.AUTH_API_BASE, DeviceIdentity())
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val lease = AccessLease()
    private var running = false
    private var busy = false
    private var generation = 0
    private var nextCheck = 0L
    private var support = ""
    private var previous = ""
    val userId: String? get() = sessions.userId
    fun canPlay() = running && lease.canPlay(SystemClock.elapsedRealtime())

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            if (lease.state == AuthState.APPROVED && !lease.canPlay(now)) lease.networkFailure(now)
            publish()
            if (!busy && now >= nextCheck) request(null)
            handler.postDelayed(this, 1000)
        }
    }
    fun start() {
        if (running) return
        running = true; generation++; busy = false; nextCheck = 0L
        lease.invalidate(); previous = ""; tick.run()
    }
    fun stop() {
        if (!running) return
        val id = sessions.userId; val token = sessions.token; val sendFinal = lease.state == AuthState.APPROVED
        running = false; generation++; busy = false; lease.invalidate(); handler.removeCallbacks(tick)
        if (sendFinal && id != null && token != null) executor.execute {
            runCatching { client.request("heartbeat", id, JSONObject().put("token", token).put("playing", false).put("appVersion", BuildConfig.VERSION_NAME)) }
        }
    }
    fun close() { stop(); executor.shutdownNow() }
    fun retry() { nextCheck = 0L; if (running && !busy) request(null) }
    fun heartbeat() { if (running && lease.state == AuthState.APPROVED) { nextCheck = 0L; if (!busy) request(null) } }
    fun register(raw: String) {
        val id = raw.trim().lowercase(Locale.ROOT)
        require(id.matches(Regex("[a-z0-9_.-]{3,32}")))
        if (running && !busy) request(id)
    }
    private fun request(registerId: String?) {
        val id = registerId ?: sessions.userId
        if (id == null) { lease.serverStatus("unregistered", SystemClock.elapsedRealtime()); nextCheck = Long.MAX_VALUE; publish(); return }
        busy = true
        val epoch = generation; val token = sessions.token; val isPlaying = playing()
        executor.execute {
            val result = runCatching {
                when {
                    registerId != null -> client.request("register", id, JSONObject().put("deviceType", deviceType).put("deviceModel", (Build.MANUFACTURER + " " + Build.MODEL).take(128)))
                    token == null -> client.request("session", id)
                    else -> client.request("heartbeat", id, JSONObject().put("token", token).put("playing", isPlaying).put("appVersion", BuildConfig.VERSION_NAME))
                }
            }
            handler.post {
                if (!running || generation != epoch) return@post
                busy = false
                val now = SystemClock.elapsedRealtime()
                result.mapCatching { response ->
                    val status = response.optString("status")
                    support = response.optString("supportNumber").take(32)
                    if (registerId != null) {
                        if (status == "pending" || status == "approved") sessions.save(id, null)
                        lease.serverStatus(if (status == "approved") "pending" else status, now)
                        nextCheck = if (status == "pending" || status == "approved") now else Long.MAX_VALUE
                    } else if (status == "session_expired") {
                        sessions.save(id, null); lease.serverStatus(status, now); nextCheck = now
                    } else {
                        val issued = response.optString("token").takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) }
                        val approved = status == "approved" && response.optInt("leaseSeconds") == 300 && (token != null || issued != null)
                        if (issued != null) sessions.save(id, issued)
                        lease.serverStatus(if (status == "approved" && !approved) "unavailable" else status, now)
                        nextCheck = if (issued != null) now else now + if (approved) 60_000 else 10_000
                    }
                }.onFailure { lease.networkFailure(now); nextCheck = now + 10_000 }
                publish()
            }
        }
    }
    private fun publish() {
        val allowed = canPlay()
        val signature = "${lease.state}:$support:$allowed"
        if (signature == previous) return
        previous = signature; changed(lease.state, support, allowed)
    }
}
