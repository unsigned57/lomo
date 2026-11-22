package com.lomo.data.source

class FileMediaStorageDataSourceDelegate(private val backendResolver: FileStorageBackendResolver) :
    MediaStorageDataSource {
    override suspend fun listImageFiles(selection: MediaStorageSelection): List<Pair<String, String>> {
        val workspace = selection.workspaceLocation?.let(backendResolver::mediaRootAt)
        val selected =
            selection.imageLocation?.run {
                backendResolver
                    .mediaRootAt(this)
                    .takeUnless { workspace != null && it.vfs.sameDirectoryAs(workspace.vfs) }
            }
        val workspaceFiles =
            workspace?.run {
                backend.listImageFiles(ImageScanScope.Notes(includeWorkspaceImages = selected == null))
            }.orEmpty()
        if (selected == null) return workspaceFiles
        val mounted = selected.backend.listImageFiles()
        // A bare filename aliases only a unique claim inside its selected image directory.
        val unique = mounted.groupBy { (path, _) -> path.substringAfterLast('/') }
            .filterValues { it.size == 1 }
        return workspaceFiles + mounted.map { (path, location) -> "images/$path" to location } +
            unique.map { (basename, matches) -> basename to matches.single().second }
    }
}
