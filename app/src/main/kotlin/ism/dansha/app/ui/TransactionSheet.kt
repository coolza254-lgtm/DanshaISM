package ism.dansha.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
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
import kotlinx.coroutines.launch

/**
 * เพิ่ม/แก้รายการ: แผ่นเลื่อนขึ้นจากล่าง
 * ยอดเงินพิมพ์ด้วยแป้นตัวเลขของมือถือ · หมวดเป็นไอคอน · ปุ่ม "จดลงสมุด" ติดล่างเสมอ (ลอยเหนือแป้นพิมพ์)
 * existing = null → เพิ่มใหม่
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var saving by remember { mutableStateOf(false) }

    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val amountFocus = remember { FocusRequester() }
    // ปิดแบบเลื่อนลงนุ่มๆ (ไม่หายวับ)
    fun close() {
        scope.launch { sheet.hide() }.invokeOnCompletion { onClose() }
    }

    val accounts = remember(d) {
        d.accounts.filter { it.active || it.id == e?.account_id || it.id == e?.to_account_id }
            .sortedWith(compareBy({ it.sort ?: Int.MAX_VALUE }, { it.name }))
    }
    val eligible = Forms.copayEligible(d, type, accountId, date)
    val useCopay = copay && eligible
    val value = Forms.parseAmount(amount)
    val preview = if (useCopay) Forms.copayPreview(d, type, accountId, date, value, e?.id) else null
    val typeColor = when (type) { "income" -> p.positive; "expense" -> p.negative; else -> p.primary }

    fun save() {
        if (saving) return
        error = null
        if (value == null || value <= 0) {
            error = "กรอกจำนวนเงินก่อน"
            amountFocus.requestFocus()
            return
        }
        val acc = d.accounts.firstOrNull { it.id == accountId }
        val amt = if (useCopay) preview?.self else value
        val row = (e ?: Transaction(id = "", source = "manual")).copy(
            date = date, type = type, account_id = accountId,
            to_account_id = if (type == "transfer") toAccountId else "",
            amount = amt?.toDec(), currency = acc?.currency ?: e?.currency.orEmpty(),
            category_id = if (type == "transfer") "" else categoryId,
            subcategory_id = if (type == "transfer") "" else subId,
            note = note.trim(),
            full_price = if (useCopay) value.toDec() else e?.full_price,
            gov_subsidy = e?.gov_subsidy,
        )
        // true = คำนวณสิทธิใหม่, false = ยกเลิกสิทธิของรายการนี้, null = ไม่เกี่ยว
        val copayFlag: Boolean? = when {
            useCopay -> true
            hadCopay -> false
            else -> null
        }
        saving = true
        focus.clearFocus()
        vm.edit<Any>(
            ok = if (e == null) "จดแล้ว ✓" else "แก้แล้ว ✓",
            onError = { error = it; saving = false },
            onOk = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); close() },
        ) {
            if (e == null) createTransaction(row, copayFlag) else updateTransaction(row, copayFlag)
        }
    }

    // รายการใหม่: เปิดแป้นตัวเลขให้พิมพ์ยอดได้ทันที
    LaunchedEffect(Unit) { if (e == null) amountFocus.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheet,
        containerColor = p.card,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                // ประเภท + ลบ
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeSwitch(type, Modifier.weight(1f)) { t ->
                        if (t != type) {
                            type = t
                            if (e == null) accountId = Forms.defaultAccount(d, t)
                            categoryId = ""; subId = ""
                        }
                    }
                    if (e != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "ลบ", tint = p.negative) }
                }

                // ยอดเงิน: เขียนบนเส้นสมุด
                Spacer(Modifier.height(16.dp))
                Text(if (useCopay) "ราคาเต็ม" else if (type == "income") "ได้รับ" else if (type == "transfer") "ยอดโอน" else "จ่ายไป", color = p.muted, fontSize = 14.sp)
                Row(
                    Modifier.fillMaxWidth().drawBehind {
                        val y = size.height - 1.dp.toPx()
                        drawLine(typeColor.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                    }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { amountFocus.requestFocus() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("฿", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = typeColor.copy(alpha = 0.75f))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        val style = TextStyle(fontSize = 42.sp, fontWeight = FontWeight.Bold, color = typeColor, fontFamily = Sarabun, fontFeatureSettings = "tnum")
                        if (amount.isEmpty()) Text("0.00", style = style.copy(color = typeColor.copy(alpha = 0.25f)))
                        BasicTextField(
                            value = amount,
                            onValueChange = { amount = cleanAmount(it, amount); error = null },
                            textStyle = style,
                            singleLine = true,
                            cursorBrush = SolidColor(typeColor),
                            visualTransformation = MoneyTransformation,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(amountFocus),
                        )
                    }
                }
                error?.let { Text(it, Modifier.padding(top = 6.dp), color = p.negative, fontSize = 14.sp, fontWeight = FontWeight.Medium) }

                if (eligible) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.stickyBlue)
                            .border(1.5.dp, p.primary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .clickable { copay = !copay }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("ใช้สิทธิ 60/40", fontWeight = FontWeight.SemiBold, color = p.ink)
                            val pv = preview
                            Text(
                                if (pv != null) "รัฐจ่าย ${formatMoney(pv.gov)} · จ่ายเอง ${formatMoney(pv.self)} · วันนี้เหลือ ${formatMoney(pv.leftToday)}"
                                else "กรอกราคาเต็ม แล้วแอพคิดส่วนที่รัฐจ่ายให้",
                                fontSize = 13.sp, color = p.muted,
                            )
                        }
                        Switch(checked = copay, onCheckedChange = { copay = it })
                    }
                }

                // บัญชี
                SheetLabel(if (type == "transfer") "จากบัญชี" else if (type == "income") "เข้าบัญชี" else "จ่ายจากบัญชี")
                AccountChips(accounts, accountId) { accountId = it; if (toAccountId == it) toAccountId = "" }
                if (type == "transfer") {
                    SheetLabel("ไปบัญชี (จ่ายหนี้ = โอนเข้าบัญชีวงเงิน)")
                    AccountChips(accounts.filter { it.id != accountId }, toAccountId) { toAccountId = it }
                } else {
                    // หมวด: ตาราง 4 ช่องเต็มความกว้าง
                    SheetLabel("หมวด")
                    val tree = remember(d, type, categoryId, subId) { Forms.categoryTree(d, type, keep = setOf(categoryId, subId)) }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tree.chunked(4).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { n ->
                                    val c = n.category
                                    val sel = c.id == categoryId
                                    CategoryCell(c.name, c.icon, c.color, sel, Modifier.weight(1f)) {
                                        if (sel) { categoryId = ""; subId = "" } else { categoryId = c.id; subId = "" }
                                    }
                                }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    val subs = tree.firstOrNull { it.category.id == categoryId }?.children.orEmpty()
                    if (subs.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SubChip("ทั้งหมวด", null, null, null, subId.isEmpty()) { subId = "" }
                            subs.forEach { s -> SubChip(s.name, s.name, s.icon, s.color, subId == s.id) { subId = s.id } }
                        }
                    }
                }

                // วันที่
                SheetLabel("วันที่")
                val today = Dates.todayStr()
                val yesterday = Dates.today().minusDays(1).toString()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftChip("วันนี้", date == today) { date = today }
                    SoftChip("เมื่อวาน", date == yesterday) { date = yesterday }
                    SoftChip(if (date != today && date != yesterday) thaiDate(date) else "เลือกวัน", date != today && date != yesterday, Icons.Outlined.CalendarMonth) { pickDate = true }
                }

                // โน้ต: บรรทัดเขียน
                SheetLabel("โน้ต")
                Row(
                    Modifier.fillMaxWidth().drawBehind {
                        val y = size.height - 1.dp.toPx()
                        drawLine(p.line, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx())
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.EditNote, null, tint = p.muted)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (note.isEmpty()) Text("เช่น ข้าวมันไก่ / ค่าไฟเดือนนี้", color = p.muted.copy(alpha = 0.7f), fontSize = 16.sp)
                        BasicTextField(
                            value = note, onValueChange = { note = it }, singleLine = true,
                            textStyle = TextStyle(fontSize = 16.sp, color = p.ink, fontFamily = Sarabun),
                            cursorBrush = SolidColor(p.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (e?.source == "bill_plan") Note("มาจากแผนบิล: ลบแล้วแผนจะกลับเป็น \"ยังไม่จ่าย\"")
                if (e?.source == "debt") Note("ผูกกับหนี้ย่อย (เบิกเงินสด): ลบแล้วหนี้ย่อยจะถูกลบด้วย")
                Spacer(Modifier.height(12.dp))
            }

            // ปุ่มบันทึกติดล่าง
            Box(Modifier.fillMaxWidth().background(p.card).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)) {
                Button(
                    onClick = ::save, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(2.dp, p.ink.copy(alpha = if (p.dark) 0.5f else 1f)),
                    colors = ButtonDefaults.buttonColors(containerColor = p.primary, contentColor = p.onPrimary),
                ) {
                    val label = if (e == null) "จดลงสมุด" else "แก้ในสมุด"
                    Text(if (value != null && value > 0) "$label · ฿${formatMoney(value)}" else label, fontFamily = Hand, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (pickDate) DatePickerPopup(date, { date = it }, { pickDate = false })
    if (confirmDelete && e != null) {
        ConfirmDialog("ลบรายการนี้?", "ลบแล้วกู้คืนไม่ได้", "ลบ", {
            vm.edit<Unit>(ok = "ลบแล้ว", onError = { error = it }, onOk = { close() }) { deleteTransaction(e.id) }
        }, { confirmDelete = false })
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, Modifier.padding(top = 18.dp, bottom = 8.dp), color = LocalPalette.current.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

/** สลับ รายจ่าย / รายรับ / โอน แบบแถบเดียว */
@Composable
private fun TypeSwitch(type: String, modifier: Modifier, onType: (String) -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(50)
    Row(modifier.clip(shape).background(p.surface).border(1.5.dp, p.ink.copy(alpha = if (p.dark) 0.4f else 0.8f), shape).padding(3.dp)) {
        listOf("expense" to "รายจ่าย", "income" to "รายรับ", "transfer" to "โอน").forEach { (t, label) ->
            val sel = t == type
            val c = when (t) { "income" -> p.positive; "expense" -> p.negative; else -> p.primary }
            Box(
                Modifier.weight(1f).clip(shape).background(if (sel) c else Color.Transparent).clickable { onType(t) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (sel) (if (p.dark) p.bg else Color.White) else p.ink, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun CategoryCell(name: String, emoji: String, color: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) p.primarySoft else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CategoryIcon(name, emoji, color, size = 48.dp, ring = if (selected) p.primary else null)
        Spacer(Modifier.height(4.dp))
        Text(
            name, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = if (selected) p.primary else p.ink, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SubChip(label: String, name: String?, emoji: String?, color: String?, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).background(if (selected) p.primarySoft else p.surface)
            .border(1.5.dp, if (selected) p.primary else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(start = if (name != null) 4.dp else 14.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (name != null) {
            CategoryIcon(name, emoji, color, size = 28.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 14.sp, color = if (selected) p.primary else p.ink, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(vertical = if (name == null) 4.dp else 0.dp))
    }
}

@Composable
private fun SoftChip(label: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).background(if (selected) p.ink else p.surface).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) p.card else p.ink, modifier = Modifier.padding(end = 6.dp).size(18.dp))
        }
        Text(label, fontSize = 14.sp, color = if (selected) p.card else p.ink, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** เลือกบัญชีแบบชิปเลื่อนข้าง */
@Composable
private fun AccountChips(accounts: List<Account>, selected: String, onSelect: (String) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.forEach { a ->
            val sel = a.id == selected
            val shape = RoundedCornerShape(50)
            Row(
                Modifier.clip(shape).background(if (sel) p.primarySoft else p.surface)
                    .border(1.5.dp, if (sel) p.primary else Color.Transparent, shape)
                    .clickable { onSelect(a.id) }.padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountBadge(a, 30)
                Spacer(Modifier.width(8.dp))
                Text(a.name, fontSize = 14.sp, color = if (sel) p.primary else p.ink, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** รับเฉพาะตัวเลข + จุดเดียว, ทศนิยมไม่เกิน 2 ตำแหน่ง, ไม่เกิน 9 หลัก (พิมพ์เกินกติกา = คงค่าเดิม) */
private fun cleanAmount(input: String, old: String): String {
    val s = input.replace(',', '.').filter { it.isDigit() || it == '.' }
    if (s.count { it == '.' } > 1) return old
    val dot = s.indexOf('.')
    val int = if (dot >= 0) s.substring(0, dot) else s
    if (int.length > 9 || (dot >= 0 && s.length - dot - 1 > 2)) return old
    val trimmed = int.trimStart('0').ifEmpty { if (int.isNotEmpty() || dot >= 0) "0" else "" }
    return if (dot >= 0) trimmed + s.substring(dot) else trimmed
}

/** แสดงตัวเลขที่กำลังพิมพ์พร้อมคอมมา (คงจุดทศนิยมที่พิมพ์ค้างไว้) */
private fun formatTyped(s: String): String {
    val parts = s.split(".")
    val int = parts[0].toBigIntegerOrNull()?.let { java.text.DecimalFormat("#,##0").format(it) } ?: parts[0]
    return if (parts.size > 1) "$int.${parts[1]}" else int
}

/** ใส่คอมมาตอนแสดง โดยตำแหน่งเคอร์เซอร์ยังตรงกับตัวเลขจริง */
private object MoneyTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = formatTyped(raw)
        val o2t = IntArray(raw.length + 1)
        val t2o = IntArray(out.length + 1)
        var i = 0
        var j = 0
        while (j < out.length) {
            if (i < raw.length && out[j] == raw[i]) {
                o2t[i] = j; t2o[j] = i; i++
            } else {
                t2o[j] = i
            }
            j++
        }
        while (i <= raw.length) { o2t[i] = out.length; i++ }
        t2o[out.length] = raw.length
        return TransformedText(AnnotatedString(out), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = o2t[offset.coerceIn(0, raw.length)]
            override fun transformedToOriginal(offset: Int) = t2o[offset.coerceIn(0, out.length)]
        })
    }
}
