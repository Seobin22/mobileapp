package kr.ssbin.assistant.lock

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kr.ssbin.assistant.R
import kr.ssbin.assistant.data.AppDatabase
import kr.ssbin.assistant.data.AppJson
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.intervention.InterventionController
import kr.ssbin.assistant.intervention.WindowGuard
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** plan.sync 의 monitor 창(빌드문서 v4 §5.1). */
@Serializable
data class MonitorWindow(
    val block_id: Long,
    val title: String = "",
    val from: String,
    val to: String,
    val fixed: Boolean = false,
    val record_only: Boolean = false,
    val allowed_apps: List<String> = emptyList(),
)

@Serializable
data class AppRule(val `package`: String, val kind: String)

/**
 * 딴짓 감지(§5.2·§5.3). 접근성 이벤트로 지금 앞의 앱을 알고, 기기 안에서 혼자 판단한다(매번 서버에 묻지 않는다).
 * - 딴짓 앱(또는 '딴짓' 판정을 받은 애매한 화면)에 있으면 시간을 센다. 딴짓 앱끼리 옮겨도 이어서 센다.
 * - 딴짓이 아닌 앱에 30초 미만 있다가 돌아오면 끊긴 걸로 보지 않는다. 1분 넘게 가 있으면(또는 화면이 꺼지면) 구간을 닫는다.
 * - 연속 distract.trigger_min 분이 되면 서버에 한 번 알리고, 구간이 닫히면 끝을 알린다. 서버와 끊기면 모아 두었다가 보낸다.
 * 화면 글자는 애매한 앱의 정해진 칸(유튜브 영상 제목, 크롬 주소창 도메인)만 읽는다.
 */
object DistractionTracker {

    private const val CONTINUE_GAP_MS = 30_000L
    private const val CLOSE_GAP_MS = 60_000L
    private const val JUDGE_AFTER_MS = 60_000L
    private const val NOTIFICATION_ID = 6001
    private const val CHANNEL = "monitor"

    private val net = Executors.newSingleThreadExecutor()

    private class Episode(
        val key: String, val blockId: Long, var pkg: String, var label: String, var kind: String,
        var domain: String?, var title: String?, val startMs: Long, val recordOnly: Boolean,
    ) {
        var accMs = 0L
        var reported = false
    }

    private var episode: Episode? = null
    private var currentPkg: String = ""
    private var lastEvalMs = 0L
    private var lastDistract = false
    private var nonDistractSince: Long? = null
    private var notified: Long? = null

    // 애매한 화면의 제목
    private var screenKey: String? = null
    private var screenKind = "app"
    private var screenDomain: String? = null
    private var screenTitle: String? = null
    private var screenSince = 0L
    private val verdicts = HashMap<String, String>()
    private val judging = HashSet<String>()

    private fun monitors(context: Context): List<MonitorWindow> =
        runCatching { AppJson.decodeFromString<List<MonitorWindow>>(Prefs.get(context).monitorWindows) }.getOrDefault(emptyList())

    private fun rules(context: Context): Map<String, String> =
        runCatching { AppJson.decodeFromString<List<AppRule>>(Prefs.get(context).appRules) }.getOrDefault(emptyList())
            .associate { it.`package` to it.kind }

    /** 지금 볼 창. 끄는 조건(§5.1): 방해 금지·[10분만 조용히]·다른 고정 일정 진행 중·블록 밖. */
    fun activeMonitor(context: Context, now: Instant = Instant.now()): MonitorWindow? {
        if (WindowGuard.dnd(context, now) != null || WindowGuard.paused(context, now)) return null
        val m = monitors(context).firstOrNull {
            !now.isBefore(OffsetDateTime.parse(it.from).toInstant()) && now.isBefore(OffsetDateTime.parse(it.to).toInstant())
        } ?: return null
        val plan = AppDatabase.dao(context).plan()
        if (plan != null && InterventionController.upcoming(plan).any {
                it.kind == "fixed" && it.block_id != m.block_id &&
                    !now.isBefore(OffsetDateTime.parse(it.alarm_at ?: it.start).toInstant()) &&
                    now.isBefore(OffsetDateTime.parse(it.end).toInstant())
            }) return null
        return m
    }

    /** 앞에 온 앱이 바뀜. */
    fun onForeground(context: Context, pkg: String) {
        if (pkg == currentPkg) return
        currentPkg = pkg
        screenKey = null
        evaluate(context)
    }

