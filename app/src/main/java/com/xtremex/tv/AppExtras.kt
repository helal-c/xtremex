package com.xtremex.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Optional app content is independent of account access and playback. */
class AppExtras(private val activity: Activity, allowed: () -> Boolean) {
    private val mobileBanner = com.xtremex.tv.ads.MobileBanner(activity, allowed)
    fun accessChanged() = mobileBanner.accessChanged()
    fun suspendAds() = mobileBanner.detach()
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var loading = false
    private var config = JSONObject()
    private var dialog: AlertDialog? = null
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    fun close() { closed = true; mobileBanner.close(); dialog?.dismiss(); executor.shutdownNow() }
    fun refresh(done: () -> Unit) {
        if (closed || loading) return
        loading = true
        executor.execute {
            val result = runCatching {
                val connection = URL(BuildConfig.AUTH_API_BASE.trimEnd('/') + "/api/app-config").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5000; connection.readTimeout = 5000; connection.instanceFollowRedirects = false
                    check(connection.responseCode == 200)
                    val bytes = connection.inputStream.use { it.readBytesLimited(400000) }
                    JSONObject(String(bytes, Charsets.UTF_8))
                } finally { connection.disconnect() }
            }.getOrNull()
            handler.post { loading = false; if (!closed && !activity.isFinishing) { config = result ?: JSONObject(); done() } }
        }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = read(buffer); if (n < 0) break; check(output.size() + n <= limit); output.write(buffer, 0, n) }
        return output.toByteArray()
    }
    fun donationEnabled() = config.optJSONObject("donation")?.optBoolean("enabled", false) == true
    fun sponsor(): JSONObject? = config.optJSONObject("ads")?.takeIf { it.optBoolean("enabled", false) }
    fun openAdmin() = openUrl(BuildConfig.AUTH_API_BASE.trimEnd('/') + "/")
    fun openUrl(value: String) {
        val uri = Uri.parse(value)
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: android.content.ActivityNotFoundException) { android.widget.Toast.makeText(activity, "Open this address on your phone: $value", Toast.LENGTH_LONG).show() }
    }
    private fun label(value: String, size: Float = 14f) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); setPadding(dp(4), dp(6), dp(4), dp(6))
    }
    fun button(value: String, action: () -> Unit) = TextView(activity).apply {
        text = value; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48); setPadding(dp(14), dp(10), dp(14), dp(10)); isFocusable = true; isClickable = true
        fun background(focused: Boolean) = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (focused) Color.rgb(166, 27, 54) else Color.rgb(28, 34, 45)); setStroke(dp(1), Color.rgb(65, 72, 87)) }
        background = background(false); setOnFocusChangeListener { _, focused -> background = background(focused) }
        setOnClickListener { action() }
    }
    fun panel(title: String, build: (LinearLayout, () -> Unit) -> Unit) {
        dialog?.dismiss()
        mobileBanner.detach()
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(title, 20f), LinearLayout.LayoutParams(0, -2, 1f))
        val close = button("✕") { dialog?.dismiss() }; close.contentDescription = "Close"
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(48))); body.addView(header)
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { isFillViewport = false; addView(content) }
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val current = AlertDialog.Builder(activity).setView(body).create()
        dialog = current
        build(content) { current.dismiss() }
        current.setOnDismissListener { if (dialog === current) { mobileBanner.detach(); dialog = null } }
        current.show()
        current.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.rgb(12, 17, 25)) })
            setLayout(minOf(dp(460), activity.resources.displayMetrics.widthPixels - dp(32)), minOf(dp(520), activity.resources.displayMetrics.heightPixels - dp(32)))
        }
    }
    fun addAdMob(content: LinearLayout) {
        val settings = config.optJSONObject("admob") ?: return
        val privacy = button("Ad privacy options") { mobileBanner.showPrivacy() }
        content.addView(privacy, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        val container = FrameLayout(activity).apply { setPadding(0, dp(20), 0, dp(20)) }
        content.addView(container, LinearLayout.LayoutParams(-1, -2))
        mobileBanner.attach(container, privacy, settings)
    }
    fun showSports() {
        panel("Live sports · official services") { content, dismiss ->
            content.addView(label("The providers update live events on their own sites. Sign in or subscribe there when required."))
            content.addView(button("Tapmad · sports ↗") { dismiss(); openUrl("https://www.tapmad.com/sports") })
            content.addView(button("Tapmad · live TV ↗") { dismiss(); openUrl("https://www.tapmad.com/live") })
            content.addView(button("FanCode · live events ↗") { dismiss(); openUrl("https://www.fancode.com/liveevents") })
        }
    }
    fun showDonation() {
        val d = config.optJSONObject("donation") ?: return
        if (!d.optBoolean("enabled")) return
        panel("Support XtremeX TV") { content, _ ->
            content.addView(label(d.optString("message")))
            for ((key, name) in listOf("bkash" to "bKash", "nagad" to "Nagad")) {
                val method = d.optJSONObject(key) ?: continue
                val number = method.optString("number"); val qr = method.optString("qr")
                if (number.isBlank() && qr.isBlank()) continue
                content.addView(label(name, 18f))
                if (number.isNotBlank()) {
                    content.addView(label(number, 22f))
                    content.addView(button("Copy $name number") {
                        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("$name donation number", number))
                        Toast.makeText(activity, "Number copied", Toast.LENGTH_SHORT).show()
                    })
                    content.addView(label("Open $name → Send Money → paste this number → choose an amount. Check the recipient before confirming."))
                }
                if (qr.startsWith("data:image/") && qr.length <= 180000) runCatching {
                    val bytes = Base64.decode(qr.substringAfter(","), Base64.DEFAULT)
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    check(options.outWidth in 1..2048 && options.outHeight in 1..2048)
                    options.inJustDecodeBounds = false
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@runCatching
                    val image = ImageView(activity).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE); contentDescription = "$name donation QR"; setPadding(dp(8), dp(8), dp(8), dp(8)) }
                    content.addView(image, LinearLayout.LayoutParams(dp(210), dp(210)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12); bottomMargin = dp(12) })
                    content.addView(label("Scan with your payment app and check the recipient."))
                }
            }
            content.addView(label("Donations are optional. Complete the payment in your payment app; XtremeX TV does not confirm payment automatically."))
        }
    }
}
