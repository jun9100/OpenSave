package com.jun9100.opensave.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.CoverRepository
import com.jun9100.opensave.GameIconKey
import com.jun9100.opensave.GamePresentation
import com.jun9100.opensave.ui.preview.ComponentPreviews
import com.jun9100.opensave.ui.preview.PreviewSurface

@Composable
internal fun GameArtwork(
    presentation: GamePresentation,
    coverRepository: CoverRepository,
    modifier: Modifier,
) {
    var bitmap by remember(presentation.coverCacheKey) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(presentation.coverCacheKey) {
        bitmap = coverRepository.load(presentation)
    }
    if (bitmap != null) {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
        ) {
            Image(
                bitmap!!.asImageBitmap(),
                contentDescription = "${presentation.primaryName}封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    } else {
        GameIcon(presentation.iconKey, modifier)
    }
}

@Composable
internal fun GameIcon(iconKey: GameIconKey, modifier: Modifier) {
    val (image, tint) = when (iconKey) {
        GameIconKey.SWITCH -> Icons.Outlined.SportsEsports to MaterialTheme.colorScheme.primary
        GameIconKey.PC -> Icons.Outlined.Computer to MaterialTheme.colorScheme.secondary
        GameIconKey.ANDROID -> Icons.Outlined.PhoneAndroid to MaterialTheme.colorScheme.tertiary
        GameIconKey.PPSSPP -> Icons.Outlined.SportsEsports to MaterialTheme.colorScheme.primary
        GameIconKey.RETROARCH -> Icons.Outlined.Storage to MaterialTheme.colorScheme.secondary
        GameIconKey.DOLPHIN -> Icons.Outlined.SportsEsports to MaterialTheme.colorScheme.tertiary
        GameIconKey.DUCKSTATION -> Icons.Outlined.Memory to MaterialTheme.colorScheme.secondary
        GameIconKey.AETHERSX2 -> Icons.Outlined.Memory to MaterialTheme.colorScheme.tertiary
        GameIconKey.EMULATOR -> Icons.Outlined.Memory to MaterialTheme.colorScheme.primary
        GameIconKey.UNKNOWN -> Icons.Outlined.Storage to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        color = tint.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(image, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        }
    }
}

@ComponentPreviews
@Composable
private fun GameIconPreview() {
    PreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GameIconKey.entries.forEach { key ->
                GameIcon(key, Modifier.size(36.dp))
            }
        }
    }
}
