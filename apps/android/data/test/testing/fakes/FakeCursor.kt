package com.lomo.data.testing.fakes

import android.content.ContentResolver
import android.database.CharArrayBuffer
import android.database.ContentObserver
import android.database.Cursor
import android.database.DataSetObserver
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk

/** In-memory provider result with real positioning, column validation and close semantics. */
class FakeCursor(
    private val columns: Array<String>,
    private val rows: List<List<Any?>>,
    private val cursorExtras: Bundle = completeDirectoryExtras,
) : Cursor {
    private var rowIndex = -1
    private var closed = false

    init {
        require(columns.distinct().size == columns.size)
        require(rows.all { it.size == columns.size })
    }

    private fun cell(column: Int): Any? {
        check(!closed) { "Cursor is closed" }
        check(rowIndex in rows.indices) { "Cursor is not positioned on a row" }
        return rows[rowIndex][column]
    }

    override fun getCount() = rows.size
    override fun getPosition() = rowIndex
    override fun moveToPosition(position: Int): Boolean {
        check(!closed) { "Cursor is closed" }
        rowIndex = position.coerceIn(-1, rows.size)
        return rowIndex in rows.indices
    }
    override fun move(offset: Int) = moveToPosition(rowIndex + offset)
    override fun moveToFirst() = moveToPosition(0)
    override fun moveToLast() = moveToPosition(rows.lastIndex)
    override fun moveToNext() = moveToPosition(rowIndex + 1)
    override fun moveToPrevious() = moveToPosition(rowIndex - 1)
    override fun isFirst() = rows.isNotEmpty() && rowIndex == 0
    override fun isLast() = rows.isNotEmpty() && rowIndex == rows.lastIndex
    override fun isBeforeFirst() = rows.isEmpty() || rowIndex == -1
    override fun isAfterLast() = rows.isEmpty() || rowIndex == rows.size
    override fun getColumnIndex(name: String) = columns.indexOf(name)
    override fun getColumnIndexOrThrow(name: String): Int =
        getColumnIndex(name).also { require(it >= 0) { "Unknown column: $name" } }
    override fun getColumnName(index: Int) = columns[index]
    override fun getColumnNames() = columns.copyOf()
    override fun getColumnCount() = columns.size
    override fun getString(column: Int) = cell(column) as String?
    override fun getBlob(column: Int) = cell(column) as ByteArray
    override fun getLong(column: Int) = (cell(column) as Number).toLong()
    override fun getInt(column: Int) = (cell(column) as Number).toInt()
    override fun getShort(column: Int) = (cell(column) as Number).toShort()
    override fun getFloat(column: Int) = (cell(column) as Number).toFloat()
    override fun getDouble(column: Int) = (cell(column) as Number).toDouble()
    override fun isNull(column: Int) = cell(column) == null
    override fun getType(column: Int) = when (val value = cell(column)) {
        null -> Cursor.FIELD_TYPE_NULL
        is ByteArray -> Cursor.FIELD_TYPE_BLOB
        is String -> Cursor.FIELD_TYPE_STRING
        is Float, is Double -> Cursor.FIELD_TYPE_FLOAT
        is Number -> Cursor.FIELD_TYPE_INTEGER
        else -> error("Unsupported cursor cell: $value")
    }
    override fun close() {
        closed = true
    }
    override fun isClosed() = closed
    override fun copyStringToBuffer(column: Int, buffer: CharArrayBuffer): Unit = unsupported()

    @Deprecated("Cursor deactivation is unsupported by this fixture")
    override fun deactivate(): Unit = unsupported()

    @Deprecated("Cursor requery is unsupported by this fixture")
    override fun requery(): Boolean = unsupported()
    override fun registerContentObserver(observer: ContentObserver): Unit = unsupported()
    override fun unregisterContentObserver(observer: ContentObserver): Unit = unsupported()
    override fun registerDataSetObserver(observer: DataSetObserver): Unit = unsupported()
    override fun unregisterDataSetObserver(observer: DataSetObserver): Unit = unsupported()
    override fun setNotificationUri(resolver: ContentResolver, uri: Uri): Unit = unsupported()
    override fun getNotificationUri(): Uri = unsupported()
    override fun getWantsAllOnMoveCalls() = false
    override fun setExtras(extras: Bundle): Unit = unsupported()
    override fun getExtras(): Bundle = cursorExtras
    override fun respond(extras: Bundle): Bundle = unsupported()

    private fun unsupported(): Nothing = error("Provider observation and extras are outside this cursor fixture")
}

internal val completeDirectoryExtras: Bundle = mockk<Bundle>().also { extras ->
    every { extras.getBoolean(DocumentsContract.EXTRA_LOADING) } returns false
    every { extras.containsKey(DocumentsContract.EXTRA_ERROR) } returns false
}
