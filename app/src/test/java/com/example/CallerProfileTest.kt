package com.example

import com.example.data.CallerProfileBuilder
import com.example.data.Recording
import com.example.data.SimpleEncryption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerProfileTest {

    private fun createRecording(
        id: Int,
        title: String,
        summary: String,
        timestamp: Long = System.currentTimeMillis()
    ): Recording {
        return Recording(
            id = id,
            title = title,
            contentEncrypted = SimpleEncryption.encrypt("Test audio transcription"),
            summaryEncrypted = SimpleEncryption.encrypt(summary),
            timestamp = timestamp
        )
    }

    @Test
    fun testGroupingByPhoneNumber() {
        val now = 1700000000000L
        val rec1 = createRecording(1, "+919876543210_incoming.mp3", "Summary 1", now)
        val rec2 = createRecording(2, "Call_+91-98765-43210_20240307.m4a", "Summary 2", now + 1000)

        val profiles = CallerProfileBuilder.buildProfiles(
            recordings = listOf(rec1, rec2),
            completedActionItemKeys = emptySet(),
            autoAnalyzeTargets = emptySet()
        )

        assertEquals(1, profiles.size)
        val profile = profiles.first()
        assertEquals(2, profile.totalCalls)
        assertTrue(profile.callerKey.contains("9876543210"))
    }

    @Test
    fun testGroupingByNamedContact() {
        val now = 1700000000000L
        val rec1 = createRecording(1, "Boss_20240307_120000.mp3", "Call 1", now)
        val rec2 = createRecording(2, "Boss_20240308_150000.m4a", "Call 2", now + 5000)
        val rec3 = createRecording(3, "Mom_20240307_160000.mp3", "Call 3", now + 10000)

        val profiles = CallerProfileBuilder.buildProfiles(
            recordings = listOf(rec1, rec2, rec3),
            completedActionItemKeys = emptySet(),
            autoAnalyzeTargets = emptySet()
        )

        assertEquals(2, profiles.size)
        val bossProfile = profiles.firstOrNull { it.displayName.contains("Boss", ignoreCase = true) }
        val momProfile = profiles.firstOrNull { it.displayName.contains("Mom", ignoreCase = true) }

        assertEquals(2, bossProfile?.totalCalls)
        assertEquals(1, momProfile?.totalCalls)
    }

    @Test
    fun testActionItemsExtractionAndCompletionMapping() {
        val summary = """
            Call discussing project deliverables.
            Action items:
            - Send the signed contract to the client
            - Schedule the demo for Friday
        """.trimIndent()

        val rec = createRecording(1, "Client_Project.mp3", summary)
        val item1 = "Send the signed contract to the client"
        val completedKey = "1_${item1.hashCode()}"

        val profiles = CallerProfileBuilder.buildProfiles(
            recordings = listOf(rec),
            completedActionItemKeys = setOf(completedKey),
            autoAnalyzeTargets = emptySet()
        )

        assertEquals(1, profiles.size)
        val profile = profiles.first()
        assertEquals(2, profile.actionItems.size)
        assertEquals(1, profile.pendingActionItemsCount)

        val completedItem = profile.actionItems.firstOrNull { it.text == item1 }
        val pendingItem = profile.actionItems.firstOrNull { it.text != item1 }

        assertTrue(completedItem?.isCompleted == true)
        assertFalse(pendingItem?.isCompleted == true)
    }

    @Test
    fun testDatesAndDeadlinesExtraction() {
        val summary = """
            Meeting summary:
            Dates & Deadlines:
            - March 15th at 3 PM
            - Tomorrow at 5 PM
        """.trimIndent()

        val rec = createRecording(1, "Manager_Review.mp3", summary)

        val profiles = CallerProfileBuilder.buildProfiles(
            recordings = listOf(rec),
            completedActionItemKeys = emptySet(),
            autoAnalyzeTargets = emptySet()
        )

        assertEquals(1, profiles.size)
        val profile = profiles.first()
        assertEquals(2, profile.datesAndDeadlines.size)
        assertTrue(profile.datesAndDeadlines.any { it.contains("March 15th") })
    }

    @Test
    fun testAutoAnalyzeTargetMatching() {
        val rec1 = createRecording(1, "Boss_Office.mp3", "Meeting notes")
        val rec2 = createRecording(2, "Unknown_+919999988888.mp3", "Sales pitch")

        val targets = setOf("Boss", "+919999988888")

        val profiles = CallerProfileBuilder.buildProfiles(
            recordings = listOf(rec1, rec2),
            completedActionItemKeys = emptySet(),
            autoAnalyzeTargets = targets
        )

        assertEquals(2, profiles.size)
        for (p in profiles) {
            assertTrue("Expected ${p.displayName} to be auto-analyze target", p.isAutoAnalyzeTarget)
        }
    }

    @Test
    fun testSortingPendingTasksFirst() {
        val now = 1700000000000L

        // rec1 has no action items, but newer timestamp
        val rec1 = createRecording(1, "Friend_20240307.mp3", "Just catching up, no tasks.", now + 50000)

        // rec2 has pending action item, but older timestamp
        val summaryWithTask = """
            Discussion on invoice.
            Action items:
            - Pay the pending electricity bill
        """.trimIndent()
        val rec2 = createRecording(2, "Vendor_20240307.mp3", summaryWithTask, now)

        val profiles = CallerProfileBuilder.buildProfiles(
            recordings = listOf(rec1, rec2),
            completedActionItemKeys = emptySet(),
            autoAnalyzeTargets = emptySet()
        )

        assertEquals(2, profiles.size)
        // Vendor should come first because it has pending tasks
        assertEquals("Vendor", profiles[0].displayName.trim())
        assertEquals(1, profiles[0].pendingActionItemsCount)
        assertEquals("Friend", profiles[1].displayName.trim())
    }
}
