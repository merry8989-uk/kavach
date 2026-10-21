package com.kavach.caller.domain.usecase

import com.kavach.caller.data.update.UpdateChecker
import com.kavach.caller.data.update.UpdateInfo
import javax.inject.Inject

/**
 * Compare the installed version against the latest published release.
 *
 * A network failure is reported as [Result.Unreachable] — never as
 * "up to date", which would be a lie.
 */
class CheckForUpdatesUseCase @Inject constructor(
    private val checker: UpdateChecker,
) {
    sealed interface Result {
        data object UpToDate : Result
        data class Available(val info: UpdateInfo) : Result
        data object Unreachable : Result
    }

    suspend operator fun invoke(currentVersion: String): Result {
        val info = checker.latest() ?: return Result.Unreachable
        return if (UpdateChecker.isNewer(info.versionName, currentVersion)) {
            Result.Available(info)
        } else {
            Result.UpToDate
        }
    }
}
