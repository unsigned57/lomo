package com.lomo.ui.component.input

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: pass-through input interceptor policy.
 * - Behavior focus: editor text changes must remain editable content, even when users enter double or triple trailing line breaks.
 * - Observable outcomes: interception result stays as UpdateValue with the exact edited text.
 * - Red phase: Fails before the fix because the editor still uses line-break-based quick-send interception instead of preserving trailing line breaks.
 * - Excludes: IME delivery, submit button wiring, and Compose host lifecycle.
 */
class PassThroughInputInterceptorTest {
    @Test
    fun `double trailing line breaks remain editable content`() {
        val interceptor = passThroughInputInterceptor()

        val result =
            interceptor.intercept(
                previousValue = TextFieldValue("memo\n", TextRange(5)),
                newValue = TextFieldValue("memo\n\n", TextRange(6)),
            )

        assertEquals(
            InputInterceptionResult.UpdateValue(TextFieldValue("memo\n\n", TextRange(6))),
            result,
        )
    }

    @Test
    fun `triple trailing line breaks remain editable content`() {
        val interceptor = passThroughInputInterceptor()

        val result =
            interceptor.intercept(
                previousValue = TextFieldValue("memo\n\n", TextRange(6)),
                newValue = TextFieldValue("memo\n\n\n", TextRange(7)),
            )

        assertEquals(
            InputInterceptionResult.UpdateValue(TextFieldValue("memo\n\n\n", TextRange(7))),
            result,
        )
    }
}
