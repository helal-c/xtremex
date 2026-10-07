package com.xtremex.tv.auth

enum class AuthState { UNREGISTERED, PENDING, APPROVED, BLOCKED, WRONG_DEVICE, UNAVAILABLE }

class AccessLease {
    var state = AuthState.UNREGISTERED
        private set
    private var deadline = 0L

    fun serverStatus(status: String, now: Long) {
        state = when (status) {
            "approved" -> AuthState.APPROVED
            "pending" -> AuthState.PENDING
            "blocked" -> AuthState.BLOCKED
            "wrong_device", "device_registered" -> AuthState.WRONG_DEVICE
            "unregistered" -> AuthState.UNREGISTERED
            else -> AuthState.UNAVAILABLE
        }
        deadline = if (state == AuthState.APPROVED) now + 300_000L else 0L
    }
    fun networkFailure(now: Long) {
        if (!canPlay(now)) { state = AuthState.UNAVAILABLE; deadline = 0L }
    }
    fun canPlay(now: Long): Boolean = state == AuthState.APPROVED && now < deadline
    fun remaining(now: Long): Long = if (canPlay(now)) deadline - now else 0L
    fun invalidate() { deadline = 0L; state = AuthState.UNAVAILABLE }
}
