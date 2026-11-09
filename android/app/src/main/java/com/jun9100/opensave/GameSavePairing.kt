package com.jun9100.opensave

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Crockford Base32 pairing codes, as printed by the SaveVault 云端 pairing dialog. */
internal object PairingCode {
    /** Canonical 8-char code, or null when [input] cannot be one (after the doc's normalisation). */
    fun normalize(input: String): String? {
        val canonical = buildString {
            for (character in input) {
                if (character.isWhitespace() || character == '-') continue
                append(
                    when (character.uppercaseChar()) {
                        'O' -> '0'
                        'I', 'L' -> '1'
                        else -> character.uppercaseChar()
                    },
                )
            }
        }
        if (canonical.length != LENGTH || canonical.any { it !in ALPHABET }) return null
        return canonical
    }

    /** "ABCD-EFGH" for display. */
    fun display(code: String): String {
        val canonical = normalize(code)
            ?: code.trim().uppercase(Locale.ROOT).filterNot { it == '-' || it.isWhitespace() }
        return if (canonical.length == LENGTH) "${canonical.take(HALF)}-${canonical.drop(HALF)}" else canonical
    }

    private const val LENGTH = 8
    private const val HALF = 4

    /** Crockford Base32: digits plus A-Z without I, L, O and U. */
    private val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toSet()
}

/** A pairing link before the user confirms the server it points at. */
data class PairingLink(val serverUrl: String, val code: String) {
    /** Host (and non-default port) the user must confirm before anything is sent. */
    val host: String
        get() {
            val uri = runCatching { URI(serverUrl) }.getOrNull() ?: return serverUrl
            val host = uri.host?.takeIf(String::isNotBlank) ?: return serverUrl
            val defaultPort = if (uri.scheme.equals("https", ignoreCase = true)) HTTPS_PORT else HTTP_PORT
            return if (uri.port > 0 && uri.port != defaultPort) "$host:${uri.port}" else host
        }

    private companion object {
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443
    }
}

internal object PairingLinks {
    /**
     * Parses exactly `savevault://pair?v=1&server=…&code=…`. Unknown or
     * repeated fields and text that is not one of these links are refused
     * rather than guessed at, because the server URL decides where saves go.
     */
    fun parse(text: String): Result<PairingLink> = runCatching {
        val raw = text.trim()
        require(raw.isNotEmpty()) { "配对链接为空" }
        require(raw.length <= MAX_LENGTH) { "配对链接过长" }
        val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("配对链接格式无效") }
        require(uri.scheme.equals(SCHEME, ignoreCase = true) && uri.rawAuthority.equals(HOST, ignoreCase = true)) {
            "只支持 savevault://pair 配对链接"
        }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "配对链接不能包含路径" }
        require(uri.rawFragment == null) { "配对链接不能包含 # 片段" }
        val query = uri.rawQuery ?: throw IllegalArgumentException("配对链接缺少 server 和 code")

        val fields = linkedMapOf<String, String>()
        query.split('&').forEach { part ->
            require(part.isNotEmpty()) { "配对链接参数格式无效" }
            val separator = part.indexOf('=')
            require(separator > 0) { "配对链接参数格式无效" }
            val name = part.substring(0, separator)
            require(name in FIELDS) { "配对链接包含未知参数 $name" }
            require(name !in fields) { "配对链接重复了参数 $name" }
            fields[name] = decode(part.substring(separator + 1), name)
        }
        require(fields["v"] == "1") { "不支持的配对链接版本" }

        val server = fields["server"].orEmpty().trim()
        require(server.isNotEmpty()) { "配对链接缺少 SaveVault 云端地址" }
        val normalizedServer = GameSaveClient.normalizeServiceUrl(server)
        val code = PairingCode.normalize(fields["code"].orEmpty()) ?: throw IllegalArgumentException("配对码无效")
        PairingLink(serverUrl = normalizedServer, code = code)
    }

    private fun decode(value: String, name: String): String =
        runCatching {
            // Keep a literal '+' in a service URL; only '%xx' is percent-encoding here.
            URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrElse { throw IllegalArgumentException("配对链接参数 $name 编码无效") }

    private const val SCHEME = "savevault"
    private const val HOST = "pair"
    private const val MAX_LENGTH = 4096
    private val FIELDS = setOf("v", "server", "code")
}
