package com.jun9100.opensave

import android.content.Context
import java.net.URLDecoder
import org.json.JSONObject

/**
 * Names the built-in catalog does not know: Switch titles learned from the
 * emulator's own game list or from ROM file names, and names the user typed.
 * Process-wide so the scanner, the library and the workers agree; [revision]
 * changes whenever a name does, so the UI can recompute its rows.
 */
object GameNames {
    @Volatile private var titles: Map<String, String> = emptyMap()
    @Volatile private var overrides: Map<String, String> = emptyMap()
    @Volatile var revision: Long = 0L
        private set

    /** Learned name for a Switch TitleID (upper-case hex). */
    fun switchTitle(titleId: String): String? = titles[titleId.uppercase()]

    /** The user's own name for a game, keyed by its game key. */
    fun override(gameKey: String): String? = overrides[gameKey]

    /** Another name source changed (the bundled catalog finished loading). */
    @Synchronized
    fun touch() {
        revision++
    }

    @Synchronized
    fun replace(titles: Map<String, String>, overrides: Map<String, String>) {
        if (titles == this.titles && overrides == this.overrides) return
        this.titles = titles
        this.overrides = overrides
        revision++
    }
}

/** Persists [GameNames] and loads it at startup. */
class GameNameStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load() = synchronized(LOCK) {
        GameNames.replace(decode(preferences.getString(TITLES_KEY, null)), decode(preferences.getString(OVERRIDES_KEY, null)))
    }

    /** Merges learned Switch titles; an existing title is replaced only by a non-blank new one. */
    fun learnSwitchTitles(learned: Map<String, String>) = synchronized(LOCK) {
        if (learned.isEmpty()) return@synchronized
        val titles = decode(preferences.getString(TITLES_KEY, null)) + learned.mapKeys { it.key.uppercase() }
        preferences.edit().putString(TITLES_KEY, encode(titles)).commit()
        load()
    }

    /** Sets or, with a blank [name], clears the user's name for [gameKey]. */
    fun rename(gameKey: String, name: String) = synchronized(LOCK) {
        val current = decode(preferences.getString(OVERRIDES_KEY, null))
        val next = if (name.isBlank()) current - gameKey else current + (gameKey to name.trim().take(MAX_NAME_LENGTH))
        preferences.edit().putString(OVERRIDES_KEY, encode(next)).commit()
        load()
    }

    companion object {
        private val LOCK = Any()
        private const val PREFERENCES = "opensave_game_names"
        private const val TITLES_KEY = "switch_titles"
        private const val OVERRIDES_KEY = "overrides"
        const val MAX_NAME_LENGTH = 80

        private fun encode(map: Map<String, String>): String =
            JSONObject().apply { map.forEach { (key, value) -> put(key, value) } }.toString()

        private fun decode(json: String?): Map<String, String> {
            if (json.isNullOrBlank()) return emptyMap()
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
            return root.keys().asSequence()
                .mapNotNull { key -> root.optString(key).takeIf(String::isNotBlank)?.let { key to it } }
                .toMap()
        }
    }
}

/** Pure parsers for the places a Switch title's name can be found on the device. */
object SwitchTitleNameSources {
    private val titleIdPattern = Regex("^[0-9A-F]{16}$")
    private val bracketedTitleId = Regex("\\[([0-9A-Fa-f]{16})]")
    private val gamesSet = Regex("<set name=\"Games\">(.*?)</set>", RegexOption.DOT_MATCHES_ALL)
    private val setString = Regex("<string>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

    /**
     * Eden/Yuzu keep their game list in shared preferences as a string set
     * named "Games"; each item is JSON with a `title` and the decimal
     * `programId`.
     */
    fun fromEmulatorPreferences(xml: String): Map<String, String> {
        val set = gamesSet.find(xml)?.groupValues?.get(1) ?: return emptyMap()
        return setString.findAll(set).mapNotNull { match ->
            val json = runCatching { JSONObject(unescapeXml(match.groupValues[1])) }.getOrNull() ?: return@mapNotNull null
            val title = json.optString("title").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val titleId = programIdToTitleId(json.optString("programId")) ?: return@mapNotNull null
            titleId to title
        }.toMap()
    }

    /** ROM folders the emulator's game list points at, as absolute shared-storage paths. */
    fun romDirectories(xml: String): Set<String> {
        val set = gamesSet.find(xml)?.groupValues?.get(1) ?: return emptySet()
        return setString.findAll(set).mapNotNull { match ->
            val json = runCatching { JSONObject(unescapeXml(match.groupValues[1])) }.getOrNull() ?: return@mapNotNull null
            treeUriToPath(json.optString("path"))
        }.toSet()
    }

    /** `Danganronpa V3 Anniversary Edition [01001AB0140F2000] [v1.0.2].xci` → TitleID to name. */
    fun fromRomFileName(fileName: String): Pair<String, String>? {
        val match = bracketedTitleId.find(fileName) ?: return null
        val titleId = match.groupValues[1].uppercase()
        val name = fileName.substring(0, match.range.first)
            .replace(Regex("^\\[[^]]*]"), "")
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '.')
        if (name.isBlank()) return null
        return titleId to name
    }

