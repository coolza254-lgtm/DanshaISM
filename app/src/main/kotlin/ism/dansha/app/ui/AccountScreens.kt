package ism.dansha.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import ism.dansha.core.Account
import ism.dansha.core.DanshaData
import ism.dansha.core.Schema
import ism.dansha.core.engine.Forms
import ism.dansha.core.engine.toDec
import java.math.BigDecimal

private val PALETTE = listOf(
    "#FFF1C9", "#D4F0DC", "#D6EEFB", "#D9E1F5", "#FFE6CC", "#D6E4FA", "#FFD6E0", "#FFE0C7", "#FFDCD3", "#E7D8FF", "#EEEEEE",
)

private val BRANDS = listOf(
    "" to "— ไม่ใช้ —", "brand:K-Bank" to "K-Bank (K)", "brand:Krungthai NEXT" to "Krungthai (KTB)", "brand:Bangkok Bank" to "Bangkok Bank (BBL)",
    "brand:TrueMoney Wallet" to "TrueMoney (TM)", "brand:G-Wallet" to "G-Wallet (G)", "brand:Ascend PayNext" to "PayNext (PN)",
    "brand:Ascend PayNext Extra" to "PayNext Extra (EX)", "brand:Shopee PayLater" to "SPayLater (SP)",
)

