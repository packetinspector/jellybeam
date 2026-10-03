package tv.jellybeam.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import java.io.File
import kotlinx.coroutines.*
import tv.jellybeam.AppGraph
import uniffi.jellybeam_core.UpdateSnapshot

internal class AndroidUpdateInstaller(private val context: Context) {
    private val manager = context.packageManager.packageInstaller
    private val journal = context.getSharedPreferences("update-installer", Context.MODE_PRIVATE)
    private var stagingId: Int? = null
    val committedGeneration: ULong? get() = journal.getLong("generation", -1).takeIf { it >= 0 && journal.getBoolean("committed", false) }?.toULong()
    val targetCode: Long get() = journal.getLong("target", 0)
    @Suppress("DEPRECATION")
    fun allowed(): Boolean = if (Build.VERSION.SDK_INT >= 26) context.packageManager.canRequestPackageInstalls()
        else Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
    suspend fun stage(snapshot: UpdateSnapshot): Int {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(snapshot.total.toLong())
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        val id = manager.createSession(params)
        stagingId = id
        check(journal.edit().putInt("session", id).putLong("generation", snapshot.generation.toLong())
            .putLong("target", snapshot.versionCode.toLong()).putBoolean("committed", false).commit())
        manager.openSession(id).use { session ->
            for ((name, path) in listOfNotNull("base.apk" to snapshot.apkPath, snapshot.profilePath?.let { "base.dm" to it })) {
                val file = File(path)
                session.openWrite(name, 0, file.length()).use { out ->
                    file.inputStream().use { input ->
                        val buf = ByteArray(65536)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    session.fsync(out)
                }
            }
        }
        return id
    }
    private fun callback(id: Int, generation: ULong): PendingIntent {
        val intent = Intent(context, UpdateInstallReceiver::class.java).setAction("tv.jellybeam.UPDATE_RESULT")
            .putExtra("operation", generation.toLong())
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, id, intent, flags)
    }
    fun commit(id: Int, generation: ULong) {
        check(journal.edit().putBoolean("committed", true).commit())
        try {
            manager.openSession(id).use { it.commit(callback(id, generation).intentSender) }
            stagingId = null
        } catch (error: Exception) {
            journal.edit().putBoolean("committed", false).commit()
            throw error
        }
    }
    /** False when the session is gone or recommitting fails; the caller must surface the failure. */
    fun repeatConfirmation(generation: ULong): Boolean {
        val id = journal.getInt("session", -1)
        return id >= 0 && sessionExists() && runCatching { manager.openSession(id).use { it.commit(callback(id, generation).intentSender) } }.isSuccess
    }
    /** True once the committed session is gone and the journal cleared; false while Android is still finalizing it. */
    fun abandonCommitted(): Boolean {
        val id = journal.getInt("session", -1)
        if (id >= 0 && runCatching { manager.abandonSession(id) }.isFailure && sessionExists()) return false
        clear()
        return true
    }
    fun matches(intent: Intent): Boolean = intent.action == "tv.jellybeam.UPDATE_RESULT"
        && matchesSession(journal.getInt("session", -1), committedGeneration,
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1), intent.getLongExtra("operation", -1))
    fun sessionExists(): Boolean = manager.getSessionInfo(journal.getInt("session", -1)) != null
    fun abandonStaging() {
        val id = stagingId ?: journal.getInt("session", -1).takeIf { it >= 0 && !journal.getBoolean("committed", false) }
        if (id != null) runCatching { manager.abandonSession(id) }
        stagingId = null
    }
    fun removeOrphans() {
        manager.mySessions.filter { it.appPackageName == context.packageName && it.sessionId != journal.getInt("session", -1) }
            .forEach { runCatching { manager.abandonSession(it.sessionId) } }
    }
    fun clear() { check(journal.edit().clear().commit()) }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try { AppGraph.updates.installResult(intent) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* docs/26 §4: failed owner creation leaves session identity for restart reconciliation. */ }
            finally { pending.finish() }
        }
    }
}
