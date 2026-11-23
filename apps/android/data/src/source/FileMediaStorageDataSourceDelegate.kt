package com.lomo.data.source

class FileMediaStorageDataSourceDelegate(private val backendResolver: FileStorageBackendResolver) :
    MediaStorageDataSource {
    override suspend fun listImageFiles(selection: MediaStorageSelection): List<Pair<String, String>> {
        val imageLocation = selection.imageLocation ?: return emptyList()
        val workspace = selection.workspaceLocation?.let(backendResolver::mediaRootAt)
        val selected = backendResolver.mediaRootAt(imageLocation)
        val sameAsWorkspace = workspace != null && selected.vfs.sameDirectoryAs(workspace.vfs)
        val mounted = selected.backend.listImageFiles()
        // A bare filename aliases only a unique claim inside the explicitly configured image directory.
        val mapped = if (sameAsWorkspace) {
            mounted
        } else {
            mounted.map { (path, location) -> "images/$path" to location }
        }
        val mappedKeys = mapped.mapTo(HashSet(mapped.size)) { (path, _) -> path }
        val unique = mounted.groupBy { (path, _) -> path.substringAfterLast('/') }
            .filterValues { it.size == 1 }
            .filterKeys { basename -> basename !in mappedKeys }
        return mapped + unique.map { (basename, matches) -> basename to matches.single().second }
    }
}
