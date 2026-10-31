package com.componentvault.android.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Animates only the current page, keeping navigation and saveable state immediate. */
@Composable
internal fun pageEntranceMotion(key: Any?, rise: Dp = 0.dp): Modifier {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(progress) {
        // Compose's animation clock also applies the system motion duration scale.
        progress.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
    }
    return Modifier.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * rise.toPx()
    }
}
