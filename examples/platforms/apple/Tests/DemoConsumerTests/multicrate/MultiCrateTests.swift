import Demo
import XCTest

private final class SwiftForeignLabeler: ForeignLabeler {
    func label(user: ForeignUser, kind: ForeignKind) -> String {
        "label:\(user.name):\(kindLabel(kind))"
    }

    private func kindLabel(_ kind: ForeignKind) -> String {
        switch kind {
        case .standard:
            return "standard"
        case .express:
            return "express"
        case .archive:
            return "archive"
        }
    }
}

final class MultiCrateTests: DemoTestCase {
    func testRootValueExportsUseDependencyTypes() {
        let user = ForeignUser(name: "Ada", age: 37)

        XCTAssertEqual(multiEchoKind(kind: .archive), .archive)
        XCTAssertEqual(multiKindLabel(kind: .express), "express")
        XCTAssertEqual(multiShiftPoint(point: ForeignPoint(x: 1.0, y: 2.0), dx: 3.0, dy: 4.0), ForeignPoint(x: 4.0, y: 6.0))
        XCTAssertEqual(multiPointSum(point: ForeignPoint(x: 2.0, y: 5.0)), 7.0)
        XCTAssertEqual(multiUserSummary(user: user), "Ada#37")
        XCTAssertEqual(multiEchoCode(code: "mc-7"), "mc-7")
        XCTAssertEqual(multiCodeValue(code: "mc-7"), "mc-7")
        XCTAssertEqual(multiStateSummary(state: .ready), "ready")
        XCTAssertEqual(multiStateSummary(state: .busy(reason: "sync")), "busy:sync")
    }

    func testRootSessionExportsUseDependencyTypes() throws {
        let user = ForeignUser(name: "Ada", age: 37)
        let session = ForeignSession(id: 7, user: user, kind: .express)

        XCTAssertEqual(multiMakeSession(id: 7, user: user, kind: .express), session)
        XCTAssertEqual(multiSessionSummary(session: session), "7#Ada#37#express")
        XCTAssertEqual(
            multiTotalAge(sessions: [
                session,
                ForeignSession(id: 8, user: ForeignUser(name: "Grace", age: 41), kind: .archive)
            ]),
            78
        )
        XCTAssertEqual(multiOptionalUserName(session: session), "Ada")
        XCTAssertNil(multiOptionalUserName(session: nil))
        XCTAssertEqual(multiEventSummary(event: .started(session: session)), "started:7#Ada#37#express")
        XCTAssertEqual(multiEventSummary(event: .stopped), "stopped")
        XCTAssertEqual(try multiTrySession(id: 9, user: user, kind: .archive), ForeignSession(id: 9, user: user, kind: .archive))
        assertThrowsMessageContains("session id must be positive", try multiTrySession(id: 0, user: user, kind: .standard))
    }

    func testRootBorrowedExportUsesDependencyTypes() {
        let user = ForeignUser(name: "Ada", age: 37)
        let session = ForeignSession(id: 7, user: user, kind: .express)

        XCTAssertEqual(multiBorrowedSummary(user: user, session: session, kind: .archive), "Ada#37#7#express#archive")
    }

    func testRootCallbackExportUsesDependencyTypes() {
        let user = ForeignUser(name: "Ada", age: 37)

        XCTAssertEqual(multiFormatWithLabeler(labeler: SwiftForeignLabeler(), user: user, kind: .express), "label:Ada:express")
    }

