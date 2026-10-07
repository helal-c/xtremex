package com.xtremex.tv.auth

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

class AuthClient(private val base: String, private val identity: DeviceIdentity) {
    init {
        val uri = URI(base)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
    }
    fun request(action: String, userId: String, extra: JSONObject = JSONObject()): JSONObject {
        val key = identity.publicKey
        val challenge = post("challenge", JSONObject().put("action", action).put("userId", userId).put("publicKey", key))
        val payload = JSONObject().put("userId", userId).put("publicKey", key)
        for (name in extra.keys()) payload.put(name, extra.get(name))
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val canonical = "$action\n$userId\n${challenge.getString("challengeId")}\n${challenge.getString("nonce")}\n$hash"
        return post(action, JSONObject().put("challengeId", challenge.getString("challengeId"))
            .put("payload", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("signature", identity.sign(canonical.toByteArray(Charsets.UTF_8))))
    }
    private fun post(path: String, body: JSONObject): JSONObject {
        val connection = URL(base.trimEnd('/') + "/api/" + path).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000; connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false; connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("User-Agent", "XtremeX-TV-Android/1.1")
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) error("Access service unavailable")
            val text = connection.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder(); val chars = CharArray(1024)
                while (true) { val count = reader.read(chars); if (count < 0) break; result.append(chars, 0, count); require(result.length <= 32768) }
                result.toString()
            }
            return JSONObject(text)
        } finally { connection.disconnect() }
    }
}
