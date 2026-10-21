package com.kavach.caller

import com.kavach.caller.data.update.UpdateChecker
import com.kavach.caller.data.update.UpdateInfo
import com.kavach.caller.domain.usecase.CheckForUpdatesUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckForUpdatesUseCaseTest {

    private val checker = mockk<UpdateChecker>()
    private val useCase = CheckForUpdatesUseCase(checker)

    private fun info(version: String) = UpdateInfo(
        versionName = version,
        notes = "notes",
        apkUrl = "https://github.com/merry8989-uk/kavach/releases/download/v$version/kavach-$version.apk",
        htmlUrl = "https://github.com/merry8989-uk/kavach/releases/tag/v$version",
        publishedAt = "2026-01-01T00:00:00Z",
    )

    @Test
    fun reportsAvailableWhenRemoteIsNewer() = runTest {
        coEvery { checker.latest() } returns info("0.7.0")
        val result = useCase("0.6.0")
        assertTrue(result is CheckForUpdatesUseCase.Result.Available)
        assertEquals("0.7.0", (result as CheckForUpdatesUseCase.Result.Available).info.versionName)
    }

    @Test
    fun reportsUpToDateWhenSameVersion() = runTest {
        coEvery { checker.latest() } returns info("0.6.0")
        assertEquals(CheckForUpdatesUseCase.Result.UpToDate, useCase("0.6.0"))
    }

    @Test
    fun reportsUpToDateWhenRemoteIsOlder() = runTest {
        coEvery { checker.latest() } returns info("0.5.0")
        assertEquals(CheckForUpdatesUseCase.Result.UpToDate, useCase("0.6.0"))
    }

    @Test
    fun networkFailureIsUnreachableNotUpToDate() = runTest {
        coEvery { checker.latest() } returns null
        assertEquals(CheckForUpdatesUseCase.Result.Unreachable, useCase("0.6.0"))
    }
}
