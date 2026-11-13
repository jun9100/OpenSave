package com.jun9100.opensave

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 上一次扫描留在磁盘上的样子。
 *
 * 冷启动时没有任何东西可画：ViewModel 是新的，`scan` 为空，于是用户先对着一个
 * 空列表等整轮扫描（全盘来源在这台机器上要 16 秒）。把上次扫出来的东西存下来，
 * 启动就先渲染它，扫描在后台继续——数据没有变新，但界面不再是空的。
 *
 * [fingerprint] 同时承担另一件事：判断"这个来源这一轮还用不用再扫一遍"。
 */
data class SourceScanSnapshot(
    val result: SourceScanResult,
    val fingerprint: List<String>? = null,
)

data class LibraryScanSnapshot(
    val savedAt: Long,
    val sources: List<SourceScanSnapshot>,
) {
    fun resultOf(sourceId: String): SourceScanResult? =
        sources.firstOrNull { it.result.sourceId == sourceId }?.result

    fun fingerprintOf(sourceId: String): List<String>? =
        sources.firstOrNull { it.result.sourceId == sourceId }?.fingerprint
}

/**
 * 快照的 JSON 编解码。字段是手写的：仓库没有引入 kotlinx.serialization，
 * 而这些结构一共七个字段级别，手写比加依赖便宜。
 *
 * 解码一律"能读多少读多少"：格式对不上就当没有快照，走原来的全量扫描。
 */
object ScanReuse {
    /**
     * 能不能拿上次的结果顶替这一轮扫描。
     *
     * 空指纹一律不许：算不出指纹（SAF、无权限）时它和"缓存里也是空"长得一样，
     * 放过去就等于永不重扫。
     */
    fun canSkip(cached: List<String>?, current: List<String>): Boolean =
        current.isNotEmpty() && cached != null && cached == current
}

object ScanSnapshotCodec {
    /** 结构变了就加一，老快照会被直接丢弃而不是误解析。 */
    const val VERSION = 1

    fun encode(snapshot: LibraryScanSnapshot): String = JSONObject().apply {
        put("version", VERSION)
        put("savedAt", snapshot.savedAt)
        put("sources", JSONArray().apply {
            for (source in snapshot.sources) put(encodeSource(source))
        })
    }.toString()

    fun decode(text: String): LibraryScanSnapshot? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optInt("version", -1) != VERSION) return null
        val array = root.optJSONArray("sources") ?: return null
        val sources = ArrayList<SourceScanSnapshot>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            runCatching { decodeSource(item) }.getOrNull()?.let(sources::add)
        }
        if (sources.isEmpty()) return null
        return LibraryScanSnapshot(root.optLong("savedAt", 0L), sources)
    }

    private fun encodeSource(source: SourceScanSnapshot): JSONObject = JSONObject().apply {
        put("source", encodeSelection(source.result.source))
        put("label", source.result.label)
        put("access", encodeAccess(source.result.access))
        source.result.skippedReason?.let { put("skippedReason", it) }
        source.result.scan?.let { put("scan", encodeReport(it)) }
        source.fingerprint?.let { print ->
            put("fingerprint", JSONArray().apply { print.forEach(::put) })
        }
    }

    private fun decodeSource(item: JSONObject): SourceScanSnapshot? {
        val selection = decodeSelection(item.optJSONObject("source") ?: return null) ?: return null
        val access = item.optJSONObject("access")?.let(::decodeAccess)
            ?: SourceAccessStatus.pending(selection.requiredAccessCapability())
        val report = item.optJSONObject("scan")?.let(::decodeReport)
        val fingerprint = item.optJSONArray("fingerprint")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it, null) }
        }
        return SourceScanSnapshot(
            result = SourceScanResult(
                source = selection,
                label = item.optString("label", selection.displayPath),
                scan = report,
                skippedReason = item.optString("skippedReason", null),
                access = access,
            ),
            fingerprint = fingerprint,
        )
    }

    private fun encodeSelection(selection: SourceSelection): JSONObject = when (selection) {
        is SourceSelection.Saf -> JSONObject().put("kind", "saf").put("uri", selection.uri.toString())
        is SourceSelection.Root -> JSONObject().put("kind", "root").put("path", selection.path)
    }

    private fun decodeSelection(item: JSONObject): SourceSelection? = when (item.optString("kind")) {
        "saf" -> item.optString("uri", "").takeIf { it.isNotEmpty() }?.let { SourceSelection.Saf(Uri.parse(it)) }
        "root" -> item.optString("path", "").takeIf { it.isNotEmpty() }?.let { SourceSelection.Root(it) }
        else -> null
    }

    private fun encodeAccess(access: SourceAccessStatus): JSONObject = JSONObject().apply {
        put("capability", access.capability.name)
        put("required", access.requiredCapability.name)
        put("readable", access.readable)
        put("writable", access.writable)
        put("reason", access.reason)
    }

    private fun decodeAccess(item: JSONObject): SourceAccessStatus = SourceAccessStatus(
        capability = enumOf(item.optString("capability"), SourceAccessCapability.APP),
        requiredCapability = enumOf(item.optString("required"), SourceAccessCapability.APP),
        readable = item.optBoolean("readable", true),
        writable = item.optBoolean("writable", true),
        reason = item.optString("reason", ""),
    )

    private fun encodeReport(report: SaveScanReport): JSONObject = JSONObject().apply {
        put("rootPath", report.rootPath)
        put("scannedDirectoryCount", report.scannedDirectoryCount)
        put("unreadableDirectoryCount", report.unreadableDirectoryCount)
        put("truncated", report.truncated)
        put("detected", JSONArray().apply {
            report.detected.forEach { save ->
                put(
                    JSONObject().apply {
                        put("kind", save.kind)
                        put("relativePath", save.relativePath)
                        put("note", save.note)
                        save.titleId?.let { put("titleId", it) }
                        save.displayName?.let { put("displayName", it) }
                        save.gameKey?.let { put("gameKey", it) }
                        put("candidate", save.candidate)
                    },
                )
            }
        })
    }

    private fun decodeReport(item: JSONObject): SaveScanReport {
        val detected = item.optJSONArray("detected")
        val saves = ArrayList<DetectedSave>(detected?.length() ?: 0)
        if (detected != null) {
            for (index in 0 until detected.length()) {
                val save = detected.optJSONObject(index) ?: continue
                val kind = save.optString("kind", "").takeIf { it.isNotEmpty() } ?: continue
                saves.add(
                    DetectedSave(
                        kind = kind,
                        relativePath = save.optString("relativePath", ""),
                        titleId = save.optString("titleId", null),
                        note = save.optString("note", ""),
                        displayName = save.optString("displayName", null),
                        gameKey = save.optString("gameKey", null),
                        candidate = save.optBoolean("candidate", false),
                    ),
                )
            }
        }
        return SaveScanReport(
            rootPath = item.optString("rootPath", ""),
            scannedDirectoryCount = item.optInt("scannedDirectoryCount", 0),
            detected = saves,
            unreadableDirectoryCount = item.optInt("unreadableDirectoryCount", 0),
            truncated = item.optBoolean("truncated", false),
        )
    }

    private inline fun <reified T : Enum<T>> enumOf(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}

