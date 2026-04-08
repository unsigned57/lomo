package com.lomo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun Typography.memoBodyTextStyle(): TextStyle =
    bodyMedium.copy(
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    )

fun Typography.memoSummaryTextStyle(): TextStyle =
    bodyMedium.copy(
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    )

fun Typography.memoEditorTextStyle(): TextStyle =
    memoBodyTextStyle()

fun Typography.memoHintTextStyle(): TextStyle =
    bodyMedium.copy(
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    )

fun Typography.memoListTextStyle(): TextStyle = memoBodyTextStyle()

fun memoParagraphBlockSpacing() = 8.dp
