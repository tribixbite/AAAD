package com.legs.appsforaa.data

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppTest {

    private fun app(
        state: ConversionState,
        uses: Set<String>? = null,
        appCategory: Int = ApplicationInfo.CATEGORY_UNDEFINED,
    ) = InstalledApp(
        packageName = "com.example.app",
        label = "Example",
        versionName = "1",
        installerPackage = null,
        apkPaths = listOf("/base.apk"),
        state = state,
        carCapabilities = uses?.let { AutomotiveDescriptor.Capabilities(it, appCategory) },
    )

    @Test
    fun `ordinary app gets a car copy even with a trusted install`() {
        val installed = app(ConversionState.TRUSTED_INSTALL)

        assertEquals(ConversionAction.CAR_COPY, installed.conversionAction)
        assertFalse(installed.hasCarVersion)
        assertFalse(installed.blockedWhileDriving)
    }

    @Test
    fun `media only app gets a car compatible copy`() {
        val installed = app(
            ConversionState.TRUSTED_INSTALL,
            setOf(AutomotiveDescriptor.USES_MEDIA),
        )

        assertEquals(ConversionAction.CAR_COPY, installed.conversionAction)
        assertFalse(installed.hasCarVersion)
        assertTrue(installed.blockedWhileDriving)
    }

    @Test
    fun `sideloaded template app gets a discoverable parked copy`() {
        val installed = app(
            ConversionState.CONVERTIBLE,
            setOf(AutomotiveDescriptor.USES_TEMPLATE),
        )

        assertEquals(ConversionAction.CAR_COPY, installed.conversionAction)
        assertTrue(installed.hasCarVersion)
        assertFalse(installed.blockedWhileDriving)
    }

    @Test
    fun `sideloaded legacy projection app keeps lightweight restage`() {
        val installed = app(
            ConversionState.CONVERTIBLE,
            setOf(AutomotiveDescriptor.USES_PROJECTION),
        )

        assertEquals(ConversionAction.RESTAGE, installed.conversionAction)
        assertTrue(installed.hasCarVersion)
    }


    @Test
    fun `trusted native car app needs no action`() {
        val installed = app(
            ConversionState.TRUSTED_INSTALL,
            setOf(AutomotiveDescriptor.USES_PROJECTION),
        )

        assertTrue(installed.hasCarVersion)
        assertNull(installed.conversionAction)
    }

    @Test
    fun `game category car app is reported as parked only`() {
        val installed = app(
            ConversionState.TRUSTED_INSTALL,
            setOf(AutomotiveDescriptor.USES_TEMPLATE),
            ApplicationInfo.CATEGORY_GAME,
        )

        assertTrue(installed.hasCarVersion)
        assertTrue(installed.blockedWhileDriving)
        assertNull(installed.conversionAction)
    }
}
