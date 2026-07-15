package com.lomo.data.characterization

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Resolves repository-root fixture paths from any Kotlin Toolchain working directory.
 */
internal object FixtureRepositoryPaths {
    fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(8) {
            val fixtures = current.resolve("fixtures")
            val rust = current.resolve("rust")
            if (fixtures.isDirectory() && rust.isDirectory()) {
                return current
            }
            current = current.parent ?: error("repository root not found from user.dir")
        }
        error("repository root not found from user.dir=${System.getProperty("user.dir")}")
    }

    fun fixturesRoot(): Path = repositoryRoot().resolve("fixtures")

    fun markdownFixtures(): Path = fixturesRoot().resolve("markdown")

    fun characterizationMarkdown(): Path = fixturesRoot().resolve("characterization/markdown")

    fun requireDirectory(path: Path): Path {
        check(path.exists() && path.isDirectory()) {
            "expected directory at $path"
        }
        return path
    }
}
