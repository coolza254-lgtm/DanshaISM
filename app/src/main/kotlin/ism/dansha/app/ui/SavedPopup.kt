package ism.dansha.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ป๊อปอัป "บันทึกแล้ว": การ์ดเด้งขึ้นกลางจอ วงกลมวาดตัวเอง แล้วติ๊กถูกลากตามมา ค้าง ~1 วิ แล้วจางหาย
 * ไม่บังการแตะ (แตะจอต่อได้ทันที)
 */
@Composable
fun SavedPopup(saved: MainViewModel.Saved?, onDone: (Long) -> Unit) {
    val state = remember { MutableTransitionState(false) }
    var shown by remember { mutableStateOf<MainViewModel.Saved?>(null) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(saved?.id) {
        if (saved != null) {
            shown = saved
            state.targetState = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(1150)
            state.targetState = false
            onDone(saved.id)
        }
    }
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visibleState = state,
            enter = fadeIn(tween(140)) + scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f),
            exit = fadeOut(tween(220)) + scaleOut(tween(220), targetScale = 0.92f),
        ) {
            val item = shown ?: return@AnimatedVisibility
            Column(
                Modifier.widthIn(min = 170.dp, max = 260.dp).paperCard(p, p.card, radius = 18.dp).padding(horizontal = 26.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnimatedTick(item.id)
                Text(
                    item.text, fontFamily = Hand, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    color = p.ink, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** วงกลมวาดตัวเอง → ติ๊กถูกลากเส้น */
@Composable
private fun AnimatedTick(key: Long) {
    val p = LocalPalette.current
    val ring = remember(key) { Animatable(0f) }
    val tick = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        launch { ring.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
        delay(220)
        tick.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
    }
    Box(Modifier.size(76.dp).background(p.positiveSoft, CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(76.dp)) {
            val w = 5.dp.toPx()
            drawArc(
                p.positive, startAngle = -90f, sweepAngle = 360f * ring.value, useCenter = false,
                topLeft = Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                style = Stroke(w, cap = StrokeCap.Round),
            )
            val path = Path().apply {
                moveTo(size.width * 0.29f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.66f)
                lineTo(size.width * 0.72f, size.height * 0.37f)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val part = Path()
            measure.getSegment(0f, measure.length * tick.value, part, true)
            drawPath(part, p.positive, style = Stroke(w * 1.25f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