/** 快照文件：`files/scan/snapshot.json`，原子写入（先写 .tmp 再 rename）。 */
class ScanSnapshotStore(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, "scan"))

    private val file: File get() = File(directory, "snapshot.json")

    companion object {
        /** 正常快照几 KB；超过这个数说明文件不对，直接当没有。 */
        const val MAX_SNAPSHOT_BYTES = 4L shl 20
    }

    fun load(): LibraryScanSnapshot? {
        // 这行在主线程上跑（启动就要画），所以先看大小：几十 KB 是一次毫秒级读，
        // 但一份被写坏/塞满的大文件不能把启动拖住。
        val text = runCatching {
            if (!file.isFile || file.length() > MAX_SNAPSHOT_BYTES) null else file.readText()
        }.getOrNull() ?: return null
        return ScanSnapshotCodec.decode(text)
    }

    fun save(snapshot: LibraryScanSnapshot) {
        runCatching {
            directory.mkdirs()
            val temp = File(directory, "snapshot.json.tmp")
            temp.writeText(ScanSnapshotCodec.encode(snapshot))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    fun clear() {
        runCatching {
            file.delete()
            File(directory, "snapshot.json.tmp").delete()
        }
    }
}

/**
 * `find -printf '%T@ %p'` 与 `stat -c '%Y %n'` 两种输出的解析。
 *
 * toybox 的 `find` 不一定有 `-printf`，所以两条命令都要能读懂；时间可能是
 * 小数秒（`%T@`）也可能是整秒（`%Y`），路径里可以有空格——按第一个空格切开就好。
 */
object DirStamps {
    /**
     * 指纹超过 [maxEntries] 就算不可用。
     *
     * 走不完的指纹只能证明"我看过的那部分没变"，用它判定整轮复用会把新存档挡在
     * 门外；宁可返回空表，让调用方退化成整轮重扫。
     */
    fun limit(stamps: List<String>, maxEntries: Int): List<String> =
        if (stamps.size > maxEntries) emptyList() else stamps

    fun parseLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val space = trimmed.indexOf(' ')
        if (space <= 0) return null
        val seconds = trimmed.substring(0, space).toDoubleOrNull() ?: return null
        val path = trimmed.substring(space + 1).trim()
        if (path.isEmpty()) return null
        return "$path\t${(seconds * 1000).toLong()}"
    }
}
