package ism.dansha.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.key
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import ism.dansha.core.DanshaData

enum class Tab(val label: String, val icon: ImageVector) {
    Home("ภาพรวม", Icons.Outlined.Home),
    Transactions("รายการ", Icons.AutoMirrored.Outlined.ReceiptLong),
    Plan("แผนบิล", Icons.AutoMirrored.Outlined.EventNote),
    Debt("หนี้", Icons.Outlined.CreditCard),
}

/** หน้าที่เปิดซ้อนบนแท็บ */
enum class Page { More, Accounts, Templates, Shopee, Port, Settings }

@Composable
fun DanshaRoot(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    val message by vm.message.collectAsState()
    val update by vm.update.collectAsState()
    val pending by vm.pendingImport.collectAsState()
    val saved by vm.saved.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    // กองหน้าซ้อน: เปิดหน้าใหม่ทับ (หน้าเดิมยังอยู่ข้างใต้) ย้อนกลับ = ปิดหน้าบนสุด
    val pages = rememberSaveable(saver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<Page>, String>(save = { l -> l.map { it.name } }, restore = { l -> l.map(Page::valueOf).toMutableStateList() })) {
        mutableStateListOf<Page>()
    }
    fun open(p: Page) {
        val i = pages.indexOf(p)
        if (i >= 0) while (pages.size > i + 1) pages.removeAt(pages.lastIndex) else pages.add(p)
    }
    fun back() { if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) }
    var adding by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val overlay = remember { OverlayHostState() }
    val tabStates = rememberSaveableStateHolder()

    val data = ui.computed?.data ?: ui.data
    val computed = ui.computed

    // ขออนุญาตแจ้งเตือนครั้งแรก (Android 13+) — ปฏิเสธแล้วเปิดทีหลังได้ในหน้าตั้งค่า
    val context = androidx.compose.ui.platform.LocalContext.current
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(data?.isEmpty()) {
        val prefs = context.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
        if (data?.isEmpty() == false && android.os.Build.VERSION.SDK_INT >= 33 &&
            !ism.dansha.app.notify.Notifier.canNotify(context) && !prefs.getBoolean("asked_notify", false)
        ) {
            prefs.edit().putBoolean("asked_notify", true).apply()
            askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(message) {
        message?.let {
            vm.clearMessage()
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it)
        }
    }

    val hasData = data?.isEmpty() == false
    CompositionLocalProvider(LocalOpenMore provides { open(Page.More) }, LocalOverlayHost provides overlay) {
        Box(Modifier.fillMaxSize().background(LocalPalette.current.bg)) {
            Scaffold(
                containerColor = LocalPalette.current.bg,
                bottomBar = { BottomBar(tab, { tab = it }, onAdd = { if (hasData) adding = true else vm.toast("นำเข้าข้อมูลหรือกดเริ่มใหม่ก่อน") }) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).notebookPaper(LocalPalette.current)) {
                    if (data == null) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center), color = LocalPalette.current.primary)
                    } else {
                        // สลับแท็บแบบจางเข้า-ออกสั้นๆ และจำตำแหน่งเลื่อนของแต่ละแท็บไว้
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = { fadeIn(tween(180, delayMillis = 40)) togetherWith fadeOut(tween(120)) },
                            label = "tab",
                        ) { t ->
                            tabStates.SaveableStateProvider(t.name) {
                                when (t) {
                                    Tab.Home -> HomeScreen(data, computed, vm, onOpenDebt = { tab = Tab.Debt }, onOpenTransactions = { tab = Tab.Transactions })
                                    Tab.Transactions -> TransactionsTab(data, vm)
                                    Tab.Plan -> PlanScreen(data, computed, vm, onOpenTemplates = { open(Page.Templates) })
                                    Tab.Debt -> DebtScreen(data, computed, vm)
                                }
                            }
                        }
                    }
                }
            }

            // หน้าซ้อน (วาดใน OverlayHost ด้านล่าง)
            if (data != null) {
                pages.forEach { p ->
                    key(p) {
                        when (p) {
                            Page.More -> OverlayPage("เพิ่มเติม", ::back) { MoreScreen(data, vm, onOpen = ::open) }
                            Page.Accounts -> AccountsPage(data, computed, vm, ::back)
                            Page.Templates -> TemplatesPage(data, vm, ::back)
                            Page.Shopee -> ShopeePage(data, computed, vm, ::back)
                            Page.Port -> PortPage(data, vm, ::back)
                            Page.Settings -> SettingsPage(data, vm, ::back)
                        }
                    }
                }
                if (adding) TransactionEditor(data, null, vm) { adding = false }
            }
            OverlayHost(overlay)
            SavedPopup(saved) { vm.clearSaved(it) }

            // ข้อความแจ้ง: อยู่บนสุดเสมอ (เห็นได้แม้เปิดหน้าซ้อนอยู่)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 76.dp)) { s ->
                val p = LocalPalette.current
                Text(
                    s.visuals.message,
                    Modifier.padding(horizontal = 24.dp).paperCard(p, p.ink).padding(horizontal = 16.dp, vertical = 12.dp),
                    color = p.card, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    UpdateDialog(update, vm)
    pending?.let { ImportConfirmDialog(it, data, vm) }
}

/** แถบล่าง: แถบกระดาษ 4 แท็บ + ปุ่มปากกา ➕ กลาง */
@Composable
private fun BottomBar(tab: Tab, onTab: (Tab) -> Unit, onAdd: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().background(p.surface).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(p.ink.copy(alpha = if (p.dark) 0.5f else 1f)))
        Row(Modifier.fillMaxWidth().height(66.dp), verticalAlignment = Alignment.CenterVertically) {
            val tabs = Tab.entries
            tabs.take(2).forEach { t -> TabItem(t, t == tab, Modifier.weight(1f)) { onTab(t) } }
            Box(Modifier.weight(1f))
            tabs.drop(2).forEach { t -> TabItem(t, t == tab, Modifier.weight(1f)) { onTab(t) } }
        }
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = (-16).dp).size(60.dp)
                .drawBehind { drawCircle(p.ink.copy(alpha = 0.2f), center = center.copy(x = center.x + 2.dp.toPx(), y = center.y + 3.dp.toPx())) }
                .background(p.primary, CircleShape)
                .border(2.dp, p.ink, CircleShape)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Add, contentDescription = "เพิ่มรายการ", tint = p.onPrimary, modifier = Modifier.size(30.dp)) }
    }
}

@Composable
private fun TabItem(t: Tab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val mark by animateColorAsState(if (selected) p.highlight else p.highlight.copy(alpha = 0f), tween(220), label = "tabMark")
        Icon(t.icon, contentDescription = null, tint = if (selected) p.ink else p.muted, modifier = Modifier.size(22.dp))
        Text(
            t.label,
            Modifier.background(mark, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp),
            fontSize = 13.sp, color = if (selected) p.ink else p.muted, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

/** แท็บรายการ: สลับดู "รายการ" กับ "สรุป" */
@Composable
private fun TransactionsTab(d: DanshaData, vm: MainViewModel) {
    var summary by rememberSaveable { mutableStateOf(false) }
    val header: @Composable () -> Unit = {
        Box(Modifier.padding(start = 36.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
            ChoiceChips(listOf(false to "รายการ", true to "สรุป"), summary, { summary = it })
        }
    }
    if (summary) SummaryScreen(d, vm, header) else TransactionsScreen(d, vm, header)
}
