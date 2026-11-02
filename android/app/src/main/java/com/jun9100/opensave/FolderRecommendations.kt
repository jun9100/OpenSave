package com.jun9100.opensave

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Folders worth granting through the system picker on a device without
 * Root, for the emulators and folders actually present. Android 11+ lets the
 * picker grant neither Android/data nor the whole shared storage, so each
 * folder is granted on its own; some emulators publish their private files
 * through a documents provider that the picker lists in its side menu.
 */
data class FolderRecommendation(
    val title: String,
    /** What scanning it finds. */
    val finds: String,
    /** Where it is in the picker; also the reason when it cannot be granted. */
    val where: String,
    /** The documents provider and document the picker opens on; null for an entry that only explains why Root is needed. */
    val authority: String?,
    val documentId: String?,
    val granted: Boolean = false,
) {
    val needsRoot: Boolean
        get() = authority == null || documentId == null

    val initialUri: Uri?
        get() = if (needsRoot) null else DocumentsContract.buildDocumentUri(authority, documentId)
}

/** One known location; [build] keeps those whose app is installed or whose folder exists. */
internal data class RecommendationTemplate(
    val title: String,
    val finds: String,
    val where: String,
    /** Shown when this package is installed. */
    val packageName: String? = null,
    /** Shown when this folder exists in shared storage (relative to its root). */
    val sharedFolder: String? = null,
    /** A documents provider: its authority and the document the picker opens on. */
    val providerAuthority: String? = null,
    val providerDocumentId: String? = null,
)

object FolderRecommendations {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val WHOLE_STORAGE_DOCUMENT = "primary:"

    /**
     * Android 10 and older still let the picker grant the whole shared
     * storage, Android/data included: one grant then reaches every emulator
     * folder and native Android games' saves, without Root.
     */
    private val wholeStorage = RecommendationTemplate(
        "整个内部存储（推荐）",
        "所有模拟器、PC 和安卓游戏的存档，包括 Android/data",
        "选择器打开内部存储后直接点“使用此文件夹”",
    )

    internal val templates = listOf(
        RecommendationTemplate(
            "Eden 用户数据", "Switch 存档", "选择器左侧菜单 → Eden",
            packageName = "dev.eden.eden_emulator",
            providerAuthority = "dev.eden.eden_emulator.user", providerDocumentId = "root/",
        ),
        RecommendationTemplate("Eden 公共目录 eden", "Switch 存档", "内部存储 → eden", sharedFolder = "eden"),
        RecommendationTemplate(
            "DroidDeck", "PC 游戏存档", "选择器左侧菜单 → DroidDeck",
            packageName = "com.droiddeck.launcher",
            providerAuthority = "com.droiddeck.launcher.documents", providerDocumentId = "/data/user/0/com.droiddeck.launcher",
        ),
        RecommendationTemplate(
            "Winlator 6.1（鲁大师版）", "PC 游戏存档", "选择器左侧菜单 → Winlator6.1",
            packageName = "com.ludashi.benchmark",
            providerAuthority = "com.ludashi.benchmark.MTDataFilesProvider", providerDocumentId = "com.ludashi.benchmark",
        ),
        RecommendationTemplate("PC 游戏公共目录 pc-game", "PC 游戏存档", "内部存储 → pc-game", sharedFolder = "pc-game"),
        RecommendationTemplate("PPSSPP", "PSP 存档", "内部存储 → PSP", sharedFolder = "PSP"),
        RecommendationTemplate("RetroArch", "RetroArch 存档和即时存档", "内部存储 → RetroArch", sharedFolder = "RetroArch"),
        RecommendationTemplate("Dolphin", "GC / Wii 存档", "内部存储 → dolphin-emu", sharedFolder = "dolphin-emu"),
        RecommendationTemplate("DuckStation", "PS1 记忆卡和即时存档", "内部存储 → DuckStation", sharedFolder = "DuckStation"),
        RecommendationTemplate("AetherSX2", "PS2 记忆卡和即时存档", "内部存储 → AetherSX2", sharedFolder = "AetherSX2"),
        RecommendationTemplate("NetherSX2", "PS2 记忆卡和即时存档", "内部存储 → NetherSX2", sharedFolder = "NetherSX2"),
        RecommendationTemplate("ePSXe（小鸡模拟器 PS1）", "PS1 记忆卡和即时存档", "内部存储 → epsxe", sharedFolder = "epsxe"),
        RecommendationTemplate("小鸡模拟器 XiaoJi", "街机（MAME）存档", "内部存储 → XiaoJi", sharedFolder = "XiaoJi"),
        RecommendationTemplate(
            "GameHub", "PC 游戏存档", "存档在 GameHub 私有目录，没有授权入口，只能用 Root 读取",
            packageName = "com.xiaoji.egggame",
        ),
        RecommendationTemplate(
            "Winlator", "PC 游戏存档", "存档在 Winlator 私有目录，没有授权入口，只能用 Root 读取",
            packageName = "com.winlator",
        ),
    )

