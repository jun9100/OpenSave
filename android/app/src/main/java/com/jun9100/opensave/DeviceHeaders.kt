package com.jun9100.opensave

/**
 * Encodes user-visible text carried in HTTP headers.
 *
 * OkHttp rejects header values outside printable ASCII, while device names come
 * from [android.os.Build.MODEL] and may later be user-chosen, so any script can
 * appear in them. Plain ASCII names stay readable for older servers and logs;
 * everything else travels as an RFC 8187 ext-value that the server decodes
 * before display.
 */
object DeviceHeaders {
    private const val PREFIX = "UTF-8''"
    private const val HEX = "0123456789ABCDEF"

    fun encodeName(name: String): String {
        val cleaned = name.filterNot(Char::isISOControl).trim()
        // Spaces and punctuation are legal header characters, so only fall back
        // to percent-encoding when the value would actually be rejected.
        if (cleaned.all { it in ' '..'~' }) return cleaned
        return buildString(PREFIX.length + cleaned.length * 3) {
            append(PREFIX)
            for (byte in cleaned.toByteArray(Charsets.UTF_8)) {
                val value = byte.toInt() and 0xFF
                val char = value.toChar()
                if (isUnreserved(char)) {
                    append(char)
                } else {
                    append('%')
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0F])
                }
            }
        }
    }

    private fun isUnreserved(char: Char): Boolean =
        char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
            char == '-' || char == '.' || char == '_' || char == '~'
}
