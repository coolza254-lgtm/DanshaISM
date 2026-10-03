package ism.dansha.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel

enum class Tab(val label: String, val icon: ImageVector) {
    Home("ภาพรวม", Icons.Outlined.Home),
    Transactions("รายการ", Icons.AutoMirrored.Outlined.ReceiptLong),
    Summary("สรุป", Icons.Outlined.PieChart),
    Plan("แผนบิล", Icons.AutoMirrored.Outlined.EventNote),
    Debt("หนี้", Icons.Outlined.CreditCard),
    More("เพิ่มเติม", Icons.Outlined.MoreHoriz),
}

@Composable
fun DanshaRoot(vm: MainViewModel) {
    val data by vm.data.collectAsState()
    val message by vm.message.collectAsState()
    val update by vm.update.collectAsState()
    val pending by vm.pendingImport.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label, fontSize = 11.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = DanshaColors.Surface),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val d = data
            if (d == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                when (tab) {
                    Tab.Home -> HomeScreen(d, vm, onOpenMore = { tab = Tab.More })
                    Tab.Transactions -> TransactionsScreen(d)
                    Tab.Summary -> ComingSoon("สรุป", "สรุปรายรับรายจ่ายตามหมวด กราฟรายวัน", 4)
                    Tab.Plan -> ComingSoon("แผนบิล", "แผนบิลรายรอบ สร้างจากแม่แบบ จ่าย/ยกเลิกจ่าย ภาพรวมรายรอบ", 3)
                    Tab.Debt -> ComingSoon("หนี้", "PayNext / Extra / SPayLater หนี้ย่อยรายก้อน ยอดปิด เบิกเงินสด", 3)
                    Tab.More -> MoreScreen(d, vm)
                }
            }
        }
    }

    UpdateDialog(update, vm)
    pending?.let { ImportConfirmDialog(it, data, vm) }
}
