package com.xtremex.tv

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PlaylistRepository(private val context: Context) {
    companion object {
        const val CHANNELS_URL = "https://xtremextv.vercel.app/channels.json"
        private const val CACHE_FILE = "channels-cache.json"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val refreshing = AtomicBoolean(false)
    private val backupPattern = Regex("\\s*\\[\\s*Backup\\s*\\d*\\s*]\\s*$", RegexOption.IGNORE_CASE)

    fun loadCached(): List<TvChannel> = runCatching {
        context.openFileInput(CACHE_FILE).bufferedReader().use { parse(it.readText()) }
    }.getOrDefault(emptyList())

    fun refresh(callback: (Result<List<TvChannel>>) -> Unit) {
        if (!refreshing.compareAndSet(false, true)) return

        executor.execute {
            val result = runCatching {
                val connection = URL(CHANNELS_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "XtremeX-TV-Android/1.0")
                connection.setRequestProperty("Accept", "application/json")

                try {
                    if (connection.responseCode !in 200..299) {
                        error("Playlist HTTP " + connection.responseCode)
                    }

                    val text = connection.inputStream.bufferedReader().use { it.readText() }
                    val channels = parse(text)
                    require(channels.isNotEmpty()) { "Playlist contains no channels" }

                    context.openFileOutput(CACHE_FILE, Context.MODE_PRIVATE).bufferedWriter().use {
                        it.write(text)
                    }
                    channels
                } finally {
                    connection.disconnect()
                }
            }

            refreshing.set(false)
            callback(result)
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private data class Builder(
        var name: String,
        var category: String,
        var logo: String?,
        var group: String?,
        val sources: MutableList<String> = mutableListOf(),
    )

    private fun parse(text: String): List<TvChannel> {
        val array = JSONObject(text).getJSONArray("channels")
        val grouped = LinkedHashMap<String, Builder>()

        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val rawName = item.optString("name").trim()
            val source = item.optString("src").trim()
            if (rawName.isBlank() || source.isBlank()) continue

            val category = item.optString("category", "Other").ifBlank { "Other" }
            val isBackup = backupPattern.containsMatchIn(rawName)
            val baseName = rawName.replace(backupPattern, "").trim().ifBlank { rawName }
            val key = (category + "|" + baseName).lowercase(Locale.ROOT)

            val builder = grouped.getOrPut(key) {
                Builder(
                    name = baseName,
                    category = category,
                    logo = item.optString("logo").takeIf { it.isNotBlank() },
                    group = item.optString("group").takeIf { it.isNotBlank() },
                )
            }

            if (builder.logo.isNullOrBlank()) {
                builder.logo = item.optString("logo").takeIf { it.isNotBlank() }
            }
            if (builder.group.isNullOrBlank()) {
                builder.group = item.optString("group").takeIf { it.isNotBlank() }
            }

            if (!builder.sources.contains(source)) {
                if (isBackup || builder.sources.isEmpty()) builder.sources.add(source)
                else builder.sources.add(0, source)
            }
        }

        return grouped.map { (key, item) ->
            TvChannel(
                id = key,
                name = item.name,
                category = item.category,
                sources = item.sources.toList(),
                logo = item.logo,
                group = item.group,
            )
        }.filter { it.sources.isNotEmpty() }
    }
}
