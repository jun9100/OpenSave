package com.jun9100.opensave

/**
 * The access boundary is part of a source's identity. It must be shown before
 * an empty scan can be interpreted as a source with no saves.
 */
enum class SourceAccessCapability {
    APP,
    SAF,
    ROOT,
    /** 路径型来源，由「所有文件访问」免 root 读取。 */
    ALL_FILES,
    UNAVAILABLE,
}

data class SourceAccessStatus(
    val capability: SourceAccessCapability,
    val requiredCapability: SourceAccessCapability,
    val readable: Boolean,
    val writable: Boolean,
    val reason: String = "",
) {
    companion object {
        fun appAccessible(writable: Boolean = true) = SourceAccessStatus(
            capability = SourceAccessCapability.APP,
            requiredCapability = SourceAccessCapability.APP,
            readable = true,
            writable = writable,
        )

        fun safAccessible(writable: Boolean) = SourceAccessStatus(
            capability = SourceAccessCapability.SAF,
            requiredCapability = SourceAccessCapability.SAF,
            readable = true,
            writable = writable,
        )

        fun rootOnly() = SourceAccessStatus(
            capability = SourceAccessCapability.ROOT,
            requiredCapability = SourceAccessCapability.ROOT,
            readable = true,
            writable = true,
        )

        /**
         * 同一条路径由「所有文件访问」读取。可读可写，但不能 `chown`：
         * 写回的文件归属本 App，目标 App 靠组权限访问。
         */
        fun allFilesAccessible() = SourceAccessStatus(
            capability = SourceAccessCapability.ALL_FILES,
            requiredCapability = SourceAccessCapability.ALL_FILES,
            readable = true,
            writable = true,
        )

        fun unavailable(requiredCapability: SourceAccessCapability, reason: String) =
            SourceAccessStatus(
                capability = SourceAccessCapability.UNAVAILABLE,
                requiredCapability = requiredCapability,
                readable = false,
                writable = false,
                reason = reason,
            )

        fun pending(requiredCapability: SourceAccessCapability) = unavailable(
            requiredCapability,
            when (requiredCapability) {
                SourceAccessCapability.APP -> "等待应用访问检查"
                SourceAccessCapability.SAF -> "等待系统目录授权"
                SourceAccessCapability.ROOT -> "等待 Root 权限检查"
                SourceAccessCapability.ALL_FILES -> "等待「所有文件访问」授权"
                SourceAccessCapability.UNAVAILABLE -> "等待来源检查"
            },
        )
    }
}

fun SourceSelection.requiredAccessCapability(): SourceAccessCapability = when (this) {
    is SourceSelection.Saf -> SourceAccessCapability.SAF
    is SourceSelection.Root -> SourceAccessCapability.ROOT
}

/**
 * A source the last scan proved missing or unreachable (or a Root source after
 * su was refused) is shown unchecked and cannot be checked. Its stored choice
 * is kept, so it is picked up again once the directory appears.
 */
object SourceOptionPolicy {
    fun unreachable(
        option: SaveSourceOption,
        scannedAccess: SourceAccessStatus?,
        rootChecked: Boolean,
        rootAvailable: Boolean,
        /**
         * 「所有文件访问」已授予。有它时路径型来源不需要 su，
         * 所以 root 缺席不再等于不可达。
         */
        allFilesGranted: Boolean = false,
    ): Boolean = when {
        scannedAccess != null -> !scannedAccess.readable
        else -> option.requiredAccess == SourceAccessCapability.ROOT &&
            rootChecked &&
            !rootAvailable &&
            !allFilesGranted
    }

    /** Whether the row shows a check mark. */
    fun displayedChecked(option: SaveSourceOption, unreachable: Boolean): Boolean =
        option.selected && !unreachable
}
