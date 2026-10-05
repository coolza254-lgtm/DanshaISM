package ism.dansha.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import ism.dansha.app.ui.Launch

/** ทางลัดเมื่อกดค้างไอคอนแอพบนหน้าจอหลัก */
object Shortcuts {
    fun intent(context: Context, launch: Launch): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(launch.action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun setup(context: Context) {
        fun item(id: String, short: String, long: String, icon: Int, launch: Launch) =
            ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(short)
                .setLongLabel(long)
                .setIcon(IconCompat.createWithResource(context, icon))
                .setIntent(intent(context, launch))
                .build()
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                listOf(
                    item("add_expense", "จดรายจ่าย", "จดรายจ่าย", R.drawable.ic_sc_expense, Launch.ADD_EXPENSE),
                    item("add_income", "จดรายรับ", "จดรายรับ", R.drawable.ic_sc_income, Launch.ADD_INCOME),
                    item("add_transfer", "โอน / จ่ายหนี้", "โอนเงิน / จ่ายหนี้", R.drawable.ic_sc_transfer, Launch.ADD_TRANSFER),
                    item("plan", "แผนบิล", "ดูแผนบิลรอบนี้", R.drawable.ic_sc_plan, Launch.PLAN),
                ),
            )
        }
    }
}

/** ปุ่ม "จดรายจ่าย" ใน Quick Panel (แผงที่ดึงลงจากด้านบนจอ) */
class AddExpenseTile : TileService() {
    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { openApp() } else openApp()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Shortcuts.intent(this, Launch.ADD_EXPENSE)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            startActivityAndCollapse(i)
        }
    }
}
