package kr.ssbin.assistant.work

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.AppLatestDto
import java.io.File

/**
 * 앱 안 업데이트. 서버(v1/app/latest)의 버전이 설치된 것보다 높으면 새 APK 를 받아 설치 화면을 띄운다.
 * CI 가 고정 키로 서명하므로 기존 앱 위에 덮어 설치되고 설정·데이터가 유지된다. 설치 확인은 사용자가 누른다.
 */
object Updater {

    fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** 더 새 버전이 있으면 그 정보, 없으면 null. 네트워크 호출이라 IO 스레드에서 부른다. */
    suspend fun check(context: Context): AppLatestDto? {
        val latest = runCatching { ApiClient.get(context).appLatest() }.getOrNull() ?: return null
        return if (latest.available && latest.version_code > installedVersionCode(context)) latest else null
    }

    /** 이 앱이 APK 설치를 요청할 수 있는가(안드로이드 8+ '출처를 알 수 없는 앱' 허용). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** 허용 설정 화면(이 앱 전용)을 연다. */
    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 새 APK 를 받아 캐시에 저장한다. 진행률(0~100)을 알려준다. IO 스레드에서 부른다. */
    suspend fun download(context: Context, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "assistant.apk")
        val body = ApiClient.get(context).appApk()
        val total = body.contentLength()
        body.byteStream().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    read += n
                    if (total > 0) onProgress((read * 100 / total).toInt())
                }
            }
        }
        return file
    }

    /** 설치 화면을 띄운다(사용자가 [업데이트]를 눌러 확인). */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
