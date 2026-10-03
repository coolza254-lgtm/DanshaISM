package ism.dansha.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.BuildConfig
import ism.dansha.app.MainViewModel
import ism.dansha.app.PendingImport
import ism.dansha.app.UpdateState
import ism.dansha.app.update.Updater
import ism.dansha.core.Account
import ism.dansha.core.DanshaData
import ism.dansha.core.Transaction

internal val TABLE_LABELS = linkedMapOf(
    "accounts" to "บัญชี",
    "transactions" to "รายการ",
    "bills" to "แผนบิล",
    "billTemplates" to "แม่แบบบิล",
    "debts" to "หนี้ย่อย",
    "shopee" to "Shopee",
    "port" to "พอร์ต",
    "categories" to "หมวดหมู่",
    "fx" to "อัตราแลกเปลี่ยน",
    "prices" to "ราคาหลักทรัพย์",
)

internal val ACCOUNT_TYPE_LABELS = mapOf("cash" to "เงินสด", "bank" to "บัญชี/วอลเล็ท", "revolving_credit" to "วงเงิน")

// ---------- ส่วนประกอบ ----------

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = DanshaColors.Muted, fontSize = 13.sp)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .border(1.dp, DanshaColors.Line, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) { content() }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, color = DanshaColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

/** วงกลมสีพร้อมตัวย่อของบัญชี (K, KTB, BBL, TM, G, PN, EX, SP …) */
@Composable
fun AccountBadge(account: Account?, size: Int = 36) {
    val label = accountShort(account)
    Box(
        Modifier.size(size.dp).background(parseColor(account?.color.orEmpty()), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = (if (label.length >= 3) size / 3.4 else size / 2.6).sp, fontWeight = FontWeight.Bold, color = DanshaColors.Ink)
    }
}

private val BRAND_SHORT = mapOf(
    "k-bank" to "K", "kbank" to "K", "kasikorn" to "K",
    "krungthai next" to "KTB", "krungthai" to "KTB", "ktb" to "KTB",
    "bangkok bank" to "BBL", "bbl" to "BBL",
    "truemoney wallet" to "TM", "truemoney" to "TM",
    "g-wallet" to "G", "เป๋าตัง" to "G",
    "ascend paynext extra" to "EX",
    "ascend paynext" to "PN",
    "shopee paylater" to "SP", "spaylater" to "SP",
    "scb" to "SCB", "krungsri" to "KMA", "ttb" to "ttb",
)

fun accountShort(a: Account?): String {
    if (a == null) return "?"
    val brand = a.icon.removePrefix("brand:").trim().lowercase()
    if (a.icon.startsWith("brand:")) {
        BRAND_SHORT[brand]?.let { return it }
    }
    if (a.icon.isNotBlank() && !a.icon.startsWith("brand:")) return a.icon
    BRAND_SHORT.entries.firstOrNull { a.name.lowercase().startsWith(it.key) }?.let { return it.value }
    return a.name.take(1).uppercase()
}

@Composable
fun ComingSoon(title: String, what: String, phase: Int) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(title)
        Card {
            Text("กำลังทำ", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(what, color = DanshaColors.Muted)
            Spacer(Modifier.height(8.dp))
            Text("จะมาใน Phase $phase · ข้อมูลที่นำเข้าเก็บไว้ในเครื่องเรียบร้อยแล้ว", color = DanshaColors.Muted, fontSize = 13.sp)
        }
    }
}

private val THAI_MONTHS = listOf("ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.")

/** "2026-10-03" → "3 ต.ค. 2569" */
fun thaiDate(iso: String): String {
    if (iso.length < 10) return iso
    val y = iso.substring(0, 4).toIntOrNull() ?: return iso
    val m = iso.substring(5, 7).toIntOrNull() ?: return iso
    val day = iso.substring(8, 10).toIntOrNull() ?: return iso
    return "$day ${THAI_MONTHS[m - 1]} ${y + 543}"
}

// ---------- เพิ่มเติม ----------

