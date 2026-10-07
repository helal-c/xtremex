package com.xtremex.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Small, bounded logo loader. Tags prevent a late result replacing a new channel. */
object ChannelLogo {
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    fun show(view: ImageView, address: String?) {
        view.tag = address; view.setImageDrawable(null); view.visibility = View.GONE
        if (address.isNullOrBlank() || !address.startsWith("https://")) return
        cache.get(address)?.let { view.setImageBitmap(it); view.visibility = View.VISIBLE; return }
        worker.execute {
            val bitmap = runCatching {
                val connection = URL(address).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000; connection.readTimeout = 5000; connection.instanceFollowRedirects = false
                try {
                    require(connection.responseCode == 200)
                    val bytes = connection.inputStream.use { it.readNBytesCompat(256 * 1024 + 1) }
                    require(bytes.size <= 256 * 1024)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
                    val options = BitmapFactory.Options().apply {
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
                        inSampleSize = sample
                    }
                    requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
                } finally { connection.disconnect() }
            }.getOrNull()
            if (bitmap != null) {
                cache.put(address, bitmap)
                main.post { if (view.tag == address) { view.setImageBitmap(bitmap); view.visibility = View.VISIBLE } }
            }
        }
    }
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
