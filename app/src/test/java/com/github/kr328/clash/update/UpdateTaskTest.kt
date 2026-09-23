package com.github.kr328.clash.update

import com.github.kr328.clash.update.UpdateTask.InstallOutcome
import com.github.kr328.clash.update.UpdateTask.State
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class UpdateTaskTest {
    private val available = Updater.Available(
        UpdateManifest(version = "1.0", versionCode = 2),
        UpdateManifest.Platform(url = "https://example.org/a.apk", sha256 = "00"),
    )

    private val ready = State.Ready(available, File("update.apk"))

    @Test
    fun installedClearsTheOffer() {
        assertEquals(State.Idle, UpdateTask.afterInstall(ready, InstallOutcome.Installed))
    }

    @Test
    fun returnedInstallKeepsTheOffer() {
        assertEquals(State.Available(available), UpdateTask.afterInstall(ready, InstallOutcome.Returned))
    }

    @Test
    fun refusedInstallIsReported() {
        assertEquals(
            State.Failed(null, "INSTALL_FAILED"),
            UpdateTask.afterInstall(ready, InstallOutcome.Refused("INSTALL_FAILED")),
        )
    }

    @Test
    fun installResultOutsideReadyIsIgnored() {
        val states = listOf(State.Idle, State.Available(available), State.Downloading(available, 0.5f))

        for (state in states) {
            for (outcome in listOf(InstallOutcome.Installed, InstallOutcome.Returned, InstallOutcome.Refused(null))) {
                assertEquals(state, UpdateTask.afterInstall(state, outcome))
            }
        }
    }

    @Test
    fun cancelledDownloadReturnsToTheOffer() {
        assertEquals(State.Available(available), UpdateTask.afterCancel(State.Downloading(available, 0.3f)))
    }

    @Test
    fun cancelledCheckGoesIdle() {
        assertEquals(State.Idle, UpdateTask.afterCancel(State.Checking(manual = true)))
    }

    @Test
    fun cancelLeavesOtherStatesAlone() {
        for (state in listOf(State.Idle, State.Available(available), ready)) {
            assertEquals(state, UpdateTask.afterCancel(state))
        }
    }
}
