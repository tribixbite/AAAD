package com.legs.appsforaa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [VersionCompare].
 *
 * This decides whether the catalog shows "Update available", so a wrong answer is user-visible and
 * corrosive: a phantom badge teaches people to ignore the list. The cases below are all real
 * strings from the shipped catalog's publishers, not invented ones.
 *
 *   ./gradlew :app:testDebugUnitTest
 */
class VersionCompareTest {

    @Test
    fun `orders plain semantic versions`() {
        assertEquals(true, VersionCompare.isNewer("1.0.3", "1.0.4"))
        assertEquals(false, VersionCompare.isNewer("1.0.4", "1.0.3"))
        assertEquals(false, VersionCompare.isNewer("1.0.3", "1.0.3"))
    }

    @Test
    fun `treats missing trailing segments as zero`() {
        // Fermata publishes both "2.0" and "2.0.0"; neither is an update over the other.
        assertEquals(false, VersionCompare.isNewer("2.0", "2.0.0"))
        assertEquals(false, VersionCompare.isNewer("2.0.0", "2.0"))
        assertEquals(true, VersionCompare.isNewer("2.0", "2.0.1"))
    }

    @Test
    fun `compares segments numerically, not as text`() {
        // The classic: "10" sorts before "9" as a string.
        assertEquals(true, VersionCompare.isNewer("1.9.0", "1.10.0"))
        assertEquals(false, VersionCompare.isNewer("1.10.0", "1.9.0"))
    }

    @Test
    fun `strips a v prefix, as GitHub tags carry`() {
        // Nav2Contacts tags releases v1.0.3.
        assertEquals("1.0.3", VersionCompare.normalize("v1.0.3"))
        assertEquals(true, VersionCompare.isNewer("1.0.2", "v1.0.3"))
    }

    @Test
    fun `keeps the numeric prefix of a decorated version`() {
        // Performance Monitor ships "v0.88B".
        assertEquals("0.88", VersionCompare.normalize("v0.88B"))
        assertEquals(true, VersionCompare.isNewer("0.87", "v0.88B"))
    }

    @Test
    fun `returns null rather than guessing at an uncomparable version`() {
        // AA Browser tags "beta1.1"; CarStream's release is an untagged commit hash. Claiming an
        // update from either would be a phantom badge.
        assertNull(VersionCompare.isNewer("1.0", "beta1.1"))
        assertNull(VersionCompare.isNewer("1.0", "untagged-7666cf8b031e67be69d2"))
        assertNull(VersionCompare.isNewer(null, "1.0"))
        assertNull(VersionCompare.isNewer("1.0", ""))
    }

    @Test
    fun `normalize rejects strings with no leading number`() {
        assertNull(VersionCompare.normalize("beta1.1"))
        assertNull(VersionCompare.normalize("untagged-abc123"))
        assertNull(VersionCompare.normalize(null))
    }
}
