package com.example

import com.example.network.VersionComparator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {

    @Test
    fun testVersionComparatorNewerMajor() {
        assertTrue(VersionComparator.isNewer("1.0", "v2.0"))
        assertTrue(VersionComparator.isNewer("1.0.0", "2.0.0"))
    }

    @Test
    fun testVersionComparatorNewerMinor() {
        assertTrue(VersionComparator.isNewer("1.0", "1.1"))
        assertTrue(VersionComparator.isNewer("v1.0", "v1.1"))
        assertTrue(VersionComparator.isNewer("1.0.0", "v1.1.0"))
    }

    @Test
    fun testVersionComparatorNewerPatch() {
        assertTrue(VersionComparator.isNewer("1.0.0", "1.0.1"))
        assertTrue(VersionComparator.isNewer("v1.0.0", "v1.0.1"))
    }

    @Test
    fun testVersionComparatorSameVersion() {
        assertFalse(VersionComparator.isNewer("1.0", "1.0"))
        assertFalse(VersionComparator.isNewer("v1.0", "1.0"))
        assertFalse(VersionComparator.isNewer("1.0.0", "v1.0.0"))
    }

    @Test
    fun testVersionComparatorOlderVersion() {
        assertFalse(VersionComparator.isNewer("1.1", "1.0"))
        assertFalse(VersionComparator.isNewer("2.0.0", "v1.9.9"))
        assertFalse(VersionComparator.isNewer("1.0.1", "1.0.0"))
    }
}