    /** 애매한 앱의 화면 내용이 바뀜: 정해진 칸만 읽는다(§5.3). */
    fun onContent(service: AccessibilityService, pkg: String) {
        if (pkg != currentPkg || rules(service)[pkg] != "ambiguous" || activeMonitor(service) == null) return
        val read = readScreen(service, pkg)
        if (read == null) {
            screenKey = null
            return
        }
        val key = (read.second ?: "") + "|" + (read.third ?: "")
        if (key != screenKey) {
            screenKey = key
            screenKind = read.first
            screenDomain = read.second
            screenTitle = read.third
            screenSince = System.currentTimeMillis()
        }
        evaluate(service)
    }

    /** 15초마다(접근성 서비스가 부른다). */
    fun tick(context: Context) = evaluate(context)

    private fun distractNow(context: Context, m: MonitorWindow): Boolean {
        val pkg = currentPkg
        if (pkg.isEmpty() || pkg in m.allowed_apps || Lock.alwaysAllowed(context, pkg)) return false
        return when (rules(context)[pkg]) {
            "distract" -> true
            "ambiguous" -> {
                val key = screenKey ?: return false // 못 읽으면 neutral
                val cacheKey = "${m.block_id}|$key"
                val v = verdicts[cacheKey]
                if (v == null && System.currentTimeMillis() - screenSince >= JUDGE_AFTER_MS && judging.add(cacheKey)) {
                    judge(context, m.block_id, cacheKey)
                }
                v == "unrelated" // 판정 전에는 세지 않고 기다린다
            }
            else -> false
        }
    }

    private fun evaluate(context: Context) {
        val now = System.currentTimeMillis()
        val m = activeMonitor(context)
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive
        if (m == null || !interactive) {
            close(context, now)
            showNotice(context, m)
            lastEvalMs = now
            lastDistract = false
            return
        }
        showNotice(context, m)
        val distract = distractNow(context, m)
        val delta = if (lastEvalMs == 0L) 0L else (now - lastEvalMs).coerceIn(0, 120_000)
        val ep = episode
        if (ep != null && ep.blockId != m.block_id) close(context, now)
        if (distract) {
            nonDistractSince = null
            val e = episode ?: Episode(UUID.randomUUID().toString(), m.block_id, currentPkg, label(context, currentPkg),
                if (rules(context)[currentPkg] == "ambiguous") screenKind else "app", screenDomain, screenTitle, now,
                m.record_only).also { episode = it }
            if (lastDistract) e.accMs += delta
            if (e.label != label(context, currentPkg) && e.accMs < 60_000) {
                e.pkg = currentPkg; e.label = label(context, currentPkg)
            }
            val trigger = Prefs.get(context).distractTriggerMin * 60_000L
            if (!e.reported && e.accMs >= trigger) {
                e.reported = true
                post(context, event(e, null))
            }
        } else if (episode != null) {
            val since = nonDistractSince ?: now.also { nonDistractSince = it }
            if (now - since < CONTINUE_GAP_MS && lastDistract) episode?.let { it.accMs += delta }
            if (now - since >= CLOSE_GAP_MS) close(context, since)
        }
        lastDistract = distract
        lastEvalMs = now
    }

    private fun close(context: Context, endMs: Long) {
        val e = episode ?: return
        episode = null
        nonDistractSince = null
        if (e.reported) post(context, event(e, endMs))
    }

    private fun event(e: Episode, endMs: Long?): JsonObject = buildJsonObject {
        put("client_key", e.key)
        put("block_id", e.blockId)
        put("package", e.pkg)
        put("app_label", e.label)
        put("kind", e.kind)
        e.domain?.let { put("domain", it) }
        e.title?.let { put("title", it) }
        put("started_at", Instant.ofEpochMilli(e.startMs).toString())
        put("minutes", (e.accMs / 60_000).toInt())
        endMs?.let { put("ended_at", Instant.ofEpochMilli(it).toString()) }
    }

    private fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // ------------------------------------------------------------------ 서버

