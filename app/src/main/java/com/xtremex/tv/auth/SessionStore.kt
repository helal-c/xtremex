package com.xtremex.tv.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionStore(context: Context) {
    private val alias = "xtremex-tv-session-v1"
    private val file = AtomicFile(File(context.noBackupFilesDir, "access.dat"))
    private val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    var userId: String? = null
        private set
    var token: String? = null
        private set
    init {
        if (!keys.containsAlias(alias)) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
        runCatching {
            val parts = file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }.split('.')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            val json = JSONObject(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8))
            userId = json.optString("userId").takeIf { it.matches(Regex("[a-z0-9_.-]{3,32}")) }
            token = json.optString("token").takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) }
        }
    }
    private fun key() = keys.getKey(alias, null) as SecretKey
    fun save(id: String, session: String?) {
        val json = JSONObject().put("userId", id).put("token", session ?: "")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val stream = file.startWrite()
        try { stream.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        userId = id; token = session
    }
}
