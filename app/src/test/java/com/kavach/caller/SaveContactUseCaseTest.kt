package com.kavach.caller

import com.kavach.caller.data.local.entity.PhoneNumber
import com.kavach.caller.data.repo.ContactDraft
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.domain.usecase.SaveContactUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveContactUseCaseTest {

    private val repository = mockk<ContactRepository>(relaxed = true)
    private val useCase = SaveContactUseCase(repository)

    @Test
    fun rejectsDraftWithNoNameAndNoNumber() = runTest {
        val result = useCase(ContactDraft(name = "", numbers = listOf(PhoneNumber("Mobile", ""))))
        assertEquals(SaveContactUseCase.Result.NeedsNameOrNumber, result)
    }

    @Test
    fun rejectsAnObviouslyInvalidNumber() = runTest {
        val result = useCase(
            ContactDraft(name = "Amit", numbers = listOf(PhoneNumber("Mobile", "12"))),
        )
        assertTrue(result is SaveContactUseCase.Result.InvalidNumber)
        assertEquals("12", (result as SaveContactUseCase.Result.InvalidNumber).number)
    }

    @Test
    fun savesAValidDraft() = runTest {
        coEvery { repository.saveDraft(any()) } returns 42L

        val result = useCase(
            ContactDraft(name = "Amit", numbers = listOf(PhoneNumber("Mobile", "9876543210"))),
        )
        assertEquals(SaveContactUseCase.Result.Saved(42L), result)
    }

    @Test
    fun aNumberAloneIsEnough() = runTest {
        coEvery { repository.saveDraft(any()) } returns 7L

        val result = useCase(
            ContactDraft(name = "", numbers = listOf(PhoneNumber("Mobile", "9876543210"))),
        )
        assertEquals(SaveContactUseCase.Result.Saved(7L), result)
    }
}
