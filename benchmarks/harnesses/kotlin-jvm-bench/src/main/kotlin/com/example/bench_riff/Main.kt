package com.example.bench_boltffi

import kotlin.time.measureTime
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("=== BoltFFI Kotlin Benchmark ===\n")

    // Basic function tests
    testBasicFunctions()
    
    // Result/throws tests
    testResultFunctions()
    
    // Record tests
    testRecords()
    
    // Vec tests
    testVecOperations()
    
    // Enum tests
    testEnums()
    
    // Data enum tests
    testDataEnums()
    
    // Class tests
    testClasses()
    
    // Async class method tests
    testAsyncClassMethods()
    
    // Async tests
    testAsyncFunctions()
    
    // Callback trait tests
    testCallbackTraits()

    // Status data enum tests
    testStatusDataEnums()

    // Result field tests
    testResultFields()

    // User profile tests
    testUserProfiles()
    
    // Benchmarks
    runBenchmarks()
    
    println("\n=== All tests passed! ===")
}

fun testBasicFunctions() {
    println("--- Basic Functions ---")
    
    noop()
    println("  noop: OK")
    
    check(echoI32(42) == 42) { "echoI32 failed" }
    println("  echoI32(42) = 42: OK")
    
    check(echoF64(3.14) == 3.14) { "echoF64 failed" }
    println("  echoF64(3.14) = 3.14: OK")
    
    check(echoString("hello") == "hello") { "echoString failed" }
    println("  echoString(\"hello\") = \"hello\": OK")
    
    check(add(10, 20) == 30) { "add failed" }
    println("  add(10, 20) = 30: OK")
    
    check(multiply(2.5, 4.0) == 10.0) { "multiply failed" }
    println("  multiply(2.5, 4.0) = 10.0: OK")
    
    // Option<i32> - ScalarPacked
    val evenResult = findEven(4)
    check(evenResult == 4) { "findEven(4) should be 4: $evenResult" }
    println("  findEven(4) = 4: OK")
    
    val oddResult = findEven(5)
    check(oddResult == null) { "findEven(5) should be null: $oddResult" }
    println("  findEven(5) = null: OK")

    // Option<i64> - Boxed(Long)
    val posI64 = findPositiveI64(100L)
    check(posI64 == 100L) { "findPositiveI64(100) should be 100: $posI64" }
    println("  findPositiveI64(100) = 100: OK")
    
    val negI64 = findPositiveI64(-5L)
    check(negI64 == null) { "findPositiveI64(-5) should be null: $negI64" }
    println("  findPositiveI64(-5) = null: OK")

    // Option<f64> - Boxed(Double)
    val posF64 = findPositiveF64(3.14)
    check(posF64 == 3.14) { "findPositiveF64(3.14) should be 3.14: $posF64" }
    println("  findPositiveF64(3.14) = 3.14: OK")
    
    val negF64 = findPositiveF64(-1.0)
    check(negF64 == null) { "findPositiveF64(-1.0) should be null: $negF64" }
    println("  findPositiveF64(-1.0) = null: OK")

    // Option<String> - NullableString
    val foundName = findName(42)
    check(foundName == "Name_42") { "findName(42) should be 'Name_42': $foundName" }
    println("  findName(42) = 'Name_42': OK")
    
    val noName = findName(-1)
    check(noName == null) { "findName(-1) should be null: $noName" }
    println("  findName(-1) = null: OK")

    // Option<Record> - NullableBuffer
    val foundLoc = findLocation(5)
    check(foundLoc != null && foundLoc.id == 5L) { "findLocation(5) failed: $foundLoc" }
    println("  findLocation(5) = Location(id=5): OK")
    
    val noLoc = findLocation(-1)
    check(noLoc == null) { "findLocation(-1) should be null: $noLoc" }
    println("  findLocation(-1) = null: OK")

    // Option<Vec<i32>> - VecPrimitive
    val foundNums = findNumbers(5)
    check(foundNums != null && foundNums.size == 5) { "findNumbers(5) failed: $foundNums" }
    println("  findNumbers(5).size = 5: OK")
    
    val noNums = findNumbers(-1)
    check(noNums == null) { "findNumbers(-1) should be null: $noNums" }
    println("  findNumbers(-1) = null: OK")

    // Option<Vec<Record>> - VecRecord
    val foundLocs = findLocations(3)
    check(foundLocs != null && foundLocs.size == 3) { "findLocations(3) failed: $foundLocs" }
    println("  findLocations(3).size = 3: OK")
    
    val noLocs = findLocations(-1)
    check(noLocs == null) { "findLocations(-1) should be null: $noLocs" }
    println("  findLocations(-1) = null: OK")
    
    // Option<Vec<String>> - VecString
    val foundNames = findNames(3)
    check(foundNames != null && foundNames.size == 3) { "findNames(3) failed: $foundNames" }
    check(foundNames!![0] == "Name_0") { "findNames(3)[0] should be 'Name_0': ${foundNames[0]}" }
    println("  findNames(3).size = 3: OK")
    
    val noNames = findNames(-1)
    check(noNames == null) { "findNames(-1) should be null: $noNames" }
    println("  findNames(-1) = null: OK")
    
    val genStr = generateString(100)
    check(genStr.length == 100) { "generateString failed: ${genStr.length}" }
    println("  generateString(100).length = 100: OK")
}

