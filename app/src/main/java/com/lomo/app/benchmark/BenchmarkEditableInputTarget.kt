package com.lomo.app.benchmark

const val ANDROID_EDIT_TEXT_CLASS_NAME = "android.widget.EditText"

data class BenchmarkUiNodeSnapshot(
    val className: String?,
    val children: List<BenchmarkUiNodeSnapshot> = emptyList(),
)

fun BenchmarkUiNodeSnapshot.editableInputPathOrSelf(): List<Int>? =
    if (className == ANDROID_EDIT_TEXT_CLASS_NAME) {
        emptyList()
    } else {
        children.forEachIndexed { index, child ->
            child.editableInputPathOrSelf()?.let { childPath ->
                return listOf(index) + childPath
            }
        }
        null
    }
