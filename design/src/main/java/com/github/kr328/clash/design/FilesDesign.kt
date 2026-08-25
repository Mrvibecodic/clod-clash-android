package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.kr328.clash.design.compose.screen.FilesAction
import com.github.kr328.clash.design.compose.screen.FilesScreen
import com.github.kr328.clash.design.compose.screen.FilesState
import com.github.kr328.clash.design.dialog.requestModelTextInput
import com.github.kr328.clash.design.model.File
import com.github.kr328.clash.design.util.ValidatorFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FilesDesign(context: Context) : Design<FilesDesign.Request>(context) {
    sealed interface Request {
        data class OpenFile(val file: File) : Request
        data class OpenDirectory(val file: File) : Request
        data class RenameFile(val file: File) : Request
        data class DeleteFile(val file: File) : Request
        data class ImportFile(val file: File?) : Request
        data class ExportFile(val file: File) : Request

        data object PopStack : Request
    }

    private var state by mutableStateOf(FilesState(currentTime = System.currentTimeMillis()))

    override val root: View = composeRoot {
        FilesScreen(state = state, onAction = ::onAction)
    }

    var configurationEditable: Boolean
        get() = state.configurationEditable
        set(value) {
            state = state.copy(configurationEditable = value)
        }

    private fun onAction(action: FilesAction) {
        when (action) {
            FilesAction.Back -> requests.trySend(Request.PopStack)
            FilesAction.New -> requests.trySend(Request.ImportFile(null))
            FilesAction.CloseMenu -> state = state.copy(menuFor = null)
            is FilesAction.More -> state = state.copy(menuFor = action.file)
            is FilesAction.Open -> {
                if (action.file.isDirectory) {
                    requests.trySend(Request.OpenDirectory(action.file))
                } else {
                    requests.trySend(Request.OpenFile(action.file))
                }
            }
            is FilesAction.Import -> pick(Request.ImportFile(action.file))
            is FilesAction.Export -> pick(Request.ExportFile(action.file))
            is FilesAction.Rename -> pick(Request.RenameFile(action.file))
            is FilesAction.Delete -> pick(Request.DeleteFile(action.file))
        }
    }

    private fun pick(request: Request) {
        state = state.copy(menuFor = null)

        requests.trySend(request)
    }

    suspend fun swapFiles(files: List<File>, currentInBaseDir: Boolean) {
        withContext(Dispatchers.Main) {
            state = state.copy(files = files, loaded = true, inBaseDir = currentInBaseDir)
        }
    }

    fun updateElapsed() {
        state = state.copy(currentTime = System.currentTimeMillis())
    }

    suspend fun requestFileName(name: String): String {
        return context.requestModelTextInput(
            initial = name,
            title = context.getText(R.string.file_name),
            hint = context.getText(R.string.file_name),
            error = context.getText(R.string.invalid_file_name),
            validator = ValidatorFileName,
        )
    }
}
