package com.mhmh2.bujirun.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A button that only fires after being held down for [holdMillis], filling up while held.
 * Used for ending a workout, so a brush in a pocket or a sweaty mis-tap can't end it.
 */
@Composable
fun HoldToConfirmButton(
    text: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Int = 1200,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val confirm by rememberUpdatedState(onConfirm)
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)

    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .drawBehind { drawRect(fill, size = size.copy(width = size.width * progress.value)) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val hold = scope.launch {
                        progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        confirm()
                    }
                    waitForUpOrCancellation()
                    if (progress.value < 1f) {
                        hold.cancel()
                        scope.launch { progress.animateTo(0f, tween(200)) }
                    }
                }
            }
            // Screen readers can't "hold", so for them a plain activation confirms.
            .semantics {
                role = Role.Button
                onClick(label = text) { confirm(); true }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
