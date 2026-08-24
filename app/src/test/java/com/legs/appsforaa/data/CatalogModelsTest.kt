package com.legs.appsforaa.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogModelsTest {

    @Test
    fun `publisher unchanged policy is parsed`() {
        assertEquals(
            InstallPolicy.PUBLISHER_UNCHANGED,
            InstallPolicy.fromJson("publisher-unchanged"),
        )
    }

    @Test
    fun `unknown policy safely uses compatible-copy default`() {
        assertEquals(
            InstallPolicy.AUTO_CAR_COMPATIBLE,
            InstallPolicy.fromJson("future-policy"),
        )
    }
}
