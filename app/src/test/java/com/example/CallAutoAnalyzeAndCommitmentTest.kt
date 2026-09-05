package com.example

import com.example.data.AutoAnalyzeMode
import com.example.data.CallDirection
import com.example.data.CallMetadataParser
import com.example.data.CommitmentExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAutoAnalyzeAndCommitmentTest {

    @Test
    fun testIsUnknownNumber() {
        // Pure phone numbers / unknown caller patterns
        assertTrue(CallMetadataParser.isUnknownNumber("+919876543210.mp3"))
        assertTrue(CallMetadataParser.isUnknownNumber("Call_Recording_+14155552671_20240307.mp3"))
        assertTrue(CallMetadataParser.isUnknownNumber("9876543210_incoming.m4a"))
        assertTrue(CallMetadataParser.isUnknownNumber("Unknown_240307_1234.mp3"))
        assertTrue(CallMetadataParser.isUnknownNumber("Private Number_rec.m4a"))

        // Named contacts
        assertFalse(CallMetadataParser.isUnknownNumber("Mom_20240307_120000.m4a"))
        assertFalse(CallMetadataParser.isUnknownNumber("Boss_Office_Incoming.mp3"))
        assertFalse(CallMetadataParser.isUnknownNumber("Dr_Smith_Consultation.wav"))
    }

    @Test
    fun testMatchesAutoAnalyzeRuleAllAndManual() {
        val unknownCall = "+919876543210.mp3"
        val knownCall = "Mom_Calling.m4a"

        // Mode: ALL
        assertTrue(CallMetadataParser.matchesAutoAnalyzeRule(unknownCall, AutoAnalyzeMode.ALL, emptySet()))
        assertTrue(CallMetadataParser.matchesAutoAnalyzeRule(knownCall, AutoAnalyzeMode.ALL, emptySet()))

        // Mode: MANUAL_ONLY
        assertFalse(CallMetadataParser.matchesAutoAnalyzeRule(unknownCall, AutoAnalyzeMode.MANUAL_ONLY, emptySet()))
        assertFalse(CallMetadataParser.matchesAutoAnalyzeRule(knownCall, AutoAnalyzeMode.MANUAL_ONLY, emptySet()))
    }

    @Test
    fun testMatchesAutoAnalyzeRuleUnknownOnly() {
        val unknownCall = "+14155552671_incoming.mp3"
        val knownCall = "Client_Acme_Corp.m4a"

        assertTrue(CallMetadataParser.matchesAutoAnalyzeRule(unknownCall, AutoAnalyzeMode.UNKNOWN_ONLY, emptySet()))
        assertFalse(CallMetadataParser.matchesAutoAnalyzeRule(knownCall, AutoAnalyzeMode.UNKNOWN_ONLY, emptySet()))
    }

    @Test
    fun testMatchesAutoAnalyzeRuleSpecificContacts() {
        val targets = setOf("Boss", "+919876543210", "Dr. Smith")

        assertTrue(CallMetadataParser.matchesAutoAnalyzeRule("Call_Boss_20240307.mp3", AutoAnalyzeMode.SPECIFIC_CONTACTS, targets))
        assertTrue(CallMetadataParser.matchesAutoAnalyzeRule("Rec_9876543210_in.m4a", AutoAnalyzeMode.SPECIFIC_CONTACTS, targets))
        assertTrue(CallMetadataParser.matchesAutoAnalyzeRule("Dr_Smith_Appointment.wav", AutoAnalyzeMode.SPECIFIC_CONTACTS, targets))

        // Non-matching contacts
        assertFalse(CallMetadataParser.matchesAutoAnalyzeRule("Mom_20240307.m4a", AutoAnalyzeMode.SPECIFIC_CONTACTS, targets))
        assertFalse(CallMetadataParser.matchesAutoAnalyzeRule("+15550001122_Call.mp3", AutoAnalyzeMode.SPECIFIC_CONTACTS, targets))
    }

    @Test
    fun testCommitmentExtractorActionItems() {
        val summary = """
            ## 📋 Summary
            Discussed the upcoming Q3 project launch and timeline.

            ## ✅ Action Items
            • Send contract proposal by Friday afternoon
            • Review architecture diagrams with technical lead
            • Schedule demo call with the product team

            ## 📅 Dates & Times
            • Friday 4:00 PM: Proposal deadline
            • Monday 10:00 AM: Team sync
        """.trimIndent()

        val actionItems = CommitmentExtractor.extractActionItems(summary)
        assertEquals(3, actionItems.size)
        assertEquals("Send contract proposal by Friday afternoon", actionItems[0])
        assertEquals("Review architecture diagrams with technical lead", actionItems[1])
        assertEquals("Schedule demo call with the product team", actionItems[2])
    }

    @Test
    fun testCommitmentExtractorDates() {
        val summary = """
            ## Summary
            Quick sync on delivery schedule.

            ## 📅 Dates & Deadlines
            1. Friday 4:00 PM: Proposal deadline
            2. Monday Oct 15th at 10:00 AM: Sync meeting

            ## Action Items
            - Call vendor
        """.trimIndent()

        val dates = CommitmentExtractor.extractDates(summary)
        assertEquals(2, dates.size)
        assertEquals("Friday 4:00 PM: Proposal deadline", dates[0])
        assertEquals("Monday Oct 15th at 10:00 AM: Sync meeting", dates[1])
    }

    @Test
    fun testCommitmentExtractorEmptyWhenNone() {
        val summary = """
            ## Summary
            Casual catchup, no business discussed.

            ## Action Items
            None

            ## Dates
            None
        """.trimIndent()

        val actionItems = CommitmentExtractor.extractActionItems(summary)
        val dates = CommitmentExtractor.extractDates(summary)

        assertTrue(actionItems.isEmpty())
        assertTrue(dates.isEmpty())
    }

    @Test
    fun testCallMetadataParserDirectionsAndDuration() {
        val incoming = CallMetadataParser.parse("Call_Incoming_+919876543210_20240307.mp3")
        assertEquals(CallDirection.INCOMING, incoming.direction)

        val outgoing = CallMetadataParser.parse("Call_Outgoing_Boss_20240307.mp3")
        assertEquals(CallDirection.OUTGOING, outgoing.direction)

        assertEquals("00:00", CallMetadataParser.formatDuration(0))
        assertEquals("01:05", CallMetadataParser.formatDuration(65000))
        assertEquals("10:30", CallMetadataParser.formatDuration(630000))
    }
}
