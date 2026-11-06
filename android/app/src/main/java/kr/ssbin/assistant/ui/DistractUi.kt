package kr.ssbin.assistant.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.DistractionDto
import kr.ssbin.assistant.lock.DistractionTracker
import kr.ssbin.assistant.lock.Lock
import kr.ssbin.assistant.work.Sync

private val WATCH_CATEGORIES = listOf(
    "study" to "공부", "work" to "일", "class" to "수업", "exercise" to "운동", "chores" to "집안일",
    "selfcare" to "자기관리", "rest" to "휴식", "social" to "약속", "other" to "기타",
)
private val KIND_LABEL = listOf("distract" to "딴짓", "ambiguous" to "애매", "neutral" to "기본")
private val AMBIGUOUS_DEFAULT = setOf("com.google.android.youtube", "com.android.chrome")

private data class InstalledApp(val pkg: String, val label: String, val locked: Boolean)

private fun installedApps(context: android.content.Context): List<InstalledApp> {
    val pm = context.packageManager
    val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launch, 0).map { it.activityInfo.packageName }.distinct()
        .filter { it != context.packageName }
        .map { InstalledApp(it, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrDefault(it), Lock.alwaysAllowed(context, it)) }
        .sortedBy { it.label }
}

/** 설정 → 딴짓 앱(빌드문서 v4 §7). 설치된 앱마다 딴짓/애매/기본. 처음에는 추천대로 표시하고, 저장해야 켜진다. */
@Composable
fun DistractAppsSection() {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("딴짓 앱", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("공부·일 블록 중 딴짓 앱을 오래 보면 비서가 알려요. L4 에서는 이 앱들이 잠겨요. 유튜브·크롬(애매)은 영상 제목·사이트를 보고 판단해요.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        OutlinedButton(onClick = { open = true }) { Text("딴짓 앱·감시할 분류 고르기") }
    }
    if (open) DistractAppsDialog(onDismiss = { open = false })
}

@Composable
private fun DistractAppsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    val kinds = remember { mutableStateMapOf<String, String>() }
    val cats = remember { mutableStateMapOf<String, Boolean>() }
    var suggested by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { installedApps(context) }
        apps = list
        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).appRules() } }
        r.onSuccess { body ->
            val rules = body["rules"]?.jsonArray.orEmpty().map { it.jsonObject }
            if (rules.isEmpty()) {
                suggested = true // 추천: 체크된 상태로 제안, 저장해야 켜진다
                list.forEach { a ->
                    kinds[a.pkg] = when {
                        a.locked -> "neutral"
                        a.pkg in Lock.DEFAULT_DISTRACT || DistractionTracker.isGame(context, a.pkg) -> "distract"
                        a.pkg in AMBIGUOUS_DEFAULT -> "ambiguous"
                        else -> "neutral"
                    }
                }
            } else {
                rules.forEach { kinds[it["package"]!!.jsonPrimitive.content] = it["kind"]!!.jsonPrimitive.content }
            }
            body["watch_categories"]?.jsonArray?.forEach { cats[it.jsonPrimitive.content] = true }
        }.onFailure { message = ApiClient.errorMessage(it) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("딴짓 앱", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (suggested) Text("추천대로 표시했어요. [저장]을 눌러야 켜져요.", color = Color(0xFFD6336C))
                Text("감시할 분류(이 분류의 계획 블록만 봐요. 블록마다 따로 켜고 끌 수 있어요)", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WATCH_CATEGORIES.forEach { (k, label) ->
                        FilterChip(selected = cats[k] == true, onClick = { cats[k] = cats[k] != true }, label = { Text(label) })
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(apps, key = { it.pkg }) { a ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(a.label + if (a.locked) " · 항상 허용" else "", color = if (a.locked) Color.Gray else Color.Unspecified)
                            if (!a.locked) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                KIND_LABEL.forEach { (k, label) ->
                                    FilterChip(selected = (kinds[a.pkg] ?: "neutral") == k, onClick = { kinds[a.pkg] = k }, label = { Text(label) })
                                }
                            }
                        }
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val labels = apps.associate { it.pkg to it.label }
                        val body = buildJsonObject {
                            put("rules", JsonArray(kinds.filter { it.value != "neutral" }.map { (pkg, kind) ->
                                buildJsonObject {
                                    put("package", JsonPrimitive(pkg))
                                    put("label", JsonPrimitive(labels[pkg] ?: pkg))
                                    put("kind", JsonPrimitive(kind))
                                    put("set_by", JsonPrimitive(if (suggested) "default" else "user"))
                                }
                            }))
                            put("watch_categories", JsonArray(cats.filter { it.value }.keys.map { JsonPrimitive(it) }))
                        }
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { ApiClient.get(context).putAppRules(body).also { Sync.runOnce(context.applicationContext) } }
                            }
                            r.onSuccess { onDismiss() }.onFailure { message = ApiClient.errorMessage(it) }
                        }
                    }) { Text("저장") }
                    TextButton(onClick = onDismiss) { Text("닫기") }
                }
            }
        }
    }
}

