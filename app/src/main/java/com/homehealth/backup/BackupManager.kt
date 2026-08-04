package com.homehealth.backup

import android.content.Context
import android.net.Uri
import com.homehealth.db.DocumentEntity
import com.homehealth.db.HomeDatabaseFactory
import com.homehealth.db.UserHomeDao
import com.homehealth.db.UserHomeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(private val context: Context) {

    data class Result(val copied: Int, val skipped: List<String>)
    data class RestoreResult(val success: Boolean, val attachments: Int, val message: String? = null)

    private val attachmentsDir: File
        get() = File(context.filesDir, "attachments").also { it.mkdirs() }

    // Copies external content:// URIs into filesDir and updates DB rows.
    // No-op for docs already pointing to local paths.
    suspend fun internalize(
        docs: List<DocumentEntity>,
        dao: UserHomeDao,
    ): Result = withContext(Dispatchers.IO) {
        var copied = 0
        val skipped = mutableListOf<String>()
        val cr = context.contentResolver
        for (doc in docs) {
            if (doc.uri.startsWith(context.filesDir.absolutePath)) continue
            try {
                val stream: InputStream = cr.openInputStream(Uri.parse(doc.uri))
                    ?: run { skipped += doc.label; continue }
                val dir = File(attachmentsDir, sanitizeKey(doc.itemKey)).also { it.mkdirs() }
                val dest = uniqueFile(dir, sanitizeName(doc.label))
                stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                dao.updateDocumentUri(doc.id, dest.absolutePath)
                copied++
            } catch (_: Exception) {
                skipped += doc.label
            }
        }
        Result(copied, skipped)
    }

    // Checkpoints WAL, then streams DB + all attachments + manifest into a ZIP.
    suspend fun writeZip(
        outputStream: OutputStream,
        home: UserHomeEntity?,
        docs: List<DocumentEntity>,
    ) = withContext(Dispatchers.IO) {
        // Copy all three WAL-mode files so any SQLite tool can open the backup as-is.
        // SQLite docs recommend this approach when an exclusive lock cannot be obtained.
        val dbFile  = context.getDatabasePath("home_health.db")
        val dateStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val attachmentMap = mutableMapOf<String, MutableList<String>>()

        // Flush the WAL into the main db file so the copy below is a consistent snapshot.
        HomeDatabaseFactory.getInstance(context).userHomeDao().rawQuery(
            androidx.room.RoomRawQuery("PRAGMA wal_checkpoint(FULL)")
        )

        ZipOutputStream(outputStream.buffered()).use { zos ->
            zos.setMethod(ZipOutputStream.DEFLATED)

            for (suffix in listOf("", "-wal", "-shm")) {
                val f = if (suffix.isEmpty()) dbFile else File(dbFile.path + suffix)
                if (f.exists() && f.length() > 0) {
                    zos.putNextEntry(ZipEntry("db/home_health.db$suffix"))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            for (doc in docs) {
                val safeName = sanitizeName(doc.label)
                val entryName = "attachments/${sanitizeKey(doc.itemKey)}/$safeName"
                try {
                    val stream: InputStream = if (doc.uri.startsWith("/")) {
                        File(doc.uri).inputStream()
                    } else {
                        context.contentResolver.openInputStream(Uri.parse(doc.uri)) ?: continue
                    }
                    zos.putNextEntry(ZipEntry(entryName))
                    stream.use { it.copyTo(zos) }
                    zos.closeEntry()
                    attachmentMap.getOrPut(doc.itemKey) { mutableListOf() } += safeName
                } catch (_: Exception) { }
            }

            val manifest = JSONObject().apply {
                put("version", 1)
                put("appVersion", "0.1.0")
                put("backupDate", dateStr)
                put("homeLabel", home?.label ?: "")
                val items = JSONObject()
                attachmentMap.forEach { (k, v) -> items.put(k, JSONArray(v)) }
                put("items", items)
            }
            val manifestBytes = manifest.toString(2).toByteArray(Charsets.UTF_8)
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestBytes)
            zos.closeEntry()
        }
    }

    // Extracts a backup ZIP (from writeZip) into a temp dir, validates it, closes the live
    // DB connection, then swaps the db files and merges attachments in. The caller must
    // restart the app afterwards — Room's connection and every in-memory ViewModel/Compose
    // state were all built from the OLD database and won't pick up the swap on their own.
    suspend fun restoreZip(inputStream: InputStream): RestoreResult = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "restore_tmp_${System.currentTimeMillis()}")
        try {
            tempDir.mkdirs()
            ZipInputStream(inputStream.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = File(tempDir, entry.name)
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (!File(tempDir, "manifest.json").exists()) {
                return@withContext RestoreResult(false, 0, "Not a valid backup file")
            }

            HomeDatabaseFactory.closeInstance()

            val dbFile = context.getDatabasePath("home_health.db")
            dbFile.parentFile?.mkdirs()
            for (suffix in listOf("", "-wal", "-shm")) {
                val src = File(tempDir, "db/home_health.db$suffix")
                val dst = File(dbFile.path + suffix)
                if (src.exists()) src.copyTo(dst, overwrite = true)
                else dst.delete()   // a stale wal/shm left over from the CURRENT db would corrupt the restored one
            }

            var attachmentCount = 0
            val attachmentsSrc = File(tempDir, "attachments")
            if (attachmentsSrc.exists()) {
                attachmentsSrc.walkTopDown().filter { it.isFile }.forEach { f ->
                    val dst = File(attachmentsDir, f.relativeTo(attachmentsSrc).path)
                    dst.parentFile?.mkdirs()
                    f.copyTo(dst, overwrite = true)
                    attachmentCount++
                }
            }

            RestoreResult(true, attachmentCount)
        } catch (e: Exception) {
            RestoreResult(false, 0, e.message ?: "Restore failed")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun sanitizeKey(key: String) = key.replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun sanitizeName(label: String): String =
        label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    private fun uniqueFile(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot >= 0) name.substring(0, dot) else name
        val ext  = if (dot >= 0) name.substring(dot) else ""
        var f = File(dir, name)
        var i = 1
        while (f.exists()) { f = File(dir, "${base}_${i}${ext}"); i++ }
        return f
    }
}
