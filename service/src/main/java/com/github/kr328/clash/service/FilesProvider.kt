package com.github.kr328.clash.service

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteException
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.github.kr328.clash.common.util.PatternFileName
import com.github.kr328.clash.service.document.*
import com.github.kr328.clash.service.util.withStoredLocale
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import android.provider.DocumentsContract.Document as D

class FilesProvider : DocumentsProvider() {
    companion object {
        private const val DEFAULT_ROOT_ID = "0"

        private val DEFAULT_DOCUMENT_COLUMNS = arrayOf(
            D.COLUMN_DOCUMENT_ID,
            D.COLUMN_DISPLAY_NAME,
            D.COLUMN_MIME_TYPE,
            D.COLUMN_LAST_MODIFIED,
            D.COLUMN_SIZE,
            D.COLUMN_FLAGS
        )
        private val DEFAULT_ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_MIME_TYPES
        )
    }

    private val localized: Context
        get() = context!!.withStoredLocale()

    private val picker: Picker by lazy {
        Picker(context!!)
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val m = mode ?: "rw"

        return runBlocking {
            val path = Paths.resolve(documentId ?: "/")

            val document = picker.pick(path, m.requestWrite)

            if (document !is FileDocument)
                throw FileNotFoundException("invalid path $documentId")

            ParcelFileDescriptor.open(document.file, ParcelFileDescriptor.parseMode(m))
        }
    }

    override fun deleteDocument(documentId: String?) {
        val documentPath = documentId ?: "/"

        runBlocking {
            val path = Paths.resolve(documentPath)

            if (path.relative == null)
                throw IllegalArgumentException("invalid path $documentId")

            val document = picker.pick(path, true)

            if (document !is FileDocument)
                throw FileNotFoundException("invalid path $documentId")

            document.file.deleteRecursively()
        }
    }

    override fun renameDocument(documentId: String?, displayName: String?): String {
        val name = displayName ?: ""

        if (!PatternFileName.matches(name))
            throw IllegalArgumentException(localized.getString(R.string.clod_file_name_invalid, name))

        return runBlocking {
            val path = Paths.resolve(documentId ?: "/")

            if (path.relative == null)
                throw IllegalArgumentException(localized.getString(R.string.clod_file_rename_failed, documentId.orEmpty()))

            val document = picker.pick(path, true)

            if (document !is FileDocument)
                throw IllegalArgumentException(localized.getString(R.string.clod_file_rename_failed, document.name))

            val parent = document.file.parentFile

            if (parent == null)
                throw IllegalArgumentException(localized.getString(R.string.clod_file_rename_failed, document.name))

            val target = parent.resolve(name)

            if (name != document.file.name && target.exists())
                throw IllegalArgumentException(localized.getString(R.string.clod_file_exists, name))

            if (!document.file.renameTo(target))
                throw IllegalArgumentException(localized.getString(R.string.clod_file_rename_failed, document.file.name))

            path.copy(relative = path.relative.dropLast(1) + name).toString()
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        return runBlocking {
            val doc = parentDocumentId ?: "/"
            val path = Paths.resolve(doc)
            val documents = query { picker.list(path) }

            MatrixCursor(resolveDocumentProjection(projection)).apply {
                documents.forEach {
                    newRow().applyDocument(it)
                        .add(D.COLUMN_DOCUMENT_ID, "$doc/${it.id}")
                }
            }
        }
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        return runBlocking {
            val doc = documentId ?: "/"
            val path = Paths.resolve(doc)
            val document = query { picker.pick(path, false) }

            MatrixCursor(resolveDocumentProjection(projection)).apply {
                newRow().applyDocument(document).add(D.COLUMN_DOCUMENT_ID, doc)
            }
        }
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val flags = Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD

        return MatrixCursor(projection ?: DEFAULT_ROOT_COLUMNS).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, DEFAULT_ROOT_ID)
                add(Root.COLUMN_FLAGS, flags)
                add(Root.COLUMN_ICON, R.drawable.ic_logo_service)
                add(Root.COLUMN_TITLE, context!!.getString(R.string.clash_meta_for_android))
                add(Root.COLUMN_SUMMARY, context!!.getString(R.string.profiles_and_providers))
                add(Root.COLUMN_DOCUMENT_ID, "/")
                add(Root.COLUMN_MIME_TYPES, "*/*")
            }
        }
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        if (parentDocumentId == null || documentId == null)
            return false

        return Paths.isChild(parentDocumentId, documentId)
    }

    private fun MatrixCursor.RowBuilder.applyDocument(document: Document): MatrixCursor.RowBuilder {
        var flags = 0

        document.flags.forEach {
            flags = when (it) {
                Flag.Writable -> flags or D.FLAG_SUPPORTS_WRITE
                Flag.Deletable -> flags or D.FLAG_SUPPORTS_DELETE
            }
        }

        add(D.COLUMN_DISPLAY_NAME, document.name)
        add(D.COLUMN_MIME_TYPE, document.mimeType)
        add(D.COLUMN_LAST_MODIFIED, document.updatedAt)
        add(D.COLUMN_SIZE, document.size)
        add(D.COLUMN_FLAGS, flags)

        return this
    }

    private suspend fun <T> query(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: FileNotFoundException) {
            throw IllegalStateException(e.message, e)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: SQLiteException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(e.toString(), e)
        }
    }

    private fun resolveDocumentProjection(projection: Array<out String>?): Array<out String> {
        return projection ?: DEFAULT_DOCUMENT_COLUMNS
    }

    private val String.requestWrite: Boolean
        get() {
            return contains("w", ignoreCase = true)
        }
}
