package com.xtremex.tv
import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun updateRequiresHttpsFullHashAndIncreasingVersion() {
        val hash = "a".repeat(64)
        assertTrue(UpdatePolicy.validMetadata("https://example.com/tv.apk", hash, 2, 1))
        assertFalse(UpdatePolicy.validMetadata("http://example.com/tv.apk", hash, 2, 1))
        assertFalse(UpdatePolicy.validMetadata("https://example.com/tv.apk", "", 2, 1))
        assertFalse(UpdatePolicy.validMetadata("https://example.com/tv.apk", "x".repeat(64), 2, 1))
        assertFalse(UpdatePolicy.validMetadata("https://example.com/tv.apk", hash, 1, 1))
        assertFalse(UpdatePolicy.validMetadata("https://example.com/tv.apk", hash, 0, 1))
    }
    @Test fun apkIdentityRequiresPackageVersionAndSameCertificate() {
        assertTrue(UpdatePolicy.sameIdentity("com.xtremex.tv", "com.xtremex.tv", 2, 2, listOf("cert"), listOf("cert")))
        assertFalse(UpdatePolicy.sameIdentity("other", "com.xtremex.tv", 2, 2, listOf("cert"), listOf("cert")))
        assertFalse(UpdatePolicy.sameIdentity("com.xtremex.tv", "com.xtremex.tv", 1, 2, listOf("cert"), listOf("cert")))
        assertFalse(UpdatePolicy.sameIdentity("com.xtremex.tv", "com.xtremex.tv", 2, 2, listOf("attacker"), listOf("cert")))
        assertFalse(UpdatePolicy.sameIdentity("com.xtremex.tv", "com.xtremex.tv", 2, 2, emptyList(), emptyList()))
    }
}
