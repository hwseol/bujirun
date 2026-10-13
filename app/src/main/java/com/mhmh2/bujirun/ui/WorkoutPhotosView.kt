package com.mhmh2.bujirun.ui

import com.mhmh2.bujirun.tr

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mhmh2.bujirun.data.PhotoPlace
import com.mhmh2.bujirun.data.WorkoutPhoto
import com.mhmh2.bujirun.data.WorkoutPhotos
import com.mhmh2.bujirun.tracking.formatKm
import java.time.Instant
import java.time.ZoneId

/**
 * tr("운동하며 찍은 사진", "Photos from the run"): thumbnails (each also sits on the map where it was taken); a tap opens
 * them full screen. Asks for the photo permission first; "+" adds photos from the picker (cloud too).
 */
@Composable
fun PhotoStrip(
    photos: List<WorkoutPhoto>,
    places: List<PhotoPlace?>,
    canRead: Boolean,
    onAllow: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Text(tr("운동하며 찍은 사진", "Photos from the run"), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    if (!canRead) {
        TextButton(onClick = onAllow, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(tr("폰에 있는 사진 중 이 운동 시간에 찍은 사진 보기", "Show photos taken on this phone during the workout"))
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(photos, key = { _, p -> p.uri.toString() }) { i, p ->
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant).clickable { onOpen(i) }) {
                Thumbnail(p.uri, 256, Modifier.fillMaxSize())
            }
        }
        item {
            Column(
                Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerLow).clickable(onClick = onAdd),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.AddPhotoAlternate, null, tint = colors.primary)
                Text(tr("사진 추가", "Add photos"), style = MaterialTheme.typography.labelSmall, color = colors.primary)
                Text(tr("클라우드 포함", "Incl. cloud"), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** Full screen, swipe between them; when and where on the route each was taken. */
@Composable
fun PhotoViewer(photos: List<WorkoutPhoto>, places: List<PhotoPlace?>, start: Int, onDismiss: () -> Unit, onRemove: (WorkoutPhoto) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start) { photos.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                Thumbnail(photos[i].uri, 2048, Modifier.fillMaxSize(), ContentScale.Fit)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, tr("닫기", "Close"), tint = Color.White)
            }
            val p = photos.getOrNull(pager.currentPage)
            if (p != null) {
                // At the top, beside the close button: the bottom of a dialog can sit under the gesture bar.
                Column(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 64.dp, top = 12.dp)) {
                    val place = places.getOrNull(pager.currentPage)
                    Text(
                        listOfNotNull(
                            "${pager.currentPage + 1} / ${photos.size}",
                            p.takenAt?.let { clockTime(it) },
                            place?.let { tr("${formatKm(it.distanceM)}km 지점", "at ${formatKm(it.distanceM)}km") },
                        ).joinToString(" · "),
                        color = Color.White,
                    )
                    if (p.addedId != null) {
                        TextButton(onClick = { onRemove(p) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text(tr("이 운동에서 빼기", "Remove from this workout"), color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(uri: Uri, px: Int, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri, px) { value = WorkoutPhotos.load(context, uri, px) }
    bmp?.let { Image(it.asImageBitmap(), null, modifier, contentScale = scale) }
}


internal val PHOTO_BLUE_C = Color(0xFF2F80ED)
const val PHOTO_BLUE = 0xFF2F80ED.toInt()