fun testResultFunctions() {
    println("\n--- Result/Throws ---")
    
    check(divide(10, 2) == 5) { "divide(10, 2) failed" }
    println("  divide(10, 2) = 5: OK")
    
    try {
        divide(10, 0)
        check(false) { "divide by zero should throw" }
    } catch (e: Exception) {
        check(e.message == "division by zero") { "Wrong error: ${e.message}" }
        println("  divide(10, 0) throws: OK (${e.message})")
    }
    
    check(parseInt("42") == 42) { "parseInt failed" }
    println("  parseInt(\"42\") = 42: OK")
    
    try {
        parseInt("not a number")
        check(false) { "parseInt should throw" }
    } catch (e: Exception) {
        println("  parseInt(\"not a number\") throws: OK (${e.message})")
    }
    
    val greeting = validateName("Ali")
    check(greeting == "Hello, Ali!") { "validateName failed: $greeting" }
    println("  validateName(\"Ali\") = \"Hello, Ali!\": OK")
    
    try {
        validateName("")
        check(false) { "validateName empty should throw" }
    } catch (e: Exception) {
        println("  validateName(\"\") throws: OK (${e.message})")
    }
    
    // Structured error tests
    check(tryCompute(5) == 10) { "tryCompute(5) should return 10" }
    println("  tryCompute(5) = 10: OK")
    
    try {
        tryCompute(0)
        check(false) { "tryCompute(0) should throw" }
    } catch (e: ComputeError.InvalidInput) {
        check(e.value0 == -999) { "Wrong error value: ${e.value0}" }
        println("  tryCompute(0) throws ComputeError.InvalidInput(-999): OK")
    }
    
    try {
        tryCompute(-5)
        check(false) { "tryCompute(-5) should throw" }
    } catch (e: ComputeError.Overflow) {
        check(e.value == -5 && e.limit == 0) { "Wrong error: value=${e.value}, limit=${e.limit}" }
        println("  tryCompute(-5) throws ComputeError.Overflow: OK")
    }
}

fun testRecords() {
    println("\n--- Records ---")
    
    val locations = generateLocations(10)
    check(locations.size == 10) { "generateLocations failed: ${locations.size}" }
    check(locations[0].id == 0L) { "location[0].id failed" }
    println("  generateLocations(10).size = 10: OK")
    println("  locations[0] = ${locations[0]}")
    
    val trades = generateTrades(5)
    check(trades.size == 5) { "generateTrades failed" }
    println("  generateTrades(5).size = 5: OK")
    println("  trades[0] = ${trades[0]}")
    
    val particles = generateParticles(5)
    check(particles.size == 5) { "generateParticles failed" }
    println("  generateParticles(5).size = 5: OK")
    
    val sensors = generateSensorReadings(5)
    check(sensors.size == 5) { "generateSensorReadings failed" }
    println("  generateSensorReadings(5).size = 5: OK")
}

