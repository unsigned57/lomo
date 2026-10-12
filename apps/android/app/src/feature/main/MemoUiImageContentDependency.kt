package com.lomo.app.feature.main

import android.net.Uri

/**
 * The `key=uri` lines every image destination in [imagePaths] can legitimately
 * resolve through — the exact surface [MemoUiImageContentResolver] consults,
 * so the signature moves precisely when a resolvable location changes and
 * stays stable under unrelated map churn.
 */
internal fun buildImageMapDependencySignatureForPaths(
    imagePaths: Set<String>,
    imageMap: Map<String, Uri>,
): String =
    imagePaths
        .asSequence()
        .flatMap { path -> imageMapLookupLines(path, imageMap).asSequence() }
        .distinct()
        .sorted()
        .joinToString(separator = "\n")
