package com.xtremex.tv.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class DeviceIdentity {
    private val alias = "xtremex-tv-installation-v1"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false).build())
                generateKeyPair()
            }
        }
    }
    val publicKey: String get() = Base64.encodeToString(store.getCertificate(alias).publicKey.encoded, Base64.NO_WRAP)
    fun sign(message: ByteArray): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(store.getKey(alias, null) as java.security.PrivateKey)
        signer.update(message)
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
    }
}