    /**
     * The recommendations for this device, granted ones marked. [installed]
     * and [folderExists] are injected so the rules are unit tested.
     */
    fun build(
        installed: (String) -> Boolean,
        folderExists: (String) -> Boolean,
        /** [treeKey] of each granted folder. */
        grantedKeys: Set<String>,
        /** Build.VERSION.SDK_INT; 29 (Android 10) and older can grant the whole storage. */
        sdkInt: Int,
    ): List<FolderRecommendation> {
        val granted = grantedKeys.map(String::lowercase).toSet()
        val wholeStorageKey = "$EXTERNAL_STORAGE_AUTHORITY/$WHOLE_STORAGE_DOCUMENT".lowercase()
        val wholeStorageGranted = wholeStorageKey in granted
        val whole = if (sdkInt <= WHOLE_STORAGE_MAX_SDK) {
            listOf(
                FolderRecommendation(
                    title = wholeStorage.title,
                    finds = wholeStorage.finds,
                    where = wholeStorage.where,
                    authority = EXTERNAL_STORAGE_AUTHORITY,
                    documentId = WHOLE_STORAGE_DOCUMENT,
                    granted = wholeStorageGranted,
                ),
            )
        } else {
            emptyList()
        }
        return whole + templates.mapNotNull { template ->
            val present = (template.packageName?.let(installed) ?: false) ||
                (template.sharedFolder?.let(folderExists) ?: false)
            if (!present) return@mapNotNull null
            val (authority, documentId) = when {
                template.providerAuthority != null -> template.providerAuthority to template.providerDocumentId.orEmpty()
                template.sharedFolder != null -> EXTERNAL_STORAGE_AUTHORITY to "primary:${template.sharedFolder}"
                else -> null to null
            }
            FolderRecommendation(
                title = template.title,
                finds = template.finds,
                where = template.where,
                authority = authority,
                documentId = documentId,
                // A folder inside a granted whole storage needs no grant of its own.
                granted = authority != null && (
                    "$authority/$documentId".lowercase() in granted ||
                        (wholeStorageGranted && authority == EXTERNAL_STORAGE_AUTHORITY)
                    ),
            )
        }
    }

    /** Android 10 and older; from Android 11 the picker refuses the storage root and Android/data. */
    const val WHOLE_STORAGE_MAX_SDK = 29

    /** `authority/treeDocumentId`, the identity a recommendation is matched against. */
    fun treeKey(uri: Uri): String? = runCatching {
        "${uri.authority}/${DocumentsContract.getTreeDocumentId(uri)}"
    }.getOrNull()

    fun forDevice(context: Context, grantedTrees: List<Uri>): List<FolderRecommendation> {
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        val sharedRoot = Environment.getExternalStorageDirectory()
        return build(
            installed = { packageName ->
                runCatching { packageManager.getPackageInfo(packageName, 0) }.isSuccess
            },
            // Directory names in shared storage are visible without a storage permission.
            folderExists = { folder -> File(sharedRoot, folder).isDirectory },
            grantedKeys = grantedTrees.mapNotNull(::treeKey).toSet(),
            sdkInt = android.os.Build.VERSION.SDK_INT,
        )
    }
}
