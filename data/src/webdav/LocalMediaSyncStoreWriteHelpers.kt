package com.lomo.data.webdav

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

internal fun requireLocatedMediaFile(
    located: LocatedMediaFile?,
    relativePath: String,
): LocatedMediaFile =
    located ?: throw IOException("Media root not configured for: $relativePath")

internal fun requireSafMediaRoot(
    context: Context,
    root: MediaRoot.Saf,
    relativePath: String,
): DocumentFile =
    getSafRoot(context, root)
        ?: throw IOException("Cannot access media directory for: $relativePath")

internal fun openRequiredOutputStream(
    context: Context,
    uri: Uri,
    relativePath: String,
) = context.contentResolver.openOutputStream(uri, "w")
    ?: throw IOException("Cannot write media file: $relativePath")