@Composable
fun MoreScreen(d: DanshaData, vm: MainViewModel, onOpen: (Page) -> Unit) {
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.pickImport(it) }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.export(it) }
    }
    var confirmFresh by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTitle("เพิ่มเติม") }
        item {
            Card {
                SectionLabel("จัดการ")
                MenuRow(Icons.Outlined.AccountBalance, "บัญชี", "เพิ่ม/แก้บัญชี วงเงิน ตัวคำนวณหนี้ (${d.accounts.size} บัญชี)") { onOpen(Page.Accounts) }
                MenuRow(Icons.Outlined.EventRepeat, "แม่แบบแผนบิล", "รายการที่เกิดทุกรอบ (${d.billTemplates.size} แม่แบบ)") { onOpen(Page.Templates) }
                MenuRow(Icons.Outlined.ShoppingBag, "Shopee", "บันทึกออเดอร์ · เช็คก่อนซื้อ · สถิติ (${d.shopee.size} ออเดอร์)") { onOpen(Page.Shopee) }
                MenuRow(Icons.AutoMirrored.Outlined.ShowChart, "พอร์ตลงทุน", "รายการซื้อขาย · อัปเดตราคา · กำไร/ขาดทุน") { onOpen(Page.Port) }
                MenuRow(Icons.Outlined.Settings, "ตั้งค่า", "หมวดหมู่ · 60/40 · แจ้งเตือน · อัตราแลกเปลี่ยน") { onOpen(Page.Settings) }
            }
        }
        item {
            Card {
                SectionLabel("ข้อมูล")
                MenuRow(Icons.Outlined.FileUpload, "นำเข้าไฟล์ข้อมูล", "แทนที่ข้อมูลทั้งหมดในเครื่องด้วยไฟล์ .json") {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
                MenuRow(Icons.Outlined.Download, "ส่งออกไฟล์ข้อมูล", "บันทึกเป็น ${vm.exportFileName()} ไว้สำรอง") {
                    exportLauncher.launch(vm.exportFileName())
                }
                MenuRow(Icons.Outlined.RestartAlt, "เริ่มใหม่", "ลบข้อมูลทั้งหมด แล้วเริ่มจากหมวดหมู่ตั้งต้น") {
                    confirmFresh = true
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "ข้อมูลสำรองอัตโนมัติไปที่บัญชี Google ของเครื่องด้วย (Android Auto Backup)",
                    color = DanshaColors.Muted, fontSize = 12.sp,
                )
            }
        }
        item {
            Card {
                SectionLabel("แอพ")
                MenuRow(Icons.Outlined.SystemUpdate, "ตรวจสอบอัปเดต", "เวอร์ชันนี้ ${BuildConfig.VERSION_NAME}") {
                    vm.checkUpdate()
                }
            }
        }
    }

    if (confirmFresh) {
        AlertDialog(
            onDismissRequest = { confirmFresh = false },
            title = { Text("เริ่มใหม่?") },
            text = {
                Text(
                    if (d.isEmpty()) "จะสร้างหมวดหมู่และค่าตั้งต้นให้"
                    else "ข้อมูลทั้งหมดในเครื่อง (รายการ ${d.transactions.size} รายการ) จะถูกลบ ควรส่งออกไฟล์สำรองก่อน"
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmFresh = false; vm.startFresh() }) {
                    Text("ลบและเริ่มใหม่", color = DanshaColors.Negative)
                }
            },
            dismissButton = { TextButton(onClick = { confirmFresh = false }) { Text("ยกเลิก") } },
        )
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, color = DanshaColors.Muted, fontSize = 12.sp)
        }
    }
}

// ---------- กล่องยืนยัน ----------

@Composable
fun ImportConfirmDialog(p: PendingImport, current: DanshaData?, vm: MainViewModel) {
    AlertDialog(
        onDismissRequest = { vm.cancelImport() },
        title = { Text("นำเข้าข้อมูล?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("ในไฟล์มี:", fontWeight = FontWeight.Medium)
                p.data.counts().filterValues { it > 0 }.forEach { (k, n) -> Text("• ${TABLE_LABELS[k] ?: k} $n") }
                if (current != null && !current.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "ข้อมูลในเครื่องตอนนี้ (รายการ ${current.transactions.size} รายการ) จะถูกแทนที่ทั้งหมด",
                        color = DanshaColors.Negative,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.confirmImport() }) { Text("นำเข้า") } },
        dismissButton = { TextButton(onClick = { vm.cancelImport() }) { Text("ยกเลิก") } },
    )
}

@Composable
fun UpdateDialog(state: UpdateState, vm: MainViewModel) {
    val context = LocalContext.current
    when (state) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> AlertDialog(
            onDismissRequest = {},
            title = { Text("กำลังเช็คอัปเดต…") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
        UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text("เป็นเวอร์ชันล่าสุดแล้ว") },
            text = { Text("เวอร์ชัน ${BuildConfig.VERSION_NAME}") },
            confirmButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("ตกลง") } },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text("อัปเดตไม่สำเร็จ") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("ตกลง") } },
        )
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text("มีเวอร์ชันใหม่ ${state.info.versionName}") },
            text = {
                Column {
                    Text("เวอร์ชันนี้ ${BuildConfig.VERSION_NAME}")
                    if (state.info.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(state.info.notes.take(600), fontSize = 13.sp, color = DanshaColors.Muted)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.downloadUpdate(state.info) }) { Text("อัปเดตเลย") } },
            dismissButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("ไว้ทีหลัง") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("กำลังดาวน์โหลด ${state.info.versionName}") },
            text = { LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
        is UpdateState.Ready -> AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text("พร้อมติดตั้ง ${state.info.versionName}") },
            text = {
                Text(
                    if (Updater.canInstall(context)) "กด \"ติดตั้ง\" แล้วกดยืนยันในหน้าต่างของ Android ข้อมูลในแอพยังอยู่ครบ"
                    else "ครั้งแรก Android จะให้อนุญาตก่อน: กด \"ติดตั้ง\" → เปิด \"อนุญาตจากแหล่งที่มานี้\" → กดย้อนกลับ แล้วกด \"ติดตั้ง\" อีกครั้ง"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (Updater.canInstall(context)) Updater.install(context, state.apk)
                    else Updater.openInstallPermission(context)
                }) { Text("ติดตั้ง") }
            },
            dismissButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("ปิด") } },
        )
    }
}
