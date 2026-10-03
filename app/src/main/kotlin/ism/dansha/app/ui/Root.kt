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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
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
    val data by vm.data.collectAsState()
    val computed by vm.computed.collectAsState()
    val message by vm.message.collectAsState()
    val update by vm.update.collectAsState()
    val pending by vm.pendingImport.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    var adding by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

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
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    val hasData = data?.isEmpty() == false
    CompositionLocalProvider(LocalOpenMore provides { page = Page.More }) {
        Scaffold(
            containerColor = LocalPalette.current.bg,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { BottomBar(tab, { tab = it }, onAdd = { if (hasData) adding = true else vm.toast("นำเข้าข้อมูลหรือกดเริ่มใหม่ก่อน") }) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val d = data
                if (d == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    when (tab) {
                        Tab.Home -> HomeScreen(d, computed, vm, onOpenDebt = { tab = Tab.Debt }, onOpenTransactions = { tab = Tab.Transactions })
                        Tab.Transactions -> TransactionsTab(d, vm)
                        Tab.Plan -> PlanScreen(d, computed, vm, onOpenTemplates = { page = Page.Templates })
                        Tab.Debt -> DebtScreen(d, computed, vm)
                    }
                }
            }
        }
    }

    // หน้าที่เปิดซ้อน
    data?.let { d ->
        when (page) {
            Page.More -> OverlayPage("เพิ่มเติม", { page = null }) { MoreScreen(d, vm, onOpen = { page = it }) }
            Page.Accounts -> AccountsPage(d, computed, vm) { page = Page.More }
            Page.Templates -> TemplatesPage(d, vm) { page = null }
            Page.Shopee -> ShopeePage(d, computed, vm) { page = Page.More }
            Page.Port -> PortPage(d, vm) { page = Page.More }
            Page.Settings -> SettingsPage(d, vm) { page = Page.More }
            null -> Unit
        }
        if (adding) TransactionEditor(d, null, vm) { adding = false }
    }

    UpdateDialog(update, vm)
    pending?.let { ImportConfirmDialog(it, data, vm) }
}

/** แถบล่าง: 4 แท็บ + ปุ่ม ➕ กลาง */
@Composable
private fun BottomBar(tab: Tab, onTab: (Tab) -> Unit, onAdd: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().background(p.card).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            val tabs = Tab.entries
            tabs.take(2).forEach { t -> TabItem(t, t == tab, Modifier.weight(1f)) { onTab(t) } }
            Box(Modifier.weight(1f))
            tabs.drop(2).forEach { t -> TabItem(t, t == tab, Modifier.weight(1f)) { onTab(t) } }
        }
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = (-14).dp).size(58.dp)
                .shadow(6.dp, CircleShape, ambientColor = p.primary, spotColor = p.primary)
                .background(p.primary, CircleShape)
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
        Box(
            Modifier.background(if (selected) p.primarySoft else Color.Transparent, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 3.dp),
        ) { Icon(t.icon, contentDescription = null, tint = if (selected) p.primary else p.muted, modifier = Modifier.size(22.dp)) }
        Text(t.label, fontSize = 11.sp, color = if (selected) p.ink else p.muted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** แท็บรายการ: สลับดู "รายการ" กับ "สรุป" */
@Composable
private fun TransactionsTab(d: DanshaData, vm: MainViewModel) {
    var summary by rememberSaveable { mutableStateOf(false) }
    val header: @Composable () -> Unit = {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            ChoiceChips(listOf(false to "รายการ", true to "สรุป"), summary, { summary = it })
        }
    }
    if (summary) SummaryScreen(d, vm, header) else TransactionsScreen(d, vm, header)
}
