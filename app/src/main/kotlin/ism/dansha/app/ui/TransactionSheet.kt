package ism.dansha.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import ism.dansha.core.Account
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.Transaction
import ism.dansha.core.engine.Forms
import ism.dansha.core.engine.toDec

/**
 * เพิ่ม/แก้รายการ: แผ่นเลื่อนขึ้นจากล่าง + แป้นตัวเลขใหญ่ + ไอคอนหมวด + 60/40
 * existing = null → เพิ่มใหม่
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionEditor(d: DanshaData, existing: Transaction?, vm: MainViewModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val e = existing
    val hadCopay = (e?.gov_subsidy?.signum() ?: 0) > 0
    var type by remember { mutableStateOf(e?.type ?: "expense") }
    // ใช้สิทธิ 60/40: ตัวเลขที่พิมพ์ = ราคาเต็ม
    var copay by remember { mutableStateOf(hadCopay) }
    var amount by remember { mutableStateOf(amountText(if (hadCopay) e?.full_price else e?.amount)) }
    var date by remember { mutableStateOf(e?.date ?: Dates.todayStr()) }
    var accountId by remember { mutableStateOf(e?.account_id ?: Forms.defaultAccount(d, "expense")) }
    var toAccountId by remember { mutableStateOf(e?.to_account_id.orEmpty()) }
    var categoryId by remember { mutableStateOf(e?.category_id.orEmpty()) }
    var subId by remember { mutableStateOf(e?.subcategory_id.orEmpty()) }
    var note by remember { mutableStateOf(e?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val accounts = d.accounts.filter { it.active || it.id == e?.account_id || it.id == e?.to_account_id }
        .sortedWith(compareBy({ it.sort ?: Int.MAX_VALUE }, { it.name }))
    val eligible = Forms.copayEligible(d, type, accountId, date)
    val useCopay = copay && eligible
    val value = Forms.parseAmount(amount)
    val preview = if (useCopay) Forms.copayPreview(d, type, accountId, date, value, e?.id) else null
    val typeColor = when (type) { "income" -> p.positive; "expense" -> p.negative; else -> p.ink }

    fun save() {
        error = null
        val acc = d.accounts.firstOrNull { it.id == accountId }
        val amt = if (useCopay) preview?.self else value
        val row = (e ?: Transaction(id = "", source = "manual")).copy(
            date = date, type = type, account_id = accountId,
            to_account_id = if (type == "transfer") toAccountId else "",
            amount = amt?.toDec(), currency = acc?.currency ?: e?.currency.orEmpty(),
            category_id = if (type == "transfer") "" else categoryId,
            subcategory_id = if (type == "transfer") "" else subId,
            note = note.trim(),
            full_price = if (useCopay) value?.toDec() else e?.full_price,
            gov_subsidy = e?.gov_subsidy,
        )
        // true = คำนวณสิทธิใหม่, false = ยกเลิกสิทธิของรายการนี้, null = ไม่เกี่ยว
        val copayFlag: Boolean? = when {
            useCopay -> true
            hadCopay -> false
            else -> null
        }
        vm.edit<Any>(ok = if (e == null) "บันทึกแล้ว" else "แก้ไขแล้ว", onError = { error = it }, onOk = { onClose() }) {
            if (e == null) createTransaction(row, copayFlag) else updateTransaction(row, copayFlag)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.card,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp)) {
            // ประเภท + ลบ
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    ChoiceChips(listOf("expense" to "รายจ่าย", "income" to "รายรับ", "transfer" to "โอน"), type, { t ->
                        type = t
                        if (e == null) accountId = Forms.defaultAccount(d, t)
                        categoryId = ""; subId = ""
                    })
                }
                if (e != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "ลบ", tint = p.negative) }
            }

            // ยอดเงิน
            Spacer(Modifier.height(10.dp))
            Text(if (useCopay) "ราคาเต็ม" else "จำนวนเงิน", color = p.muted, fontSize = 13.sp)
            Text(
                "฿ " + (if (amount.isEmpty()) "0" else formatTyped(amount)),
                fontSize = 40.sp, fontWeight = FontWeight.Bold, color = typeColor, maxLines = 1,
            )
            if (eligible) {
                Row(
                    Modifier.fillMaxWidth().background(p.primarySoft, RoundedCornerShape(14.dp)).clickable { copay = !copay }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("ใช้สิทธิ 60/40", fontWeight = FontWeight.SemiBold, color = p.ink)
                        val pv = preview
                        Text(
                            if (pv != null) "รัฐจ่าย ${formatMoney(pv.gov)} · จ่ายเอง ${formatMoney(pv.self)} · วันนี้เหลือ ${formatMoney(pv.leftToday)}"
                            else "กรอกราคาเต็ม แล้วแอพคิดส่วนที่รัฐจ่ายให้",
                            fontSize = 12.sp, color = p.muted,
                        )
                    }
                    Switch(checked = copay, onCheckedChange = { copay = it })
                }
            }
            error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = p.negative, fontSize = 13.sp) }

            // บัญชี
            Spacer(Modifier.height(12.dp))
            Text(if (type == "transfer") "จากบัญชี" else "บัญชี", color = p.muted, fontSize = 13.sp)
            AccountChips(accounts, accountId) { accountId = it; if (toAccountId == it) toAccountId = "" }
            if (type == "transfer") {
                Spacer(Modifier.height(8.dp))
                Text("ไปบัญชี (จ่ายหนี้ = โอนเข้าบัญชีวงเงิน)", color = p.muted, fontSize = 13.sp)
                AccountChips(accounts.filter { it.id != accountId }, toAccountId) { toAccountId = it }
            } else {
                // หมวด
                Spacer(Modifier.height(12.dp))
                val tree = Forms.categoryTree(d, type, keep = setOf(categoryId, subId))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tree.forEach { n ->
                        val c = n.category
                        val sel = c.id == categoryId
                        Column(
                            Modifier.width(66.dp).clip(RoundedCornerShape(14.dp)).background(if (sel) p.primarySoft else Color.Transparent)
                                .clickable { if (sel) { categoryId = ""; subId = "" } else { categoryId = c.id; subId = "" } }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(40.dp).background(tint(c.color), CircleShape)
                                    .then(if (sel) Modifier.border(2.dp, p.primary, CircleShape) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) { Text(c.icon, fontSize = 18.sp) }
                            Text(c.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = p.ink)
                        }
                    }
                }
                val subs = tree.firstOrNull { it.category.id == categoryId }?.children.orEmpty()
                if (subs.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    ChoiceChips(listOf("" to "ทั้งหมวด") + subs.map { it.id to it.name }, subId, { subId = it })
                }
            }

            // วันที่ + โน้ต
            Spacer(Modifier.height(10.dp))
            val today = Dates.todayStr()
            val yesterday = Dates.today().minusDays(1).toString()
            ChoiceChips(
                listOf(today to "วันนี้", yesterday to "เมื่อวาน", "pick" to if (date != today && date != yesterday) thaiDate(date) else "เลือกวัน…"),
                if (date == today || date == yesterday) date else "pick",
                { v -> if (v == "pick") pickDate = true else date = v },
            )
            Spacer(Modifier.height(8.dp))
            TextInput("โน้ต", note, { note = it })
            if (e?.source == "bill_plan") Note("มาจากแผนบิล: ลบแล้วแผนจะกลับเป็น \"ยังไม่จ่าย\"")
            if (e?.source == "debt") Note("ผูกกับหนี้ย่อย (เบิกเงินสด): ลบแล้วหนี้ย่อยจะถูกลบด้วย")

            // แป้นตัวเลข
            Spacer(Modifier.height(10.dp))
            Keypad(
                onKey = { k -> amount = typeKey(amount, k) },
                onSave = ::save,
                saveLabel = if (e == null) "บันทึก" else "บันทึกการแก้ไข",
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    if (pickDate) DatePickerPopup(date, { date = it }, { pickDate = false })
    if (confirmDelete && e != null) {
        ConfirmDialog("ลบรายการนี้?", "ลบแล้วกู้คืนไม่ได้", "ลบ", {
            vm.edit<Unit>(ok = "ลบแล้ว", onError = { error = it }, onOk = { onClose() }) { deleteTransaction(e.id) }
        }, { confirmDelete = false })
    }
}

/** แสดงตัวเลขที่กำลังพิมพ์พร้อมคอมมา (คงจุดทศนิยมที่พิมพ์ค้างไว้) */
private fun formatTyped(s: String): String {
    val parts = s.split(".")
    val int = parts[0].toBigIntegerOrNull()?.let { java.text.DecimalFormat("#,##0").format(it) } ?: parts[0]
    return if (parts.size > 1) "$int.${parts[1]}" else int
}

