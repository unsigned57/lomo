package com.lomo.data.engine

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi

/** One operation's provider lease, shared by its queries, arbitration and stream verification. */
@RequiresApi(Build.VERSION_CODES.Q)
internal fun <T> withDocumentProviderConnection(
    resolver: ContentResolver,
    tree: Uri,
    operation: (ContentResolver) -> T,
): T {
    val client = resolver.acquireContentProviderClient(tree)
        ?: throw java.io.IOException("Document provider is unavailable: ${tree.authority}")
    return client.use { operation(ContentResolver.wrap(it)) }
}
