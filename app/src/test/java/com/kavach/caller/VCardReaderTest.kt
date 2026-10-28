package com.kavach.caller

import com.kavach.caller.domain.VCardReader
import com.kavach.caller.domain.toVCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading vCards that came from somewhere else.
 *
 * Every case here is something a real exporter emits, not something our own
 * writer produces — a parser tested only against its own output works once, on
 * files this app made.
 */
class VCardReaderTest {

    @Test
    fun `reads a simple card`() {
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Amit Gupta
            TEL;TYPE=CELL:+919876543210
            END:VCARD
            """.trimIndent(),
        )
        assertEquals(1, cards.size)
        assertEquals("Amit Gupta", cards[0].displayName)
        assertEquals("+919876543210", cards[0].numbers.single().first)
    }

    @Test
    fun `reads every card in a file`() {
        // Exporting a phonebook produces one file with hundreds of blocks.
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            FN:One
            TEL:+911111111111
            END:VCARD
            BEGIN:VCARD
            FN:Two
            TEL:+912222222222
            END:VCARD
            BEGIN:VCARD
            FN:Three
            TEL:+913333333333
            END:VCARD
            """.trimIndent(),
        )
        assertEquals(3, cards.size)
        assertEquals(listOf("One", "Two", "Three"), cards.map { it.displayName })
    }

    @Test
    fun `joins a folded line`() {
        // The space at the start of a continuation is a marker, not content.
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            FN:Amit
            NOTE:This is a long note that the writer wrapped onto
             a second line
            END:VCARD
            """.trimIndent(),
        )
        assertEquals("This is a long note that the writer wrapped ontoa second line", cards[0].notes)
    }

    @Test
    fun `unescapes an escaped comma so a name stays one name`() {
        // The failure this prevents: "Sharma, Amit" arriving as two contacts.
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            FN:Sharma\, Amit
            TEL:+919876543210
            END:VCARD
            """.trimIndent(),
        )
        assertEquals("Sharma, Amit", cards[0].displayName)
    }

    @Test
    fun `unescapes a newline in a note`() {
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            FN:A
            NOTE:line one\nline two
            END:VCARD
            """.trimIndent(),
        )
        assertEquals("line one\nline two", cards[0].notes)
    }

    @Test
    fun `handles CRLF line endings`() {
        // The format specifies CRLF. A stray \r inside a name is invisible until
        // it is not.
        val cards = VCardReader.parse(
            "BEGIN:VCARD\r\nFN:Amit\r\nTEL:+919876543210\r\nEND:VCARD\r\n",
        )
        assertEquals("Amit", cards[0].displayName)
        assertEquals("+919876543210", cards[0].numbers.single().first)
    }

    @Test
    fun `falls back to N when FN is missing`() {
        // A card without FN is legal; the name lives in N.
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            N:Sharma;Amit;;;
            TEL:+919876543210
            END:VCARD
            """.trimIndent(),
        )
        assertEquals("Amit Sharma", cards[0].displayName)
    }

    @Test
    fun `FN wins over N`() {
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            FN:Amit Gupta
            N:Gupta;Amit;;;
            END:VCARD
            """.trimIndent(),
        )
        assertEquals("Amit Gupta", cards[0].displayName)
    }

    @Test
    fun `strips a group prefix from the property name`() {
        // `item1.TEL:` is legal and common from Apple's exporter.
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            FN:Amit
            item1.TEL;TYPE=CELL:+919876543210
            END:VCARD
            """.trimIndent(),
        )
        assertEquals("+919876543210", cards[0].numbers.single().first)
    }

    @Test
    fun `reads parameters before the colon`() {
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            FN:Amit
            TEL;TYPE=CELL;PREF:+919876543210
            END:VCARD
            """.trimIndent(),
        )
        assertEquals("+919876543210", cards[0].numbers.single().first)
        assertEquals("Mobile", cards[0].numbers.single().second)
    }

    @Test
    fun `reads several numbers and keeps their labels`() {
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            FN:Amit
            TEL;TYPE=CELL:+919876543210
            TEL;TYPE=HOME:+911234567890
            TEL;TYPE=WORK:+911111111111
            END:VCARD
            """.trimIndent(),
        )
        assertEquals(3, cards[0].numbers.size)
        assertEquals(listOf("Mobile", "Home", "Work"), cards[0].numbers.map { it.second })
    }

    @Test
    fun `decodes quoted-printable, which is how a Hindi name arrives from an old phone`() {
        // Without this the name is present and unreadable.
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=E0=A4=85=E0=A4=AE=E0=A4=BF=E0=A4=A4
            TEL:+919876543210
            END:VCARD
            """.trimIndent(),
        )
        assertEquals("अमित", cards[0].displayName)
    }

    @Test
    fun `keeps a card that has a number but no name`() {
        // The number is the thing the user wanted; dropping it loses the contact.
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            TEL:+919876543210
            END:VCARD
            """.trimIndent(),
        )
        assertEquals(1, cards.size)
        assertEquals("+919876543210", cards[0].displayName)
    }

    @Test
    fun `reads company and title`() {
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            FN:Amit
            ORG:Sunrise Diagnostics
            TITLE:Engineer
            END:VCARD
            """.trimIndent(),
        )
        assertEquals("Sunrise Diagnostics", cards[0].company)
        assertEquals("Engineer", cards[0].jobTitle)
    }

    @Test
    fun `a malformed card does not lose the others`() {
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            FN:Good One
            TEL:+911111111111
            END:VCARD
            BEGIN:VCARD
            this card is nonsense
            END:VCARD
            BEGIN:VCARD
            FN:Good Two
            TEL:+912222222222
            END:VCARD
            """.trimIndent(),
        )
        assertEquals(2, cards.size)
        assertEquals(listOf("Good One", "Good Two"), cards.map { it.displayName })
    }

    @Test
    fun `an empty file yields nothing rather than throwing`() {
        assertTrue(VCardReader.parse("").isEmpty())
        assertTrue(VCardReader.parse("   \n\n  ").isEmpty())
    }

    @Test
    fun `normalises a number written with spaces and dashes`() {
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            FN:A
            TEL:+91 98765-43210
            END:VCARD
            """.trimIndent(),
        )
        assertEquals("+919876543210", cards[0].numbers.single().first)
    }

    @Test
    fun `drops a value too short to be a number`() {
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            FN:A
            TEL:123
            END:VCARD
            """.trimIndent(),
        )
        assertTrue(cards[0].numbers.isEmpty())
    }

    @Test
    fun `round-trips what we export`() {
        // The one case where our own writer is the input. It should work, and it
        // is not sufficient on its own — the cases above are the real test.
        val contact = com.kavach.caller.data.local.entity.ContactEntity(
            displayName = "Sharma, Amit",
            numbers = listOf(
                com.kavach.caller.data.local.entity.PhoneNumber(
                    label = "Mobile",
                    number = "+919876543210",
                ),
            ),
            company = "Sunrise",
            notes = "met at the clinic",
        )
        val cards = VCardReader.parse(contact.toVCard())
        assertEquals(1, cards.size)
        assertEquals("Sharma, Amit", cards[0].displayName)
        assertEquals("+919876543210", cards[0].numbers.single().first)
        assertEquals("Sunrise", cards[0].company)
        assertEquals("met at the clinic", cards[0].notes)
    }

    @Test
    fun `a card with neither name nor number is skipped`() {
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            ORG:Some Company
            END:VCARD
            """.trimIndent(),
        )
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `no company means null, not an empty string`() {
        val cards = VCardReader.parse(
            """
            BEGIN:VCARD
            FN:A
            TEL:+919876543210
            END:VCARD
            """.trimIndent(),
        )
        assertNull(cards[0].company)
    }
}
