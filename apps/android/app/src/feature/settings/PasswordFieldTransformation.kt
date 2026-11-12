package com.lomo.app.feature.settings

import androidx.compose.foundation.text.input.OutputTransformation

/**
 * Replaces every displayed character of a password field with [PASSWORD_OBFUSCATION_CHARACTER].
 *
 * `OutputTransformation` is the non-deprecated replacement for `PasswordVisualTransformation`
 * (Compose foundation 1.13). It only rewrites what the field renders: the real characters stay in
 * the `TextFieldState`, so validation and submission still read the plain value.
 */
internal val PasswordOutputTransformation: OutputTransformation =
    OutputTransformation {
        if (length > 0) {
            replace(
                start = 0,
                end = length,
                text = PASSWORD_OBFUSCATION_CHARACTER.toString().repeat(length),
            )
        }
    }

private const val PASSWORD_OBFUSCATION_CHARACTER = '•'