fun testVecOperations() {
    println("\n--- Vec Operations ---")
    
    val locations = generateLocations(100)
    val count = processLocations(locations)
    check(count == 100) { "processLocations failed: $count" }
    println("  processLocations(100 items) = 100: OK")
    
    val ratings = sumRatings(locations)
    check(ratings > 0) { "sumRatings failed" }
    println("  sumRatings(100 locations) = $ratings: OK")
    
    val bytes = generateBytes(1000)
    check(bytes.size == 1000) { "generateBytes failed" }
    println("  generateBytes(1000).size = 1000: OK")
    
    val i32Vec = generateI32Vec(100)
    check(i32Vec.size == 100) { "generateI32Vec failed" }
    println("  generateI32Vec(100).size = 100: OK")
    
    val sum = sumI32Vec(i32Vec)
    check(sum == (0L until 100L).sum()) { "sumI32Vec failed: $sum" }
    println("  sumI32Vec([0..99]) = $sum: OK")
    
    val f64Vec = generateF64Vec(100)
    check(f64Vec.size == 100) { "generateF64Vec failed" }
    println("  generateF64Vec(100).size = 100: OK")
    
    val f64Sum = sumF64Vec(f64Vec)
    println("  sumF64Vec(100 items) = $f64Sum: OK")
    
    val trades = generateTrades(50)
    val volumeSum = sumTradeVolumes(trades)
    check(volumeSum > 0) { "sumTradeVolumes failed" }
    println("  sumTradeVolumes(50 trades) = $volumeSum: OK")
    
    val particles = generateParticles(50)
    val massSum = sumParticleMasses(particles)
    check(massSum > 0) { "sumParticleMasses failed" }
    println("  sumParticleMasses(50 particles) = $massSum: OK")
    
    val sensors = generateSensorReadings(50)
    val avgTemp = avgSensorTemperature(sensors)
    check(avgTemp > 0) { "avgSensorTemperature failed" }
    println("  avgSensorTemperature(50 readings) = $avgTemp: OK")
}

fun testEnums() {
    println("\n--- C-Style Enums ---")
    
    val opposite = oppositeDirection(Direction.NORTH)
    check(opposite == Direction.SOUTH) { "oppositeDirection failed: $opposite" }
    println("  oppositeDirection(NORTH) = SOUTH: OK")
    
    val degrees = directionToDegrees(Direction.EAST)
    check(degrees == 90) { "directionToDegrees failed: $degrees" }
    println("  directionToDegrees(EAST) = 90: OK")
    
    // Option<Enum>
    val dir = findDirection(0)
    check(dir == Direction.NORTH) { "findDirection(0) should be NORTH: $dir" }
    println("  findDirection(0) = NORTH: OK")
    
    val noDir = findDirection(-1)
    check(noDir == null) { "findDirection(-1) should be null: $noDir" }
    println("  findDirection(-1) = null: OK")
    
    // Option<Vec<Direction>>
    val dirs = findDirections(4)
    check(dirs != null && dirs.size == 4) { "findDirections(4) should have 4 items: $dirs" }
    check(dirs!![0] == Direction.NORTH) { "findDirections(4)[0] should be NORTH: ${dirs[0]}" }
    check(dirs[1] == Direction.EAST) { "findDirections(4)[1] should be EAST: ${dirs[1]}" }
    println("  findDirections(4).size = 4: OK")
    
    val noDirs = findDirections(-1)
    check(noDirs == null) { "findDirections(-1) should be null: $noDirs" }
    println("  findDirections(-1) = null: OK")
}

fun testDataEnums() {
    println("\n--- Data Enums ---")
    
    val success = processValue(10)
    check(success is ApiResult.Success) { "processValue(10) should be Success: $success" }
    println("  processValue(10) = Success: OK")
    
    val errorCode = processValue(0)
    check(errorCode is ApiResult.ErrorCode) { "processValue(0) should be ErrorCode: $errorCode" }
    check((errorCode as ApiResult.ErrorCode).value0 == -1) { "ErrorCode value wrong" }
    println("  processValue(0) = ErrorCode(-1): OK")
    
    val errorWithData = processValue(-5)
    check(errorWithData is ApiResult.ErrorWithData) { "processValue(-5) should be ErrorWithData: $errorWithData" }
    val ewd = errorWithData as ApiResult.ErrorWithData
    check(ewd.code == -5 && ewd.detail == -10) { "ErrorWithData values wrong" }
    println("  processValue(-5) = ErrorWithData(code=-5, detail=-10): OK")
    
    check(apiResultIsSuccess(ApiResult.Success)) { "apiResultIsSuccess(Success) failed" }
    check(!apiResultIsSuccess(ApiResult.ErrorCode(1))) { "apiResultIsSuccess(ErrorCode) failed" }
    println("  apiResultIsSuccess(Success) = true: OK")
    println("  apiResultIsSuccess(ErrorCode(1)) = false: OK")
    
    // Option<DataEnum>
    val apiRes = findApiResult(0)
    check(apiRes is ApiResult.Success) { "findApiResult(0) should be Success: $apiRes" }
    println("  findApiResult(0) = Success: OK")
    
    val noApiRes = findApiResult(-1)
    check(noApiRes == null) { "findApiResult(-1) should be null: $noApiRes" }
    println("  findApiResult(-1) = null: OK")
}

