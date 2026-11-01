package ism.dansha.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.Transaction
import ism.dansha.core.engine.QuickEntry
import ism.dansha.core.engine.toDec

/*
 * ชิ้นส่วนบนหน้าภาพรวม: จดไวบรรทัดเดียว (ไม่ต้องเปิดฟอร์ม) + แถบ "วันนี้ใช้ไป"
 */

/** แถบวันนี้ใช้ไปเทียบกับที่ใช้ได้ทั้งวัน (ใช้ไป + ที่ยังใช้ได้) */
@Composable
fun TodaySpendBar(spent: Double, perDay: Double) {
    val p = LocalPalette.current
    val budget = spent + maxOf(perDay, 0.0)
    val frac = if (budget > 0) (spent / budget).toFloat() else if (spent > 0) 1f else 0f
    val over = perDay < 0
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("วันนี้ใช้ไป", fontSize = 13.sp, color = p.muted)
        Spacer(Modifier.weight(1f))
        Text("฿" + formatMoney(spent), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (over) p.negative else p.ink)
    }
    Spacer(Modifier.height(4.dp))
    ProgressBar(frac, if (over || frac > 0.85f) p.negative else p.primary, p.primarySoft, height = 6)
}

/**
 * จดไว: พิมพ์ "กาแฟ 35" → เลือกหมวดที่แอพเดา → กดส่ง/Enter บันทึกทันที (วันนี้)
 * อยากใส่รายละเอียด (วันที่ โอน 60/40) → onMore เปิดฟอร์มเต็ม
 */
@Composable
fun QuickAddBar(d: DanshaData, vm: MainViewModel, onMore: () -> Unit) {
    val p = LocalPalette.current
    val focus = LocalFocusManager.current
    var text by rememberSaveable { mutableStateOf("") }
    var pick by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val guesses = remember(d, text) { if (text.isBlank()) emptyList() else QuickEntry.suggest(d, text) }
    val cats = remember(d) { d.categories.associateBy { it.id } }
    val g = guesses.getOrNull(pick) ?: guesses.firstOrNull()
    val amount = g?.amount
    val canSave = g != null && amount != null && amount > 0 && g.type != "transfer" && g.accountId.isNotEmpty()

    fun save() {
        val x = g ?: return
        val a = x.amount
        if (!canSave || a == null) {
            error = when {
                x.type == "transfer" -> "รายการโอน กด \"แบบละเอียด\" เพื่อเลือกบัญชีปลายทาง"
                a == null -> "ใส่ยอดด้วย เช่น กาแฟ 35"
                else -> "ยังจดไม่ได้ ลองแบบละเอียด"
            }
            return
        }
        error = null
        vm.edit<Transaction>(ok = "จดแล้ว", onError = { error = it }, onOk = { text = ""; pick = 0; focus.clearFocus() }) {
            createTransaction(
                Transaction(
                    id = "", date = Dates.todayStr(), type = x.type, account_id = x.accountId, amount = a.toDec(),
                    currency = accounts.firstOrNull { it.id == x.accountId }?.currency.orEmpty(),
                    category_id = x.categoryId, subcategory_id = x.subcategoryId, note = x.note, source = "manual",
                )
            )
        }
    }

    Column(Modifier.padding(start = 22.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; pick = 0; error = null },
            placeholder = { Text("จดไว: กาแฟ 35 · ข้าว 50 สด · +500 ขายของ", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            trailingIcon = {
                IconButton(onClick = { save() }, enabled = text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "บันทึก", tint = if (canSave) p.primary else p.muted)
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (guesses.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            WrapRow {
                guesses.forEachIndexed { i, x ->
                    val c = cats[x.subcategoryId.ifEmpty { x.categoryId }]
                    val sel = x === g
                    val shape = RoundedCornerShape(50)
                    Row(
                        Modifier.clip(shape).background(if (sel) p.primarySoft else p.surface)
                            .border(1.5.dp, if (sel) p.primary else Color.Transparent, shape)
                            .clickable { pick = i }
                            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryIcon(c?.name, c?.icon, (cats[x.categoryId] ?: c)?.color, size = 26.dp, transfer = x.type == "transfer")
                        Spacer(Modifier.width(6.dp))
                        Text(
                            listOfNotNull(
                                if (x.type == "transfer") "โอน" else c?.name ?: "ไม่มีหมวด",
                                x.amount?.let { (if (x.type == "income") "+฿" else "฿") + amountText(it) },
                                d.accounts.firstOrNull { it.id == x.accountId }?.name,
                            ).joinToString(" · "),
                            fontSize = 13.sp, color = if (sel) p.primary else p.ink, maxLines = 1,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                error ?: if (canSave) "กด Enter หรือ ➤ = บันทึกวันนี้" else "พิมพ์ชื่อ + ยอด แอพเดาหมวดกับบัญชีให้",
                Modifier.weight(1f), fontSize = 12.sp, color = if (error != null) p.negative else p.muted,
            )
            Text("แบบละเอียด ›", Modifier.clickable(onClick = onMore).padding(vertical = 4.dp), fontSize = 13.sp, color = p.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}
