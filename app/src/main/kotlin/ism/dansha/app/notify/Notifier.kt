package ism.dansha.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ism.dansha.app.DanshaApp
import ism.dansha.app.MainActivity
import ism.dansha.app.R
import ism.dansha.core.Dates
import ism.dansha.core.engine.Engine
import ism.dansha.core.engine.Notify
import ism.dansha.core.engine.NotifyMessage
import java.util.concurrent.TimeUnit

/** แจ้งเตือนในเครื่อง: ครบกำหนดหนี้/บิล + สรุปรายวัน (แทนอีเมลของระบบเดิม) */
object Notifier {
    private const val CHANNEL_REMIND = "remind"
    private const val CHANNEL_SUMMARY = "summary"
    private const val WORK = "dansha-notify"

    fun setup(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REMIND, "ครบกำหนดชำระ", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SUMMARY, "สรุปรายวัน", NotificationManager.IMPORTANCE_DEFAULT))
        // เช็คทุกชั่วโมง แต่แจ้งจริงวันละครั้งตามเวลาที่ตั้ง
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NotifyWorker>(1, TimeUnit.HOURS).build(),
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** launch = เปิดแอพไปหน้าไหน (null = หน้าแรก) */
    fun show(context: Context, id: Int, summary: Boolean, msg: NotifyMessage, launch: ism.dansha.app.ui.Launch? = null) {
        if (!canNotify(context)) return
        val intent = if (launch != null) ism.dansha.app.Shortcuts.intent(context, launch)
        else Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, if (summary) CHANNEL_SUMMARY else CHANNEL_REMIND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(msg.title)
            .setContentText(msg.body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // ผู้ใช้ปิดสิทธิ์แจ้งเตือน
        }
    }
}

class NotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as DanshaApp
        val d = app.repository.load()
        if (d.isEmpty()) return Result.success()
        val prefs = applicationContext.getSharedPreferences("notify", Context.MODE_PRIVATE)
        val now = Dates.now()
        val today = now.toLocalDate().toString()
        val jobs = Notify.dueJobs(
            Engine.config(d), now.hour,
            sentSummary = prefs.getString("summary", "") == today,
            sentRemind = prefs.getString("remind", "") == today,
        )
        jobs.forEach { job ->
            when (job) {
                Notify.Job.Summary -> Notifier.show(applicationContext, 1, true, Notify.dailySummary(d, now))
                Notify.Job.Remind -> Notify.reminders(d, now)?.let { Notifier.show(applicationContext, 2, false, it) }
            }
            prefs.edit().putString(if (job == Notify.Job.Summary) "summary" else "remind", today).apply()
        }
        // วันที่ 14: Slip เงินเดือนออก → เตือนให้กรอก (วันละครั้ง ตั้งแต่ 9 โมง)
        if (now.dayOfMonth == 14 && now.hour >= 9 && prefs.getString("slip", "") != today) {
            Notifier.show(applicationContext, 3, false, NotifyMessage("🧾 Slip เงินเดือนออกแล้ว", "เปิดแผนบิล → รายรับ → กรอกเงินเดือนจาก Slip ไว้ก่อนเงินเข้าวันที่ 15"))
            prefs.edit().putString("slip", today).apply()
        }
        // ถึงเวลาแล้ววันนี้ยังไม่ได้จดอะไรเลย → ชวนจด (วันละครั้ง) · ไม่ได้จดหลายวัน → เปิดหน้ากลับมาจดต่อ
        if (prefs.getString("nudge", "") != today) {
            Notify.logNudge(d, now)?.let { m ->
                val behind = ism.dansha.core.engine.CatchUp.gap(d, now.toLocalDate()).days >= ism.dansha.core.engine.CatchUp.REMIND_DAYS
                Notifier.show(applicationContext, 4, false, m, if (behind) ism.dansha.app.ui.Launch.CATCH_UP else ism.dansha.app.ui.Launch.ADD_EXPENSE)
                prefs.edit().putString("nudge", today).apply()
            }
        }
        return Result.success()
    }
}
