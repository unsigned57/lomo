package com.lomo.data.repository

import java.io.File

internal fun String.hasValidRelativePathShape(): Boolean =
    isNotBlank() &&
        indexOf(Char.MIN_VALUE) < 0 &&
        !contains('\\') &&
        !startsWith('/') &&
        !File(this).isAbsolute

internal fun List<String>.hasValidRelativePathSegments(): Boolean =
    first().startsWithWindowsDrivePrefix().not() &&
        none(String::isUnsafeRelativePathSegment)

private fun String.isUnsafeRelativePathSegment(): Boolean =
    isBlank() ||
        this == "." ||
        this == ".." ||
        matchesWindowsDrivePrefix()

private fun String.matchesWindowsDrivePrefix(): Boolean =
    length == WINDOWS_DRIVE_PREFIX_LENGTH &&
        this[1] == ':' &&
        this[0].isLetter()

private fun String.startsWithWindowsDrivePrefix(): Boolean =
    length >= WINDOWS_DRIVE_PREFIX_LENGTH &&
        this[1] == ':' &&
        this[0].isLetter()

private const val WINDOWS_DRIVE_PREFIX_LENGTH = 2
