package ism.dansha.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * ชิ้นส่วนฟอร์มที่ใช้ทั้งแอพ — เปลี่ยนหน้าตาที่นี่ที่เดียวตอนออกแบบ UI ใหม่
 */

/** หน้าฟอร์มเต็มจอ: หัว (ย้อนกลับ + ชื่อ + ปุ่มบันทึก) + เนื้อหาเลื่อนได้ */
@Composable
fun FormPage(
    title: String,
    onDismiss: () -> Unit,
    onSave: (() -> Unit)?,
    saveLabel: String = "บันทึก",
    saveEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ย้อนกลับ") }
                    Text(title, Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (onSave != null) {
                        Button(onClick = onSave, enabled = saveEnabled, modifier = Modifier.padding(end = 8.dp)) { Text(saveLabel) }
                    }
                }
                HorizontalDivider(color = DanshaColors.Line)
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
fun TextInput(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true, placeholder: String? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine,
        placeholder = placeholder?.let { { Text(it) } }, modifier = modifier.fillMaxWidth(),
    )
}

/** ช่องตัวเลข/เงิน (แป้นตัวเลขมีจุดทศนิยม) */
@Composable
fun AmountField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, supporting: String? = null, isError: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

/** BigDecimal/Double → ข้อความในช่องกรอก (ไม่มีศูนย์ท้าย) */
fun amountText(v: BigDecimal?): String = v?.stripTrailingZeros()?.toPlainString()?.let { if (it == "0") "" else it }.orEmpty()
fun amountText(v: Double?): String = v?.let { amountText(BigDecimal.valueOf(it)) }.orEmpty()

/** ช่องเลือกวันที่ (yyyy-MM-dd) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, allowEmpty: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = if (value.isEmpty()) "" else thaiDate(value), onValueChange = {}, readOnly = true,
            label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        // ชั้นโปร่งใสรับการแตะ (ช่องที่ readOnly ไม่ส่ง onClick)
        Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = true })
    }
    if (open) {
        val initial = runCatching { LocalDate.parse(value) }.getOrNull() ?: LocalDate.now()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text("ตกลง") }
            },
            dismissButton = {
                Row {
                    if (allowEmpty) TextButton(onClick = { onChange(""); open = false }) { Text("ไม่ระบุ") }
                    TextButton(onClick = { open = false }) { Text("ยกเลิก") }
                }
            },
        ) { DatePicker(state = state) }
    }
}

/** เลือก 1 ค่าจากรายการ (dropdown) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Picker(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second.orEmpty(), onValueChange = {}, readOnly = true,
            label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (v, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(v); expanded = false })
            }
        }
    }
}

/** ตัวเลือกแบบปุ่มเรียงแถว (เลื่อนแนวนอนได้) */
@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, text) ->
            FilterChip(
                selected = v == selected, onClick = { onSelect(v) }, label = { Text(text) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = DanshaColors.Ink, selectedLabelColor = Color.White,
                ),
            )
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (sub != null) Text(sub, color = DanshaColors.Muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** กล่องข้อความ/คำอธิบายในฟอร์ม */
@Composable
fun Note(text: String, color: Color = DanshaColors.Muted) {
    Text(text, color = color, fontSize = 13.sp)
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmLabel, color = if (danger) DanshaColors.Negative else DanshaColors.Ink)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } },
    )
}

/** ปุ่มลบแบบข้อความสีแดงท้ายฟอร์ม (ถามยืนยันก่อน) */
@Composable
fun DeleteButton(what: String, onDelete: () -> Unit) {
    var ask by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { ask = true }, modifier = Modifier.fillMaxWidth()) { Text("ลบ$what", color = DanshaColors.Negative) }
    if (ask) ConfirmDialog("ลบ$what?", "ลบแล้วกู้คืนไม่ได้", "ลบ", onDelete, { ask = false })
}
