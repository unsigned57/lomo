# Kotest Golden Sample: Property-Based - `StorageFilenameFormats`

Use this walkthrough when the target is a pure parser, formatter, validator,
or policy whose input space is naturally a generator rather than a finite
scenario matrix.

## Target

- Production file: `domain/src/main/java/com/lomo/domain/model/StorageFilenameFormats.kt`
- Test file: `domain/src/test/java/com/lomo/domain/model/StorageFilenameFormatsPropertyTest.kt`
- Layer: `domain`
- Priority: `P2`

## Scenario matrix

- Happy: any non-empty ASCII title round-trips through encode -> decode unchanged.
- Boundary: the maximum-length title (64 chars) still round-trips.
- Failure: titles containing forbidden path separators are rejected with `IllegalArgumentException`.
- Must-not-happen: encoded output must never reintroduce a path separator.

## Red

Write the failing property-based reproducer first:

```kotlin
/*
 * Test Contract:
 * - Unit under test: StorageFilenameFormats
 * - Owning layer: domain
 * - Priority tier: P2
 *
 * Scenario matrix:
 * - Happy: arbitrary non-empty ASCII titles round-trip encode -> decode.
 * - Boundary: 64-char titles still round-trip.
 * - Failure: titles with '/' or '\\' raise IllegalArgumentException.
 * - Must-not-happen: encoded output never contains '/' or '\\'.
 *
 * Observable outcomes:
 * - decode(encode(title)) equals title; encode rejects separators; encoded
 *   output excludes separators.
 *
 * Red phase:
 * - Fails before the fix because encode does not strip an interior backslash,
 *   so decode produces a string that differs from the input.
 *
 * Excludes:
 * - file-system I/O, content addressing, image-cache filenames.
 */
class StorageFilenameFormatsPropertyTest : FunSpec({
    test("encode/decode round-trip preserves any non-empty ASCII title") {
        checkAll(Arb.string(minSize = 1, maxSize = 64, codepoints = Codepoint.ascii())) { title ->
            assume(!title.contains('/') && !title.contains('\\'))
            StorageFilenameFormats.decode(StorageFilenameFormats.encode(title)) shouldBe title
        }
    }

    test("encode rejects titles containing a path separator") {
        checkAll(
            Arb.string(minSize = 1, maxSize = 32, codepoints = Codepoint.ascii()),
            Arb.of('/', '\\'),
        ) { prefix, sep ->
            shouldThrow<IllegalArgumentException> {
                StorageFilenameFormats.encode("$prefix$sep")
            }
        }
    }

    test("encoded output never reintroduces a path separator") {
        checkAll(Arb.string(minSize = 1, maxSize = 64, codepoints = Codepoint.ascii())) { title ->
            assume(!title.contains('/') && !title.contains('\\'))
            val encoded = StorageFilenameFormats.encode(title)
            withClue("title=$title encoded=$encoded") {
                encoded.shouldNotContain("/")
                encoded.shouldNotContain("\\")
            }
        }
    }
})
```

Run the narrowest command:

```bash
./gradlew :domain:test --tests 'com.lomo.domain.model.StorageFilenameFormatsPropertyTest'
```

Expected red symptom (when the production bug exists):

```text
Property failed after 17 attempts
Arguments:
        0) "ab\\cd"

Caused by: io.kotest.assertions.AssertionFailedError: expected:<ab\cd> but was:<abcd>
```

## Green

Fix `encode` to either escape or reject the separator. After the fix the
property holds for any of the 1000 generated inputs per test by default.

## Refactor

If the same `Arb<String>` is reused across multiple tests, hoist it to a
companion object - keep arbitraries close to the spec, not in a shared util
file.

## When NOT to use property-based testing

- ViewModel state machines: input is not a generator; use scenario matrix.
- Repository orchestration: collaborator behavior is not naturally generated.
- Anything with network/file IO in the assertion path: 1000 iterations x IO
  is too slow.
