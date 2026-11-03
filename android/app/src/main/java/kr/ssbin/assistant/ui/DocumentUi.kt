package kr.ssbin.assistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.DocumentDto
import kr.ssbin.assistant.data.TravelStatusDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

// ------------------------------------------------------------------ v3.3 §4 자료(문서 받기)

/** 고를 수 있는 형식(서버가 다시 확인한다). */
internal val DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf", "image/jpeg", "image/png", "text/plain", "text/markdown",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
)

private const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024

private fun fileName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { return it }
        }
    }
    return uri.lastPathSegment ?: "file"
}

/** 고른 파일을 서버에 올린다. 20MB 를 넘으면 올리지 않고 예외. */
internal suspend fun uploadDocument(context: Context, uri: Uri, goalId: Long?, fromChat: Boolean, message: String? = null): JsonObject {
    val name = fileName(context, uri)
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > MAX_UPLOAD_BYTES) throw IllegalArgumentException("파일은 20MB 이하만 올릴 수 있어요")
            out.write(buf, 0, n)
        }
        out.toByteArray()
    } ?: throw IllegalArgumentException("파일을 열 수 없어요")
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    val part = MultipartBody.Part.createFormData("file", name, bytes.toRequestBody(mime.toMediaType()))
    val text = "text/plain".toMediaType()
    return ApiClient.get(context).uploadDocument(
        part,
        goalId?.toString()?.toRequestBody(text),
        if (fromChat) "true".toRequestBody(text) else null,
        message?.takeIf { it.isNotBlank() }?.toRequestBody(text),
    )
}

/** 원본을 받아 다른 앱(PDF 보기·사진)으로 연다. */
private suspend fun openDocument(context: Context, d: DocumentDto) {
    val dir = File(context.cacheDir, "docs").apply { mkdirs() }
    val file = File(dir, "${d.id}-" + d.filename.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
    withContext(Dispatchers.IO) {
        ApiClient.get(context).documentFile(d.id).byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
    context.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, d.mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun statusText(d: DocumentDto): String = when (d.status) {
    "processing" -> "읽는 중…"
    "ready" -> "완료"
    "failed" -> "실패"
    else -> d.status
}

/** 목표 상세의 '자료' 칸: 목록·처리 상태·요약 보기·원본 열기·삭제, [자료 올리기]. */
@Composable
fun DocumentsSection(goalId: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf<List<DocumentDto>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<DocumentDto?>(null) }

    LaunchedEffect(goalId, reload) {
        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).documents(goalId).documents } }
        r.onSuccess { docs = it }.onFailure { error = ApiClient.errorMessage(it) }
        // 읽는 중이면 잠시 뒤 다시 본다.
        if (r.getOrNull()?.any { it.status == "processing" } == true) {
            delay(4000)
            reload++
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { uploadDocument(context, uri, goalId, false) } }
            uploading = false
            r.onSuccess { reload++ }.onFailure { error = ApiClient.errorMessage(it) }
        }
    }

    Text("자료", fontWeight = FontWeight.Bold)
    val list = docs
    if (list == null) Text("불러오는 중…") else if (list.isEmpty()) {
        Text("공지·안내서·시험 범위를 올리면 비서가 읽고 계획에 써요", style = MaterialTheme.typography.bodySmall)
    }
    list?.forEach { d ->
        Row(Modifier.fillMaxWidth().clickable { open = d }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("📎 " + d.filename, modifier = Modifier.weight(1f), maxLines = 1)
            Text(statusText(d), color = if (d.status == "failed") Color(0xFFC92A2A) else Color(0xFF495057),
                style = MaterialTheme.typography.bodySmall)
        }
    }
    OutlinedButton(onClick = { picker.launch(DOCUMENT_MIME_TYPES) }, enabled = !uploading) {
        Text(if (uploading) "올리는 중…" else "자료 올리기")
    }
    error?.let { Text(it, color = Color(0xFFC92A2A)) }

    open?.let { d ->
        DocumentDialog(d, onDismiss = { open = null }, onDeleted = { open = null; reload++ })
    }
}

