package com.lomo.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTokenizerTest {
    @Test
    fun `tokenize ASCII text`() {
        val input = "Hello World"
        val expected = "Hello World" // Tokenizer space splits
        assertEquals(expected, SearchTokenizer.tokenize(input))
    }

    @Test
    fun `tokenize CJK text`() {
        val input = "你好世界"
        // Bigrams: "你好", "好世", "世界", "界" (last char)
        val expected = "你好 好世 世界 界"
        assertEquals(expected, SearchTokenizer.tokenize(input))
    }

    @Test
    fun `tokenize Mixed text`() {
        val input = "Hello你好"
        // "Hello", "你" (start of CJK block, but next is '好') -> "你好"
        // "你好"
        // "好" (last)
        val expected = "Hello 你好 好"
        assertEquals(expected, SearchTokenizer.tokenize(input))
    }

    @Test
    fun `tokenize CJK with spaces`() {
        val input = "我 爱 编程"
        // "我" (next is space) -> "我" (unigram because next is not CJK/Digit)
        // "爱" -> "爱"
        // "编程" -> "编程 程"
        val expected = "我 爱 编程 程"
        assertEquals(expected, SearchTokenizer.tokenize(input))
    }
}