    private fun http() = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun send(context: Context, path: String, body: JsonObject): JsonObject? {
        val p = Prefs.get(context)
        val req = Request.Builder().url(p.serverUrl.trimEnd('/') + "/" + path)
            .header("Authorization", "Bearer " + p.apiToken)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return http().newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()?.let { AppJson.parseToJsonElement(it).jsonObject }
        }
    }

    /** 보내고, 실패하면 모아 둔다(다시 연결되면 late 로 보내 단계는 올리지 않는다). */
    private fun post(context: Context, body: JsonObject) {
        val app = context.applicationContext
        net.execute {
            flush(app)
            if (runCatching { send(app, "v1/distraction/events", body) }.getOrNull() == null) {
                val p = Prefs.get(app)
                p.pendingDistractions = (p.pendingDistractions + body.toString()).takeLast(50)
            }
        }
    }

    private fun flush(context: Context) {
        val p = Prefs.get(context)
        val pending = p.pendingDistractions
        if (pending.isEmpty()) return
        val left = pending.filter { line ->
            val o = runCatching { AppJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@filter false
            val late = JsonObject(o + ("late" to JsonPrimitive(true)))
            runCatching { send(context, "v1/distraction/events", late) }.getOrNull() == null
        }
        p.pendingDistractions = left
    }

    private fun judge(context: Context, blockId: Long, cacheKey: String) {
        val domain = screenDomain
        val title = screenTitle
        val kind = if (screenKind == "web") "web" else "video"
        val app = context.applicationContext
        net.execute {
            val r = runCatching {
                send(app, "v1/distraction/judge", buildJsonObject {
                    put("block_id", blockId)
                    put("kind", kind)
                    domain?.let { put("domain", it) }
                    title?.let { put("title", it) }
                })
            }.getOrNull()
            val v = (r?.get("verdict") as? JsonPrimitive)?.content ?: "unsure"
            synchronized(this) {
                verdicts[cacheKey] = v
                judging.remove(cacheKey)
            }
        }
    }

    // ------------------------------------------------------------------ 화면 읽기(§5.3)

    private val YOUTUBE_TITLE_IDS = listOf(
        "com.google.android.youtube:id/title", "com.google.android.youtube:id/video_title",
    )

    /**
     * (kind, domain, title) 또는 null(못 읽음·시크릿). 검색창·입력칸은 읽지 않는다(입력 중인 주소창도 읽지 않는다).
     * 삼성 인터넷은 비밀 모드를 알아낼 방법이 없어 읽지 않는다(DECISIONS).
     */
    private fun readScreen(service: AccessibilityService, pkg: String): Triple<String, String?, String?>? {
        val root = service.rootInActiveWindow ?: return null
        try {
            when (pkg) {
                "com.google.android.youtube" -> {
                    for (id in YOUTUBE_TITLE_IDS) {
                        val node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { !it.isEditable && !it.text.isNullOrBlank() }
                        if (node != null) return Triple("video", null, node.text.toString().take(300))
                    }
                    return null
                }
                "com.android.chrome" -> {
                    if (root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/incognito_badge").isNotEmpty() ||
                        hasIncognitoMarker(root)
                    ) return null
                    val bar = root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar").firstOrNull() ?: return null
                    if (bar.isFocused) return null // 입력 중
                    val host = domainOf(bar.text?.toString() ?: return null) ?: return null
                    return Triple("web", host, null)
                }
                else -> return null
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun hasIncognitoMarker(root: AccessibilityNodeInfo): Boolean {
        val d = root.contentDescription?.toString()?.lowercase() ?: ""
        return "incognito" in d || "시크릿" in d
    }

    /** 경로·검색어는 버리고 도메인만. */
    fun domainOf(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || ' ' in t) return null
        val withScheme = if ("://" in t) t else "https://$t"
        return runCatching { java.net.URI(withScheme).host?.removePrefix("www.")?.lowercase() }.getOrNull()
    }

    // ------------------------------------------------------------------ 감시 표시(§7)

    /** 감시 중에는 "집중 확인 중 · {블록}" 을 조용히 띄워 둔다(몰래 보지 않는다). */
    private fun showNotice(context: Context, m: MonitorWindow?) {
        val nm = NotificationManagerCompat.from(context)
        if (m == null || rules(context).isEmpty()) {
            if (notified != null) {
                nm.cancel(NOTIFICATION_ID)
                notified = null
            }
            return
        }
        if (notified == m.block_id) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "집중 확인", NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("집중 확인 중 · " + m.title)
            .setContentText(if (m.record_only) "미룬 동안은 기록만 해요" else "딴짓 앱을 오래 보면 비서가 알려요")
            .setOngoing(true)
            .setSilent(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
        notified = m.block_id
    }

    /** 게임 분류(기본 추천용). */
    fun isGame(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_GAME
    }.getOrDefault(false)
}
