package com.kavach.caller

import com.kavach.caller.ai.SpamScorer
import com.kavach.caller.data.remote.CallerLookupDataSource
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.domain.model.CallerIdentity
import com.kavach.caller.domain.model.RemoteIdentity
import com.kavach.caller.domain.usecase.IdentifyCallerUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyCallerUseCaseTest {

    private val contacts = mockk<ContactRepository>(relaxed = true)
    private val scorer = mockk<SpamScorer>(relaxed = true)
    private val remote = mockk<CallerLookupDataSource>(relaxed = true)
    private val useCase = IdentifyCallerUseCase(contacts, scorer, remote)

    private fun remoteIdentity(
        name: String?,
        confidence: Double,
        source: String,
        spamRisk: Int = 0,
    ) = RemoteIdentity(
        number = "+919876543210",
        displayName = name,
        numberType = null,
        businessCategory = null,
        source = source,
        confidence = confidence,
        verificationStatus = "UNVERIFIED",
        spamRisk = spamRisk,
        reports = 0,
        freshness = "FRESH",
    )

    @Test
    fun blankNumberResolvesToNothing() = runTest {
        val result = useCase("")
        assertEquals(CallerIdentity.Source.NONE, result.source)
        assertNull(result.name)
    }

    @Test
    fun aSavedContactBeatsTheServer() = runTest {
        coEvery { contacts.nameFor(any()) } returns "Amit"
        coEvery { remote.lookup(any()) } returns remoteIdentity("Wrong Name", 1.0, "COMMUNITY")

        val result = useCase("9876543210")

        assertEquals("Amit", result.name)
        assertEquals(CallerIdentity.Source.CONTACT, result.source)
        assertEquals(1f, result.confidence, 0.001f)
    }

    @Test
    fun aConfidentServerNameIsUsedWhenThereIsNoContact() = runTest {
        coEvery { contacts.nameFor(any()) } returns null
        coEvery { remote.lookup(any()) } returns remoteIdentity("Acme Bank", 0.95, "BUSINESS")

        val result = useCase("9876543210")

        assertEquals("Acme Bank", result.name)
        assertEquals(CallerIdentity.Source.BUSINESS, result.source)
    }

    @Test
    fun aWeakCommunityTagIsNotShownAsAName() = runTest {
        coEvery { contacts.nameFor(any()) } returns null
        coEvery { remote.lookup(any()) } returns remoteIdentity("Maybe Someone", 0.2, "COMMUNITY")
        coEvery { scorer.score(any(), isContact = false) } returns 30

        val result = useCase("9876543210")

        // Too weak to assert as a name — but the local risk signal must survive:
        // a server record with no spam data must not erase it.
        assertNull(result.name)
        assertEquals(30, result.spamScore)
    }

    @Test
    fun aHigherServerSpamScoreWins() = runTest {
        coEvery { contacts.nameFor(any()) } returns null
        coEvery { remote.lookup(any()) } returns remoteIdentity(null, 0.0, "COMMUNITY", spamRisk = 80)
        coEvery { scorer.score(any(), isContact = false) } returns 10

        val result = useCase("9876543210")

        assertEquals(80, result.spamScore)
    }

    @Test
    fun anUnknownNumberGetsNoInventedName() = runTest {
        coEvery { contacts.nameFor(any()) } returns null
        coEvery { remote.lookup(any()) } returns null
        coEvery { scorer.score(any(), isContact = false) } returns 0

        val result = useCase("9876543210")

        assertNull(result.name)
        assertTrue(result.source == CallerIdentity.Source.LOCAL_DB)
    }
}
