package com.xtremex.tv

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

class UpdateManager(private val activity: Activity) {
    companion object {
        private const val UPDATE_URL = "https://raw.githubusercontent.com/helal-c/xtremex/main/update.json"
    }
    data class UpdateInfo(val versionName: String, val versionCode: Int, val apkUrl: String,
        val sha256: String, val notes: String, val mandatory: Boolean) {
        fun json() = JSONObject().put("versionName", versionName).put("versionCode", versionCode)
            .put("apkUrl", apkUrl).put("sha256", sha256).put("notes", notes).put("mandatory", mandatory)
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs = activity.getSharedPreferences("xtremex-update", Activity.MODE_PRIVATE)
    @Volatile private var monitoringId = -1L
    private var foreground = false
    private var installerShown = false
    var isShowingDialog = false
        private set

    fun check(force: Boolean = false) {
        if (activity.packageName != "com.xtremex.tv") {
            if (force) toast("Install the production app to receive production updates")
            return
        }
        executor.execute {
            val result = runCatching { fetchUpdateInfo() }
            activity.runOnUiThread {
                result.onSuccess { info ->
                    if (info.versionCode <= currentVersionCode() || info.apkUrl.isBlank()) {
                        if (force) toast("XtremeX TV is up to date")
                    } else if (!UpdatePolicy.validMetadata(info.apkUrl, info.sha256, info.versionCode, currentVersionCode())) {
                        if (force) toast("Invalid update metadata")
                    } else showUpdate(info)
                }.onFailure { if (force) toast("Update check failed") }
            }
        }
    }
    fun onResume() {
        foreground = true
        val info = pending() ?: return
        if (info.versionCode <= currentVersionCode()) { clear(); return }
        if (canInstall()) download(info)
    }
    fun onStop() { foreground = false }
    fun close() { foreground = false; executor.shutdownNow() }
    private fun decode(root: JSONObject) = UpdateInfo(root.optString("versionName").take(80), root.optInt("versionCode"),
        root.optString("apkUrl"), root.optString("sha256").lowercase(), root.optString("notes").take(4096), root.optBoolean("mandatory"))
    private fun pending() = runCatching { prefs.getString("info", null)?.let { decode(JSONObject(it)) } }.getOrNull()
    private fun fetchUpdateInfo(): UpdateInfo {
        val connection = URL(UPDATE_URL + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000; connection.readTimeout = 12000
        connection.setRequestProperty("Cache-Control", "no-cache")
        try {
            require(connection.responseCode in 200..299)
            val content = connection.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder(); val buffer = CharArray(1024)
                while (true) { val count = reader.read(buffer); if (count < 0) break; result.append(buffer, 0, count); require(result.length <= 32768) }
                result.toString()
            }
            return decode(JSONObject(content))
        } finally { connection.disconnect() }
    }
    private fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    private fun currentVersionCode() = version(activity.packageManager.getPackageInfo(activity.packageName, 0)).toInt()
    private fun canInstall() = Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()
    private fun showUpdate(info: UpdateInfo) {
        if (isShowingDialog || activity.isFinishing) return
        val builder = AlertDialog.Builder(activity).setTitle("XtremeX TV ${info.versionName} available")
            .setMessage(info.notes.ifBlank { "A new TV update is ready." })
            .setPositiveButton("Update now") { _, _ ->
                installerShown = false
                prefs.edit().putString("info", info.json().toString()).apply()
                if (canInstall()) download(info) else {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName)))
                    toast("Allow installs. Download continues when you return.")
                }
            }
        if (!info.mandatory) builder.setNegativeButton("Later", null)
        builder.create().apply {
            setCancelable(!info.mandatory); setCanceledOnTouchOutside(!info.mandatory)
            setOnDismissListener { isShowingDialog = false }; isShowingDialog = true; show()
        }
    }
    private fun download(info: UpdateInfo) {
        if (!UpdatePolicy.validMetadata(info.apkUrl, info.sha256, info.versionCode, currentVersionCode())) { clear(); return }
        val manager = activity.getSystemService<DownloadManager>() ?: return
        var id = prefs.getLong("downloadId", -1)
        if (id < 0) {
            val request = DownloadManager.Request(Uri.parse(info.apkUrl)).setTitle("XtremeX TV ${info.versionName}")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "xtremex-update-${info.versionCode}.apk")
            id = manager.enqueue(request)
            prefs.edit().putString("info", info.json().toString()).putLong("downloadId", id).commit()
            toast("Update downloading…")
        }
        if (monitoringId == id || installerShown) return
        monitoringId = id
        executor.execute {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val state = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        if (!cursor.moveToFirst()) -1 else cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    }
                    if (state == -1 || state == DownloadManager.STATUS_FAILED) {
                        clear(); activity.runOnUiThread { toast("Update download failed. Try again.") }; break
                    }
                    if (state == DownloadManager.STATUS_SUCCESSFUL) {
                        val uri = manager.getUriForDownloadedFile(id) ?: error("Missing download")
                        val verified = verifyApk(uri, info)
                        if (verified == null) {
                            clear(); activity.runOnUiThread { toast("Update verification failed. APK was not installed.") }; break
                        }
                        activity.runOnUiThread {
                            if (foreground && !installerShown && !activity.isFinishing && canInstall()) {
                                installerShown = true
                                runCatching { activity.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(FileProvider.getUriForFile(activity, activity.packageName + ".updates", verified))
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                    .onFailure { installerShown = false; toast("Unable to open installer") }
                            }
                        }
                        break
                    }
                    Thread.sleep(1000)
                }
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            catch (_: Exception) { if (!Thread.currentThread().isInterrupted) { clear(); activity.runOnUiThread { toast("Update verification failed") } } }
            finally { monitoringId = -1 }
        }
    }
    private fun certificates(info: PackageInfo): List<String> {
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } } ?: emptyList()
    }
    private fun verifyApk(uri: Uri, info: UpdateInfo): File? {
        val directory = File(activity.cacheDir, "verified-updates").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val temp = File.createTempFile("verified-", ".apk", directory)
        var accepted = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = activity.contentResolver.openInputStream(uri) ?: return null
            input.use { source -> temp.outputStream().use { target ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    val count = source.read(buffer); if (count < 0) break
                    total += count; require(total <= 128L * 1024 * 1024)
                    digest.update(buffer, 0, count); target.write(buffer, 0, count)
                }
            } }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hash.equals(info.sha256, ignoreCase = true)) return null
            @Suppress("DEPRECATION")
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val archive = activity.packageManager.getPackageArchiveInfo(temp.absolutePath, flags) ?: return null
            val installed = activity.packageManager.getPackageInfo(activity.packageName, flags)
            accepted = UpdatePolicy.sameIdentity(archive.packageName, activity.packageName, version(archive), info.versionCode.toLong(), certificates(archive), certificates(installed))
            return if (accepted) temp else null
        } finally { if (!accepted) temp.delete() }
    }
    private fun clear() {
        val id = prefs.getLong("downloadId", -1)
        if (id >= 0) activity.getSystemService<DownloadManager>()?.remove(id)
        prefs.edit().clear().commit()
        File(activity.cacheDir, "verified-updates").listFiles()?.forEach { it.delete() }
    }
    private fun toast(message: String) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
}
