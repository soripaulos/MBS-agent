package me.rerere.rikkahub.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.ImportedDatabaseReconciler
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Shared archive format and restore lifecycle for local, WebDAV and S3 backups. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    private val restoreMutex = Mutex()

    suspend fun createBackup(
        includeDatabase: Boolean,
        includeFiles: Boolean,
        // A full ("everything") backup additionally captures the app data dir siblings of
        // files/: DataStore preferences and shared_prefs, which hold every setting that does
        // NOT live in settings.json (tool approvals, termux/browser/telegram/notification
        // prefs) plus whatever else sits directly in filesDir. Used by the local Export tab so
        // one zip round-trips the whole app; cloud sync keeps the lighter selection.
        includeEverything: Boolean = false,
    ): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val archive = File.createTempFile("backup_${timestamp}_", ".zip", context.cacheDir)
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "backup-").toFile()
        try {
            val settings = settingsStore.settingsFlowRaw.first()
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("settings.json"))
                zip.write(json.encodeToString(settings).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (includeDatabase) {
                    val snapshot = File(staging, SQLiteConfiguration.DATABASE_NAME)
                    DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, snapshot)
                    addFile(zip, snapshot, DatabaseBackup.ARCHIVE_DATABASE)
                }
                if (includeFiles) {
                    for (folder in listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, FileFolders.IMAGES)) {
                        val directory = File(context.filesDir, folder)
                        val files = if (folder == FileFolders.SKILLS) directory.walkTopDown().asSequence()
                        else directory.listFiles().orEmpty().asSequence()
                        for (file in files.filter { it.isFile }) {
                            currentCoroutineContext().ensureActive()
                            val relative = file.relativeTo(directory).invariantSeparatorsPath
                            PendingRestore.resolveInside(directory, relative)
                            addFile(zip, file, "$folder/$relative")
                        }
                    }
                }
                if (includeEverything) {
                    addAppData(zip)
                }
            }
            archive
        } catch (e: Throwable) {
            archive.delete()
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun stageRestore(
        archive: File,
        includeDatabase: Boolean,
        includeFiles: Boolean,
        // Entries written by a full backup. Restored whenever the archive carries them, since
        // their presence means the user deliberately exported everything.
        includeEverything: Boolean = true,
    ) =
        withContext(Dispatchers.IO) {
            restoreMutex.withLock {
                val restore = pendingRestore(context)
                val staging = restore.createStagingDirectory()
                try {
                    val payload = File(staging, "payload")
                    val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val stagedWal = File(stagedDatabase.path + "-wal")
                    val seen = mutableSetOf<String>()
                    var restoredEntries = 0
                    ZipFile(archive).use { zip ->
                        for (entry in zip.entries()) {
                            currentCoroutineContext().ensureActive()
                            if (entry.isDirectory) continue
                            val target = when (entry.name) {
                                "settings.json" -> File(staging, "settings.json")
                                DatabaseBackup.ARCHIVE_DATABASE -> if (includeDatabase) stagedDatabase else null
                                DatabaseBackup.WAL -> if (includeDatabase) stagedWal else null
                                DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
                                else -> when {
                                    includeFiles && isAttachment(entry.name) ->
                                        PendingRestore.resolveInside(File(payload, "files"), entry.name)

                                    includeEverything && isAppData(entry.name) ->
                                        PendingRestore.resolveInside(
                                            File(payload, "appdata"),
                                            entry.name.removePrefix("$APP_DATA_PREFIX/"),
                                        )

                                    else -> null
                                }
                            } ?: continue
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create backup staging directory"
                            }
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { output ->
                                    input.copyTo(output)
                                    output.fd.sync()
                                }
                            }
                            restoredEntries++
                        }
                    }
                    require(restoredEntries > 0) { "No selected data found in the backup" }
                    require(!stagedWal.exists() || stagedDatabase.exists()) { "Backup WAL has no matching database" }
                    if (stagedDatabase.exists()) {
                        DatabaseBackup.normalize(context, stagedDatabase)
                        // A backup exported from upstream RikkaHub lacks the fork-only tables and
                        // carries upstream's schema version; reconcile the staged copy before Room
                        // opens it so the import neither fails validation nor crashes on first launch.
                        ImportedDatabaseReconciler.reconcileDatabaseFile(stagedDatabase)
                        // Reject unsupported schemas before publishing; run supported old migrations on the copy.
                        val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
                        try {
                            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                        } finally {
                            room.close()
                        }
                        DatabaseBackup.removeSidecars(stagedDatabase)
                    }

                    val settingsFile = File(staging, "settings.json")
                    if (settingsFile.exists()) {
                        val settings = json.decodeFromString<Settings>(SettingsJsonMigrator.migrate(settingsFile.readText()))
                        require(!settings.init) { "Backup contains uninitialized settings" }
                        // Persist the migrated value once, including generated IDs, for restart/retry consistency.
                        PendingRestore.writeDurably(settingsFile, json.encodeToString(settings))
                    }
                    currentCoroutineContext().ensureActive()
                    restore.publish(staging)
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    /**
     * Zips the DataStore/shared_prefs trees and any remaining top-level filesDir entries under
     * [APP_DATA_PREFIX]. Rebuildable or multi-gigabyte trees (workspace rootfs, downloaded model
     * weights) are skipped, and single files are capped so one stray blob can't balloon the zip;
     * an oversized or unreadable file is logged past rather than failing the whole export.
     */
    private fun addAppData(zip: ZipOutputStream) {
        val dataDir = context.filesDir.parentFile ?: return
        for (directory in listOf("datastore", "shared_prefs")) {
            addTree(zip, File(dataDir, directory), "$APP_DATA_PREFIX/$directory")
        }
        val covered = setOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, FileFolders.IMAGES)
        context.filesDir.listFiles().orEmpty().forEach { child ->
            if (child.name in covered || child.name in EXCLUDED_FROM_FULL_BACKUP) return@forEach
            if (child.isDirectory) {
                addTree(zip, child, "$APP_DATA_PREFIX/files/${child.name}")
            } else if (child.isFile && child.length() <= FULL_BACKUP_MAX_FILE_BYTES) {
                runCatching { addFile(zip, child, "$APP_DATA_PREFIX/files/${child.name}") }
            }
        }
    }

    private fun addTree(zip: ZipOutputStream, directory: File, prefix: String) {
        if (!directory.isDirectory) return
        directory.walkTopDown().filter { it.isFile }.forEach { file ->
            if (file.length() > FULL_BACKUP_MAX_FILE_BYTES) return@forEach
            val relative = file.relativeTo(directory).invariantSeparatorsPath
            runCatching { addFile(zip, file, "$prefix/$relative") }
        }
    }

    private fun isAppData(name: String): Boolean {
        if (!name.startsWith("$APP_DATA_PREFIX/")) return false
        val relative = name.removePrefix("$APP_DATA_PREFIX/")
        return relative.isNotBlank() && relative.split('/').none { it == ".." || it == "." }
    }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, FileFolders.IMAGES) || '/' !in name) return false
        val relative = name.substringAfter('/')
        require(relative.isNotBlank()) { "Invalid backup attachment: $name" }
        require(folder == FileFolders.SKILLS || '/' !in relative) { "Invalid backup attachment: $name" }
        return true
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    companion object {
        /** Archive prefix for full-backup extras; mirrored by PendingRestore.targetFile. */
        private const val APP_DATA_PREFIX = "appdata"
        private const val FULL_BACKUP_MAX_FILE_BYTES = 128L * 1024 * 1024
        private val EXCLUDED_FROM_FULL_BACKUP =
            setOf("rootfs", "workspace", "workspaces", "models", "local-llm", "llm")

        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
        )

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            pendingRestore(context).apply { settingsJson ->
                SettingsStore.restoreBeforeInitialization(context, json.decodeFromString<Settings>(settingsJson))
            }
        }
    }
}
