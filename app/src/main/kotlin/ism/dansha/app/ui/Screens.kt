package ism.dansha.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
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
import androidx.compose.material.icons.outlined.GridView
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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

/** ปุ่ม "เพิ่มเติม" มุมขวาบน (Root ใส่ให้ทุกแท็บ) */
val LocalOpenMore = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    val openMore = LocalOpenMore.current
    Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 8.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Hand, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = DanshaColors.Primary, lineHeight = 34.sp)
            if (subtitle != null) Text(subtitle, color = DanshaColors.Muted, fontSize = 14.sp)
        }
        actions()
        if (openMore != null) {
            androidx.compose.material3.IconButton(onClick = openMore) {
                Icon(Icons.Outlined.GridView, contentDescription = "เพิ่มเติม", tint = DanshaColors.Ink)
            }
        }
    }
}

/** การ์ดกระดาษแปะ: ขอบหมึก + เงาเยื้อง */
@Composable
fun Card(modifier: Modifier = Modifier, padding: androidx.compose.ui.unit.Dp = 16.dp, color: androidx.compose.ui.graphics.Color? = null, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    // ในหน้าฟอร์มมีขอบ 16dp อยู่แล้ว ไม่ต้องเว้นขอบสมุดซ้ายอีก
    val inForm = LocalInForm.current
    Column(
        modifier
            .padding(start = if (inForm) 0.dp else 36.dp, end = if (inForm) 0.dp else 16.dp, top = if (inForm) 0.dp else 8.dp, bottom = if (inForm) 0.dp else 8.dp)
            .fillMaxWidth()
            .paperCard(p, color ?: p.card)
            .padding(padding)
    ) { content() }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, color = DanshaColors.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

/** สีประจำแบรนด์ + ตัวย่อ (วาดเอง ไม่ใช่โลโก้จริง) */
data class BrandStyle(val short: String, val bg: androidx.compose.ui.graphics.Color, val fg: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White)

private val BRAND_STYLES: List<Pair<List<String>, BrandStyle>> = listOf(
    listOf("ascend paynext extra") to BrandStyle("EX", androidx.compose.ui.graphics.Color(0xFF4B2A7B)),
    listOf("ascend paynext", "paynext") to BrandStyle("PN", androidx.compose.ui.graphics.Color(0xFF7A3FB8)),
    listOf("k-bank", "kbank", "kasikorn", "กสิกร") to BrandStyle("K", androidx.compose.ui.graphics.Color(0xFF138F2D)),
    listOf("krungthai", "ktb", "กรุงไทย") to BrandStyle("KTB", androidx.compose.ui.graphics.Color(0xFF1BA5E1)),
    listOf("bangkok bank", "bbl", "กรุงเทพ") to BrandStyle("BBL", androidx.compose.ui.graphics.Color(0xFF1E4598)),
    listOf("truemoney", "ทรูมันนี่") to BrandStyle("TM", androidx.compose.ui.graphics.Color(0xFFFF8300)),
    listOf("g-wallet", "เป๋าตัง", "paotang") to BrandStyle("G", androidx.compose.ui.graphics.Color(0xFF1C64F2)),
    listOf("shopee", "spaylater") to BrandStyle("SP", androidx.compose.ui.graphics.Color(0xFFEE4D2D)),
    listOf("scb", "ไทยพาณิชย์") to BrandStyle("SCB", androidx.compose.ui.graphics.Color(0xFF4E2A84)),
    listOf("krungsri", "กรุงศรี") to BrandStyle("KMA", androidx.compose.ui.graphics.Color(0xFFFEC43B), androidx.compose.ui.graphics.Color(0xFF4A3A00)),
    listOf("ttb", "ทีทีบี") to BrandStyle("ttb", androidx.compose.ui.graphics.Color(0xFF0050F0)),
    listOf("gsb", "ออมสิน") to BrandStyle("GSB", androidx.compose.ui.graphics.Color(0xFFEB198D)),
    listOf("line bk", "line bank") to BrandStyle("LB", androidx.compose.ui.graphics.Color(0xFF06C755)),
    listOf("dime") to BrandStyle("D!", androidx.compose.ui.graphics.Color(0xFF1A1A1A)),
)

/** แบรนด์ของบัญชี: จาก icon "brand:ชื่อ" ก่อน แล้วค่อยเดาจากชื่อบัญชี */
fun brandOf(a: Account?): BrandStyle? {
    if (a == null) return null
    val key = if (a.icon.startsWith("brand:")) a.icon.removePrefix("brand:").trim().lowercase() else if (a.icon.isBlank()) a.name.lowercase() else return null
    return BRAND_STYLES.firstOrNull { (names, _) -> names.any { key.startsWith(it) || key.contains(it) } }?.second
}

/** icon "img:<base64>" = รูปที่ผู้ใช้เลือกเอง */
fun isImageIcon(icon: String) = icon.startsWith("img:")

/** รูปไอคอนที่ถอดแล้ว เก็บไว้ใช้ซ้ำ (ไม่ต้องถอด base64 ใหม่ทุกครั้งที่วาดรายการ) */
private val iconCache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(32)

private fun iconBitmap(icon: String): androidx.compose.ui.graphics.ImageBitmap? = iconCache.get(icon) ?: runCatching {
    val bytes = android.util.Base64.decode(icon.removePrefix("img:"), android.util.Base64.DEFAULT)
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()?.also { iconCache.put(icon, it) }

/** ไอคอนบัญชีแบบวงกลม: รูปที่เลือกเอง > สีแบรนด์ > อีโมจิ > ตัวอักษรแรก */
@Composable
fun AccountBadge(account: Account?, size: Int = 36) {
    val icon = account?.icon.orEmpty()
    if (isImageIcon(icon)) {
        val bmp = remember(icon) { iconBitmap(icon) }
        if (bmp != null) {
            androidx.compose.foundation.Image(bmp, contentDescription = null, modifier = Modifier.size(size.dp).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            return
        }
    }
    val brand = brandOf(account)
    val label = brand?.short ?: accountShort(account)
    Box(
        Modifier.size(size.dp).background(brand?.bg ?: tint(account?.color.orEmpty()), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = (if (label.length >= 3) size / 3.4 else size / 2.4).sp, fontWeight = FontWeight.Bold, color = brand?.fg ?: DanshaColors.Ink)
    }
}

fun accountShort(a: Account?): String {
    if (a == null) return "?"
    if (a.icon.isNotBlank() && !a.icon.startsWith("brand:") && !isImageIcon(a.icon)) return a.icon
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

/** รอบ "2026-09 Sep-Oct" → "ก.ย.–ต.ค. 69" */
fun cycleLabel(cycle: String): String {
    val y = cycle.take(4).toIntOrNull() ?: return cycle
    val m = cycle.drop(5).take(2).toIntOrNull()?.takeIf { it in 1..12 } ?: return cycle
    val next = if (m == 12) 1 else m + 1
    return "${THAI_MONTHS[m - 1]}–${THAI_MONTHS[next - 1]} ${(y + 543) % 100}"
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
        item {
            Card {
                SectionLabel("หน้าตา")
                val mode by vm.uiMode.collectAsState()
                ChoiceChips(listOf("system" to "ตามมือถือ", "light" to "สว่าง", "dark" to "มืด"), mode, { vm.setUiMode(it) })
                Spacer(Modifier.height(8.dp))
                val anim by vm.savedAnim.collectAsState()
                val exitAsk by vm.confirmExit.collectAsState()
                SwitchRow("ถามก่อนออกจากแอพ", exitAsk, { vm.setConfirmExit(it) }, sub = "ปัดย้อนกลับที่หน้าภาพรวมแล้วจะถามก่อนปิด กันเผลอออก")
                SwitchRow("ป๊อปอัปบันทึกสำเร็จ", anim, { vm.setSavedAnim(it) }, sub = "เด้งติ๊กถูกกลางจอทุกครั้งที่บันทึก (ปิด = แสดงข้อความเล็กด้านล่างแทน)")
            }
        }
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
                    color = DanshaColors.Muted, fontSize = 13.sp,
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
            Text(subtitle, color = DanshaColors.Muted, fontSize = 13.sp)
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
