package com.xtremex.tv

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.getSystemService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

class UpdateManager(private val activity: Activity) {
    companion object {
        private const val UPDATE_URL =
            "https://raw.githubusercontent.com/helal-c/xtremex/main/update.json"
    }

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val sha256: String,
        val notes: String,
        val mandatory: Boolean,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private var pendingInfo: UpdateInfo? = null

    fun check(force: Boolean = false) {
        executor.execute {
            val result = runCatching { fetchUpdateInfo() }
            val info = result.getOrNull()

            if (info == null) {
                if (force) activity.runOnUiThread { toast("Update check failed") }
                return@execute
            }

            val currentCode = currentVersionCode()
            if (info.apkUrl.isBlank() || info.versionCode <= currentCode) {
                if (force) activity.runOnUiThread { toast("XtremeX TV is up to date") }
                return@execute
            }

            activity.runOnUiThread { showUpdate(info) }
        }
    }

    fun onResume() {
        val info = pendingInfo ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()
        ) {
            pendingInfo = null
            download(info)
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun fetchUpdateInfo(): UpdateInfo {
        val connection = URL(UPDATE_URL + "?t=" + System.currentTimeMillis())
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 12_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "XtremeX-TV-Android/1.0")
        connection.setRequestProperty("Cache-Control", "no-cache")

        try {
            if (connection.responseCode !in 200..299) {
                error("Update HTTP " + connection.responseCode)
            }

            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return UpdateInfo(
                versionName = root.optString("versionName", "0.0.0"),
                versionCode = root.optInt("versionCode", 0),
                apkUrl = root.optString("apkUrl"),
                sha256 = root.optString("sha256").lowercase(),
                notes = root.optString("notes"),
                mandatory = root.optBoolean("mandatory", false),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun currentVersionCode(): Int {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    private fun showUpdate(info: UpdateInfo) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle("XtremeX TV " + info.versionName + " available")
            .setMessage(info.notes.ifBlank { "A new TV app update is ready." })
            .setPositiveButton("Update now") { _, _ -> requestOrDownload(info) }

        if (!info.mandatory) dialog.setNegativeButton("Later", null)

        dialog.create().apply {
            setCancelable(!info.mandatory)
            setCanceledOnTouchOutside(!info.mandatory)
            show()
        }
    }

    private fun requestOrDownload(info: UpdateInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            pendingInfo = info
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.packageName)
                )
            )
            toast("Allow app installs. Download will continue when you return.")
            return
        }

        download(info)
    }

    private fun download(info: UpdateInfo) {
        val manager = activity.getSystemService<DownloadManager>() ?: return
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("XtremeX TV " + info.versionName)
            .setDescription("Downloading TV app update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                activity,
                Environment.DIRECTORY_DOWNLOADS,
                "XtremeX-TV-v" + info.versionName + ".apk"
            )

        val downloadId = manager.enqueue(request)
        toast("Update downloading…")

        executor.execute {
            while (!Thread.currentThread().isInterrupted) {
                manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                    if (!cursor.moveToFirst()) return@execute

                    when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val uri = manager.getUriForDownloadedFile(downloadId) ?: return@execute
                            if (info.sha256.isNotBlank() && !verifySha256(uri, info.sha256)) {
                                activity.runOnUiThread {
                                    toast("Update verification failed. APK was not installed.")
                                }
                                return@execute
                            }

                            activity.runOnUiThread { launchInstaller(uri) }
                            return@execute
                        }

                        DownloadManager.STATUS_FAILED -> {
                            activity.runOnUiThread { toast("Update download failed") }
                            return@execute
                        }
                    }
                }

                Thread.sleep(1_000)
            }
        }
    }

    private fun verifySha256(uri: Uri, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = activity.contentResolver.openInputStream(uri) ?: return false
        input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expected.trim(), ignoreCase = true)
    }

    private fun launchInstaller(uri: Uri) {
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        activity.startActivity(intent)
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
