package com.legs.appsforaa.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppTest {

    private fun app(
        state: ConversionState,
        uses: Set<String>? = null,
    ) = InstalledApp(
        packageName = "com.example.app",
        label = "Example",
        versionName = "1",
        installerPackage = null,
        apkPaths = listOf("/base.apk"),
        state = state,
        carCapabilities = uses?.let(AutomotiveDescriptor::Capabilities),
    )

    @Test
    fun `ordinary app gets a car copy even when Play attributed`() {
        val installed = app(ConversionState.ALREADY_ATTRIBUTED)

        assertEquals(ConversionAction.CAR_COPY, installed.conversionAction)
        assertFalse(installed.hasCarVersion)
        assertFalse(installed.blockedWhileDriving)
    }

    @Test
    fun `media only app gets a car compatible copy`() {
        val installed = app(
            ConversionState.ALREADY_ATTRIBUTED,
            setOf(AutomotiveDescriptor.USES_MEDIA),
        )

        assertEquals(ConversionAction.CAR_COPY, installed.conversionAction)
        assertFalse(installed.hasCarVersion)
        assertTrue(installed.blockedWhileDriving)
    }

    @Test
    fun `unattributed native car app is restaged without rewriting`() {
        val installed = app(
            ConversionState.CONVERTIBLE,
            setOf(AutomotiveDescriptor.USES_TEMPLATE),
        )

        assertEquals(ConversionAction.RESTAGE, installed.conversionAction)
        assertTrue(installed.hasCarVersion)
        assertFalse(installed.blockedWhileDriving)
    }

    @Test
    fun `Play attributed native car app needs no action`() {
        val installed = app(
            ConversionState.ALREADY_ATTRIBUTED,
            setOf(AutomotiveDescriptor.USES_PROJECTION),
        )

        assertTrue(installed.hasCarVersion)
        assertNull(installed.conversionAction)
    }
}