/**
 * 블록 상세 → 감시(빌드문서 v4 §7): [분류 따름/켜기/끄기], [허용 앱]. 반복 일정이면 앞으로의 회차 전체에 적용할 수 있다.
 * 사용자만 바꾼다(비서는 [블록 설정 열기] 버튼만 붙인다).
 */
@Composable
fun BlockWatchDialog(blockId: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<JsonObject?>(null) }
    var watch by remember { mutableStateOf<String?>(null) }
    val allowed = remember { mutableStateMapOf<String, Boolean>() }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var series by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(blockId) {
        apps = withContext(Dispatchers.IO) { installedApps(context) }.filter { !it.locked }
        withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).blockWatch(blockId) } }
            .onSuccess { r ->
                info = r
                watch = r["watch"]?.jsonPrimitive?.contentOrNull
                r["allowed_apps"]?.jsonArray?.forEach { allowed[it.jsonPrimitive.content] = true }
            }.onFailure { message = ApiClient.errorMessage(it) }
    }
    val seriesId = info?.get("series_id")?.jsonPrimitive?.longOrNull

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("집중 확인 · " + (info?.get("title")?.jsonPrimitive?.contentOrNull ?: "")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(null to "분류 따름", "on" to "켜기", "off" to "끄기").forEach { (k, label) ->
                        FilterChip(selected = watch == k, onClick = { watch = k }, label = { Text(label) })
                    }
                }
                Text("고정 일정은 '켜기'일 때만 봐요.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text("이 블록에서 작업으로 인정할 앱", fontWeight = FontWeight.SemiBold)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(apps, key = { it.pkg }) { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = allowed[a.pkg] == true, onCheckedChange = { allowed[a.pkg] = it })
                            Text(a.label)
                        }
                    }
                }
                if (seriesId != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = series, onCheckedChange = { series = it })
                    Text("반복 일정 전체(앞으로의 회차)에 적용")
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val body = buildJsonObject {
                    put("watch", watch?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("allowed_apps", JsonArray(allowed.filter { it.value }.keys.map { JsonPrimitive(it) }))
                }
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            if (series && seriesId != null) ApiClient.get(context).patchSeriesWatch(seriesId, body)
                            else ApiClient.get(context).patchBlockWatch(blockId, body)
                            Sync.runOnce(context.applicationContext)
                        }
                    }
                    r.onSuccess { onDismiss() }.onFailure { message = ApiClient.errorMessage(it) }
                }
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 지난 기록(v3.5 화면): 블록의 딴짓 시간·앱, 7일 안이면 제목과 [이건 작업이었어]. */
@Composable
fun DistractionRows(list: List<DistractionDto>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fixed = remember { mutableStateMapOf<Long, Boolean>() }
    list.forEach { d ->
        val related = d.judged == "related" || fixed[d.id] == true
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (d.record_only) "미룬 동안 " else "딴짓 ") + (d.app ?: "") + " " + d.minutes + "분"
                + (d.title ?: d.domain)?.let { " · $it" }.orEmpty() + if (related) " (작업으로 고침)" else "",
                style = MaterialTheme.typography.bodySmall, color = if (related) Color.Gray else Color(0xFFC92A2A),
                modifier = Modifier.weight(1f))
            if (!related) TextButton(onClick = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { ApiClient.get(context).markDistractionRelated(d.id, JsonObject(emptyMap())) }
                    }
                    if (r.isSuccess) fixed[d.id] = true
                }
            }) { Text("이건 작업이었어") }
        }
    }
}
