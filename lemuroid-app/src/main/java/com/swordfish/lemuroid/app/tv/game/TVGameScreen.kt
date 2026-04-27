package com.swordfish.lemuroid.app.tv.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.swordfish.lemuroid.app.shared.game.BaseGameScreenViewModel
import com.swordfish.lemuroid.app.shared.settings.AspectRatio

@Composable
fun TVGameScreen(viewModel: BaseGameScreenViewModel) {
    val localContext = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current

    // Reactive — updates immediately when the setting is changed
    val currentAspectRatio = viewModel
        .getAspectRatioFlow()
        .collectAsState(AspectRatio.CORE_PROVIDED)
        .value
    val customAspectX = viewModel.getCustomAspectXFlow().collectAsState(4).value
    val customAspectY = viewModel.getCustomAspectYFlow().collectAsState(3).value

    val fullScreenPosition = remember { mutableStateOf<Rect?>(null) }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { fullScreenPosition.value = it.boundsInRoot() },
        factory = { viewModel.createRetroView(localContext, lifecycle) },
    )

    val fullPos = fullScreenPosition.value

    LaunchedEffect(fullPos, currentAspectRatio, customAspectX, customAspectY) {
        val gameView = viewModel.retroGameView.retroGameViewFlow()
        if (fullPos == null) return@LaunchedEffect
        // On TV the whole GLSurfaceView is the game area — no touch controls.
        // The full screen IS the container, so viewport is always (0,0,1,1);
        // VideoLayout handles letterboxing via the aspect ratio override.
        applyTVAspectRatio(gameView, fullPos, currentAspectRatio, customAspectX, customAspectY)
    }

    if (viewModel.loadingState.collectAsState(true).value) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

private fun applyTVAspectRatio(
    gameView: com.swordfish.libretrodroid.GLRetroView,
    fullBounds: androidx.compose.ui.geometry.Rect,
    aspectRatio: AspectRatio,
    customX: Int,
    customY: Int,
) {
    // On TV the full GLSurfaceView is the game area, so viewport is always (0,0,1,1).
    // VideoLayout does one letterboxing pass using the override AR — no double correction.
    gameView.viewport = android.graphics.RectF(0f, 0f, 1f, 1f)
    when (aspectRatio) {
        AspectRatio.CORE_PROVIDED -> gameView.setAspectRatioOverride(0f) // use core's native AR
        AspectRatio.FULL -> {
            // Use Compose-measured bounds (always valid here) rather than
            // gameView.width/height which can be 0 before the Android View is laid out.
            val screenAR = fullBounds.width.coerceAtLeast(1f) /
                           fullBounds.height.coerceAtLeast(1f)
            gameView.setAspectRatioOverride(screenAR)
        }
        AspectRatio.SQUARE_PIXEL -> {
            val w = gameView.getVideoWidth().coerceAtLeast(1)
            val h = gameView.getVideoHeight().coerceAtLeast(1)
            val g = tvGcd(w, h)
            gameView.setAspectRatioOverride((w / g).toFloat() / (h / g).toFloat())
        }
        AspectRatio.CUSTOM -> {
            gameView.setAspectRatioOverride(
                customX.coerceAtLeast(1).toFloat() / customY.coerceAtLeast(1).toFloat()
            )
        }
        else -> gameView.setAspectRatioOverride(aspectRatio.ratio!!)
    }
}

private fun tvGcd(a: Int, b: Int): Int = if (b == 0) a else tvGcd(b, a % b)

