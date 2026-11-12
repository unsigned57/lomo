package com.lomo.data.source

class FileMediaStorageDataSourceDelegate(private val backendResolver: FileStorageBackendResolver) :
    MediaStorageDataSource {
    override suspend fun listImageFiles(selection: MediaStorageSelection): List<Pair<String, String>> {
        val workspace = selection.workspaceLocation?.let(backendResolver::mediaRootAt)
        val mounts = buildMap {
            for ((name, location) in listOf("images" to selection.imageLocation, "voice" to selection.voiceLocation)) {
                val selected = location?.let(backendResolver::mediaRootAt) ?: continue
                if (workspace == null || !selected.vfs.sameDirectoryAs(workspace.vfs)) put(name, selected)
            }
        }
        val files = workspace?.run { backend.listImageFiles() }.orEmpty().filterNot { (path, _) ->
            path.substringBefore('/') in mounts
        }.toMutableList()
        for ((name, selected) in mounts) {
            val entries = selected.backend.listImageFiles()
            files += entries.map { (path, location) -> "$name/$path" to location }
            if (name == "images") {
                // A bare selected filename is a display alias only when one selected file owns it.
                // Qualified workspace identities never acquire aliases under another namespace.
                val unique = entries.groupBy { (path, _) -> path.substringAfterLast('/') }.filterValues { it.size == 1 }
                files += unique.map { (basename, matches) -> basename to matches.single().second }
            }
        }
        return files
    }
}
