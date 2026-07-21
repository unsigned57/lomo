package com.boltffi.demo

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

fun main(args: Array<String>) {
    when (args.single()) {
        "result-input-roundtrip" -> {
            check(resultToString(BoltFFIResult.Ok(7)) == "ok: 7")
            check(resultToString(BoltFFIResult.Err("bad")) == "err: bad")
        }
        "vec-processor-callback" -> {
            val vecProcessor = object : VecProcessor {
                override fun process(values: IntArray): IntArray = values.map { it * it }.toIntArray()
            }
            check(processVec(vecProcessor, intArrayOf(1, 2, 3)).contentEquals(intArrayOf(1, 4, 9)))
        }
        "async-callback-traits" -> runBlocking {
            withTimeout(10_000) {
                val asyncFetcher = object : AsyncFetcher {
                    override suspend fun fetchValue(key: Int): Int = key * 100
                    override suspend fun fetchString(input: String): String = input.uppercase()
                    override suspend fun fetchJoinedMessage(scope: String, message: String): String =
                        "$scope::${message.uppercase()}"
                }
                val asyncPointTransformer = object : AsyncPointTransformer {
                    override suspend fun transformPoint(point: Point): Point = Point(point.x + 50.0, point.y + 60.0)
                }
                val asyncOptionFetcher = object : AsyncOptionFetcher {
                    override suspend fun find(key: Int): Long? = key.takeIf { it > 0 }?.toLong()?.times(1000L)
                }
                val asyncOptionalMessageFetcher = object : AsyncOptionalMessageFetcher {
                    override suspend fun findMessage(key: Int): String? = key.takeIf { it > 0 }?.let { "async-message:$it" }
                }
                val asyncResultFormatter = object : AsyncResultFormatter {
                    override suspend fun renderMessage(scope: String, message: String): String {
                        check(scope.isNotEmpty())
                        return "$scope::${message.uppercase()}"
                    }

                    override suspend fun transformPoint(point: Point, status: Status): Point {
                        check(status == Status.ACTIVE)
                        return Point(point.x + 500.0, point.y + 600.0)
                    }
                }
                check(fetchWithAsyncCallback(asyncFetcher, 5) == 500)
                check(fetchStringWithAsyncCallback(asyncFetcher, "boltffi") == "BOLTFFI")
                check(fetchJoinedMessageWithAsyncCallback(asyncFetcher, "async", "borrowed strings") == "async::BORROWED STRINGS")
                check(transformPointWithAsyncCallback(asyncPointTransformer, Point(1.0, 2.0)) == Point(51.0, 62.0))
                check(invokeAsyncOptionFetcher(asyncOptionFetcher, 7) == 7_000L)
                check(invokeAsyncOptionFetcher(asyncOptionFetcher, 0) == null)
                check(invokeAsyncOptionalMessageFetcher(asyncOptionalMessageFetcher, 9) == "async-message:9")
                check(invokeAsyncOptionalMessageFetcher(asyncOptionalMessageFetcher, 0) == null)
                check(renderMessageWithAsyncResultCallback(asyncResultFormatter, "async", "result") == "async::RESULT")
                check(
                    transformPointWithAsyncResultCallback(asyncResultFormatter, Point(3.0, 4.0), Status.ACTIVE)
                        == Point(503.0, 604.0)
                )
            }
        }
        "event-bus-streams" -> runBlocking {
            demoCase("case:classes.streams.event_bus.subscribe_messages.should_deliver_encoded_record_items")
            withTimeout(10_000) {
                EventBus().use { bus ->
                    val valuesDeferred = async {
                        withTimeout(5_000) {
                            bus.subscribeValues().take(4).toList()
                        }
                    }
                    val pointsDeferred = async {
                        withTimeout(5_000) {
                            bus.subscribePoints().take(2).toList()
                        }
                    }
                    val messagesDeferred = async {
                        withTimeout(5_000) {
                            bus.subscribeMessages().take(2).toList()
                        }
                    }
                    delay(100)
                    bus.emitValue(1)
                    check(bus.emitBatch(intArrayOf(2, 3, 4)) == 3u)
                    bus.emitPoint(Point(1.0, 2.0))
                    bus.emitPoint(Point(3.0, 4.0))
                    bus.emitMessage(StreamMessage("alpha", intArrayOf(1, 2)))
                    bus.emitMessage(StreamMessage("beta", intArrayOf(3, 4, 5)))
                    check(valuesDeferred.await() == listOf(1, 2, 3, 4))
                    val points = pointsDeferred.await()
                    check(points.size == 2)
                    check(points[0] == Point(1.0, 2.0))
                    check(points[1] == Point(3.0, 4.0))
                    val messages = messagesDeferred.await()
                    check(messages.size == 2)
                    check(messages[0].text == "alpha")
                    check(messages[0].values.contentEquals(intArrayOf(1, 2)))
                    check(messages[1].text == "beta")
                    check(messages[1].values.contentEquals(intArrayOf(3, 4, 5)))
                }
            }
        }
        else -> error("unknown isolated case ${args.singleOrNull()}")
    }
}
