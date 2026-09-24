package com.lomo.domain.usecase

/**
 * Settings-precheck for the Git remote URL and branch fields.
 *
 * Mirrors the production wire rules owned by `lomo-git` (`validate_git_remote_url` and
 * `is_valid_branch_name`): the user-facing remote path is HTTPS only (`file://` / bare paths
 * exist solely for hermetic Rust tests). URL userinfo (`user:pass@host`) is rejected so
 * credentials can never ride inside a persisted endpoint. A blank URL is **not** a valid
 * remote — clearing stays an explicit dialog action, not an accepted endpoint.
 */
class GitRemoteUrlUseCase {
        fun normalize(url: String): String = url.trim().removeSuffix("/")

        fun isValid(url: String): Boolean {
            val trimmed = url.trim()
            if (trimmed.isBlank()) return false
            if (!trimmed.startsWith(HTTPS_PREFIX)) return false
            val remainder = trimmed.removePrefix(HTTPS_PREFIX)
            val authority = remainder.substringBefore(PATH_SEPARATOR)
            if (authority.isBlank() || authority.contains(USERINFO_MARKER)) return false
            return remainder.substringAfter(PATH_SEPARATOR, "").isNotBlank()
        }

        /**
         * Short ref segment check mirroring `lomo-git::is_valid_branch_name`: a single
         * component only — no slashes, no git-ref metacharacters, no `.lock` suffix.
         */
        fun isValidBranch(branch: String): Boolean {
            // GitEndpoint::parse trims before validating; the precheck mirrors that so
            // whitespace-only input is rejected rather than silently normalized away.
            val normalized = branch.trim()
            if (normalized.isEmpty()) return false
            if (normalized.contains('/') || normalized.contains('\\')) return false
            if (normalized.startsWith('-') || normalized.startsWith('.')) return false
            if (normalized.endsWith('.')) return false
            if (normalized.endsWith(LOCK_SUFFIX, ignoreCase = true)) return false
            if (normalized.contains("..") || normalized.contains("@{")) return false
            return normalized.none { ch -> ch.isISOControl() || ch in REF_FORBIDDEN_CHARS }
        }

        private companion object {
            private const val HTTPS_PREFIX = "https://"
            private const val PATH_SEPARATOR = '/'
            private const val USERINFO_MARKER = '@'
            private const val LOCK_SUFFIX = ".lock"
            private const val REF_FORBIDDEN_CHARS = "~^:?*["
        }
}
