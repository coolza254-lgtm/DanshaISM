package ism.dansha.core.engine

import ism.dansha.core.Account
import ism.dansha.core.Schema
import ism.dansha.core.Transaction
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplFoodTest {
    private val base = Schema.seed().copy(
        accounts = listOf(
            Account("acc_cash", "เงินสด", "cash", "THB", opening_balance = BigDecimal(1000), active = true),
            Account("acc_spl", "SPayLater", "revolving_credit", "THB", credit_limit = BigDecimal(5000), active = true, debt_engine = "spaylater"),
        ),
    )

    private fun food(date: String, amount: Int, acc: String = "acc_spl") =
        Transaction(id = "", date = date, type = "expense", account_id = acc, amount = BigDecimal(amount), currency = "THB", note = "ข้าว")

    @Test
    fun foodGoesToBillByOrderDate() {
        val r = Store.run(base) {
            val a = createTransaction(food("2026-10-10", 89))
            val b = createTransaction(food("2026-10-16", 120))
            a to b
        }
        val d = r.data
        val orders = d.shopee.filter { it.kind == SPayLater.FOOD }
        assertEquals(2, orders.size)
        val st = SPayLater.state(d.accounts[1], d.shopee, d.transactions, "2026-10-20")
        // สั่ง 10 ต.ค. → ครบ 25 ต.ค. · สั่ง 16 ต.ค. → ครบ 25 พ.ย. (ไม่ต้องกดรับ)
        assertEquals(89.0, st.installments.first { it.due == "2026-10-25" }.amount)
        assertEquals(120.0, st.installments.first { it.due == "2026-11-25" }.amount)
        assertTrue(st.installments.all { it.kind == SPayLater.FOOD })
        assertEquals(209.0, st.outstanding)
    }

    @Test
    fun editAndDeleteFollowTransaction() {
        val r = Store.run(base) {
            val t = createTransaction(food("2026-10-10", 89))
            updateTransaction(t.copy(amount = BigDecimal(99), date = "2026-10-15"))
            val o = shopee.first { it.txn_id == t.id }
            check(o.price.num() == 99.0 && o.order_date == "2026-10-15")
            val t2 = createTransaction(food("2026-10-11", 50))
            updateTransaction(t2.copy(account_id = "acc_cash")) // ย้ายไปจ่ายเงินสด → ไม่ใช่ SPayLater แล้ว
            deleteTransaction(t.id)
        }
        assertTrue(r.data.shopee.isEmpty())
    }
}
