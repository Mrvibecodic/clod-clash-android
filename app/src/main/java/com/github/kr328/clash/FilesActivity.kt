@file:Suppress("BlockingMethodInNonBlockingContext")

package com.github.kr328.clash

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import com.github.kr328.clash.common.util.grantPermissions
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.design.FilesDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.remote.FilesClient
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.displayName
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.util.*
import java.util.concurrent.TimeUnit

class FilesActivity : BaseActivity<FilesDesign>() {
    private var stack: Stack<String>? = null
    private var namingTarget: Bundle? = null

    override suspend fun main() {
        val uuid = intent.uuid ?: return finish()
        val profile = withProfile { queryByUUID(uuid) } ?: return finish()
        val root = uuid.toString()

        val design = FilesDesign(this)
        val client = FilesClient(this)
        val stack = Stack<String>()

        restored?.getStringArrayList("stack")?.forEach { stack.push(it) }

        this.stack = stack

        design.configurationEditable = profile.type == Profile.Type.File

        // Экран, уже видимый к этому чтению, первое ActivityStart не перечитывает;
        // неудачное чтение там повторяется.
        val visible = activityStarted
        var fresh = design.refresh(client, stack, root) && visible

        setContentDesign(design)

        restored?.getBundle(KEY_NAMING)?.let { target ->
            val typed = target.getString(KEY_TEXT).orEmpty()
            val file = target.getString(KEY_FILE)

            try {
                if (file != null) {
                    design.renameFile(client, file, target.getString(KEY_NAME).orEmpty(), typed)
                } else {
                    val folder = target.getString(KEY_FOLDER)
                    val uri = BundleCompat.getParcelable(target, KEY_URI, Uri::class.java)

                    if (folder != null && uri != null) design.importFile(client, folder, uri, typed, restored = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                design.showExceptionToast(e)
            }

            design.refresh(client, stack, root)
        }

        val ticker = ticker(TimeUnit.MINUTES.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> {
                            if (!fresh) design.refresh(client, stack, root)

                            fresh = false
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    try {
                        when (it) {
                            FilesDesign.Request.PopStack -> {
                                if (stack.empty()) {
                                    finish()
                                } else {
                                    stack.pop()
                                }
                            }
                            FilesDesign.Request.Refresh -> Unit
                            is FilesDesign.Request.OpenDirectory -> {
                                stack.push(it.file.id)
                            }
                            is FilesDesign.Request.OpenFile -> {
                                startActivityForResult(
                                    ActivityResultContracts.StartActivityForResult(),
                                    Intent(Intent.ACTION_VIEW).setDataAndType(
                                        client.buildDocumentUri(it.file.id),
                                        "text/plain"
                                    ).grantPermissions(write = false)
                                )
                            }
                            is FilesDesign.Request.DeleteFile -> {
                                client.deleteDocument(it.file.id)
                            }
                            is FilesDesign.Request.RenameFile -> {
                                design.renameFile(client, it.file.id, it.file.name)
                            }
                            is FilesDesign.Request.ImportFile -> {
                                val uri: Uri? = startActivityForResult(
                                    ActivityResultContracts.GetContent(),
                                    "*/*"
                                )

                                if (uri != null) {
                                    if (it.file == null) {
                                        design.importFile(client, stack.last(), uri, uri.displayName(contentResolver) ?: getString(R.string.file))
                                    } else {
                                        client.copyDocument(it.file!!.id, uri)
                                    }
                                }
                            }
                            is FilesDesign.Request.ExportFile -> {
                                val uri: Uri? = startActivityForResult(
                                    ActivityResultContracts.CreateDocument("text/plain"),
                                    it.file.name
                                )

                                if (uri != null) {
                                    client.copyDocument(uri, it.file.id)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        design.showExceptionToast(e)
                    }

                    design.refresh(client, stack, root)
                }
                if (activityStarted) {
                    ticker.onReceive {
                        design.updateElapsed()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        stack?.let { outState.putStringArrayList("stack", ArrayList(it)) }

        val text = design?.namingText ?: return

        namingTarget?.let { outState.putBundle(KEY_NAMING, Bundle(it).apply { putString(KEY_TEXT, text) }) }
    }

    override fun onBackPressed() {
        design?.requests?.trySend(FilesDesign.Request.PopStack)
    }

    private suspend fun FilesDesign.renameFile(client: FilesClient, id: String, name: String, typed: String? = null) {
        val newName = askName(bundleOf(KEY_FILE to id, KEY_NAME to name), typed ?: name, typed != null)

        if (newName != null && newName != name) {
            client.renameDocument(id, newName)
        }
    }

    private suspend fun FilesDesign.importFile(client: FilesClient, folder: String, uri: Uri, name: String, restored: Boolean = false) {
        val newName = askName(bundleOf(KEY_FOLDER to folder, KEY_URI to uri), name, restored)

        if (newName != null) {
            client.importDocument(folder, uri, newName)
        }
    }

    private suspend fun FilesDesign.askName(target: Bundle, name: String, restored: Boolean): String? {
        namingTarget = target

        return try {
            requestFileName(name, restored)
        } finally {
            namingTarget = null
        }
    }

    private suspend fun FilesDesign.refresh(client: FilesClient, stack: Stack<String>, root: String): Boolean =
        try {
            fetch(client, stack, root)

            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(e.message ?: e.toString())

            false
        }

    private suspend fun FilesDesign.fetch(client: FilesClient, stack: Stack<String>, root: String) {
        val documentId = stack.lastOrNull() ?: root
        val files = if (stack.empty()) {
            val list = client.list(documentId)
            val config = list.firstOrNull { it.id.endsWith("config.yaml") }

            if (config == null || config.size > 0) list else listOf(config)
        } else {
            client.list(documentId)
        }

        swapFiles(files, stack.empty())
    }

    private companion object {
        const val KEY_NAMING = "naming"
        const val KEY_TEXT = "text"
        const val KEY_FILE = "file"
        const val KEY_NAME = "name"
        const val KEY_FOLDER = "folder"
        const val KEY_URI = "uri"
    }
}
