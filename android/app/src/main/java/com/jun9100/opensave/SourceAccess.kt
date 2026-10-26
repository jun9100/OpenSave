package com.jun9100.opensave

/**
 * The access boundary is part of a source's identity. It must be shown before
 * an empty scan can be interpreted as a source with no saves.
 */
enum class SourceAccessCapability {
    APP,
    SAF,
    ROOT,
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
                SourceAccessCapability.UNAVAILABLE -> "等待来源检查"
            },
        )
    }
}

fun SourceSelection.requiredAccessCapability(): SourceAccessCapability = when (this) {
    is SourceSelection.Saf -> SourceAccessCapability.SAF
    is SourceSelection.Root -> SourceAccessCapability.ROOT
}
