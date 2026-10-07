package com.xtremex.tv

data class TvChannel(
    val id: String,
    val name: String,
    val category: String,
    val sources: List<String>,
    val logo: String? = null,
    val group: String? = null,
) {
    val primarySource: String get() = sources.first()
    val hasBdixSource: Boolean get() = sources.any { it.startsWith("http:") }
}

data class ChannelRow(
    val globalIndex: Int,
    val channel: TvChannel,
)