/** กดแป้น: ตัวเลข, ".", "⌫" — ทศนิยมไม่เกิน 2 ตำแหน่ง */
private fun typeKey(cur: String, k: String): String = when (k) {
    "⌫" -> cur.dropLast(1)
    "." -> if (cur.contains('.')) cur else (cur.ifEmpty { "0" } + ".")
    else -> {
        val dot = cur.indexOf('.')
        when {
            dot >= 0 && cur.length - dot > 2 -> cur
            cur == "0" -> k
            cur.replace(".", "").length >= 9 -> cur
            else -> cur + k
        }
    }
}

@Composable
private fun Keypad(onKey: (String) -> Unit, onSave: () -> Unit, saveLabel: String) {
    val p = LocalPalette.current
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "⌫"))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.forEach { k ->
                    Box(
                        Modifier.weight(1f).height(50.dp).background(p.surface, RoundedCornerShape(14.dp)).clickable { onKey(k) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == "⌫") Icon(Icons.AutoMirrored.Outlined.Backspace, "ลบตัวเลข", tint = p.ink)
                        else Text(k, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = p.ink)
                    }
                }
            }
        }
        Button(
            onClick = onSave, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.primary, contentColor = p.onPrimary),
        ) { Text(saveLabel, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** เลือกบัญชีแบบชิปเลื่อนข้าง */
@Composable
private fun AccountChips(accounts: List<Account>, selected: String, onSelect: (String) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.forEach { a ->
            val sel = a.id == selected
            Row(
                Modifier.background(if (sel) p.primarySoft else p.surface, RoundedCornerShape(50))
                    .then(if (sel) Modifier.border(1.5.dp, p.primary, RoundedCornerShape(50)) else Modifier)
                    .clickable { onSelect(a.id) }.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountBadge(a, 28)
                Spacer(Modifier.width(6.dp))
                Text(a.name, fontSize = 13.sp, color = p.ink, maxLines = 1)
            }
        }
    }
}
