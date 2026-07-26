package me.rerere.rikkahub.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import me.rerere.rikkahub.R
import java.io.File

/**
 * Phase 20 — expose the app's own data directory (filesDir: skills, uploads, fonts,
 * exports, everything the agent writes) through the Storage Access Framework, so any
 * file manager (Files by Google, Material Files, "File Shortcut"-style apps, a PC over
 * MTP+SAF bridges) can browse, read, edit, and copy it. This is the "make the app folder
 * accessible" ask without relocating data out of the sandbox — the sandbox location keeps
 * Android's encryption + uninstall semantics, while SAF grants the visibility.
 *
 * Copying this tree out (or into) a file manager doubles as a manual backup/restore path,
 * complementing the full zip export (Settings -> Backup -> Import/Export).
 *
 * documentId scheme: "app" = filesDir root; "app/<relative-path>" = entry inside it.
 * Full read-write: create/delete/rename/write are all enabled — the user asked for the
 * directory to be openly editable. Path-escape is guarded in [resolveFile].
 */
class AppDataDocumentsProvider : DocumentsProvider() {

    private fun baseDir(): File = requireNotNull(context).filesDir.canonicalFile

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(Root.COLUMN_TITLE, ctx.getString(R.string.app_data_provider_title))
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD
            )
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher_omni_omniverse)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addFileRow(cursor, resolveFile(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val dir = resolveFile(parentDocumentId)
        if (dir.isDirectory) {
            dir.listFiles()
                .orEmpty()
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                .forEach { addFileRow(cursor, it) }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = resolveFile(documentId)
        require(!file.isDirectory) { "Cannot open a directory" }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parentDir = resolveFile(parentDocumentId)
        require(parentDir.isDirectory) { "Parent is not a directory" }
        val safe = displayName.replace('/', '_').ifBlank { "untitled" }
        var target = File(parentDir, safe)
        var n = 1
        while (target.exists()) {
            val stem = File(parentDir, safe).nameWithoutExtension
            val ext = File(parentDir, safe).extension.let { if (it.isNotEmpty()) ".$it" else "" }
            target = File(parentDir, "$stem ($n)$ext")
            n++
        }
        if (mimeType == Document.MIME_TYPE_DIR) {
            require(target.mkdir()) { "Failed to create directory: $displayName" }
        } else {
            require(target.createNewFile()) { "Failed to create file: $displayName" }
        }
        notifyChange(parentDocumentId)
        return docIdOf(target)
    }

    override fun deleteDocument(documentId: String) {
        require(documentId != ROOT_DOC_ID) { "Cannot delete the root" }
        val file = resolveFile(documentId)
        val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
        require(ok) { "Failed to delete: $documentId" }
        notifyChange(parentDocIdOf(documentId))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        require(documentId != ROOT_DOC_ID) { "Cannot rename the root" }
        val file = resolveFile(documentId)
        val dest = File(file.parentFile, displayName.replace('/', '_'))
        require(!dest.exists()) { "Target already exists: $displayName" }
        require(file.renameTo(dest)) { "Failed to rename: $documentId" }
        notifyChange(parentDocIdOf(documentId))
        return docIdOf(dest)
    }

    override fun getDocumentType(documentId: String): String = mimeOf(resolveFile(documentId))

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == ROOT_DOC_ID) return documentId.startsWith("$ROOT_DOC_ID/")
        return documentId.startsWith("$parentDocumentId/")
    }

    // --- helpers ---

    private fun addFileRow(cursor: MatrixCursor, file: File) {
        val docId = docIdOf(file)
        val isDir = file.isDirectory
        val isRoot = docId == ROOT_DOC_ID
        val flags = when {
            isRoot -> Document.FLAG_DIR_SUPPORTS_CREATE
            isDir -> Document.FLAG_DIR_SUPPORTS_CREATE or
                Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            else -> Document.FLAG_SUPPORTS_WRITE or
                Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(
                Document.COLUMN_DISPLAY_NAME,
                if (isRoot) context?.getString(R.string.app_data_provider_title) else file.name
            )
            add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mimeOf(file))
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (isDir) null else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
    }

    private fun mimeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        return ext.takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
    }

    private fun docIdOf(file: File): String {
        val rel = file.canonicalFile.relativeTo(baseDir()).path.replace(File.separatorChar, '/')
        return if (rel.isEmpty()) ROOT_DOC_ID else "$ROOT_DOC_ID/$rel"
    }

    private fun parentDocIdOf(documentId: String): String =
        documentId.substringBeforeLast('/', ROOT_DOC_ID)

    /** Resolve an UNTRUSTED documentId; reject anything escaping filesDir. */
    private fun resolveFile(documentId: String): File {
        if (documentId == ROOT_DOC_ID) return baseDir()
        require(documentId.startsWith("$ROOT_DOC_ID/")) { "Invalid documentId: $documentId" }
        val rel = documentId.removePrefix("$ROOT_DOC_ID/")
        require(rel.isNotBlank() && !rel.contains("..")) { "Invalid path: $documentId" }
        val base = baseDir()
        val target = File(base, rel).canonicalFile
        require(target.path == base.path || target.path.startsWith(base.path + File.separator)) {
            "Path escapes app data root: $documentId"
        }
        return target
    }

    private fun notifyChange(parentDocumentId: String) {
        val ctx = context ?: return
        val uri = DocumentsContract.buildChildDocumentsUri(
            ctx.packageName + ".appdata.documents",
            parentDocumentId,
        )
        ctx.contentResolver.notifyChange(uri, null)
    }

    companion object {
        private const val ROOT_ID = "omnitrix_app_data"
        private const val ROOT_DOC_ID = "app"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
