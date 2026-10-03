package ism.dansha.app.data

import androidx.room.withTransaction
import ism.dansha.core.DanshaData
import ism.dansha.core.DataFile
import ism.dansha.core.Dates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ที่เก็บข้อมูลของแอพ: ข้อมูลทั้งหมดโหลดขึ้นหน่วยความจำ (ข้อมูลส่วนตัวมีไม่กี่ร้อยแถว)
 * ทุกการแก้ไขผ่านที่นี่ แล้วโหลดใหม่จากฐานข้อมูลทุกครั้ง หน้าจอจึงเห็นข้อมูลล่าสุดเสมอ
 */
class Repository(private val db: AppDatabase) {
    private val dao = db.dao()
    private val lock = Mutex()
    private val _data = MutableStateFlow<DanshaData?>(null)

    /** null = กำลังโหลด */
    val data: StateFlow<DanshaData?> = _data.asStateFlow()

    suspend fun load(): DanshaData = lock.withLock { reload() }

    private suspend fun reload(): DanshaData {
        val d = DanshaData(
            config = dao.config().associate { it.key to it.value },
            accounts = dao.accounts().map { it.row },
            transactions = dao.transactions().map { it.row },
            bills = dao.bills().map { it.row },
            billTemplates = dao.billTemplates().map { it.row },
            debts = dao.debts().map { it.row },
            shopee = dao.shopee().map { it.row },
            port = dao.port().map { it.row },
            categories = dao.categories().map { it.row },
            fx = dao.fx().map { it.row },
            prices = dao.prices().map { it.row },
        )
        _data.value = d
        return d
    }

    /** แทนที่ข้อมูลทั้งหมดในเครื่อง (นำเข้า / เริ่มใหม่) ทำในธุรกรรมเดียว พังกลางทางจะไม่มีอะไรเปลี่ยน */
    suspend fun replaceAll(d: DanshaData): DanshaData = lock.withLock {
        db.withTransaction {
            dao.clearConfig(); dao.clearAccounts(); dao.clearTransactions(); dao.clearBills()
            dao.clearBillTemplates(); dao.clearDebts(); dao.clearShopee(); dao.clearPort()
            dao.clearCategories(); dao.clearFx(); dao.clearPrices()
            dao.putConfig(d.config.map { (k, v) -> ConfigEntity(k, v) })
            dao.putAccounts(d.accounts.map(::AccountEntity))
            dao.putTransactions(d.transactions.map(::TransactionEntity))
            dao.putBills(d.bills.map(::BillEntity))
            dao.putBillTemplates(d.billTemplates.map(::BillTemplateEntity))
            dao.putDebts(d.debts.map(::DebtEntity))
            dao.putShopee(d.shopee.map(::ShopeeEntity))
            dao.putPort(d.port.map(::PortEntity))
            dao.putCategories(d.categories.map(::CategoryEntity))
            dao.putFx(d.fx.map(::FxEntity))
            dao.putPrices(d.prices.map(::PriceEntity))
        }
        reload()
    }

    /** ข้อความไฟล์ส่งออก (dansha-data/1) */
    suspend fun exportText(appVersion: String): String {
        val d = load()
        return DataFile.write(d, Dates.nowIso() + "+07:00", "断捨ISM Android $appVersion")
    }
}