fun testClasses() {
    println("\n--- Classes ---")
    
    Counter().use { counter ->
        counter.increment()
        counter.increment()
        counter.increment()
        val value = counter.get()
        check(value == 3UL) { "Counter.get() failed: $value" }
        println("  Counter: 3 increments = 3: OK")
        
        counter.set(100UL)
        check(counter.get() == 100UL) { "Counter.set() failed" }
        println("  Counter: set(100) = 100: OK")
    }
    
    Accumulator().use { acc ->
        acc.add(10)
        acc.add(20)
        acc.add(30)
        val sum = acc.get()
        check(sum == 60L) { "Accumulator.get() failed: $sum" }
        println("  Accumulator: 10+20+30 = 60: OK")
        
        acc.reset()
        check(acc.get() == 0L) { "Accumulator.reset() failed" }
        println("  Accumulator: reset() = 0: OK")
    }
}

suspend fun testAsyncClassMethods() {
    println("\n--- Async Class Methods ---")
    
    DataStore().use { store ->
        val len = store.asyncLen()
        check(len == 0UL) { "DataStore.asyncLen() on empty should be 0: $len" }
        println("  DataStore.asyncLen() (empty) = 0: OK")
        
        try {
            store.asyncSum()
            check(false) { "asyncSum() on empty should throw" }
        } catch (e: Exception) {
            check(e.message == "no items to sum") { "Wrong error: ${e.message}" }
            println("  DataStore.asyncSum() (empty) throws \"no items to sum\": OK")
        }
    }

    println("\n--- Factory Constructors ---")
    DataStore().use { store ->
        check(store.len() == 0uL) { "new() should create empty store" }
        println("  DataStore() - empty store: OK")
    }
    
    DataStore.withSampleData().use { store ->
        check(store.len() == 3uL) { "withSampleData() should have 3 items" }
        println("  DataStore.withSampleData() - 3 items: OK")
    }
    
    DataStore(100).use { store ->
        check(store.len() == 0uL) { "withCapacity() should be empty" }
        println("  DataStore(100) - empty with capacity: OK")
    }
    
    DataStore(1.0, 2.0, 100).use { store ->
        check(store.len() == 1uL) { "withInitialPoint() should have 1 item" }
        var foundPoint = false
        store.foreach { point ->
            check(point.x == 1.0) { "x should be 1.0" }
            check(point.y == 2.0) { "y should be 2.0" }
            check(point.timestamp == 100L) { "timestamp should be 100" }
            foundPoint = true
        }
        check(foundPoint) { "should have found the point" }
        println("  DataStore(1.0, 2.0, 100) - 1 item verified: OK")
    }

    println("\n--- Closure Callbacks ---")
    DataStore.withSampleData().use { store ->
        var callCount = 0
        var sumX = 0.0
        store.foreach { point ->
            callCount++
            sumX += point.x
        }
        check(callCount == 3) { "foreach should be called 3 times: $callCount" }
        check(sumX == 9.0) { "sumX should be 9.0: $sumX" }
        println("  DataStore.foreach (3 items) calls=3, sumX=9.0: OK")
    }
}