    fun fromRomFileNames(fileNames: Iterable<String>): Map<String, String> =
        fileNames.mapNotNull(::fromRomFileName).toMap()

    internal fun programIdToTitleId(programId: String): String? {
        val value = programId.trim().toULongOrNull() ?: return null
        return value.toString(16).uppercase().padStart(16, '0').takeIf(titleIdPattern::matches)
    }

    /** `content://…/tree/primary%3Aswitch-roms/document/primary%3Aswitch-roms%2FGame.xci` → `/storage/emulated/0/switch-roms`. */
    internal fun treeUriToPath(uri: String): String? {
        val document = uri.substringAfter("/document/", "").takeIf(String::isNotBlank) ?: return null
        val decoded = runCatching { URLDecoder.decode(document, Charsets.UTF_8.name()) }.getOrNull() ?: return null
        if (!decoded.startsWith("primary:")) return null
        val relative = decoded.removePrefix("primary:").substringBeforeLast('/', "")
        if (relative.isBlank() || relative.contains("..")) return null
        return "/storage/emulated/0/$relative"
    }

    private fun unescapeXml(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}

/**
 * Android apps the user said are games. Apps Android itself does not mark as
 * games stay out of the library (they are listed on the 来源 page) until the
 * user confirms them here.
 */
object AndroidAppConfirmations {
    @Volatile private var confirmed: Set<String> = emptySet()

    fun isConfirmed(packageName: String): Boolean = packageName in confirmed

    internal fun replace(packages: Set<String>) {
        confirmed = packages
    }
}

class AndroidAppConfirmationStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load() = synchronized(LOCK) {
        AndroidAppConfirmations.replace(preferences.getStringSet(CONFIRMED_KEY, emptySet()).orEmpty().toSet())
    }

    fun set(packageName: String, isGame: Boolean) = synchronized(LOCK) {
        val current = preferences.getStringSet(CONFIRMED_KEY, emptySet()).orEmpty().toSet()
        val next = if (isGame) current + packageName else current - packageName
        preferences.edit().putStringSet(CONFIRMED_KEY, next).commit()
        load()
    }

    private companion object {
        val LOCK = Any()
        const val PREFERENCES = "opensave_android_games"
        const val CONFIRMED_KEY = "confirmed_packages"
    }
}

/**
 * Names and eShop icon hashes of about 24,000 Switch titles bundled with the
 * app (assets/switch-titles.tsv, built by scripts/build_switch_titles.py from
 * blawar/titledb), so a save copied from another device is named without the
 * game being installed here. Loaded once, off the main thread.
 */
object OfflineSwitchTitles {
    data class Title(val name: String, val iconHash: String?)

    @Volatile private var titles: Map<String, Title> = emptyMap()

    fun name(titleId: String): String? = titles[titleId.uppercase()]?.name

    fun icon(titleId: String): String? = titles[titleId.uppercase()]?.iconHash

    fun load(context: Context) {
        if (titles.isNotEmpty()) return
        titles = runCatching {
            // Stored uncompressed: the APK zip compresses it, and the build
            // would unpack a .gz asset anyway.
            context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).useLines(::parse)
        }.getOrDefault(emptyMap())
    }

    /** Lines are `TitleID<TAB>name[<TAB>icon hash]`. */
    internal fun parse(lines: Sequence<String>): Map<String, Title> = lines
        .mapNotNull { line ->
            val columns = line.split('\t')
            if (columns.size < 2 || columns[0].length != 16) return@mapNotNull null
            val name = columns[1].trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val icon = columns.getOrNull(2)?.trim()?.takeIf(iconHashPattern::matches)
            columns[0].uppercase() to Title(name, icon)
        }
        .toMap(HashMap())

    private val iconHashPattern = Regex("^[0-9a-f]{64}$")
    private const val ASSET = "switch-titles.tsv"
}
