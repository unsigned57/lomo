package com.lomo.data.source

class FileMediaStorageDataSourceDelegate(
    private val backendResolver: FileStorageBackendResolver,
) : MediaStorageDataSource {
    override suspend fun listImageFiles(): List<Pair<String, String>> =
        resolvedImageBackend()?.listImageFiles() ?: emptyList()

    override suspend fun getImageLocation(filename: String): String? =
        resolvedImageBackend()?.getImageLocation(filename)

    private suspend fun resolvedImageBackend(): MediaStorageBackend? =
        backendResolver.resolvedMediaRoot(StorageRootType.IMAGE)?.backend
}