suspend fun testAsyncFunctions() {
    println("\n--- Async Functions ---")
    
    val addResult = asyncAdd(10, 20)
    check(addResult == 30) { "asyncAdd(10, 20) should be 30: $addResult" }
    println("  asyncAdd(10, 20) = 30: OK")
    
    val heavyResult = computeHeavy(5)
    check(heavyResult == 10) { "computeHeavy(5) should be 10: $heavyResult" }
    println("  computeHeavy(5) = 10: OK")
    
    val stringResult = asyncMakeString(42)
    check(stringResult == "Value is: 42") { "asyncMakeString(42) failed: $stringResult" }
    println("  asyncMakeString(42) = \"Value is: 42\": OK")
    
    val pointResult = asyncFetchPoint(1.5, 2.5)
    check(pointResult.x == 1.5 && pointResult.y == 2.5) { "asyncFetchPoint failed: $pointResult" }
    println("  asyncFetchPoint(1.5, 2.5) = DataPoint(x=1.5, y=2.5): OK")
    
    val numbersResult = asyncGetNumbers(5)
    check(numbersResult.size == 5) { "asyncGetNumbers(5) size failed: ${numbersResult.size}" }
    check(numbersResult.toList() == listOf(0, 1, 2, 3, 4)) { "asyncGetNumbers(5) content failed: ${numbersResult.toList()}" }
    println("  asyncGetNumbers(5) = [0, 1, 2, 3, 4]: OK")
    
    val fetchResult = fetchData(10)
    check(fetchResult == 100) { "fetchData(10) should be 100: $fetchResult" }
    println("  fetchData(10) = 100: OK")
    
    try {
        fetchData(-1)
        check(false) { "fetchData(-1) should throw" }
    } catch (e: Exception) {
        check(e.message == "invalid id") { "fetchData(-1) wrong error: ${e.message}" }
        println("  fetchData(-1) throws \"invalid id\": OK")
    }
    
    val computeResult = tryComputeAsync(5)
    check(computeResult == 10) { "tryComputeAsync(5) should be 10: $computeResult" }
    println("  tryComputeAsync(5) = 10: OK")
    
    try {
        tryComputeAsync(0)
        check(false) { "tryComputeAsync(0) should throw" }
    } catch (e: ComputeError.InvalidInput) {
        check(e.value0 == -999) { "tryComputeAsync(0) wrong error value: ${e.value0}" }
        println("  tryComputeAsync(0) throws ComputeError.InvalidInput(-999): OK")
    }
    
    try {
        tryComputeAsync(-5)
        check(false) { "tryComputeAsync(-5) should throw" }
    } catch (e: ComputeError.Overflow) {
        check(e.value == -5 && e.limit == 0) { "tryComputeAsync(-5) wrong error: value=${e.value}, limit=${e.limit}" }
        println("  tryComputeAsync(-5) throws ComputeError.Overflow: OK")
    }
}

suspend fun testCallbackTraits() {
    println("\n--- Callback Traits ---")
    
    // Test sync callback trait (DataProvider)
    val myProvider = object : DataProvider {
        override fun getCount(): UInt = 5u
        override fun getItem(index: UInt): DataPoint = DataPoint(index.toDouble(), 0.0, index.toLong())
    }
    
    DataConsumer().use { consumer ->
        val handleId = DataProviderBridge.create(myProvider)
        println("  DataProviderBridge.create() = $handleId: OK")
    }
    
    // Test async callback trait (AsyncDataFetcher)
    val myFetcher = object : AsyncDataFetcher {
        override suspend fun fetchValue(key: UInt): ULong {
            return (key * 10u).toULong()
        }
    }
    
    val fetcherHandle = AsyncDataFetcherBridge.create(myFetcher)
    check(fetcherHandle > 0) { "AsyncDataFetcherBridge.create() failed" }
    println("  AsyncDataFetcherBridge.create() = $fetcherHandle: OK")
    
    println("  Callback traits: OK")
}

fun testStatusDataEnums() {
    println("\n--- Status Data Enums ---")

    val pendingProgress = getStatusProgress(TaskStatus.Pending)
    check(pendingProgress == 0) { "Pending should have progress 0: $pendingProgress" }
    println("  getStatusProgress(Pending) = 0: OK")

    val inProgressProgress = getStatusProgress(TaskStatus.InProgress(75))
    check(inProgressProgress == 75) { "InProgress(75) should return 75: $inProgressProgress" }
    println("  getStatusProgress(InProgress(75)) = 75: OK")

    val completedProgress = getStatusProgress(TaskStatus.Completed(100))
    check(completedProgress == 100) { "Completed(100) should return 100: $completedProgress" }
    println("  getStatusProgress(Completed(100)) = 100: OK")

    val failedProgress = getStatusProgress(TaskStatus.Failed(-5, 3))
    check(failedProgress == -5) { "Failed(-5, 3) should return -5: $failedProgress" }
    println("  getStatusProgress(Failed(-5, 3)) = -5: OK")

    val isPendingComplete = isStatusComplete(TaskStatus.Pending)
    check(!isPendingComplete) { "Pending should not be complete" }
    println("  isStatusComplete(Pending) = false: OK")

    val isCompletedComplete = isStatusComplete(TaskStatus.Completed(42))
    check(isCompletedComplete) { "Completed should be complete" }
    println("  isStatusComplete(Completed(42)) = true: OK")
}

