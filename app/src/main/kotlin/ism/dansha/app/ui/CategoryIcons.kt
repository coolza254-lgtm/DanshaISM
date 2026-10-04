package ism.dansha.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DeliveryDining
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Icecream
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.LocalTaxi
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * ไอคอนของหมวด: เดาจากชื่อหมวดก่อน (หมวดย่อยได้ไอคอนของตัวเอง เช่น กาแฟ ☕ น้ำมัน ⛽)
 * ไม่รู้จักชื่อ → ดูจากอีโมจิของหมวด → ไม่รู้จักอีก ใช้อีโมจินั้นเลย
 */

private val BY_NAME: List<Pair<List<String>, ImageVector>> = listOf(
    // รายรับ
    listOf("เงินเดือน", "salary") to Icons.Outlined.Work,
    listOf("ฟรีแลนซ์", "freelance") to Icons.Outlined.Laptop,
    listOf("ขายของ") to Icons.Outlined.Sell,
    listOf("ปันผล", "เงินออม", "ออม") to Icons.Outlined.Savings,
    listOf("รายได้เสริม", "โบนัส") to Icons.Outlined.AutoAwesome,
    listOf("รายรับอื่น") to Icons.Outlined.AddCircleOutline,
    // อาหาร
    listOf("กาแฟ", "coffee", "คาเฟ่") to Icons.Outlined.LocalCafe,
    listOf("ขนม", "เครื่องดื่ม", "ของหวาน") to Icons.Outlined.Icecream,
    listOf("สะดวกซื้อ", "7-11", "เซเว่น", "ซูเปอร์") to Icons.Outlined.Storefront,
    listOf("เดลิเวอรี่", "delivery", "grabfood", "lineman") to Icons.Outlined.DeliveryDining,
    listOf("อาหาร", "ข้าว") to Icons.Outlined.Restaurant,
    // เดินทาง
    listOf("แท็กซี่", "grab", "วิน") to Icons.Outlined.LocalTaxi,
    listOf("น้ำมัน", "ปั๊ม") to Icons.Outlined.LocalGasStation,
    listOf("รถไฟ", "bts", "mrt") to Icons.Outlined.Train,
    listOf("รถสาธารณะ", "รถเมล์", "เดินทาง") to Icons.Outlined.DirectionsBus,
    // บ้าน
    listOf("ค่าไฟ", "ไฟฟ้า") to Icons.Outlined.Bolt,
    listOf("ค่าน้ำ", "ประปา") to Icons.Outlined.WaterDrop,
    listOf("อินเทอร์เน็ต", "เน็ต", "wifi") to Icons.Outlined.Wifi,
    listOf("ค่าเช่า", "ที่พัก", "บ้าน") to Icons.Outlined.Home,
    // โทรศัพท์
    listOf("subscription", "สมาชิก", "netflix", "youtube", "spotify") to Icons.Outlined.Subscriptions,
    listOf("โทรศัพท์", "มือถือ") to Icons.Outlined.PhoneAndroid,
    // ช้อปปิ้ง
    listOf("เสื้อผ้า", "รองเท้า") to Icons.Outlined.Checkroom,
    listOf("ของใช้") to Icons.Outlined.Inventory2,
    listOf("shopee", "lazada", "ช้อป") to Icons.Outlined.ShoppingBag,
    // สุขภาพ
    listOf("โรงพยาบาล", "หมอ", "คลินิก") to Icons.Outlined.LocalHospital,
    listOf("ยา", "สุขภาพ") to Icons.Outlined.Medication,
    // บันเทิง
    listOf("เกม", "game") to Icons.Outlined.SportsEsports,
    listOf("หนัง", "เพลง") to Icons.Outlined.Movie,
    listOf("เที่ยว", "travel") to Icons.Outlined.Flight,
    listOf("บันเทิง") to Icons.Outlined.SportsEsports,
    // อื่นๆ
    listOf("ครอบครัว") to Icons.Outlined.FamilyRestroom,
    listOf("แฟน") to Icons.Outlined.Favorite,
    listOf("ดอกเบี้ย") to Icons.Outlined.Percent,
    listOf("ค่าธรรมเนียม") to Icons.AutoMirrored.Outlined.ReceiptLong,
    listOf("หนี้", "บัตร") to Icons.Outlined.CreditCard,
    listOf("ลงทุน", "หุ้น", "กองทุน") to Icons.AutoMirrored.Outlined.ShowChart,
    listOf("สัตว์", "แมว", "หมา") to Icons.Outlined.Pets,
    listOf("เรียน", "การศึกษา") to Icons.Outlined.School,
    listOf("ของขวัญ", "บริจาค", "ทำบุญ") to Icons.Outlined.CardGiftcard,
    listOf("อื่น") to Icons.Outlined.MoreHoriz,
)

private val BY_EMOJI: Map<String, ImageVector> = mapOf(
    "💼" to Icons.Outlined.Work, "✨" to Icons.Outlined.AutoAwesome, "🌱" to Icons.Outlined.Savings, "➕" to Icons.Outlined.AddCircleOutline,
    "🍜" to Icons.Outlined.Restaurant, "🚆" to Icons.Outlined.DirectionsBus, "🏠" to Icons.Outlined.Home, "📱" to Icons.Outlined.PhoneAndroid,
    "🛍️" to Icons.Outlined.ShoppingBag, "💊" to Icons.Outlined.Medication, "🎮" to Icons.Outlined.SportsEsports, "💗" to Icons.Outlined.Favorite,
    "💳" to Icons.Outlined.CreditCard, "📈" to Icons.AutoMirrored.Outlined.ShowChart, "📦" to Icons.Outlined.MoreHoriz, "💰" to Icons.Outlined.Payments,
)

/** ไอคอนของหมวด (null = ไม่รู้จัก ให้ใช้อีโมจิแทน) */
fun categoryVector(name: String, emoji: String): ImageVector? {
    val key = name.trim().lowercase()
    return BY_NAME.firstOrNull { (words, _) -> words.any { key.contains(it) } }?.second ?: BY_EMOJI[emoji.trim()]
}

/** วงกลมสีพาสเทล + ไอคอนหมึก (transfer = ลูกศรสลับ) */
@Composable
fun CategoryIcon(name: String?, emoji: String?, color: String?, size: Dp = 36.dp, transfer: Boolean = false, ring: Color? = null) {
    val p = LocalPalette.current
    val vector = if (transfer) Icons.Outlined.SwapHoriz else name?.let { categoryVector(it, emoji.orEmpty()) }
    Box(
        Modifier.size(size).background(if (transfer) p.surface else tint(color.orEmpty()), CircleShape)
            .border(if (ring != null) 2.5.dp else 1.3.dp, ring ?: p.ink.copy(alpha = if (p.dark) 0.45f else 0.75f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            vector != null -> Icon(vector, contentDescription = name, tint = p.ink, modifier = Modifier.size(size * 0.55f))
            !emoji.isNullOrBlank() -> Text(emoji, fontSize = (size.value * 0.42f).sp)
            else -> Text(categoryMark(name ?: "•"), fontFamily = Hand, fontSize = (size.value * 0.42f).sp, color = p.ink)
        }
    }
}
