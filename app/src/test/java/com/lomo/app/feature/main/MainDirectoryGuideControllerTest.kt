package com.lomo.app.feature.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainDirectoryGuideControllerTest {
    @Test
    fun `requestImage sets image guide type`() {
        val controller = MainDirectoryGuideController()

        controller.requestImage()

        assertEquals(DirectorySetupType.Image, controller.setupType)
    }

    @Test
    fun `requestVoice then clear resets guide type`() {
        val controller = MainDirectoryGuideController()
        controller.requestVoice()

        controller.clear()

        assertNull(controller.setupType)
    }
}
