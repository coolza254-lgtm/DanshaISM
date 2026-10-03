package ism.dansha.core.engine

import ism.dansha.core.DataFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormsTest {
    private val d = DataFile.parse(File(System.getProperty("dansha.reference"), "sample-data.json").readText())

    @Test
    fun parseAmount() {
        assertEquals(1234.5, Forms.parseAmount("1,234.50"))
        assertEquals(43.2, Forms.parseAmount(" ฿43.2 "))
        assertNull(Forms.parseAmount(""))
        assertNull(Forms.parseAmount("abc"))
    }

    @Test
    fun copayPreviewFollowsCaps() {
        // 2 ต.ค. ใช้ไปแล้ว 60 → เหลือวันนี้ 140, ราคา 300 → รัฐ min(180, 140) = 140
        val p = Forms.copayPreview(d, "expense", "acc_wallet", "2026-10-02", 300.0)!!
        assertEquals(140.0, p.leftToday)
        assertEquals(140.0, p.gov)
        assertEquals(160.0, p.self)
        // แก้รายการเดิมของวันนั้น: ไม่นับตัวเอง
        assertEquals(200.0, Forms.copayPreview(d, "expense", "acc_wallet", "2026-10-02", 300.0, excludeId = "txn_c2")!!.leftToday)
        assertNull(Forms.copayPreview(d, "expense", "acc_bank", "2026-10-02", 300.0)) // ไม่ใช่ G-Wallet
        assertNull(Forms.copayPreview(d, "income", "acc_wallet", "2026-10-02", 300.0))
        assertNull(Forms.copayPreview(d, "expense", "acc_wallet", "2026-09-30", 300.0)) // ก่อนเริ่มโครงการ
    }

    @Test
    fun choices() {
        assertEquals("acc_extra", Forms.defaultAccount(d, "expense")) // รายจ่ายล่าสุดคือ txn_e1
        assertEquals("acc_extra", Forms.defaultAccount(d, "income"))
        val tree = Forms.categoryTree(d, "expense")
        assertEquals(listOf("cat_food", "cat_shop", "cat_bill", "cat_unused"), tree.map { it.category.id })
        assertEquals(listOf("cat_food_main", "cat_food_snack"), tree[0].children.map { it.id })
        assertEquals(listOf("txn_s2"), Forms.search(d, "7/11").map { it.id })
        assertEquals(false, Forms.accountChoices(d).any { it.id == "acc_old" })
        assertEquals(true, Forms.accountChoices(d, keep = "acc_old").any { it.id == "acc_old" })
    }
}
