package ism.dansha.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import ism.dansha.app.notify.Notifier
import ism.dansha.core.Category
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.engine.Balances
import ism.dansha.core.engine.Engine
import ism.dansha.core.engine.Forms
import ism.dansha.core.engine.Notify

private val CAT_COLORS = listOf(
    "#B8E0D2", "#C7E9F1", "#D6EADF", "#E2F0CB", "#FFD6E0", "#C9E4FF", "#E7D8FF", "#D4F1F4", "#FFE5EC", "#D8F3DC", "#FDE2E4", "#FAD2E1", "#E2ECF9", "#DDF3F5", "#EEEEEE",
)

@Composable
fun SettingsPage(d: DanshaData, vm: MainViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val cfg = remember(d) { Engine.config(d) }
    var categories by remember { mutableStateOf(false) }
    // ค่าตั้งค่าที่แก้ในหน้านี้ (บันทึกพร้อมกันตอนกด "บันทึก")
    val v = remember { mutableStateMapOf<String, String>().apply { putAll(cfg) } }
    var error by remember { mutableStateOf<String?>(null) }
    var canNotify by remember { mutableStateOf(Notifier.canNotify(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = it }
    val fxManual = remember { mutableStateMapOf<String, String>().apply { d.fx.forEach { put(it.currency, amountText(it.manual_rate)) } } }

    fun bool(k: String) = v[k] == "true"

    fun save() {
        val keys = listOf(
            "pay_cycle_start_day", "shop_warn_pct", "shop_buffer",
            "copay_enabled", "copay_name", "copay_account_id", "copay_gov_rate", "copay_daily_cap", "copay_monthly_cap", "copay_total_cap", "copay_start", "copay_end",
            "daily_summary_enabled", "daily_summary_hour", "debt_reminder_enabled", "debt_reminder_days", "bill_reminder_enabled", "bill_reminder_days",
        )
        val changed = keys.filter { v[it] != cfg[it] }.associateWith { v[it].orEmpty() }
        val manual = fxManual.filter { (c, text) -> c != "THB" && text != amountText(d.fx.firstOrNull { it.currency == c }?.manual_rate) }
            .mapValues { Forms.parseAmount(it.value) }
        vm.edit<Unit>(ok = "บันทึกการตั้งค่าแล้ว", onError = { error = it }, onOk = { onClose() }) {
            if (changed.isNotEmpty()) setConfig(changed)
            if (manual.isNotEmpty()) setFxManual(manual)
        }
    }

    FormPage("ตั้งค่า", onClose, ::save) {
        error?.let { Note(it, DanshaColors.Negative) }
        OutlinedButton(onClick = { categories = true }, modifier = Modifier.fillMaxWidth()) { Text("จัดการหมวดหมู่ (${d.categories.size})") }

        Section("รอบเงินเดือน / เช็คก่อนซื้อ")
        AmountField("รอบเงินเดือนเริ่มวันที่", v["pay_cycle_start_day"].orEmpty(), { v["pay_cycle_start_day"] = it.filter(Char::isDigit).take(2) })
        Row {
            AmountField("เตือนเมื่อราคาเกิน (% ของเงินเหลือ)", v["shop_warn_pct"].orEmpty(), { v["shop_warn_pct"] = it }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            AmountField("เงินกันไว้ต่อรอบ (บาท)", v["shop_buffer"].orEmpty(), { v["shop_buffer"] = it }, Modifier.weight(1f))
        }

        Section("ไทยช่วยไทยพลัส 60/40")
        SwitchRow("เปิดใช้", bool("copay_enabled"), { v["copay_enabled"] = it.toString() })
        if (bool("copay_enabled")) {
            TextInput("ชื่อโครงการ", v["copay_name"].orEmpty(), { v["copay_name"] = it })
            Picker("บัญชี G-Wallet", Forms.accountChoices(d, v["copay_account_id"].orEmpty()).map { it.id to it.name }, v["copay_account_id"].orEmpty(), { v["copay_account_id"] = it })
            Row {
                DateField("เริ่ม", v["copay_start"].orEmpty(), { v["copay_start"] = it }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                DateField("สิ้นสุด", v["copay_end"].orEmpty(), { v["copay_end"] = it }, Modifier.weight(1f))
            }
            Row {
                AmountField("รัฐจ่าย %", v["copay_gov_rate"].orEmpty(), { v["copay_gov_rate"] = it }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                AmountField("ต่อวันไม่เกิน", v["copay_daily_cap"].orEmpty(), { v["copay_daily_cap"] = it }, Modifier.weight(1f))
            }
            Row {
                AmountField("ต่อเดือนไม่เกิน", v["copay_monthly_cap"].orEmpty(), { v["copay_monthly_cap"] = it }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                AmountField("ทั้งโครงการไม่เกิน", v["copay_total_cap"].orEmpty(), { v["copay_total_cap"] = it }, Modifier.weight(1f))
            }
            Note("เฟสที่ไม่มีเพดานรายเดือน: ใส่ต่อเดือนเท่ากับทั้งโครงการ")
        }

        Section("แจ้งเตือน")
        if (!canNotify && Build.VERSION.SDK_INT >= 33) {
            Button(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) { Text("อนุญาตให้แอพแจ้งเตือน") }
        }
        SwitchRow("สรุปรายวัน", bool("daily_summary_enabled"), { v["daily_summary_enabled"] = it.toString() })
        if (bool("daily_summary_enabled")) {
            AmountField("แจ้งสรุปตอนกี่โมง (0–23)", v["daily_summary_hour"].orEmpty(), { v["daily_summary_hour"] = it.filter(Char::isDigit).take(2) },
                supporting = "แจ้งครบกำหนดตอนเวลาเดียวกัน (ปิดสรุปรายวัน = 9 โมง)")
        }
        SwitchRow("ครบกำหนดหนี้", bool("debt_reminder_enabled"), { v["debt_reminder_enabled"] = it.toString() })
        if (bool("debt_reminder_enabled")) TextInput("ล่วงหน้ากี่วัน (คั่นด้วย ,)", v["debt_reminder_days"].orEmpty(), { v["debt_reminder_days"] = it }, placeholder = "3,1")
        SwitchRow("บิลในแผนที่ยังไม่จ่าย", bool("bill_reminder_enabled"), { v["bill_reminder_enabled"] = it.toString() })
        if (bool("bill_reminder_enabled")) TextInput("ล่วงหน้ากี่วัน (คั่นด้วย ,)", v["bill_reminder_days"].orEmpty(), { v["bill_reminder_days"] = it }, placeholder = "1")
        Row {
            OutlinedButton(onClick = { Notifier.show(context, 1, true, Notify.dailySummary(d, Dates.now())) }) { Text("ลองแจ้งสรุป") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val m = Notify.reminders(d, Dates.now())
                if (m != null) Notifier.show(context, 2, false, m) else vm.toast("วันนี้ไม่มีรายการที่ต้องแจ้ง")
            }) { Text("ลองแจ้งครบกำหนด") }
        }

        Section("อัตราแลกเปลี่ยน (บาทต่อ 1 หน่วย)")
        d.fx.filter { it.currency != "THB" }.forEach { r ->
            AmountField(
                "${r.currency} กรอกเอง (ว่าง = ใช้อัตโนมัติ)", fxManual[r.currency].orEmpty(), { fxManual[r.currency] = it },
                supporting = "อัตโนมัติ ${amountText(r.auto_rate).ifEmpty { "–" }} · ใช้จริง ${Balances.fxRateOf(r)} · ${r.updated_note}",
            )
        }
        OutlinedButton(onClick = { vm.updateFxNow() }, modifier = Modifier.fillMaxWidth()) { Text("ดึงเรทล่าสุดตอนนี้") }
        Note("แอพดึงเรทให้เองวันละครั้งเมื่อเปิดแอพ (open.er-api.com สำรองด้วย frankfurter.app)")
    }
    if (categories) CategoriesPage(d, vm) { categories = false }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp), color = DanshaColors.Line)
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
}

// ---------- หมวดหมู่ ----------

@Composable
private fun CategoriesPage(d: DanshaData, vm: MainViewModel, onClose: () -> Unit) {
    var type by remember { mutableStateOf("expense") }
    var editing by remember { mutableStateOf<Category?>(null) }
    var creatingParent by remember { mutableStateOf<String?>(null) } // "" = หมวดหลัก, id = หมวดย่อยของ id
    FormPage("หมวดหมู่", onClose, onSave = { creatingParent = "" }, saveLabel = "+ หมวดหลัก") {
        ChoiceChips(listOf("expense" to "รายจ่าย", "income" to "รายรับ"), type, { type = it })
        val all = d.categories.filter { it.type == type }
        all.filter { it.parent_id.isEmpty() }.sortedBy { it.sort ?: 0 }.forEach { p ->
            CategoryLine(p, false) { editing = p }
            all.filter { it.parent_id == p.id }.sortedBy { it.sort ?: 0 }.forEach { c -> CategoryLine(c, true) { editing = c } }
            Text("+ หมวดย่อยของ ${p.name}", Modifier.padding(start = 40.dp, bottom = 6.dp).clickable { creatingParent = p.id }, color = DanshaColors.Muted, fontSize = 13.sp)
            HorizontalDivider(color = DanshaColors.Line)
        }
    }
    creatingParent?.let { parent ->
        val p = d.categories.firstOrNull { it.id == parent }
        CategoryEditor(d, null, type, parent, p, vm) { creatingParent = null }
    }
    editing?.let { c -> CategoryEditor(d, c, c.type, c.parent_id, d.categories.firstOrNull { it.id == c.parent_id }, vm) { editing = null } }
}

@Composable
private fun CategoryLine(c: Category, child: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = if (child) 32.dp else 0.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CategoryIcon(c.name, c.icon, c.color, size = if (child) 28.dp else 36.dp)
        Spacer(Modifier.width(10.dp))
        Text(c.name + if (!c.active) " (ปิด)" else "", color = if (c.active) DanshaColors.Ink else DanshaColors.Muted)
    }
}

@Composable
private fun CategoryEditor(d: DanshaData, existing: Category?, type: String, parentId: String, parent: Category?, vm: MainViewModel, onClose: () -> Unit) {
    val e = existing
    var name by remember { mutableStateOf(e?.name.orEmpty()) }
    var icon by remember { mutableStateOf(e?.icon ?: parent?.icon ?: "📦") }
    var color by remember { mutableStateOf(e?.color ?: parent?.color ?: CAT_COLORS.last()) }
    var active by remember { mutableStateOf(e?.active ?: true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        val sort = e?.sort ?: ((d.categories.mapNotNull { it.sort }.maxOrNull() ?: 0) + 1)
        val row = (e ?: Category(id = "", type = type, parent_id = parentId)).copy(name = name.trim(), icon = icon, color = color, active = active, sort = sort)
        vm.edit<Category>(ok = "บันทึกแล้ว", onError = { error = it }, onOk = { onClose() }) {
            if (e == null) createCategory(row) else updateCategory(row)
        }
    }

    FormPage(if (e == null) (if (parent != null) "หมวดย่อยของ ${parent.name}" else "เพิ่มหมวดหลัก") else "แก้ไขหมวด", onClose, ::save) {
        error?.let { Note(it, DanshaColors.Negative) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryIcon(name.ifBlank { parent?.name ?: "" }, icon, color, size = 56.dp)
            Spacer(Modifier.width(12.dp))
            Text("ไอคอนเลือกให้อัตโนมัติจากชื่อหมวด", color = DanshaColors.Muted, fontSize = 14.sp)
        }
        TextInput("ชื่อหมวด", name, { name = it })
        TextInput("อีโมจิสำรอง (ใช้เมื่อแอพไม่รู้จักชื่อหมวด)", icon, { icon = it.take(4) })
        Text("สี", fontWeight = FontWeight.Medium)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            CAT_COLORS.forEach { hex ->
                Box(
                    Modifier.padding(end = 8.dp).size(32.dp).background(tint(hex), CircleShape)
                        .border(if (hex == color) 2.dp else 1.dp, if (hex == color) DanshaColors.Ink else DanshaColors.Line, CircleShape)
                        .clickable { color = hex },
                )
            }
        }
        SwitchRow("ใช้งาน", active, { active = it }, sub = "ปิดแล้วไม่แสดงในฟอร์ม แต่รายการเดิมยังอยู่")
        if (e != null) {
            DeleteButton("หมวดนี้") {
                vm.edit<Unit>(ok = "ลบแล้ว", onError = { error = it }, onOk = { onClose() }) { deleteCategory(e.id) }
            }
            Note("หมวดที่ถูกใช้แล้วลบไม่ได้ (ให้ปิดใช้งาน) · ลบหมวดหลัก = ลบหมวดย่อยด้วย")
        }
    }
}
