package com.jun9100.opensave

import java.io.FileNotFoundException

/** Why a granted folder could not be read, in words shown to the user. */
object SafFailures {
    const val NOT_A_DIRECTORY = "目录不存在、已被移动，或授权已失效"
    const val PROVIDER_UNAVAILABLE = "提供这个目录的应用不可用，可能已被卸载"
    const val PERMISSION_REVOKED = "授权已被撤销，请重新授权"

    /**
     * The reason for [error]; the system's own exceptions carry English
     * developer messages, so the known kinds get their own text.
     */
    fun reason(error: Throwable): String = when (error) {
        is SecurityException -> PERMISSION_REVOKED
        is FileNotFoundException -> NOT_A_DIRECTORY
        else -> error.message?.takeIf(String::isNotBlank) ?: "读取失败"
    }
}
