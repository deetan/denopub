package dev.dt.kinopub.system

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.dt.kinopub.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Self-update from GitHub Releases (`deetan/denopub`, public — no token on the TV).
 *
 * A release carries one asset named `denopub-<versionName>-<versionCode>.apk` (written by
 * `scripts/release.sh`); it is newer when that versionCode beats ours. Home checks on start and on
 * every return to the foreground, at most every [CHECK_EVERY_MS], and shows a «Обновление» button;
 * OK downloads the APK and hands it to [PackageInstaller]. Nothing here runs during playback:
 * only Home calls [checkForUpdate] and [install].
 *
 * The APK must be signed with the same key as the installed app (the debug key of the build Mac)
 * or Android refuses it — and uninstalling to get around that loses the kino.pub login.
 */
class Updater(private val context: Context, private val http: OkHttpClient) {

    @Serializable
    data class Asset(val name: String = "", val browser_download_url: String = "", val size: Long = 0)

    @Serializable
    data class Release(val tag_name: String = "", val name: String? = null, val body: String? = null, val assets: List<Asset> = emptyList())

    data class Available(val versionName: String, val versionCode: Int, val url: String, val size: Long, val notes: String?)

    sealed interface State {
        data object Idle : State
        data class Ready(val update: Available) : State
        data class Downloading(val update: Available, val percent: Int) : State
        /** Waiting for the system installer (confirmation dialog or the install itself). */
        data class Installing(val update: Available) : State
        /** "Install unknown apps" isn't granted yet; OK opens the settings screen. */
        data class NeedsPermission(val update: Available) : State
        data class Failed(val update: Available, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val apk get() = File(context.cacheDir, "update/denopub.apk")

    /** Reads the latest release, unless checked recently ([force] ignores that). */
    suspend fun checkForUpdate(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (_state.value !is State.Idle && _state.value !is State.Ready) return@withContext
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("checked_at", 0) < CHECK_EVERY_MS) return@withContext
        val found = runCatching { latest() }
            .onFailure { Log.w(TAG, "update: check failed: $it") }
            .getOrElse { return@withContext }
        Log.i(TAG, "update: have ${BuildConfig.VERSION_CODE}, latest ${found?.versionCode ?: "none"}")
        if (found != null && found.versionCode > BuildConfig.VERSION_CODE) {
            // Not throttled while an update is waiting, so the button survives an app restart.
            _state.value = State.Ready(found)
        } else {
            prefs.edit().putLong("checked_at", now).apply()
            _state.value = State.Idle
            apk.delete()
        }
    }

    private fun latest(): Available? {
        val req = Request.Builder().url(BuildConfig.UPDATE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "denopub-updater")
            .build()
        val release = http.newCall(req).execute().use { r ->
            if (r.code == 404) return null // no release yet
            check(r.isSuccessful) { "HTTP ${r.code}" }
            json.decodeFromString<Release>(r.body!!.string())
        }
        val asset = release.assets.firstNotNullOfOrNull { a ->
            ASSET.matchEntire(a.name)?.let { m -> a to m }
        } ?: return null
        val (a, m) = asset
        return Available(m.groupValues[1], m.groupValues[2].toInt(), a.browser_download_url, a.size, release.body?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** OK on the Home button: grant → download → install, resuming from wherever it stopped. */
    suspend fun install() {
        val update = when (val s = _state.value) {
            is State.Ready -> s.update
            is State.NeedsPermission -> s.update
            is State.Failed -> s.update
            else -> return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission(update)
            openUnknownSourcesSettings()
            return
        }
        try {
            download(update)
            _state.value = State.Installing(update)
            withContext(Dispatchers.IO) { commit() }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "update: install failed: $e")
            _state.value = State.Failed(update, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun download(u: Available) = withContext(Dispatchers.IO) {
        val f = apk
        if (f.exists() && f.length() == u.size && u.size > 0) return@withContext
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "denopub.apk.part")
        _state.value = State.Downloading(u, 0)
        http.newCall(Request.Builder().url(u.url).header("User-Agent", "denopub-updater").build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            val body = r.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: u.size
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != shown) { shown = pct; _state.value = State.Downloading(u, pct) }
                    }
                }
            }
        }
        check(tmp.renameTo(f)) { "rename failed" }
        Log.i(TAG, "update: downloaded ${f.length()} bytes")
    }

    private fun commit() {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Once we are the installer of record (after the first self-update), Android 12+
            // updates without a confirmation dialog.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("denopub.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val pi = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pi.intentSender)
        }
        Log.i(TAG, "update: session $id committed")
    }

    internal fun onResult(status: Int, message: String?, confirm: Intent?) {
        val update = (_state.value as? State.Installing)?.update
        Log.i(TAG, "update: installer status=$status $message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> Unit // the process is replaced; nothing to do
            else -> if (update != null) _state.value = State.Failed(update, message ?: "status $status")
        }
    }

    private fun openUnknownSourcesSettings() {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure {
            Log.w(TAG, "update: no unknown-sources screen: $it")
            (_state.value as? State.NeedsPermission)?.let { s ->
                _state.value = State.Failed(s.update, "Разрешите установку из неизвестных источников в настройках")
            }
        }
    }

    companion object {
        private const val TAG = "denopub"
        const val CHECK_EVERY_MS = 3 * 60 * 60 * 1000L
        private val ASSET = Regex("""denopub-([\w.\-]+)-(\d+)\.apk""")
    }
}

/** Receives the PackageInstaller session result. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as dev.dt.kinopub.KinopubApp
        @Suppress("DEPRECATION")
        app.container.updater.onResult(
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            intent.getParcelableExtra(Intent.EXTRA_INTENT),
        )
    }
}

/** After a self-update the process is gone; bring the app back up where the owner left it. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i("denopub", "update: now ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        runCatching {
            context.startActivity(Intent(context, dev.dt.kinopub.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w("denopub", "update: relaunch refused: $it") }
    }
}
