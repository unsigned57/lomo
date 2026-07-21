package com.boltffi.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DemoMultiCrateTest {
    private val labeler = object : ForeignLabeler {
        override fun label(user: ForeignUser, kind: ForeignKind): String = "label:${user.name}:${kindName(kind)}"
    }

    @Test
    fun rootExportsUseDependencyTypes() {
        val user = ForeignUser("Ada", 37u)
        val session = ForeignSession(7u, user, ForeignKind.EXPRESS)

        assertEquals(ForeignKind.ARCHIVE, multiEchoKind(ForeignKind.ARCHIVE))
        assertEquals("express", multiKindLabel(ForeignKind.EXPRESS))
        assertEquals(ForeignPoint(4.0, 6.0), multiShiftPoint(ForeignPoint(1.0, 2.0), 3.0, 4.0))
        assertDoubleEquals(7.0, multiPointSum(ForeignPoint(2.0, 5.0)))
        assertEquals("Ada#37", multiUserSummary(user))
        assertEquals("mc-7", multiEchoCode("mc-7"))
        assertEquals("mc-7", multiCodeValue("mc-7"))
        assertEquals("ready", multiStateSummary(ForeignState.Ready))
        assertEquals("busy:sync", multiStateSummary(ForeignState.Busy("sync")))
        assertEquals(session, multiMakeSession(7u, user, ForeignKind.EXPRESS))
        assertEquals("7#Ada#37#express", multiSessionSummary(session))
        assertEquals(78u, multiTotalAge(listOf(session, ForeignSession(8u, ForeignUser("Grace", 41u), ForeignKind.ARCHIVE))))
        assertEquals("Ada", multiOptionalUserName(session))
        assertNull(multiOptionalUserName(null))
        assertEquals("started:7#Ada#37#express", multiEventSummary(SessionEvent.Started(session)))
        assertEquals("stopped", multiEventSummary(SessionEvent.Stopped))
        assertEquals(ForeignSession(9u, user, ForeignKind.ARCHIVE), multiTrySession(9u, user, ForeignKind.ARCHIVE))
        assertMessageContains(
            assertFailsWith<FfiException> {
                multiTrySession(0u, user, ForeignKind.STANDARD)
            },
            "session id must be positive",
        )
        assertEquals("Ada#37#7#express#archive", multiBorrowedSummary(user, session, ForeignKind.ARCHIVE))
        assertEquals("label:Ada:express", multiFormatWithLabeler(labeler, user, ForeignKind.EXPRESS))
    }

    @Test
    fun mergedDependencyExportsRemainCallable() {
        val user = ForeignUser("Ada", 37u)
        val session = ForeignSession(7u, user, ForeignKind.EXPRESS)

        assertEquals(ForeignKind.STANDARD, modelEchoKind(ForeignKind.STANDARD))
        assertEquals("archive", modelKindLabel(ForeignKind.ARCHIVE))
        assertEquals(ForeignPoint(7.0, 10.0), modelShiftPoint(ForeignPoint(2.0, 3.0), 5.0, 7.0))
        assertDoubleEquals(10.0, modelPointSum(ForeignPoint(4.0, 6.0)))
        assertEquals("Ada#37", modelUserSummary(user))
        assertEquals("dep", modelEchoCode("dep"))
        assertEquals("dep", modelCodeValue("dep"))
        assertEquals("busy:dep", modelStateSummary(ForeignState.Busy("dep")))
        assertEquals("label:Ada:archive", modelFormatWithLabeler(labeler, user, ForeignKind.ARCHIVE))

        assertEquals(session, sessionMake(7u, user, ForeignKind.EXPRESS))
        assertEquals("7#Ada#37#express", sessionSummary(session))
        assertEquals(37u, sessionTotalAge(listOf(session)))
        assertEquals("Ada", sessionOptionalUserName(session))
        assertNull(sessionOptionalUserName(null))
        assertEquals("started:7#Ada#37#express", sessionEventSummary(SessionEvent.Started(session)))
        assertEquals(ForeignSession(7u, user, ForeignKind.STANDARD), sessionTryMake(7u, user, ForeignKind.STANDARD))
        assertEquals("label:Ada:standard", sessionApplyLabeler(labeler, user, ForeignKind.STANDARD))
    }

    @Test
    fun dependencyClassesUseCrossCrateTypes() {
        val user = ForeignUser("Ada", 37u)
        val session = ForeignSession(7u, user, ForeignKind.EXPRESS)

        ForeignCounter(10).use { counter ->
            assertEquals(15, counter.add(5))
            assertEquals(15, counter.`get`())
            assertEquals("Ada#37#standard#15", counter.summarizeUser(user, ForeignKind.STANDARD))
        }

        SessionBook().use { emptyBook ->
            assertEquals(0u, emptyBook.count())
            assertEquals("empty#archive", emptyBook.summarizeFirst(ForeignKind.ARCHIVE))
        }

        SessionBook(session).use { book ->
            assertEquals(1u, book.count())
            assertEquals(2u, book.addSession(ForeignSession(8u, ForeignUser("Grace", 41u), ForeignKind.ARCHIVE)))
            assertEquals("7#Ada#37#express", book.summarizeFirst(ForeignKind.STANDARD))
            assertEquals("Ada#37#7#express#archive", book.summarizeBorrowed(user, session, ForeignKind.ARCHIVE))
            val metrics = book.metricsForPoints(listOf(ForeignPoint(1.0, 2.0), ForeignPoint(3.0, 4.0)))
            assertDoubleEquals(10.0, metrics.score)
            assertEquals(2u, metrics.count)
        }
    }

    private fun kindName(kind: ForeignKind): String =
        when (kind) {
            ForeignKind.STANDARD -> "standard"
            ForeignKind.EXPRESS -> "express"
            ForeignKind.ARCHIVE -> "archive"
        }
}
