package com.kavach.caller

import com.kavach.caller.data.local.entity.BlockAction
import com.kavach.caller.data.repo.BlockRepository
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.domain.usecase.DecideCallActionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DecideCallActionUseCaseTest {

    private val blockRepository = mockk<BlockRepository>(relaxed = true)
    private val contactRepository = mockk<ContactRepository>(relaxed = true)
    private val useCase = DecideCallActionUseCase(blockRepository, contactRepository)

    @Test
    fun treatsAKnownNumberAsAContact() = runTest {
        coEvery { contactRepository.nameFor("+919876543210") } returns "Amit"
        coEvery { blockRepository.decide(any(), true) } returns
            BlockRepository.Decision(BlockAction.ALLOW, null)

        val decision = useCase("+919876543210")

        assertEquals(BlockAction.ALLOW, decision.action)
        coVerify { blockRepository.decide("+919876543210", true) }
    }

    @Test
    fun treatsAnUnknownNumberAsNotAContact() = runTest {
        coEvery { contactRepository.nameFor(any()) } returns null
        coEvery { blockRepository.decide(any(), false) } returns
            BlockRepository.Decision(BlockAction.BLOCK, null)

        val decision = useCase("+919812345678")

        assertEquals(BlockAction.BLOCK, decision.action)
        coVerify { blockRepository.decide("+919812345678", false) }
    }

    @Test
    fun aBlankNumberIsNeverTreatedAsAContact() = runTest {
        coEvery { blockRepository.decide(any(), false) } returns
            BlockRepository.Decision(BlockAction.ALLOW, null)

        useCase("")

        // Must not even attempt a contact lookup for a withheld number.
        coVerify(exactly = 0) { contactRepository.nameFor(any()) }
    }
}
