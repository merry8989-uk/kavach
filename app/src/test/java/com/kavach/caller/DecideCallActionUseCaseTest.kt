package com.kavach.caller

import com.kavach.caller.data.local.entity.BlockAction
import com.kavach.caller.data.repo.BlockRepository
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.data.settings.AppSettings
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.domain.model.CallerIdentity
import com.kavach.caller.domain.usecase.DecideCallActionUseCase
import com.kavach.caller.domain.usecase.IdentifyCallerUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DecideCallActionUseCaseTest {

    private val blockRepository = mockk<BlockRepository>(relaxed = true)
    private val contactRepository = mockk<ContactRepository>(relaxed = true)
    private val identifyCaller = mockk<IdentifyCallerUseCase>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)

    private val useCase = DecideCallActionUseCase(
        blockRepository,
        contactRepository,
        identifyCaller,
        settings,
    )

    /**
     * With the shipped defaults the score never acts, so every pre-existing test
     * runs against the off state — which is itself the thing worth asserting.
     */
    private fun settingsWith(autoBlockScore: Int = 0, autoBlockAction: String = "silence") {
        every { settings.snapshot } returns AppSettings(
            autoBlockScore = autoBlockScore,
            autoBlockAction = autoBlockAction,
        )
    }

    private fun scoring(score: Int) {
        coEvery { identifyCaller(any()) } returns CallerIdentity(
            number = "+919812345678",
            spamScore = score,
        )
    }

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

    // ------------------------------------------- the score must not act alone --

    @Test
    fun `with the default settings the score never decides`() = runTest {
        // threshold 0 is what ships. A high score must change nothing.
        settingsWith(autoBlockScore = 0)
        scoring(99)
        coEvery { contactRepository.nameFor(any()) } returns null
        coEvery { blockRepository.decide(any(), false) } returns
            BlockRepository.Decision(BlockAction.ALLOW, null)

        val decision = useCase("+919812345678")

        assertEquals(BlockAction.ALLOW, decision.action)
    }

    @Test
    fun `a rule always wins over the score`() = runTest {
        // The explicit instruction outranks the crowd.
        settingsWith(autoBlockScore = 10, autoBlockAction = "block")
        scoring(99)
        coEvery { contactRepository.nameFor(any()) } returns null
        val rule = com.kavach.caller.data.local.entity.BlockRuleEntity(
            id = 1, pattern = "+919812345678",
            action = BlockAction.ALLOW,
        )
        coEvery { blockRepository.decide(any(), false) } returns
            BlockRepository.Decision(BlockAction.ALLOW, rule)

        assertEquals(BlockAction.ALLOW, useCase("+919812345678").action)
    }

    @Test
    fun `above the threshold the chosen action is applied`() = runTest {
        settingsWith(autoBlockScore = 50, autoBlockAction = "silence")
        scoring(70)
        coEvery { contactRepository.nameFor(any()) } returns null
        coEvery { blockRepository.decide(any(), false) } returns
            BlockRepository.Decision(BlockAction.ALLOW, null)

        assertEquals(BlockAction.SILENCE, useCase("+919812345678").action)
    }

    @Test
    fun `a saved contact is never acted on by the score`() = runTest {
        settingsWith(autoBlockScore = 10, autoBlockAction = "block")
        scoring(100)
        coEvery { contactRepository.nameFor(any()) } returns "Amma"
        coEvery { blockRepository.decide(any(), true) } returns
            BlockRepository.Decision(BlockAction.ALLOW, null)

        assertEquals(BlockAction.ALLOW, useCase("+919812345678").action)
    }
}
