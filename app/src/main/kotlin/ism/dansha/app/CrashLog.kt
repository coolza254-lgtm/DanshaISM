package ism.dansha.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * เก็บรายละเอียดตอนแอพเด้ง (ไม่ส่งไปไหน) — เปิดแอพครั้งถัดไปจะถามว่าจะส่งให้ผู้พัฒนาไหม
 * ไฟล์อยู่ในเครื่องเท่านั้น และมีแค่ข้อความ error ไม่มีข้อมูลการเงิน
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                file(app).writeText(
                    "断捨ISM ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n" +
                        "${java.time.LocalDateTime.now()} · thread ${thread.name}\n\n" + sw.toString().take(12000),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }
}