@Composable
fun DocumentDialog(d: DocumentDto, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf(d) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(d.id) {
        withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).document(d.id) } }.onSuccess { detail = it }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(detail.filename) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(statusText(detail) + (detail.pages?.let { " · ${it}쪽" } ?: ""), style = MaterialTheme.typography.bodySmall)
                if (detail.status == "failed") Text("읽지 못했어요: " + (detail.error ?: ""), color = Color(0xFFC92A2A))
                if (detail.summary_md.isNotBlank()) Text(detail.summary_md)
                val ex = detail.extracted as? JsonObject
                (ex?.get("deadlines") as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { arr ->
                    Text("자료에 있는 마감", fontWeight = FontWeight.Bold)
                    arr.forEach { item ->
                        val t = (item as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: item.toString()
                        Text("· $t")
                    }
                }
                (ex?.get("requirements") as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { arr ->
                    Text("요구사항", fontWeight = FontWeight.Bold)
                    arr.forEach { Text("· " + ((it as? JsonPrimitive)?.contentOrNull ?: it.toString())) }
                }
                (ex?.get("items") as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { arr ->
                    Text("준비물", fontWeight = FontWeight.Bold)
                    arr.forEach { Text("· " + ((it as? JsonPrimitive)?.contentOrNull ?: it.toString())) }
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching { openDocument(context, detail) }.onFailure { error = ApiClient.errorMessage(it) }
                    }
                }) { Text("원본 열기") }
                error?.let { Text(it, color = Color(0xFFC92A2A)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = { TextButton(onClick = { confirmDelete = true }) { Text("삭제", color = Color(0xFFC92A2A)) } },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("자료를 지울까요?") },
            text = { Text("파일과 요약이 모두 지워져요.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).deleteDocument(d.id) } }
                        confirmDelete = false
                        r.onSuccess { onDeleted() }.onFailure { error = ApiClient.errorMessage(it) }
                    }
                }) { Text("지우기", color = Color(0xFFC92A2A)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

/** 채팅 말풍선의 파일 카드(누르면 요약·원본). */
@Composable
fun DocumentCard(documentId: Long, mine: Boolean) {
    val context = LocalContext.current
    var doc by remember { mutableStateOf<DocumentDto?>(null) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(documentId) {
        doc = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).document(documentId) }.getOrNull() }
    }
    val d = doc ?: return
    Text(
        "📎 ${d.filename} · ${statusText(d)}",
        color = if (mine) Color.White else Color(0xFF212529),
        modifier = Modifier.padding(top = 6.dp)
            .background(if (mine) Color.White.copy(alpha = 0.2f) else Color(0xFFF1F3F5), RoundedCornerShape(8.dp))
            .clickable { open = true }.padding(horizontal = 8.dp, vertical = 6.dp),
    )
    if (open) DocumentDialog(d, onDismiss = { open = false }, onDeleted = { open = false; doc = null })
}

// ------------------------------------------------------------------ v3.3 §3.6 집 주소·주 이동수단

private val TRAVEL_MODES = listOf("transit" to "대중교통", "car" to "자동차", "walk" to "도보")

@Composable
fun TravelSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<TravelStatusDto?>(null) }
    var address by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).travel() } }
            .onSuccess { status = it; address = it.home_address }
            .onFailure { message = ApiClient.errorMessage(it) }
    }

    fun put(body: JsonObject) {
        saving = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).putTravel(body) } }
            saving = false
            r.onSuccess {
                status = it
                message = when (it.found) {
                    true -> "찾은 주소: " + (it.found_address ?: "")
                    false -> "주소를 찾지 못했어요(어림값으로 계산해요)"
                    null -> "저장했습니다"
                }
            }.onFailure { message = ApiClient.errorMessage(it) }
        }
    }

    Text("이동 시간", fontWeight = FontWeight.Bold)
    OutlinedTextField(address, { address = it }, label = { Text("집 주소") }, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("예: 서울 강남구 테헤란로 152") })
    Button(onClick = { put(JsonObject(mapOf("home_address" to JsonPrimitive(address.trim())))) }, enabled = !saving) {
        Text("집 주소 저장")
    }
    Text("주 이동수단", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TRAVEL_MODES.forEach { (key, label) ->
            val selected = status?.mode == key
            Text(
                label,
                color = if (selected) Color.White else CAL_PINK,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.background(if (selected) CAL_PINK else Color.Transparent, RoundedCornerShape(50))
                    .clickable(enabled = !saving && !selected) { put(JsonObject(mapOf("mode" to JsonPrimitive(key)))) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
    status?.let {
        Text("지도 연결: " + it.status_text + (if (it.home_found) " · 집 위치 확인됨" else ""),
            style = MaterialTheme.typography.bodySmall)
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** 고정 일정 상세의 오는 길·가는 길 한 줄. */
internal fun travelLine(direction: String, legs: List<kr.ssbin.assistant.data.TravelLegDto>): String? {
    val l = legs.firstOrNull { it.direction == direction } ?: return null
    val mode = TRAVEL_MODES.firstOrNull { it.first == l.mode }?.second ?: l.mode
    return (if (direction == "arrive") "오는 길: " else "가는 길: ") + "${l.from}→${l.to} $mode 약 ${l.minutes}분" +
        if (l.estimate) " (어림)" else ""
}

/** 방해 허용 정도 표시(🔕 방해 금지 / 표시 없음 조용히 / 🔔 알림 허용). */
internal fun interruptIcon(level: String?): String = when (level) {
    "dnd" -> "🔕"
    "allow" -> "🔔"
    else -> ""
}

internal fun interruptText(level: String?): String = when (level) {
    "dnd" -> "방해 금지"
    "allow" -> "알림 허용"
    "quiet" -> "조용히"
    else -> "비서가 정함"
}