/** รายชื่อบัญชีทั้งหมด (รวมที่ปิดใช้) + ยอด */
@Composable
fun AccountsPage(d: DanshaData, c: MainViewModel.Computed?, vm: MainViewModel, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<Account?>(null) }
    var creating by remember { mutableStateOf(false) }
    val views = c?.overview?.accounts?.associateBy { it.account.id }.orEmpty()
    FormPage("บัญชี", onClose, onSave = { creating = true }, saveLabel = "+ เพิ่มบัญชี") {
        d.accounts.sortedWith(compareBy({ !it.active }, { it.sort ?: Int.MAX_VALUE }, { it.name })).forEach { a ->
            val v = views[a.id]
            Row(Modifier.fillMaxWidth().clickable { editing = a }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AccountBadge(a)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name + if (!a.active) " (ปิดใช้)" else "", color = if (a.active) DanshaColors.Ink else DanshaColors.Muted)
                    Text(
                        buildString {
                            append(ACCOUNT_TYPE_LABELS[a.type] ?: a.type)
                            if (a.currency != "THB") append(" · ").append(a.currency)
                            when (a.debt_engine) {
                                "ascend" -> append(" · ตัวคำนวณ Ascend")
                                "spaylater" -> append(" · ตัวคำนวณ SPayLater")
                            }
                        },
                        color = DanshaColors.Muted, fontSize = 12.sp,
                    )
                }
                if (v != null) {
                    if (v.isCredit) Text("ใช้ไป ${formatMoney(v.used?.let { BigDecimal.valueOf(it) })}", fontWeight = FontWeight.SemiBold)
                    else Text(formatMoney(v.balance?.let { BigDecimal.valueOf(it) }), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (creating) AccountEditor(d, null, vm) { creating = false }
    editing?.let { a -> AccountEditor(d, a, vm) { editing = null } }
}

@Composable
fun AccountEditor(d: DanshaData, existing: Account?, vm: MainViewModel, onClose: () -> Unit) {
    val e = existing
    var name by remember { mutableStateOf(e?.name.orEmpty()) }
    var type by remember { mutableStateOf(e?.type ?: "bank") }
    var currency by remember { mutableStateOf(e?.currency?.ifEmpty { "THB" } ?: "THB") }
    var opening by remember { mutableStateOf(amountText(e?.opening_balance)) }
    var limit by remember { mutableStateOf(amountText(e?.credit_limit)) }
    var color by remember { mutableStateOf(e?.color?.ifEmpty { PALETTE.last() } ?: PALETTE.last()) }
    var icon by remember { mutableStateOf(e?.icon.orEmpty()) }
    var active by remember { mutableStateOf(e?.active ?: true) }
    var note by remember { mutableStateOf(e?.note.orEmpty()) }
    var sort by remember { mutableStateOf(e?.sort?.toString() ?: ((d.accounts.mapNotNull { it.sort }.maxOrNull() ?: 0) + 1).toString()) }
    var engine by remember { mutableStateOf(e?.debt_engine.orEmpty()) }
    var since by remember { mutableStateOf(e?.debt_since.orEmpty()) }
    var tenorMin by remember { mutableStateOf(e?.tenor_min?.toString().orEmpty()) }
    var tenorMax by remember { mutableStateOf(e?.tenor_max?.toString().orEmpty()) }
    var rate by remember { mutableStateOf(amountText(e?.annual_rate)) }
    var error by remember { mutableStateOf<String?>(null) }
    val credit = type == "revolving_credit"

    fun save() {
        val row = (e ?: Account(id = "")).copy(
            name = name.trim(), type = type, currency = currency,
            opening_balance = Forms.parseAmount(opening)?.toDec() ?: BigDecimal.ZERO,
            credit_limit = if (credit) Forms.parseAmount(limit)?.toDec() else null,
            color = color, icon = icon, sort = sort.toIntOrNull(), active = active, note = note,
            debt_engine = if (credit) engine else "",
            debt_since = if (credit && engine.isNotEmpty()) since else "",
            tenor_min = if (credit && engine == "ascend") tenorMin.toIntOrNull() else null,
            tenor_max = if (credit && engine == "ascend") tenorMax.toIntOrNull() else null,
            annual_rate = if (credit && engine == "ascend") Forms.parseAmount(rate)?.toDec() else null,
        )
        vm.edit<Any>(ok = "บันทึกแล้ว", onError = { error = it }, onOk = { onClose() }) {
            if (e == null) createAccount(row) else updateAccount(row)
        }
    }

    FormPage(if (e == null) "เพิ่มบัญชี" else "แก้ไขบัญชี", onClose, ::save) {
        error?.let { Note(it, DanshaColors.Negative) }
        TextInput("ชื่อบัญชี", name, { name = it })
        ChoiceChips(listOf("cash" to "เงินสด", "bank" to "บัญชี/วอลเล็ท", "revolving_credit" to "วงเงิน/บัตร"), type, { type = it })
        Picker("สกุลเงิน", Schema.CURRENCIES.map { it to it }, currency, { currency = it })
        AmountField(if (credit) "ยอดใช้ไปตั้งต้น" else "ยอดตั้งต้น", opening, { opening = it },
            supporting = if (credit && engine.isNotEmpty()) "บัญชีที่มีตัวคำนวณหนี้ใช้หนี้ย่อยแทนยอดนี้" else null)
        if (credit) {
            AmountField("วงเงิน", limit, { limit = it })
            Text("ตัวคำนวณหนี้", fontWeight = FontWeight.Medium)
            ChoiceChips(listOf("" to "ไม่ใช้", "ascend" to "Ascend PayNext", "spaylater" to "Shopee PayLater"), engine, { engine = it })
            if (engine.isNotEmpty()) {
                DateField("เริ่มนับรายการตั้งแต่ (debt_since)", since, { since = it }, allowEmpty = true)
                Note("รายการก่อนวันนี้ไม่นำมาคิด ใช้ยอดยกมา (snapshot) ของหนี้ย่อยแทน")
            }
            if (engine == "ascend") {
                Row {
                    AmountField("ผ่อนได้ต่ำสุด (งวด)", tenorMin, { tenorMin = it }, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    AmountField("สูงสุด (งวด)", tenorMax, { tenorMax = it }, Modifier.weight(1f))
                }
                AmountField("ดอกเบี้ยต่อปี (%)", rate, { rate = it }, supporting = "ว่าง = 25%")
            }
        }
        Text("สี", fontWeight = FontWeight.Medium)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            PALETTE.forEach { hex ->
                Box(
                    Modifier.padding(end = 8.dp).size(34.dp).background(tint(hex), CircleShape)
                        .border(if (hex == color) 2.dp else 1.dp, if (hex == color) DanshaColors.Ink else DanshaColors.Line, CircleShape)
                        .clickable { color = hex },
                )
            }
        }
        Picker("ไอคอนแบรนด์", BRANDS, if (BRANDS.any { it.first == icon }) icon else "", { icon = it })
        if (!icon.startsWith("brand:")) TextInput("หรือไอคอนเอง (อีโมจิ)", icon, { icon = it.take(4) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ตัวอย่าง  ")
            AccountBadge(Account(id = "", name = name, icon = icon, color = color))
        }
        AmountField("ลำดับ", sort, { sort = it.filter(Char::isDigit) })
        SwitchRow("เปิดใช้งาน", active, { active = it }, sub = "ปิดแล้วจะไม่แสดงในฟอร์ม และไม่นับรวมในเงินที่มีตอนนี้")
        TextInput("โน้ต", note, { note = it })
        if (e != null) {
            OutlinedButton(onClick = { active = false; save() }, modifier = Modifier.fillMaxWidth()) { Text("ปิดใช้งานบัญชีนี้") }
            DeleteButton("บัญชีนี้") {
                vm.edit<Unit>(ok = "ลบแล้ว", onError = { error = it }, onOk = { onClose() }) { deleteAccount(e.id) }
            }
            Note("บัญชีที่มีรายการแล้วลบไม่ได้ ให้ปิดใช้งานแทน")
        }
    }
}
