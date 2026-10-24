package com.jun9100.opensave

import java.io.File

/** Validates user-entered Root paths before they reach a shell command. */
object RootPathPolicy {
    private val protectedExactPaths = setOf(
        "/",
        "/data",
        "/data/user",
        "/data/user/0",
        "/data/user_de",
        "/data/user_de/0",
        "/data/data",
        "/data/media",
        "/storage",
        "/storage/emulated",
        "/storage/emulated/0",
        "/sdcard",
    )

    private val protectedPrefixes = listOf(
        "/system",
        "/vendor",
        "/product",
        "/odm",
        "/apex",
        "/proc",
        "/sys",
        "/dev",
        "/metadata",
        "/linkerconfig",
    )

    fun canonicalize(rawPath: String): String {
        return canonicalizeInternal(rawPath, allowBroadStorage = false)
    }

    /** Read-only scans may inspect shared storage, but backup/restore may not replace it. */
    fun canonicalizeForScan(rawPath: String): String {
        return canonicalizeInternal(rawPath, allowBroadStorage = true)
    }

    private fun canonicalizeInternal(rawPath: String, allowBroadStorage: Boolean): String {
        val value = rawPath.trim()
        require(value.isNotEmpty()) { "Root path is empty" }
        require(!value.contains('\u0000')) { "Root path contains NUL" }
        require(value.startsWith('/')) { "Root path must be absolute" }

        val normalized = value.trimEnd('/').ifEmpty { "/" }
        require(allowBroadStorage && normalized in setOf("/storage/emulated/0", "/sdcard") || !isProtected(normalized)) {
            "Refusing a protected or overly broad Root path"
        }

        val canonical = File(value).canonicalPath
        require(canonical.startsWith('/')) { "Root path must be absolute" }
        require(allowBroadStorage && canonical in setOf("/storage/emulated/0", "/sdcard") || !isProtected(canonical)) {
            "Refusing a protected or overly broad Root path"
        }
        return if (preservesAndroidUserAlias(normalized)) normalized else canonical
    }

    private fun preservesAndroidUserAlias(path: String): Boolean {
        return path.startsWith("/data/user/0/") &&
            path.split('/').none { it == "." || it == ".." }
    }

    private fun isProtected(path: String): Boolean {
        if (path in protectedExactPaths) return true
        return protectedPrefixes.any { path == it || path.startsWith("$it/") }
    }
}
