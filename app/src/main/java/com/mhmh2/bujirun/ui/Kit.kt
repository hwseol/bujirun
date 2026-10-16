package com.mhmh2.bujirun.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's look in a few pieces, shared by every screen: the orange-to-coral brand gradient,
 * cards, number tiles, the step ring and the start button.
 */
object Brand {
    val Orange = Color(0xFFFF6A2B)
    val Coral = Color(0xFFFF3D6E)
    val Ink = Color(0xFF231A16)
    val gradient = Brush.linearGradient(listOf(Orange, Coral))
    val gradientSoft = Brush.linearGradient(listOf(Orange.copy(alpha = 0.14f), Coral.copy(alpha = 0.10f)))

    /** Big figures (time, km, steps): bold with digits all one width, so they don't jiggle. */
    val number = TextStyle(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp)
}

/** A rounded card on the surface tone; [onClick] makes the whole card tappable. */
@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) { Column(Modifier.padding(padding), content = content) }
}

/** A card filled with the brand gradient, white text inside. */
@Composable
fun HeroCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier.fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(28.dp), ambientColor = Brand.Coral, spotColor = Brand.Coral)
            .clip(RoundedCornerShape(28.dp)).background(Brand.gradient)
    ) {
        // A soft light in the corner, so the gradient isn't flat.
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = 0.10f), radius = size.minDimension * 0.75f, center = Offset(size.width * 1.0f, 0f))
            drawCircle(Color.White.copy(alpha = 0.06f), radius = size.minDimension * 0.5f, center = Offset(size.width * 0.05f, size.height * 1.05f))
        }
        Column(Modifier.padding(20.dp), content = content)
    }
}

/** Label over a figure, with an optional unit after it. */
@Composable
fun StatTile(label: String, value: String, unit: String? = null, modifier: Modifier = Modifier, onHero: Boolean = false, big: Boolean = false) {
    val labelColor = if (onHero) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = if (onHero) Color.White else MaterialTheme.colorScheme.onSurface
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Brand.number.copy(fontSize = if (big) 30.sp else 20.sp), color = valueColor, maxLines = 1)
            if (unit != null) {
                Text(" $unit", style = MaterialTheme.typography.labelMedium, color = labelColor, modifier = Modifier.padding(bottom = if (big) 5.dp else 2.dp), maxLines = 1)
            }
        }
    }
}

/** A ring filled to [progress] (0..1) around [content]. */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, stroke: Dp = 10.dp, track: Color = Color.White.copy(alpha = 0.25f), fill: Color = Color.White, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = Size(size.width - w, size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            drawArc(fill, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** The round gradient start button, breathing gently so it's the obvious thing to press. */
@Composable
fun StartButton(text: String, sub: String?, onClick: () -> Unit, size: Dp = 148.dp) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        1f, 1.08f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "halo",
    )
    Box(Modifier.size(size * 1.2f), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).scale(pulse).clip(CircleShape).background(Brand.gradientSoft))
        Box(
            Modifier.size(size * 0.86f)
                .shadow(14.dp, CircleShape, ambientColor = Brand.Coral, spotColor = Brand.Coral)
                .clip(CircleShape).background(Brand.gradient).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(text, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                if (sub != null) Text(sub, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

/** A small section title. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = modifier)
}
