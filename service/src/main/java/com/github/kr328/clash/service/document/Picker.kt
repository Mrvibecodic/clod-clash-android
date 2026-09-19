package com.github.kr328.clash.service.document

import android.content.Context
import android.provider.DocumentsContract
import com.github.kr328.clash.common.util.PatternFileName
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import java.io.FileNotFoundException

class Picker(private val context: Context) {
    suspend fun list(path: Path): List<Document> {
        if (path.uuid == null) {
            return ImportedDao().queryAllUUIDs().map {
                pick(path.copy(uuid = it), false)
            }
        }

        if (path.scope == null) {
            return listOf(Path.Scope.Configuration, Path.Scope.Providers).map {
                pick(path.copy(scope = it), false)
            }
        }

        val parent = pick(path, false)

        if (parent !is FileDocument)
            return emptyList()

        return (parent.file.list() ?: emptyArray()).map {
            pick(path.copy(relative = (path.relative ?: emptyList()) + it), false)
        }
    }

    suspend fun pick(path: Path, writable: Boolean): Document {
        if (path.uuid == null) {
            return VirtualDocument(
                "",
                context.getString(R.string.clash_meta_for_android),
                DocumentsContract.Document.MIME_TYPE_DIR,
                0,
                0,
                emptySet(),
            )
        }

        val pending = PendingDao().queryByUUID(path.uuid)
        val imported = ImportedDao().queryByUUID(path.uuid)

        val name = pending?.name ?: imported?.name ?: throw FileNotFoundException("profile not found")
        val type = pending?.type ?: imported?.type ?: throw FileNotFoundException("profile not found")

        if (path.scope == Path.Scope.Configuration && path.relative != null)
            throw FileNotFoundException("invalid path")

        val storedDir = if (pending != null) {
            context.pendingDir.resolve(path.uuid.toString())
        } else {
            context.importedDir.resolve(path.uuid.toString())
        }

        if (writable) {
            if (path.scope == null || (path.scope == Path.Scope.Configuration && type != Profile.Type.File))
                throw IllegalArgumentException("invalid open mode")

            val fileName = path.relative?.lastOrNull()

            if (fileName != null && !PatternFileName.matches(fileName) &&
                !storedDir.resolve("providers").resolve(path.relative.joinToString(separator = "/")).exists()
            ) {
                throw IllegalArgumentException("invalid name $fileName")
            }

            if (pending == null)
                cloneToPending(imported ?: throw FileNotFoundException("profile not found"))
        }

        val profileDir = if (writable) context.pendingDir.resolve(path.uuid.toString()) else storedDir

        if (path.scope == null) {
            return VirtualDocument(
                id = path.uuid.toString(),
                name = name,
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                size = 0,
                updatedAt = 0,
                flags = emptySet(),
            )
        }

        if (path.relative == null) {
            if (path.scope == Path.Scope.Configuration) {
                val flags: Set<Flag> = if (type == Profile.Type.Url)
                    emptySet()
                else
                    setOf(Flag.Writable)

                return FileDocument(
                    file = profileDir.resolve("config.yaml"),
                    flags = flags,
                    idOverride = Paths.CONFIGURATION_ID,
                    nameOverride = context.getString(R.string.configuration_yaml)
                )
            } else {
                return FileDocument(
                    file = profileDir.resolve("providers"),
                    idOverride = Paths.PROVIDERS_ID,
                    nameOverride = context.getString(R.string.provider_files),
                    flags = emptySet()
                )
            }
        }

        return FileDocument(
            file = profileDir.resolve("providers").resolve(path.relative.joinToString(separator = "/")),
            flags = setOf(Flag.Writable, Flag.Deletable)
        )
    }

    private suspend fun cloneToPending(imported: Imported) {
        val source = context.importedDir.resolve(imported.uuid.toString())
        val target = context.pendingDir.resolve(imported.uuid.toString())

        target.deleteRecursively()
        source.copyRecursively(target)

        PendingDao().insert(
            Pending(
                imported.uuid,
                imported.name,
                imported.type,
                imported.source,
                imported.interval,
                imported.upload,
                imported.download,
                imported.total,
                imported.expire,
                secure = imported.secure,
                intervalManual = imported.intervalManual,
            )
        )
    }
}
