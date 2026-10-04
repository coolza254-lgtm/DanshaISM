package ism.dansha.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/*
 * หน้าซ้อน (ฟอร์ม/หน้ารายละเอียด) วาดในหน้าต่างเดียวกับแอพ ไม่ใช่ Dialog แยกหน้าต่าง
 * เดิมแต่ละหน้าเป็นหน้าต่างของตัวเอง เปลี่ยนหน้าแล้วจะเห็นจอข้างล่างแวบขึ้นมา (กระพริบ)
 * ตอนนี้ทุกหน้าซ้อนกันในกองเดียว เลื่อนเข้าจากขวา/ออกทางขวาแบบนุ่มๆ และปุ่มย้อนกลับปิดหน้าบนสุด
 */

@Stable
internal class OverlayEntry(content: @Composable () -> Unit, onBack: () -> Unit) {
    var content by mutableStateOf(content)
    var onBack by mutableStateOf(onBack)
    val visible = MutableTransitionState(false).apply { targetState = true }
}

@Stable
class OverlayHostState {
    internal val entries = mutableStateListOf<OverlayEntry>()
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHostState?> { null }

/** ลงทะเบียนหน้าซ้อน: อยู่บนจอตราบที่ composable นี้ยังถูกเรียก */
@Composable
fun Overlay(onBack: () -> Unit, content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current ?: error("Overlay ต้องอยู่ใต้ OverlayHost")
    val entry = remember { OverlayEntry(content, onBack) }
    SideEffect {
        entry.content = content
        entry.onBack = onBack
    }
    DisposableEffect(entry) {
        host.entries.add(entry)
        onDispose { entry.visible.targetState = false }
    }
}

/** วาดหน้าซ้อนทั้งหมด (วางไว้บนสุดของ Root) */
@Composable
fun OverlayHost(state: OverlayHostState) {
    val entries = state.entries.toList()
    entries.forEach { entry ->
        key(entry) {
            AnimatedVisibility(
                visibleState = entry.visible,
                enter = slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200)),
                exit = slideOutHorizontally(tween(200)) { it / 3 } + fadeOut(tween(180)),
            ) {
                Box(Modifier.fillMaxSize()) { entry.content() }
            }
            // เลื่อนออกเสร็จแล้วค่อยเอาออกจากกอง
            LaunchedEffect(entry.visible.isIdle, entry.visible.currentState) {
                if (entry.visible.isIdle && !entry.visible.currentState && !entry.visible.targetState) state.entries.remove(entry)
            }
        }
    }
    val top = entries.lastOrNull { it.visible.targetState }
    BackHandler(enabled = top != null) { top?.onBack?.invoke() }
}
