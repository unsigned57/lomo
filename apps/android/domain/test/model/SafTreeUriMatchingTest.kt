package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe

class SafTreeUriMatchingTest : DomainFunSpec() {
    init {
        test("given bare persisted grant and cross-user 0@ requested uri then tree matching succeeds") {
            val bare = "content://com.android.externalstorage.documents/tree/primary%3AObsidian"
            val crossUser = "content://0@com.android.externalstorage.documents/tree/primary%3AObsidian"
            val crossUserChild =
                "content://0@com.android.externalstorage.documents/tree/primary%3AObsidian/document/primary%3AObsidian"
            val crossUserEncodedAt = "content://0%40com.android.externalstorage.documents/tree/primary%3AObsidian"
            val decodedTree = "content://0@Com.Android.ExternalStorage.Documents/tree/primary:Obsidian"
            val utf8Encoded = "content://0@com.android.externalstorage.documents/tree/primary%3A%E7%AC%94%E8%AE%B0%2B1"
            val utf8Decoded = "content://com.android.externalstorage.documents/tree/primary:笔记+1"

            assertSoftly {
                contentTreeUrisMatch(bare, crossUser) shouldBe true
                contentTreeUrisMatch(crossUser, bare) shouldBe true
                contentTreeUrisMatch(bare, crossUserChild) shouldBe true
                contentTreeUrisMatch(bare, crossUserEncodedAt) shouldBe true
                contentTreeUrisMatch(bare, decodedTree) shouldBe true
                contentTreeUrisMatch(utf8Decoded, utf8Encoded) shouldBe true
                contentTreeUrisMatch(
                    bare,
                    "content://0@com.android.externalstorage.documents/tree/primary%ZZ",
                ) shouldBe false
            }
        }

        test("given mismatched explicit user ids, providers, or trees then tree matching fails closed") {
            val userZero = "content://0@com.android.externalstorage.documents/tree/primary%3AObsidian"
            val userTen = "content://10@com.android.externalstorage.documents/tree/primary%3AObsidian"
            val otherProvider = "content://com.other.documents/tree/primary%3AObsidian"
            val otherTree = "content://com.android.externalstorage.documents/tree/primary%3AOther"
            val bare = "content://com.android.externalstorage.documents/tree/primary%3AObsidian"

            assertSoftly {
                contentTreeUrisMatch(userZero, userTen) shouldBe false
                contentTreeUrisMatch(bare, otherProvider) shouldBe false
                contentTreeUrisMatch(bare, otherTree) shouldBe false
                contentTreeUrisMatch(
                    "content://evil@com.android.externalstorage.documents/tree/a",
                    "content://com.android.externalstorage.documents/tree/a",
                ) shouldBe false
                contentTreeUrisMatch(
                    "content://@com.android.externalstorage.documents/tree/a",
                    "content://com.android.externalstorage.documents/tree/a",
                ) shouldBe false
                contentTreeUrisMatch("content://0@/tree/a", "content://0@/tree/a") shouldBe true
                contentTreeUrisMatch("content://0@/tree/a", "content://a/tree/a") shouldBe false
                contentTreeUrisMatch("content://0@1@provider/tree/a", "content://provider/tree/a") shouldBe false
            }
        }

        test("given valid and malformed authorities then parseContentUriAuthority enforces numeric user ids") {
            assertSoftly {
                parseContentUriAuthority("content://0@com.android.externalstorage.documents/tree/a") shouldBe
                    ContentUriAuthority(userId = "0", provider = "com.android.externalstorage.documents")
                parseContentUriAuthority("content://999%40Com.Example.Docs/tree/a") shouldBe
                    ContentUriAuthority(userId = "999", provider = "com.example.docs")
                parseContentUriAuthority("content://com.android.externalstorage.documents/tree/a") shouldBe
                    ContentUriAuthority(userId = null, provider = "com.android.externalstorage.documents")
                parseContentUriAuthority("content://evil@com.android.externalstorage.documents/tree/a") shouldBe null
                parseContentUriAuthority("content://@com.android.externalstorage.documents/tree/a") shouldBe null
                parseContentUriAuthority("content://0@/tree/a") shouldBe null
                parseContentUriAuthority("content://0@1@provider/tree/a") shouldBe null
                parseContentUriAuthority("content://0@provider:8080/tree/a") shouldBe null
                parseContentUriAuthority("file:///storage/emulated/0") shouldBe null
            }
        }

        test(
            "given cross-user uri then stripContentUriUserId and canonicalCrossUserAuthorityOrNull preserve provider",
        ) {
            val raw = "content://0@Com.Example.Docs/tree/primary%3ATest%2BObsidian/document/primary%3ATest"
            val encoded = "content://999%40Com.Example.Docs/tree/primary%3ATest"
            val bare = "content://Com.Example.Docs/tree/primary%3ATest"
            val invalid = "content://evil@Com.Example.Docs/tree/primary%3ATest"

            assertSoftly {
                stripContentUriUserId(raw) shouldBe
                    "content://Com.Example.Docs/tree/primary%3ATest%2BObsidian/document/primary%3ATest"
                stripContentUriUserId(encoded) shouldBe "content://Com.Example.Docs/tree/primary%3ATest"
                stripContentUriUserId(bare) shouldBe bare
                stripContentUriUserId(invalid) shouldBe invalid

                canonicalCrossUserAuthorityOrNull(raw) shouldBe "0@Com.Example.Docs"
                canonicalCrossUserAuthorityOrNull(encoded) shouldBe "999@Com.Example.Docs"
                canonicalCrossUserAuthorityOrNull(bare) shouldBe null
                canonicalCrossUserAuthorityOrNull(invalid) shouldBe null
            }
        }
    }
}
