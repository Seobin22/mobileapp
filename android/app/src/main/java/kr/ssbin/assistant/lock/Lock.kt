package kr.ssbin.assistant.lock

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.ssbin.assistant.data.AppDatabase
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.intervention.InterventionActivity
import kr.ssbin.assistant.intervention.InterventionController
import kr.ssbin.assistant.intervention.WindowGuard
import kr.ssbin.assistant.ui.WorkProductActivity
import kr.ssbin.assistant.work.ActionQueue
import java.time.Instant
import java.time.OffsetDateTime

/**
 * L4 딴짓 앱 잠금 상태(빌드문서 v4 §3.2). Prefs 에 둔다(문서의 DataStore 와 같은 역할).
 * L4 를 받으면 켜고, intervention.end·[시작]·비상 해제·로컬 방해 금지·다른 고정 일정 시작이면 끈다.
 */
object Lock {

    /**
     * 딴짓 앱 기본 목록(§4 의 기본 추천). §4 사용자 목록이 생기기 전까지는 이 목록과 설치된 게임만 잠근다.
     * 유튜브·브라우저는 '애매'라 잠그지 않는다.
     */
    val DEFAULT_DISTRACT = setOf(
        "com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.twitter.android",
        "com.facebook.katana", "com.instagram.barcelona", "com.netflix.mediaclient", "com.nhn.android.webtoon",
        "com.naver.linewebtoon", "com.kakao.page", "com.kakaopage.app",
    )

    /** 항상 허용: 지도 앱(기본 런처·전화·문자·설정·시스템 UI 는 기기에서 찾는다). */
    private val MAPS = setOf("com.google.android.apps.maps", "net.daum.android.map", "com.nhn.android.nmap", "com.skt.tmap.ku")

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun on(context: Context, interventionId: String, blockId: Long, title: String, line: String) {
        init(context)
        val p = Prefs.get(context)
        p.lockInterventionId = interventionId
        p.lockBlockId = blockId
        p.lockTitle = title
        p.lockLine = line
        p.lockActive = true
    }

    fun off(context: Context) {
        Prefs.get(context).lockActive = false
    }

    /** 로컬에서 스스로 풀어야 하나: 방해 금지 시간대이거나 다른 고정 일정이 시작됨(§3.2). */
    fun shouldRelease(context: Context): Boolean {
        if (WindowGuard.dnd(context) != null) return true
        val p = Prefs.get(context)
        val plan = AppDatabase.dao(context).plan() ?: return false
        val now = Instant.now()
        return InterventionController.upcoming(plan).any {
            it.kind == "fixed" && it.block_id != p.lockBlockId &&
                !now.isBefore(OffsetDateTime.parse(it.alarm_at ?: it.start).toInstant()) &&
                now.isBefore(OffsetDateTime.parse(it.end).toInstant())
        }
    }

    fun alwaysAllowed(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName || pkg in MAPS || pkg == "com.android.systemui" || pkg == "android") return true
        if (pkg.startsWith("com.android.settings") || pkg == "com.samsung.android.settings") return true
        val pm = context.packageManager
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName
        if (pkg == home) return true
        val dialer = runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
        if (pkg == dialer) return true
        if (pkg == runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()) return true
        return false
    }

    /** 잠금 대상: 딴짓 앱 − 항상 허용. */
    fun isTarget(context: Context, pkg: String): Boolean {
        if (alwaysAllowed(context, pkg)) return false
        val allowed = runCatching {
            kr.ssbin.assistant.data.AppJson.decodeFromString<List<MonitorWindow>>(Prefs.get(context).monitorWindows)
                .firstOrNull { it.block_id == Prefs.get(context).lockBlockId }?.allowed_apps
        }.getOrNull().orEmpty()
        if (pkg in allowed) return false // 그 블록의 작업 앱
        val rules = runCatching {
            kr.ssbin.assistant.data.AppJson.decodeFromString<List<AppRule>>(Prefs.get(context).appRules)
        }.getOrDefault(emptyList())
        if (rules.isNotEmpty()) return rules.any { it.`package` == pkg && it.kind == "distract" } // §4 사용자 목록
        if (pkg in DEFAULT_DISTRACT) return true
        val info = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return false
        return info.category == ApplicationInfo.CATEGORY_GAME
    }

    /** 권한 점검: 이 앱의 접근성 서비스가 켜져 있나. */
    fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(context, LockAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

/** 지금 앞에 온 앱을 보고 잠금 대상이면 BlockActivity 를 띄운다(§3). 화면 내용은 읽지 않는다(제목 읽기는 §5). */
class LockAccessibilityService : AccessibilityService() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            runCatching { DistractionTracker.tick(this@LockAccessibilityService) }
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onServiceConnected() {
        Lock.init(this)
        handler.postDelayed(ticker, 15_000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // v4 §5.3: 애매한 앱(유튜브·크롬)일 때만 정해진 칸을 읽는다
            runCatching { DistractionTracker.onContent(this, pkg) }
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (pkg != "com.android.systemui") runCatching { DistractionTracker.onForeground(this, pkg) }
        if (!Prefs.get(this).lockActive) return
        if (Lock.shouldRelease(this)) {
            Lock.off(this)
            return
        }
        if (Lock.isTarget(this, pkg)) {
            startActivity(Intent(this, BlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }

    override fun onInterrupt() {}
}

/** 잠긴 앱을 열면 뜨는 화면: 블록 제목, 비서 한 줄, [시작] [미루기 협상] [비상 해제]. */
class BlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs.get(this)
        if (!p.lockActive) {
            finish()
            return
        }
        val interventionId = p.lockInterventionId
        val blockId = p.lockBlockId
        setContent {
            Column(
                Modifier.fillMaxSize().background(Color(0xFF221C1F)).padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("지금은 ${p.lockTitle} 시간이에요", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                if (p.lockLine.isNotBlank()) {
                    Text(p.lockLine, color = Color(0xFFE9DDE2), fontSize = 17.sp, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(40.dp))
                Button(
                    onClick = { start(interventionId, blockId) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD6336C)),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) { Text("시작", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {
                    startActivity(Intent(this@BlockActivity, InterventionActivity::class.java)
                        .putExtra(InterventionActivity.EXTRA_ID, interventionId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }, modifier = Modifier.fillMaxWidth()) { Text("미루기 협상", color = Color.White) }
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = { emergency(interventionId, blockId) }) {
                    Text("비상 해제", color = Color(0xFFFF8787))
                }
            }
        }
    }

    private fun start(interventionId: String, blockId: Long) {
        val app = applicationContext
        Lock.off(app)
        Thread {
            val entity = AppDatabase.dao(app).intervention(interventionId)
            if (entity != null) {
                InterventionController.onUserStart(app, entity)
                entity.workProductId?.let { app.startActivity(WorkProductActivity.intent(app, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } else {
                ActionQueue.enqueue(app, "start", interventionId, blockId)
            }
        }.start()
        finish()
    }

    private fun emergency(interventionId: String, blockId: Long) {
        val app = applicationContext
        InterventionController.onEmergency(app, interventionId, blockId)
        finish()
    }
}
