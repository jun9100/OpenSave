package com.jun9100.opensave

import java.text.Normalizer
import org.json.JSONObject

/** Pure rules for where a game's cover can come from besides a known Steam AppID or Switch TitleID. */
object GameArtworkSources {
    /**
     * Emulators by save kind, in the order their icons are tried. Their
     * launcher activities make them visible through the manifest's launcher
     * `<queries>`; no package needs listing there.
     */
    private val emulatorPackagesByKind = mapOf(
        "ppsspp" to listOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"),
        "retroarch" to listOf("com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32"),
        "dolphin" to listOf("org.dolphinemu.dolphinemu", "org.mm.jr"),
        "duckstation" to listOf("com.github.stenzek.duckstation"),
        "aethersx2" to listOf("xyz.aethersx2.android", "xyz.aethersx2.custom"),
        "citra" to listOf("org.citra.citra_emu", "io.github.lime3ds.android", "org.azahar_emu.azahar", "com.panda3ds.pandroid"),
        "melonds" to listOf("me.magnum.melonds"),
        "vita3k" to listOf("org.vita3k.emulator"),
        "mupen64plus" to listOf("org.mupen64plusae.v3.fzurita"),
        "drastic" to listOf("com.dsemu.drastic"),
        "lemuroid" to listOf("com.swordfish.lemuroid"),
        "epsxe" to listOf("com.epsxe.ePSXe", "com.xiaoji.emulator"),
        "mame" to listOf("com.seleuco.mame4droid", "com.xiaoji.emulator"),
    )

    fun emulatorPackages(kind: String): List<String> = emulatorPackagesByKind[kind].orEmpty()

    /** PPSSPP writes the game's 144×80 icon into every save folder. */
    const val PSP_ICON_FILE = "ICON0.PNG"
    const val MAX_PSP_ICON_BYTES = 512 * 1024

    /**
     * `Half-Life 2™` and `half life 2` compare equal; `Half-Life 2: Update`
     * does not. The marks go before NFKC, which would turn ™ into "TM".
     */
    fun normalizedTitle(name: String): String = Normalizer.normalize(name.replace(Regex("[™®©]"), ""), Normalizer.Form.NFKC)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    /**
     * The AppID of the Steam store search result whose name equals [name]
     * exactly (after [normalizedTitle]). The search is fuzzy, so anything
     * short of an exact match is left without a cover rather than given a
     * wrong one.
     */
    fun steamAppIdForExactName(searchJson: String, name: String): String? {
        val wanted = normalizedTitle(name).takeIf(String::isNotBlank) ?: return null
        val items = runCatching { JSONObject(searchJson).optJSONArray("items") }.getOrNull() ?: return null
        return (0 until items.length())
            .mapNotNull(items::optJSONObject)
            .filter { it.optString("type", "app") == "app" }
            .firstOrNull { normalizedTitle(it.optString("name")) == wanted }
            ?.optLong("id")
            ?.takeIf { it > 0 }
            ?.toString()
    }
}
