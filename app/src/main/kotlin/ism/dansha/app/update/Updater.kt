package ism.dansha.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import ism.dansha.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSize: Long,
    val notes: String,
) {
    val isNewer: Boolean get() = versionCode > BuildConfig.VERSION_CODE
}

class UpdateException(message: String) : Exception(message)

/**
 * อัปเดตแอพจาก GitHub Releases ของ repo (public จึงไม่ต้องใช้ token)
 * tag ของ release = v1.0.<เลข build> และเลข build = versionCode
 */
object Updater {
    private const val API = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val conn = open(API).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            when (conn.responseCode) {
                200 -> Unit
                404 -> throw UpdateException("ยังไม่มีเวอร์ชันให้ดาวน์โหลด (หรือ repo ยังไม่เป็น public)")
                403, 429 -> throw UpdateException("GitHub จำกัดจำนวนครั้ง ลองใหม่อีกครั้งภายหลัง")
                else -> throw UpdateException("เช็คอัปเดตไม่ได้ (HTTP ${conn.responseCode})")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = json.parseToJsonElement(body).jsonObject
            val tag = root.str("tag_name")
            val code = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull()
                ?: throw UpdateException("อ่านเลขเวอร์ชันไม่ได้: $tag")
            val apk = root["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it.str("name").endsWith(".apk") }
                ?: throw UpdateException("เวอร์ชัน $tag ไม่มีไฟล์ APK")
            ReleaseInfo(
                versionCode = code,
                versionName = tag.removePrefix("v"),
                apkUrl = apk.str("browser_download_url"),
                apkSize = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                notes = root.str("body"),
            )
        } catch (e: IOException) {
            throw UpdateException("เชื่อมต่ออินเทอร์เน็ตไม่ได้")
        } finally {
            conn.disconnect()
        }
    }

    /** ดาวน์โหลด APK ไปไว้ใน cache/updates แล้วคืนไฟล์ */
    suspend fun download(context: Context, info: ReleaseInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "DanshaISM-${info.versionName}.apk")
            val conn = open(info.apkUrl)
            try {
                if (conn.responseCode != 200) throw UpdateException("ดาวน์โหลดไม่สำเร็จ (HTTP ${conn.responseCode})")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
                out
            } catch (e: IOException) {
                out.delete()
                throw UpdateException("ดาวน์โหลดไม่สำเร็จ ลองใหม่อีกครั้ง")
            } finally {
                conn.disconnect()
            }
        }

    /** Android 8+ ต้องอนุญาต "ติดตั้งแอพที่ไม่รู้จัก" ให้แอพนี้ก่อน */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** เปิดตัวติดตั้งของ Android */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DanshaISM-Android/${BuildConfig.VERSION_NAME}")
        }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
