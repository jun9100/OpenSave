package com.jun9100.opensave

/** Result lines of batch operations: only the counts that are not zero (plan §5.6). */
object BatchSummary {
    /** "新建快照 3 款，无变化 2 款"; empty when every count is zero. */
    fun counts(parts: List<Pair<String, Int>>, unit: String = "款"): String = parts
        .filter { (_, count) -> count > 0 }
        .joinToString("，") { (label, count) -> "$label $count $unit" }

    fun backup(created: Int, unchanged: Int, failed: Int, uploadFailed: Int, cloudPending: Int): String {
        val counts = counts(
            listOf(
                "新建快照" to created,
                "无变化" to unchanged,
                "失败" to failed,
                "云端上传失败" to uploadFailed,
                "跨设备待处理" to cloudPending,
            ),
        )
        return "批量备份完成：" + counts.ifEmpty { "没有处理任何游戏" }
    }
}
