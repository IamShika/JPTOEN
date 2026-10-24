package com.example.jpsubs

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Finds out whether a newer version of JP Subs exists and helps the person install it.
 *
 *  1. Asks GitHub for the newest official release of this repo (the ones made by pushing a tag
 *     like v1.0.1; the "Latest build" test page is ignored).
 *  2. If it is newer than the installed version, shows a dialog: Update, or Remind me later in
 *     1 to 7 days, or never.
 *  3. "Update" downloads the APK, checks it arrived complete, and opens Android's installer.
 *     The first time, Android asks you to allow JP Subs to install apps.
 *
 * Android only accepts the update if it is signed with the same key as the installed app.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000 // look at most every 6 hours
    private const val DAY_MS = 24L * 60 * 60 * 1000

    class Release(
        val tag: String,
        val version: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
    )

    @Volatile private var checking = false
    private var dialogShowing = false
    private var pendingApk: File? = null // downloaded, waiting for the "allow installs" permission

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    // ---------------------------------------------------------------------------------------
    // Checking
    // ---------------------------------------------------------------------------------------

    /** Quiet check when the app opens. Respects "remind me later" and "never". */
    fun autoCheck(activity: Activity) {
        if (Settings.updateNever) return
        val now = System.currentTimeMillis()
        if (now < Settings.updateRemindAt) return
        if (now - Settings.updateLastCheck < CHECK_EVERY_MS) return
        check(activity, manual = false)
    }

    /** Looks for an update. With manual = true it also tells the person when there is none. */
    fun check(activity: Activity, manual: Boolean) {
        if (checking) return
        checking = true
        if (manual) toast(activity, "Checking for updates…")

        thread(name = "update-check") {
            try {
                val release = fetchLatest()
                Settings.updateLastCheck = System.currentTimeMillis()
                Settings.save(activity)
                activity.runOnUiThread {
                    if (release != null && isNewer(release.version, currentVersion())) {
                        showUpdateDialog(activity, release)
                    } else if (manual) {
                        toast(activity, "You have the newest version (${currentVersion()}).")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                if (manual) activity.runOnUiThread { toast(activity, "Could not check: ${e.message}") }
            } finally {
                checking = false
            }
        }
    }

    /** Blocking. Gives the newest official release, or null if there is none yet. */
    private fun fetchLatest(): Release? {
        val url = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "JpSubs")
            val code = connection.responseCode
            if (code == 404) return null // no release published yet
            if (code != 200) throw IOException("GitHub answered HTTP $code")

            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    return Release(
                        tag = tag,
                        version = tag.trimStart('v', 'V'),
                        notes = json.optString("body", ""),
                        apkUrl = asset.getString("browser_download_url"),
                        apkSize = asset.optLong("size", 0L),
                    )
                }
            }
            return null // a release without an APK is not an update
        } finally {
            connection.disconnect()
        }
    }

    /** True if version `a` is higher than version `b` (compares the numbers: 1.10 > 1.9). */
    fun isNewer(a: String, b: String): Boolean {
        val x = Regex("\\d+").findAll(a).map { it.value.toInt() }.toList()
        val y = Regex("\\d+").findAll(b).map { it.value.toInt() }.toList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    // ---------------------------------------------------------------------------------------
    // The "update available" dialog
    // ---------------------------------------------------------------------------------------

    private fun showUpdateDialog(activity: Activity, release: Release) {
        if (dialogShowing || activity.isFinishing) return
        dialogShowing = true

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val notes = release.notes.trim().take(600)
        box.addView(TextView(activity).apply {
            text = "You have ${currentVersion()}. The newest version is ${release.version}." +
                (if (notes.isNotEmpty()) "\n\nWhat's new:\n$notes" else "")
            textSize = 14f
        })
        box.addView(TextView(activity).apply {
            text = "If you tap \"Remind me later\", ask me again in:"
            textSize = 13f
            setPadding(0, dp(16), 0, 0)
        })
        val choices = listOf("1 day", "2 days", "3 days", "4 days", "5 days", "6 days", "7 days", "Never")
        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, choices)
        }
        box.addView(spinner)

        val scroll = ScrollView(activity).apply { addView(box) }
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setView(scroll)
            .setPositiveButton("Update") { _, _ -> startDownload(activity, release) }
            .setNegativeButton("Remind me later") { _, _ ->
                snooze(activity, spinner.selectedItemPosition)
            }
            .setOnDismissListener { dialogShowing = false }
            .show()
    }

    /** Position 0..6 = that many days (1..7); position 7 = never ask again. */
    private fun snooze(activity: Activity, position: Int) {
        if (position >= 7) {
            Settings.updateNever = true
            Settings.save(activity)
            toast(activity, "Automatic update checks are off. Turn them on again in Tuning > Other.")
        } else {
            val days = position + 1
            Settings.updateRemindAt = System.currentTimeMillis() + days * DAY_MS
            Settings.save(activity)
            toast(activity, "OK, I will ask again in $days day${if (days > 1) "s" else ""}.")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Downloading and installing
    // ---------------------------------------------------------------------------------------

    private fun startDownload(activity: Activity, release: Release) {
        val density = activity.resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        val label = TextView(activity).apply { text = "Starting…"; textSize = 13f }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val cancelled = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Downloading ${release.version}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .create()
        dialog.show()

        thread(name = "update-download") {
            try {
                val file = download(activity, release, cancelled) { fraction, megabytes ->
                    activity.runOnUiThread {
                        bar.progress = (fraction * 100).toInt()
                        label.text = String.format("%.1f MB downloaded", megabytes)
                    }
                }
                activity.runOnUiThread {
                    dialog.dismiss()
                    if (file != null) install(activity, file)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update download failed", e)
                activity.runOnUiThread {
                    dialog.dismiss()
                    toast(activity, "Download failed: ${e.message}")
                }
            }
        }
    }

    /** Blocking. Saves the APK in the app's cache folder. Returns null if cancelled. */
    private fun download(
        activity: Activity,
        release: Release,
        cancelled: AtomicBoolean,
        onProgress: (Float, Double) -> Unit,
    ): File? {
        val dir = File(activity.cacheDir, "updates")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() } // remove older downloads
        val target = File(dir, "JP-Subs-${release.version}.apk")

        val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "JpSubs")
            if (connection.responseCode != 200) {
                throw IOException("server answered HTTP ${connection.responseCode}")
            }
            val total = if (release.apkSize > 0) release.apkSize else connection.contentLengthLong
            var received = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        if (cancelled.get()) {
                            target.delete()
                            return null
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 200) {
                            lastReport = now
                            val fraction = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
                            onProgress(fraction, received / 1048576.0)
                        }
                    }
                }
            }
            // A cut-off file is what causes "package appears to be invalid", so check the size.
            if (total > 0 && received != total) {
                target.delete()
                throw IOException("the download was cut off ($received of $total bytes). Please try again.")
            }
            return target
        } finally {
            connection.disconnect()
        }
    }

    private fun install(activity: Activity, file: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            // Android needs a one-time permission for an app to install other APKs.
            pendingApk = file
            AlertDialog.Builder(activity)
                .setTitle("Allow installs")
                .setMessage(
                    "Android needs your permission for JP Subs to install the update. Tap " +
                        "\"Open settings\", switch on \"Allow from this source\", then press Back. " +
                        "The installer opens by itself."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    activity.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                }
                .setNegativeButton("Cancel") { _, _ -> pendingApk = null }
                .show()
            return
        }
        launchInstaller(activity, file)
    }

    /** Called when the app comes back to the screen: continues after the permission was given. */
    fun resumePending(activity: Activity) {
        val file = pendingApk ?: return
        if (activity.packageManager.canRequestPackageInstalls()) {
            pendingApk = null
            launchInstaller(activity, file)
        }
    }

    private fun launchInstaller(activity: Activity, file: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open the installer", e)
            toast(activity, "Could not open the installer: ${e.message}")
        }
    }

    private fun toast(activity: Activity, text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
}
