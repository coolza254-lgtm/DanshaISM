package ism.dansha.core

import kotlinx.serialization.Serializable

/*
 * แถวของแต่ละตาราง: ชื่อ property = ชื่อคอลัมน์ในไฟล์ dansha-data/1 และในฐานข้อมูล (ตรงกับ engine/schema.js)
 * ลำดับ property = ลำดับคอลัมน์ตอนส่งออก
 *
 * เงิน/อัตรา/จำนวน = BigDecimal (Dec) ไม่ใช้ Double เก็บยอด, ค่าว่าง = null
 * ข้อความว่าง = "" (ไม่ใช่ null), วันที่ = "yyyy-MM-dd", เวลา = "yyyy-MM-ddTHH:mm:ss" เวลาไทย
 */

@Suppress("PropertyName")
@Serializable
data class Account(
    val id: String,
    val name: String = "",
    /** cash | bank | revolving_credit */
    val type: String = "",
    val currency: String = "",
    val opening_balance: Dec? = null,
    val credit_limit: Dec? = null,
    val color: String = "",
    val icon: String = "",
    val sort: Int? = null,
    val active: Boolean = false,
    val note: String = "",
    val created_at: String = "",
    /** ascend | spaylater | "" */
    val debt_engine: String = "",
    val debt_since: String = "",
    val tenor_min: Int? = null,
    val tenor_max: Int? = null,
    val annual_rate: Dec? = null,
)

@Suppress("PropertyName")
@Serializable
data class Transaction(
    val id: String,
    val date: String = "",
    val pay_cycle: String = "",
    /** income | expense | transfer */
    val type: String = "",
    val account_id: String = "",
    val to_account_id: String = "",
    val amount: Dec? = null,
    val currency: String = "",
    val category_id: String = "",
    val subcategory_id: String = "",
    val note: String = "",
    /** manual | bill_plan | shopee | debt */
    val source: String = "",
    val ref_id: String = "",
    val created_at: String = "",
    val full_price: Dec? = null,
    val gov_subsidy: Dec? = null,
)

@Suppress("PropertyName")
@Serializable
data class Bill(
    val id: String,
    val pay_cycle: String = "",
    /** income | A | B */
    val group: String = "",
    val name: String = "",
    val category_id: String = "",
    val account_id: String = "",
    val est_amount: Dec? = null,
    val actual_amount: Dec? = null,
    /** planned | paid */
    val status: String = "",
    val due_date: String = "",
    val paid_date: String = "",
    val txn_id: String = "",
    val template_id: String = "",
    val note: String = "",
    val to_account_id: String = "",
    val sort: Int? = null,
)

@Suppress("PropertyName")
@Serializable
data class BillTemplate(
    val id: String,
    val group: String = "",
    val name: String = "",
    val category_id: String = "",
    val account_id: String = "",
    val est_amount: Dec? = null,
    val due_day: Int? = null,
    val active: Boolean = false,
    val sort: Int? = null,
    val note: String = "",
    val to_account_id: String = "",
)

@Suppress("PropertyName")
@Serializable
data class Debt(
    val id: String,
    val account_id: String = "",
    val txn_date: String = "",
    /** cash | installment | convert | fullpay_snapshot */
    val kind: String = "",
    val description: String = "",
    val principal: Dec? = null,
    val tenor: Int? = null,
    val annual_rate: Dec? = null,
    val installment: Dec? = null,
    val first_due_date: String = "",
    val status: String = "",
    val note: String = "",
    val created_at: String = "",
    val snap_date: String = "",
    val snap_remaining: Dec? = null,
    val snap_paid_periods: Int? = null,
    val snap_last_interest_date: String = "",
    val txn_id: String = "",
)

@Suppress("PropertyName")
@Serializable
data class ShopeeOrder(
    val id: String,
    val order_date: String = "",
    val item: String = "",
    val shop: String = "",
    val price: Dec? = null,
    val tenor: Int? = null,
    val annual_rate: Dec? = null,
    val account_id: String = "",
    /** ordered | active | cancelled */
    val status: String = "",
    val note: String = "",
    val created_at: String = "",
    /** spaylater | full */
    val pay_method: String = "",
    val installment: Dec? = null,
    val category_id: String = "",
    val txn_id: String = "",
    val order_no: String = "",
)

@Suppress("PropertyName")
@Serializable
data class PortTxn(
    val id: String,
    val date: String = "",
    val symbol: String = "",
    val name: String = "",
    /** stock | etf | fund | gold | other */
    val asset_type: String = "",
    /** buy | sell | dividend | fee */
    val side: String = "",
    val qty: Dec? = null,
    val price: Dec? = null,
    val currency: String = "",
    val fx_rate: Dec? = null,
    val fee: Dec? = null,
    val tax: Dec? = null,
    val broker: String = "",
    val note: String = "",
    val created_at: String = "",
)

@Suppress("PropertyName")
@Serializable
data class Category(
    val id: String,
    val name: String = "",
    /** income | expense */
    val type: String = "",
    val parent_id: String = "",
    val color: String = "",
    val icon: String = "",
    val sort: Int? = null,
    val active: Boolean = false,
)

@Suppress("PropertyName")
@Serializable
data class FxRate(
    val currency: String,
    val auto_rate: Dec? = null,
    val manual_rate: Dec? = null,
    val rate_to_thb: Dec? = null,
    val updated_note: String = "",
)

@Suppress("PropertyName")
@Serializable
data class Price(
    val symbol: String,
    val price: Dec? = null,
    val currency: String = "",
    val updated_at: String = "",
)

/** ข้อมูลทั้งหมดของแอพ = ไฟล์ dansha-data/1 */
data class DanshaData(
    val config: Map<String, String> = emptyMap(),
    val accounts: List<Account> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val bills: List<Bill> = emptyList(),
    val billTemplates: List<BillTemplate> = emptyList(),
    val debts: List<Debt> = emptyList(),
    val shopee: List<ShopeeOrder> = emptyList(),
    val port: List<PortTxn> = emptyList(),
    val categories: List<Category> = emptyList(),
    val fx: List<FxRate> = emptyList(),
    val prices: List<Price> = emptyList(),
) {
    /** จำนวนแถวต่อตาราง (เรียงตามไฟล์ส่งออก) */
    fun counts(): Map<String, Int> = linkedMapOf(
        "accounts" to accounts.size,
        "transactions" to transactions.size,
        "bills" to bills.size,
        "billTemplates" to billTemplates.size,
        "debts" to debts.size,
        "shopee" to shopee.size,
        "port" to port.size,
        "categories" to categories.size,
        "fx" to fx.size,
        "prices" to prices.size,
    )

    fun isEmpty(): Boolean = config.isEmpty() && counts().values.all { it == 0 }
}
