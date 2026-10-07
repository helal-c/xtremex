package com.xtremex.tv
import java.net.URI

object UpdatePolicy {
    fun validMetadata(url: String, hash: String, version: Int, current: Int): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null &&
            hash.matches(Regex("[a-fA-F0-9]{64}")) && version > current
    }.getOrDefault(false)
    fun sameIdentity(packageName: String, expectedPackage: String, version: Long, expectedVersion: Long,
        certificates: List<String>, currentCertificates: List<String>): Boolean =
        packageName == expectedPackage && version == expectedVersion && certificates.isNotEmpty() &&
            certificates.toSet() == currentCertificates.toSet()
}
