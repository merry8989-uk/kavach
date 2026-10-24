package com.kavach.caller

import com.kavach.caller.data.local.entity.ContactEntity
import com.kavach.caller.data.local.entity.PhoneNumber
import com.kavach.caller.domain.toVCard
import com.kavach.caller.domain.vcardEscape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sharing a contact as a vCard.
 *
 * The escaping is the part worth testing. A semicolon or a comma in a name is
 * ordinary, and an unescaped one silently splits the field — so a contact called
 * "Sharma, Amit" arrives as two contacts, or as one called "Sharma". Nothing
 * errors; the receiving app simply produces something wrong.
 */
class VCardTest {

    private fun contact(
        name: String = "Amit Gupta",
        numbers: List<PhoneNumber> = listOf(PhoneNumber("mobile", "+919876543210")),
        company: String? = null,
        notes: String? = null,
    ) = ContactEntity(
        displayName = name,
        numbers = numbers,
        company = company,
        notes = notes,
    )

    @Test
    fun `a card is well formed`() {
        val card = contact().toVCard()
        assertTrue(card.startsWith("BEGIN:VCARD"))
        assertTrue(card.trimEnd().endsWith("END:VCARD"))
        assertTrue(card.contains("VERSION:3.0"))
    }

    @Test
    fun `the name appears as both FN and N`() {
        val card = contact().toVCard()
        assertTrue(card.contains("FN:Amit Gupta"))
        // N is structured; we only hold one name, so it goes in the family slot,
        // which is what importers fall back to.
        assertTrue(card.contains("N:Amit Gupta;;;;"))
    }

    @Test
    fun `a comma in a name does not split the field`() {
        // The failure this prevents: "Sharma, Amit" arriving as two contacts.
        val card = contact(name = "Sharma, Amit").toVCard()
        assertTrue("the comma must be escaped: $card", card.contains("FN:Sharma\\, Amit"))
        assertFalse(card.contains("FN:Sharma, Amit"))
    }

    @Test
    fun `a semicolon in a name does not split the structured field`() {
        val card = contact(name = "A;B").toVCard()
        assertTrue(card.contains("FN:A\\;B"))
    }

    @Test
    fun `a backslash is escaped before the separators`() {
        // Order matters: escaping the backslash after the others would
        // double-escape the ones just added.
        assertEquals("a\\\\b\\,c", vcardEscape("a\\b,c"))
    }

    @Test
    fun `a newline becomes the vCard escape, not a broken line`() {
        val card = contact(notes = "line one\nline two").toVCard()
        assertTrue(card.contains("NOTE:line one\\nline two"))
        // A raw newline would end the field and produce a malformed card.
        assertFalse(card.contains("NOTE:line one\nline two"))
    }

    @Test
    fun `every number is written with a type`() {
        val card = contact(
            numbers = listOf(
                PhoneNumber("mobile", "+919876543210"),
                PhoneNumber("work", "+911140000000"),
                PhoneNumber("home", "+911123456789"),
            ),
        ).toVCard()
        assertTrue(card.contains("TEL;TYPE=CELL:+919876543210"))
        assertTrue(card.contains("TEL;TYPE=WORK:+911140000000"))
        assertTrue(card.contains("TEL;TYPE=HOME:+911123456789"))
    }

    @Test
    fun `an unrecognised label still gets a type`() {
        // A bare TEL: is valid but some importers drop it; VOICE is the safe
        // fallback for anything we do not recognise.
        val card = contact(numbers = listOf(PhoneNumber("pager", "+911234567890"))).toVCard()
        assertTrue(card.contains("TEL;TYPE=VOICE:+911234567890"))
    }

    @Test
    fun `optional fields are omitted rather than written empty`() {
        val card = contact().toVCard()
        assertFalse("an empty ORG is noise", card.contains("ORG:"))
        assertFalse(card.contains("NOTE:"))
    }

    @Test
    fun `company and notes appear when present`() {
        val card = contact(company = "Sunrise, Diagnostics", notes = "met at the clinic").toVCard()
        assertTrue(card.contains("ORG:Sunrise\\, Diagnostics"))
        assertTrue(card.contains("NOTE:met at the clinic"))
    }

    @Test
    fun `a contact with no numbers is still a valid card`() {
        val card = contact(numbers = emptyList()).toVCard()
        assertTrue(card.contains("BEGIN:VCARD"))
        assertTrue(card.contains("END:VCARD"))
    }
}
