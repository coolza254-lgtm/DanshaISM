package ism.dansha.core

import java.io.File
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DataFileTest {
    private val sample = """
    {
      "format": "dansha-data/1",
      "exported_at": "2026-10-03T12:00:00+07:00",
      "config": { "pay_cycle_start_day": "15", "copay_total_cap": 1000 },
      "tables": {
        "accounts": [
          {"id":"acc_1","name":"เงินสด","type":"cash","currency":"THB","opening_balance":17.6,"credit_limit":null,"sort":"3","active":"TRUE","debt_since":""},
          {"id":"","name":"แถวไม่มี id ต้องถูกตัดทิ้ง"}
        ],
        "transactions": [
          {"id":"txn_1","date":"2026-10-02T00:00:00","type":"expense","account_id":"acc_1","amount":43.2,"note":"7/11","full_price":108,"gov_subsidy":64.8,"extra_col":"ignored"},
          {"id":"txn_2","date":"2026-10-02","type":"income","account_id":"acc_1","amount":"1,095","note":711}
        ],
        "debts": [
          {"id":"debt_1","kind":"convert","principal":6000,"tenor":12.0,"installment":570.27,"snap_remaining":1262.12,"snap_paid_periods":9}
        ],
        "fx": [ {"currency":"USD","auto_rate":33.519995,"manual_rate":null} ]
      }
    }
    """.trimIndent()

    @Test
    fun parsesAndNormalizes() {
        val d = DataFile.parse(sample)
        assertEquals(1, d.accounts.size)
        val acc = d.accounts[0]
        assertEquals(BigDecimal("17.6"), acc.opening_balance)
        assertEquals(null, acc.credit_limit)
        assertEquals(3, acc.sort)
        assertEquals(true, acc.active)
        assertEquals("", acc.note)
        assertEquals("1000", d.config["copay_total_cap"])

        val t1 = d.transactions[0]
        assertEquals("2026-10-02", t1.date)
        assertEquals("7/11", t1.note)
        assertEquals(BigDecimal("64.8"), t1.gov_subsidy)
        val t2 = d.transactions[1]
        assertEquals(BigDecimal("1095"), t2.amount)
        assertEquals("711", t2.note)
        assertEquals(12, d.debts[0].tenor)
        assertEquals(BigDecimal("33.519995"), d.fx[0].auto_rate)
    }

    @Test
    fun roundTripKeepsEverything() {
        val d = DataFile.parse(sample)
        val text = DataFile.write(d, "2026-10-03T12:00:00", "test")
        assertTrue(text.contains("\"amount\":43.2"), text)
        assertTrue(text.contains("\"opening_balance\":17.6"), text)
        assertTrue(text.contains("\"credit_limit\":null"), text)
        assertTrue(text.contains("\"amount\":1095,"), text)
        assertEquals(d, DataFile.parse(text))
    }

    @Test
    fun emptyDataWrites() {
        val text = DataFile.write(DanshaData(), "x", "test")
        assertEquals(DanshaData(), DataFile.parse(text))
    }

    @Test
    fun rejectsBadFiles() {
        assertFailsWith<DataFileException> { DataFile.parse("not json") }
        assertFailsWith<DataFileException> { DataFile.parse("""{"format":"other/2","tables":{}}""") }
        assertFailsWith<DataFileException> { DataFile.parse("""{"hello":1}""") }
        val e = assertFailsWith<DataFileException> {
            DataFile.parse("""{"format":"dansha-data/1","tables":{"transactions":[{"id":"t","amount":"abc"}]}}""")
        }
        assertTrue(e.message!!.contains("amount"))
    }

    @Test
    fun seedHasCategoriesAndFx() {
        val s = Schema.seed()
        assertEquals(41 - 1, s.categories.size) // ข้อมูลจริงมี 41 หมวด (เพิ่ม "กาแฟ" เอง 1)
        assertEquals(4, s.fx.size)
        assertEquals("15", s.config["pay_cycle_start_day"])
        assertTrue(s.categories.all { Regex("^cat_[0-9a-f]{12}$").matches(it.id) })
    }

    /** ข้อมูลจริง (ไม่อยู่ใน repo): ./gradlew -Pdansha.android=false :core:test -Ddansha.data=/path/file.json */
    @Test
    fun realDataRoundTrip() {
        val path = System.getProperty("dansha.data") ?: return
        val d = DataFile.parse(File(path).readText())
        assertEquals(listOf(9, 47, 3, 0, 14, 4, 0, 41, 4, 0), d.counts().values.toList())
        assertEquals(d, DataFile.parse(DataFile.write(d, "x", "test")))
    }
}
