package com.jun9100.opensave

import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** A game-save address and sync key read from the clipboard, before the user confirms it. */
data class ImportedGameSaveConfig(
    /** Normalized with [GameSaveClient.normalizeServiceUrl]. */
    val serviceUrl: String,
    val syncKey: String,
) {
    /** For the confirmation: the key is never shown whole. */
    val maskedSyncKey: String
        get() = GameSaveConfigLink.maskToken(syncKey)

    /** The key must never reach a log or crash report through an implicit data-class print. */
    override fun toString(): String =
        "ImportedGameSaveConfig(serviceUrl=$serviceUrl, syncKey=***)"
}

/**
 * "从剪贴板导入配置" (plan A15): accepts exactly one of
 * `opensave://game-save?url=<encoded>&token=<encoded>` or the JSON object
 * `{"url": "...", "token": "..."}`. Anything else, an unknown field, a repeated
 * field or text around the link is refused rather than guessed at.
 */
object GameSaveConfigLink {
    fun parse(text: String?): Result<ImportedGameSaveConfig> = runCatching {
        val raw = text?.trim().orEmpty()
        require(raw.isNotEmpty()) { "剪贴板里没有文字" }
        require(raw.length <= MAX_LENGTH) { "剪贴板内容过长，不像 SaveVault 配置" }
        val (url, token) = when {
            raw.startsWith("{") -> fromJson(raw)
            raw.startsWith("$SCHEME://", ignoreCase = true) -> fromLink(raw)
            else -> throw IllegalArgumentException("剪贴板里不是 SaveVault 配置链接（opensave://game-save?...）")
        }
        validated(url, token)
    }

    /** First and last two characters around a fixed-width mask; short keys show only the mask. */
    fun maskToken(token: String): String =
        if (token.length < MASK_REVEAL_MIN) MASK else "${token.take(2)}$MASK${token.takeLast(2)}"

    private fun fromLink(raw: String): Pair<String, String> {
        require(raw.none(Char::isWhitespace)) { "配置链接中不能有空格或换行" }
        val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("配置链接格式无效") }
        require(uri.scheme.equals(SCHEME, ignoreCase = true) && uri.rawAuthority.equals(HOST, ignoreCase = true)) {
            "只支持 opensave://game-save 配置链接"
        }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "配置链接不能包含路径" }
        require(uri.rawFragment == null) { "配置链接不能包含 # 片段" }
        val query = uri.rawQuery ?: throw IllegalArgumentException("配置链接缺少 url 和 token")
        val fields = linkedMapOf<String, String>()
        query.split('&').forEach { part ->
            val separator = part.indexOf('=')
            require(separator > 0) { "配置链接参数格式无效" }
            val name = part.substring(0, separator)
            require(name in FIELDS) { "配置链接包含未知参数 $name" }
            require(name !in fields) { "配置链接重复了参数 $name" }
            fields[name] = runCatching { URLDecoder.decode(part.substring(separator + 1).replace("+", "%2B"), Charsets.UTF_8.name()) }
                .getOrElse { throw IllegalArgumentException("配置链接参数 $name 编码无效") }
        }
        return fields["url"].orEmpty() to fields["token"].orEmpty()
    }

    private fun fromJson(raw: String): Pair<String, String> {
        require(raw.endsWith("}")) { "配置 JSON 后面不能有其他内容" }
        val json = try {
            JSONObject(raw)
        } catch (error: JSONException) {
            throw IllegalArgumentException("配置 JSON 格式无效")
        }
        val names = json.keys().asSequence().toList()
        names.firstOrNull { it !in FIELDS }?.let { throw IllegalArgumentException("配置 JSON 包含未知字段 $it") }
        fun field(name: String): String {
            if (!json.has(name)) return ""
            return json.get(name) as? String ?: throw IllegalArgumentException("配置 JSON 的 $name 必须是文字")
        }
        return field("url") to field("token")
    }

    private fun validated(url: String, token: String): ImportedGameSaveConfig {
        require(url.isNotBlank()) { "配置缺少 SaveVault 云端地址（url）" }
        val trimmedToken = token.trim()
        require(trimmedToken.isNotEmpty()) { "配置缺少同步密钥（token）" }
        require(trimmedToken.none(Char::isWhitespace) && trimmedToken.none(Char::isISOControl)) { "同步密钥不能包含空格或控制字符" }
        require(trimmedToken.length <= MAX_TOKEN_LENGTH) { "同步密钥过长" }
        val normalized = GameSaveClient.normalizeServiceUrl(url)
        return ImportedGameSaveConfig(serviceUrl = normalized, syncKey = trimmedToken)
    }

    private const val SCHEME = "opensave"
    private const val HOST = "game-save"
    private val FIELDS = setOf("url", "token")
    private const val MAX_LENGTH = 4096
    private const val MAX_TOKEN_LENGTH = 1024
    private const val MASK = "••••••••"
    private const val MASK_REVEAL_MIN = 10
}
