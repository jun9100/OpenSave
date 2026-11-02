package com.jun9100.opensave.ui.sources

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.AuthorizedSafTree
import com.jun9100.opensave.FolderRecommendation
import com.jun9100.opensave.FolderRecommendations
import com.jun9100.opensave.ui.input.busyDimmed
import com.jun9100.opensave.ui.input.handheldFocusRing

/** This device's recommendations, recomputed when a folder is granted or removed. */
@Composable
internal fun rememberFolderRecommendations(trees: List<AuthorizedSafTree>): List<FolderRecommendation> {
    val context = LocalContext.current
    return remember(trees) { FolderRecommendations.forDevice(context.applicationContext, trees.map(AuthorizedSafTree::uri)) }
}

/**
 * 推荐授权: one row per emulator or folder present on this device. A on a row
 * opens the system picker already on that folder; the user only confirms.
 * Locations only Root can read are listed with the reason, not as rows to press.
 */
@Composable
internal fun RecommendedFoldersCard(
    recommendations: List<FolderRecommendation>,
    busy: Boolean,
    onGrant: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val grantable = recommendations.filterNot(FolderRecommendation::needsRoot)
    val rootOnly = recommendations.filter(FolderRecommendation::needsRoot)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("推荐授权", style = MaterialTheme.typography.titleSmall)
            if (recommendations.isEmpty()) {
                Text(
                    "这台设备上没有找到已知模拟器的目录。请用“添加授权目录”选择模拟器或游戏的存档目录，例如模拟器设置里显示的存档路径。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text(
                "按这台设备上装了的模拟器列出。选一项打开系统选择器：公共目录会直接定位；模拟器的数据在选择器左侧菜单里。选好后点“使用此文件夹”，再点“允许”。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val firstPending = grantable.firstOrNull { !it.granted } ?: grantable.firstOrNull()
            grantable.forEach { recommendation ->
                RecommendationRow(
                    recommendation = recommendation,
                    busy = busy,
                    onGrant = onGrant,
                    focusRequester = focusRequester.takeIf { recommendation === firstPending },
                )
            }
            if (rootOnly.isNotEmpty()) {
                Text(
                    "需要 Root：" + rootOnly.joinToString("；") { "${it.title}（${it.finds}）" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                if (Build.VERSION.SDK_INT <= FolderRecommendations.WHOLE_STORAGE_MAX_SDK) {
                    "授权整个内部存储后，Android/data 里的安卓游戏存档也能读到；放在应用私有目录里的仍需要 Root。"
                } else {
                    "原生安卓游戏的存档在 Android/data 或应用私有目录，Android 11 起系统不允许授权，需要 Root 才能检测。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecommendationRow(
    recommendation: FolderRecommendation,
    busy: Boolean,
    onGrant: (Uri) -> Unit,
    focusRequester: FocusRequester?,
) {
    val uri = recommendation.initialUri ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .handheldFocusRing(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { if (!busy) onGrant(uri) }
            .busyDimmed(busy)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (recommendation.granted) Icons.Outlined.CheckCircle else Icons.Outlined.FolderOpen,
            contentDescription = null,
            tint = if (recommendation.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(recommendation.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${recommendation.finds} · ${recommendation.where}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (recommendation.granted) "已授权" else "授权",
            style = MaterialTheme.typography.labelLarge,
            color = if (recommendation.granted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
}
