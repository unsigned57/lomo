package com.lomo.data.share

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LomoShareServerContractTest {
    @Test
    fun `server bound port resolution uses resolved connector without polling loop`() {
        val source = File("src/main/java/com/lomo/data/share/LomoShareServer.kt").readText()

        assertTrue(source.contains("resolvedConnectors()"))
        assertFalse(source.contains("BOUND_PORT_POLL_ATTEMPTS"))
        assertFalse(source.contains("delay("))
        assertFalse(source.contains("repeat("))
    }
}
