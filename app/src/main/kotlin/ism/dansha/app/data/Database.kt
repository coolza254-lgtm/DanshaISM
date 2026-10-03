package ism.dansha.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import ism.dansha.core.Account
import ism.dansha.core.Bill
import ism.dansha.core.BillTemplate
import ism.dansha.core.Category
import ism.dansha.core.Debt
import ism.dansha.core.FxRate
import ism.dansha.core.PortTxn
import ism.dansha.core.Price
import ism.dansha.core.ShopeeOrder
import ism.dansha.core.Transaction
import ism.dansha.core.toJsonNumber
import java.math.BigDecimal

/*
 * ตารางใน SQLite: คอลัมน์ = property ของแถวใน core (ชื่อเดียวกับไฟล์ dansha-data/1)
 * เงินเก็บเป็นข้อความทศนิยม (BigDecimal) ไม่ใช้ REAL
 */

@Entity(tableName = "accounts", primaryKeys = ["id"])
data class AccountEntity(@Embedded val row: Account)

@Entity(tableName = "transactions", primaryKeys = ["id"])
data class TransactionEntity(@Embedded val row: Transaction)

@Entity(tableName = "bills", primaryKeys = ["id"])
data class BillEntity(@Embedded val row: Bill)

@Entity(tableName = "billTemplates", primaryKeys = ["id"])
data class BillTemplateEntity(@Embedded val row: BillTemplate)

@Entity(tableName = "debts", primaryKeys = ["id"])
data class DebtEntity(@Embedded val row: Debt)

@Entity(tableName = "shopee", primaryKeys = ["id"])
data class ShopeeEntity(@Embedded val row: ShopeeOrder)

@Entity(tableName = "port", primaryKeys = ["id"])
data class PortEntity(@Embedded val row: PortTxn)

@Entity(tableName = "categories", primaryKeys = ["id"])
data class CategoryEntity(@Embedded val row: Category)

@Entity(tableName = "fx", primaryKeys = ["currency"])
data class FxEntity(@Embedded val row: FxRate)

@Entity(tableName = "prices", primaryKeys = ["symbol"])
data class PriceEntity(@Embedded val row: Price)

@Entity(tableName = "config")
data class ConfigEntity(@PrimaryKey val key: String, val value: String)

class Converters {
    @TypeConverter
    fun decToText(v: BigDecimal?): String? = v?.toJsonNumber()

    @TypeConverter
    fun textToDec(v: String?): BigDecimal? = v?.let { BigDecimal(it) }
}

@Dao
interface DanshaDao {
    @Query("SELECT * FROM config ORDER BY rowid") suspend fun config(): List<ConfigEntity>
    @Query("SELECT * FROM accounts ORDER BY rowid") suspend fun accounts(): List<AccountEntity>
    @Query("SELECT * FROM transactions ORDER BY rowid") suspend fun transactions(): List<TransactionEntity>
    @Query("SELECT * FROM bills ORDER BY rowid") suspend fun bills(): List<BillEntity>
    @Query("SELECT * FROM billTemplates ORDER BY rowid") suspend fun billTemplates(): List<BillTemplateEntity>
    @Query("SELECT * FROM debts ORDER BY rowid") suspend fun debts(): List<DebtEntity>
    @Query("SELECT * FROM shopee ORDER BY rowid") suspend fun shopee(): List<ShopeeEntity>
    @Query("SELECT * FROM port ORDER BY rowid") suspend fun port(): List<PortEntity>
    @Query("SELECT * FROM categories ORDER BY rowid") suspend fun categories(): List<CategoryEntity>
    @Query("SELECT * FROM fx ORDER BY rowid") suspend fun fx(): List<FxEntity>
    @Query("SELECT * FROM prices ORDER BY rowid") suspend fun prices(): List<PriceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putConfig(rows: List<ConfigEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccounts(rows: List<AccountEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTransactions(rows: List<TransactionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBills(rows: List<BillEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBillTemplates(rows: List<BillTemplateEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDebts(rows: List<DebtEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putShopee(rows: List<ShopeeEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPort(rows: List<PortEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCategories(rows: List<CategoryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFx(rows: List<FxEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPrices(rows: List<PriceEntity>)

    @Query("DELETE FROM config") suspend fun clearConfig()
    @Query("DELETE FROM accounts") suspend fun clearAccounts()
    @Query("DELETE FROM transactions") suspend fun clearTransactions()
    @Query("DELETE FROM bills") suspend fun clearBills()
    @Query("DELETE FROM billTemplates") suspend fun clearBillTemplates()
    @Query("DELETE FROM debts") suspend fun clearDebts()
    @Query("DELETE FROM shopee") suspend fun clearShopee()
    @Query("DELETE FROM port") suspend fun clearPort()
    @Query("DELETE FROM categories") suspend fun clearCategories()
    @Query("DELETE FROM fx") suspend fun clearFx()
    @Query("DELETE FROM prices") suspend fun clearPrices()
}

@Database(
    entities = [
        ConfigEntity::class, AccountEntity::class, TransactionEntity::class, BillEntity::class,
        BillTemplateEntity::class, DebtEntity::class, ShopeeEntity::class, PortEntity::class,
        CategoryEntity::class, FxEntity::class, PriceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): DanshaDao

    companion object {
        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "dansha.db").build()
    }
}