fun testResultFields() {
    println("\n--- Result Fields ---")

    val successPoint = DataPoint(1.5, 2.5, 999L)
    val successResponse = createSuccessResponse(42L, successPoint)
    check(successResponse.requestId == 42L) { "requestId should be 42" }
    check(isResponseSuccess(successResponse)) { "success response should be success" }
    println("  createSuccessResponse(42, point): OK")

    check(successResponse.result.isSuccess) { "result should be success" }
    val successResult = successResponse.result.getOrThrow()
    check(successResult.x == 1.5) { "point.x should be 1.5" }
    check(successResult.y == 2.5) { "point.y should be 2.5" }
    check(successResult.timestamp == 999L) { "point.timestamp should be 999" }
    println("  successResponse.result.getOrThrow() = DataPoint(1.5, 2.5, 999): OK")

    val successValue = getResponseValue(successResponse)
    check(successValue != null && successValue.x == 1.5) { "getResponseValue should return the point" }
    println("  getResponseValue(success) = point: OK")

    val errorResponse = createErrorResponse(100L, ComputeError.InvalidInput(-999))
    check(errorResponse.requestId == 100L) { "requestId should be 100" }
    check(!isResponseSuccess(errorResponse)) { "error response should not be success" }
    println("  createErrorResponse(100, InvalidInput(-999)): OK")

    check(errorResponse.result.isFailure) { "result should be failure" }
    val error = errorResponse.result.exceptionOrNull()
    check(error is ComputeError.InvalidInput && error.value0 == -999) { "error should be InvalidInput(-999)" }
    println("  errorResponse.result.exceptionOrNull() = InvalidInput(-999): OK")

    val errorValue = getResponseValue(errorResponse)
    check(errorValue == null) { "getResponseValue on error should be null" }
    println("  getResponseValue(error) = null: OK")
}

fun testUserProfiles() {
    println("\n--- User Profiles ---")

    val users100 = generateUserProfiles(100)
    check(users100.size == 100) { "generateUserProfiles(100) failed: ${users100.size}" }
    println("  generateUserProfiles(100).size = 100: OK")

    val users1k = generateUserProfiles(1000)
    check(users1k.size == 1000) { "generateUserProfiles(1000) failed: ${users1k.size}" }
    println("  generateUserProfiles(1000).size = 1000: OK")

    val scores = sumUserScores(users100)
    check(scores > 0) { "sumUserScores failed: $scores" }
    println("  sumUserScores(100 users) = $scores: OK")

    val active = countActiveUsers(users100)
    check(active >= 0) { "countActiveUsers failed: $active" }
    println("  countActiveUsers(100 users) = $active: OK")
}

fun runBenchmarks() {
    println("\n--- Benchmarks ---")
    
    val iterations = 10_000
    
    benchmark("noop", iterations) { noop() }
    benchmark("echoI32", iterations) { echoI32(42) }
    benchmark("echoString(small)", iterations) { echoString("hello") }
    benchmark("add", iterations) { add(100, 200) }
    
    benchmark("generateLocations(100)", 1000) { generateLocations(100) }
    benchmark("generateLocations(1000)", 100) { generateLocations(1000) }
    
    val locations1k = generateLocations(1000)
    benchmark("sumRatings(1000)", 1000) { sumRatings(locations1k) }
    benchmark("processLocations(1000)", 1000) { processLocations(locations1k) }
    
    benchmark("generateI32Vec(1000)", 1000) { generateI32Vec(1000) }
    val vec1k = generateI32Vec(1000)
    benchmark("sumI32Vec(1000)", 1000) { sumI32Vec(vec1k) }
    
    benchmark("oppositeDirection", iterations) { oppositeDirection(Direction.NORTH) }
    benchmark("processValue (data enum return)", iterations) { processValue(10) }
    benchmark("apiResultIsSuccess (data enum param)", iterations) { apiResultIsSuccess(ApiResult.Success) }
    
    benchmark("Counter increment x100", 1000) {
        Counter().use { c ->
            repeat(100) { c.increment() }
        }
    }
}

inline fun benchmark(name: String, iterations: Int, block: () -> Unit) {
    // Warmup
    repeat(iterations / 10) { block() }
    
    val duration = measureTime {
        repeat(iterations) { block() }
    }
    
    val perOp = duration / iterations
    println("  $name: ${perOp.inWholeMicroseconds}us/op ($iterations iterations)")
}