    func testMergedDependencyExportsRemainCallable() throws {
        let user = ForeignUser(name: "Ada", age: 37)
        let session = ForeignSession(id: 7, user: user, kind: .express)
        let labeler = SwiftForeignLabeler()

        XCTAssertEqual(modelEchoKind(kind: .standard), .standard)
        XCTAssertEqual(modelKindLabel(kind: .archive), "archive")
        XCTAssertEqual(modelShiftPoint(point: ForeignPoint(x: 2.0, y: 3.0), dx: 5.0, dy: 7.0), ForeignPoint(x: 7.0, y: 10.0))
        XCTAssertEqual(modelPointSum(point: ForeignPoint(x: 4.0, y: 6.0)), 10.0)
        XCTAssertEqual(modelUserSummary(user: user), "Ada#37")
        XCTAssertEqual(modelEchoCode(code: "dep"), "dep")
        XCTAssertEqual(modelCodeValue(code: "dep"), "dep")
        XCTAssertEqual(modelStateSummary(state: .busy(reason: "dep")), "busy:dep")
        XCTAssertEqual(modelFormatWithLabeler(labeler: labeler, user: user, kind: .archive), "label:Ada:archive")

        XCTAssertEqual(sessionMake(id: 7, user: user, kind: .express), session)
        XCTAssertEqual(sessionSummary(session: session), "7#Ada#37#express")
        XCTAssertEqual(sessionTotalAge(sessions: [session]), 37)
        XCTAssertEqual(sessionOptionalUserName(session: session), "Ada")
        XCTAssertNil(sessionOptionalUserName(session: nil))
        XCTAssertEqual(sessionEventSummary(event: .started(session: session)), "started:7#Ada#37#express")
        XCTAssertEqual(try sessionTryMake(id: 7, user: user, kind: .standard), ForeignSession(id: 7, user: user, kind: .standard))
        XCTAssertEqual(sessionApplyLabeler(labeler: labeler, user: user, kind: .standard), "label:Ada:standard")
    }

    func testDependencyCounterUsesCrossCrateTypes() {
        let user = ForeignUser(name: "Ada", age: 37)

        let counter = ForeignCounter(initial: 10)
        XCTAssertEqual(counter.add(amount: 5), 15)
        XCTAssertEqual(counter.get(), 15)
        XCTAssertEqual(counter.summarizeUser(user: user, kind: .standard), "Ada#37#standard#15")
    }

    func testEmptyDependencyBookUsesCrossCrateTypes() {
        let emptyBook = SessionBook()
        XCTAssertEqual(emptyBook.count(), 0)
        XCTAssertEqual(emptyBook.summarizeFirst(fallback: .archive), "empty#archive")
    }

    func testDependencyBookConstructorUsesCrossCrateTypes() {
        let user = ForeignUser(name: "Ada", age: 37)
        let session = ForeignSession(id: 7, user: user, kind: .express)

        let book = SessionBook(withSession: session)
        XCTAssertEqual(book.count(), 1)
        XCTAssertEqual(book.summarizeFirst(fallback: .standard), "7#Ada#37#express")
    }

    func testDependencyBookMutationUsesCrossCrateTypes() {
        let user = ForeignUser(name: "Ada", age: 37)
        let session = ForeignSession(id: 7, user: user, kind: .express)

        let book = SessionBook(withSession: session)
        XCTAssertEqual(book.addSession(session: ForeignSession(id: 8, user: ForeignUser(name: "Grace", age: 41), kind: .archive)), 2)
    }

    func testDependencyBookBorrowedParamsUseCrossCrateTypes() {
        let user = ForeignUser(name: "Ada", age: 37)
        let session = ForeignSession(id: 7, user: user, kind: .express)

        let book = SessionBook(withSession: session)
        XCTAssertEqual(book.summarizeFirst(fallback: .standard), "7#Ada#37#express")
        XCTAssertEqual(book.summarizeBorrowed(user: user, session: session, kind: .archive), "Ada#37#7#express#archive")
    }

    func testDependencyBookBlittableVecUsesCrossCrateTypes() {
        let user = ForeignUser(name: "Ada", age: 37)
        let session = ForeignSession(id: 7, user: user, kind: .express)

        let book = SessionBook(withSession: session)
        XCTAssertEqual(book.metricsForPoints(points: [ForeignPoint(x: 1.0, y: 2.0), ForeignPoint(x: 3.0, y: 4.0)]), ForeignMetrics(score: 10.0, count: 2))
    }
}
