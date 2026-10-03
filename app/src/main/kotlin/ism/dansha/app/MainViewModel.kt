package ism.dansha.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ism.dansha.app.update.ReleaseInfo
import ism.dansha.app.update.UpdateException
import ism.dansha.app.update.Updater
import ism.dansha.core.DanshaData
import ism.dansha.core.DataFile
import ism.dansha.core.DataFileException
import ism.dansha.core.Dates
import ism.dansha.core.Schema
import ism.dansha.core.engine.DebtState
import ism.dansha.core.engine.Engine
import ism.dansha.core.engine.EngineException
import ism.dansha.core.engine.Store
import ism.dansha.core.engine.Home
import ism.dansha.core.engine.HomeFigures
import ism.dansha.core.engine.Overview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    data class Downloading(val info: ReleaseInfo, val progress: Float) : UpdateState
    data class Ready(val info: ReleaseInfo, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** ไฟล์ที่เลือกนำเข้า รอให้ผู้ใช้ยืนยันก่อนแทนที่ข้อมูลในเครื่อง */
data class PendingImport(val data: DanshaData, val fileName: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as DanshaApp).repository
    private val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)

    val data: StateFlow<DanshaData?> = repo.data

    /** ตัวเลขที่คำนวณแล้ว (คำนวณใหม่ทุกครั้งที่ข้อมูลเปลี่ยน) */
    data class Computed(val overview: Overview, val home: HomeFigures, val debts: Map<String, DebtState>)

    val computed: StateFlow<Computed?> = repo.data
        .map { d ->
            d?.takeIf { !it.isEmpty() }?.let {
                try {
                    val ov = Engine.overview(it)
                    Computed(ov, Home.figures(ov), Engine.debtStates(it, ov.today, withSchedule = true))
                } catch (e: Exception) {
                    android.util.Log.e("Dansha", "คำนวณไม่สำเร็จ", e)
                    null
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    init {
        viewModelScope.launch {
            repo.load()
            autoUpdateFx()
        }
        autoCheckUpdate()
    }

    // ---------- อัตราแลกเปลี่ยน ----------

    private suspend fun fetchFx(): Map<String, Double> = withContext(Dispatchers.IO) {
        val sources = listOf(
            "https://open.er-api.com/v6/latest/THB",
            "https://api.frankfurter.app/latest?from=THB&to=USD,JPY,CNY",
        )
        for (url in sources) {
            try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10_000; readTimeout = 15_000
                }
                try {
                    if (conn.responseCode == 200) {
                        val rates = ism.dansha.core.engine.FxFeed.parse(conn.inputStream.bufferedReader().use { it.readText() }, Schema.CURRENCIES)
                        if (rates.isNotEmpty()) return@withContext rates
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                // ลองแหล่งถัดไป
            }
        }
        emptyMap()
    }

    /** ดึงเรทวันละครั้ง (เงียบๆ ถ้าไม่สำเร็จ) */
    private suspend fun autoUpdateFx() {
        val today = Dates.todayStr()
        val d = repo.data.value ?: return
        if (d.isEmpty() || prefs.getString("fx_updated", "") == today) return
        val rates = fetchFx()
        if (rates.isEmpty()) return
        try {
            repo.edit { setFxAuto(rates) }
            prefs.edit().putString("fx_updated", today).apply()
        } catch (_: Exception) {
        }
    }

    /** ปุ่ม "อัปเดตเรทตอนนี้" ในหน้าตั้งค่า */
    fun updateFxNow() = viewModelScope.launch {
        val rates = fetchFx()
        if (rates.isEmpty()) {
            _message.value = "ดึงอัตราแลกเปลี่ยนไม่ได้ (ไม่มีอินเทอร์เน็ต?)"
            return@launch
        }
        edit<Unit>(ok = "อัปเดตเรทแล้ว: " + rates.entries.joinToString { "${it.key} ${it.value}" }) { setFxAuto(rates) }
        prefs.edit().putString("fx_updated", Dates.todayStr()).apply()
    }

    // ---------- แชร์สรุป ----------

    /** ข้อความสรุปวันนี้ สำหรับแชร์ให้แฟนผ่าน LINE ฯลฯ */
    fun summaryText(): String? {
        val d = repo.data.value?.takeIf { !it.isEmpty() } ?: return null
        val m = ism.dansha.core.engine.Notify.dailySummary(d, Dates.now())
        return m.title + "\n\n" + m.body
    }

    fun clearMessage() {
        _message.value = null
    }

    // ---------- แก้ไขข้อมูล ----------

    /**
     * แก้ไขข้อมูล 1 ครั้ง: สำเร็จ → แสดง ok (ถ้ามี) แล้วเรียก onOk, ไม่สำเร็จ → แสดงข้อความ error ภาษาไทยจากตัวคำนวณ
     */
    fun <T> edit(
        ok: String? = null,
        onError: ((String) -> Unit)? = null,
        onOk: (T) -> Unit = {},
        block: Store.() -> T,
    ) {
        viewModelScope.launch {
            val err = try {
                val v = repo.edit(block)
                if (ok != null) _message.value = ok
                onOk(v)
                null
            } catch (e: EngineException) {
                e.message ?: "บันทึกไม่สำเร็จ"
            } catch (e: Exception) {
                android.util.Log.e("Dansha", "บันทึกไม่สำเร็จ", e)
                "บันทึกไม่สำเร็จ: ${e.message ?: e.javaClass.simpleName}"
            }
            if (err != null) {
                if (onError != null) onError(err) else _message.value = err
            }
        }
    }

    fun toast(text: String) {
        _message.value = text
    }

    // ---------- นำเข้า / ส่งออก ----------

    fun pickImport(uri: Uri) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        try {
            val text = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            } ?: throw DataFileException("เปิดไฟล์ไม่ได้")
            val parsed = withContext(Dispatchers.Default) { DataFile.parse(text) }
            _pendingImport.value = PendingImport(parsed, uri.lastPathSegment?.substringAfterLast('/') ?: "ไฟล์ที่เลือก")
        } catch (e: DataFileException) {
            _message.value = "นำเข้าไม่ได้: ${e.message}"
        } catch (e: Exception) {
            _message.value = "นำเข้าไม่ได้: อ่านไฟล์ไม่สำเร็จ"
        }
    }

    fun cancelImport() {
        _pendingImport.value = null
    }

    fun confirmImport() = viewModelScope.launch {
        val p = _pendingImport.value ?: return@launch
        _pendingImport.value = null
        repo.replaceAll(p.data)
        _message.value = "นำเข้าเรียบร้อย: บัญชี ${p.data.accounts.size} · รายการ ${p.data.transactions.size} · หนี้ย่อย ${p.data.debts.size}"
    }

    fun exportFileName(): String = "dansha-data-${Dates.todayStr()}.json"

    fun export(uri: Uri) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        try {
            val text = repo.exportText(BuildConfig.VERSION_NAME)
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                    ?: throw IllegalStateException()
            }
            _message.value = "ส่งออกเรียบร้อย"
        } catch (e: Exception) {
            _message.value = "ส่งออกไม่สำเร็จ"
        }
    }

    /** เริ่มใหม่: ลบข้อมูลทั้งหมด แล้วใส่หมวดหมู่ + ค่าตั้งต้น */
    fun startFresh() = viewModelScope.launch {
        repo.replaceAll(Schema.seed())
        _message.value = "เริ่มใหม่แล้ว (มีหมวดหมู่ตั้งต้นให้)"
    }

    // ---------- อัปเดตแอพ ----------

    private fun autoCheckUpdate() {
        val today = Dates.todayStr()
        if (prefs.getString("update_checked", "") == today) return
        viewModelScope.launch {
            try {
                val info = Updater.latest()
                prefs.edit().putString("update_checked", today).apply()
                if (info.isNewer) _update.value = UpdateState.Available(info)
            } catch (_: UpdateException) {
                // เช็คเองเงียบๆ ไม่ต้องแจ้ง ถ้าไม่สำเร็จ
            }
        }
    }

    fun checkUpdate() = viewModelScope.launch {
        _update.value = UpdateState.Checking
        _update.value = try {
            val info = Updater.latest()
            prefs.edit().putString("update_checked", Dates.todayStr()).apply()
            if (info.isNewer) UpdateState.Available(info) else UpdateState.UpToDate
        } catch (e: UpdateException) {
            UpdateState.Failed(e.message ?: "เช็คอัปเดตไม่ได้")
        }
    }

    fun downloadUpdate(info: ReleaseInfo) = viewModelScope.launch {
        _update.value = UpdateState.Downloading(info, 0f)
        _update.value = try {
            val apk = Updater.download(getApplication(), info) { p ->
                _update.value = UpdateState.Downloading(info, p)
            }
            UpdateState.Ready(info, apk)
        } catch (e: UpdateException) {
            UpdateState.Failed(e.message ?: "ดาวน์โหลดไม่สำเร็จ")
        }
    }

    fun dismissUpdate() {
        _update.value = UpdateState.Idle
    }
}
