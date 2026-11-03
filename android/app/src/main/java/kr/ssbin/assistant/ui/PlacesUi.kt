package kr.ssbin.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.PlaceCandidateDto
import kr.ssbin.assistant.data.PlaceItemDto
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ------------------------------------------------------------------ 장소 탭 (2026-11-04 사용자 요청)

private val PLACE_PINK = Color(0xFFD6336C)
private val PLACE_ERR = Color(0xFFC92A2A)

private fun whenText(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d(E) HH:mm"))
}.getOrDefault(iso)

private fun legSource(source: String): String = when (source) {
    "kakao" -> "카카오"
    "user" -> "직접 정함"
    else -> "어림"
}

/** 앞으로 잡힌 고정 일정의 장소를 지도에서 넣고, 바로 앞 장소에서 오는 길을 보고 고친다. */
@Composable
fun PlacesScreen(tick: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var places by remember { mutableStateOf<List<PlaceItemDto>?>(null) }
    var homeFound by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<PlaceItemDto?>(null) }
    var picked by remember { mutableStateOf<Triple<Double, Double, String>?>(null) }
    var askSeries by remember { mutableStateOf(false) }
    var editMinutes by remember { mutableStateOf<PlaceItemDto?>(null) }

    LaunchedEffect(tick, reload) {
        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).places() } }
        r.onSuccess { places = it.places; homeFound = it.home_found; error = null }.onFailure { error = ApiClient.errorMessage(it) }
    }

    fun save(item: PlaceItemDto, lat: Double, lng: Double, name: String, series: Boolean) {
        scope.launch {
            val body = JsonObject(mapOf("lat" to JsonPrimitive(lat), "lng" to JsonPrimitive(lng),
                "name" to JsonPrimitive(name), "series" to JsonPrimitive(series)))
            val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).setPlace(item.id, body) } }
            r.onSuccess { reload++ }.onFailure { error = ApiClient.errorMessage(it) }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val item = picking
        val data = res.data
        if (res.resultCode != Activity.RESULT_OK || item == null || data == null) return@rememberLauncherForActivityResult
        val lat = data.getDoubleExtra(MapPickActivity.LAT, 0.0)
        val lng = data.getDoubleExtra(MapPickActivity.LNG, 0.0)
        val name = data.getStringExtra(MapPickActivity.NAME) ?: ""
        if (item.series_id != null) {
            picked = Triple(lat, lng, name)
            askSeries = true
        } else {
            save(item, lat, lng, name, false)
        }
    }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("가야 할 곳의 위치를 넣으면 비서가 이동 시간을 보고 출발 시간을 잡아요.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            if (!homeFound) Text("설정에 집 주소를 넣으면 첫 일정의 '집에서 오는 길'도 계산해요.",
                style = MaterialTheme.typography.bodySmall, color = PLACE_PINK)
            error?.let { Text(it, color = PLACE_ERR) }
        }
        val list = places
        if (list == null) item { Text("불러오는 중…") }
        else if (list.isEmpty()) item { Text("앞으로 30일 안에 잡힌 고정 일정이 없어요") }
        items(list ?: emptyList(), key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text((if (p.series_id != null) "🔁 " else "") + p.title, fontWeight = FontWeight.Bold)
                    Text(whenText(p.start), style = MaterialTheme.typography.bodySmall)
                    val hasPlace = p.lat != null && p.lng != null
                    Text(
                        if (hasPlace) "📍 " + (p.place_name ?: p.location ?: "") + if (p.place_source == "user") " (직접 고름)" else ""
                        else "📍 위치 없음" + (p.location?.let { " · 적어 둔 장소: $it" } ?: ""),
                        color = if (hasPlace) Color.Unspecified else PLACE_ERR,
                    )
                    p.arrive?.let { a ->
                        val km = if (a.distance_m > 0) " · %.1fkm".format(a.distance_m / 1000.0) else ""
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${a.from}에서 약 ${a.minutes}분$km (${legSource(a.source)})",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { editMinutes = p }) { Text("수정") }
                        }
                    }
                    OutlinedButton(onClick = {
                        picking = p
                        launcher.launch(MapPickActivity.intent(context, p.title, p.lat, p.lng, p.location ?: p.title))
                    }) { Text(if (hasPlace) "위치 바꾸기" else "위치 넣기") }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (askSeries) {
        val item = picking
        val pk = picked
        AlertDialog(
            onDismissRequest = { askSeries = false },
            title = { Text("반복 일정이에요") },
            text = { Text("이 위치를 어디에 넣을까요?") },
            confirmButton = {
                TextButton(onClick = { askSeries = false; if (item != null && pk != null) save(item, pk.first, pk.second, pk.third, true) }) {
                    Text("반복 전체")
                }
            },
            dismissButton = {
                TextButton(onClick = { askSeries = false; if (item != null && pk != null) save(item, pk.first, pk.second, pk.third, false) }) {
                    Text("이 날만")
                }
            },
        )
    }

    editMinutes?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.arrive?.minutes?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { editMinutes = null },
            title = { Text("오는 길 시간") },
            text = {
                Column {
                    Text("${p.arrive?.from ?: "이전 장소"} → ${p.title}")
                    OutlinedTextField(text, { text = it.filter(Char::isDigit).take(4) }, label = { Text("분") })
                    Text("직접 고친 값은 자동 계산이 덮어쓰지 않아요. 위치를 다시 고르면 새로 계산해요.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = text.toIntOrNull()
                    editMinutes = null
                    if (minutes != null) scope.launch {
                        val body = JsonObject(mapOf("direction" to JsonPrimitive("arrive"), "minutes" to JsonPrimitive(minutes)))
                        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).setTravel(p.id, body) } }
                        r.onSuccess { reload++ }.onFailure { error = ApiClient.errorMessage(it) }
                    }
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { editMinutes = null }) { Text("취소") } },
        )
    }
}

// ------------------------------------------------------------------ 지도에서 위치 고르기

/**
 * 카카오 지도(SDK v2). 가운데 핀 아래 위치를 고른다. 위쪽 검색은 서버의 카카오 장소 검색을 쓴다.
 * 지도 키(네이티브 앱 키)는 서버에서 받는다(관리 화면 '지도 연결'). 지도가 실패해도 검색 결과로 고를 수 있다.
 */
class MapPickActivity : ComponentActivity() {

    companion object {
        const val LAT = "lat"
        const val LNG = "lng"
        const val NAME = "name"
        private const val TITLE = "title"
        private const val QUERY = "query"
        private const val SEOUL_LAT = 37.5665
        private const val SEOUL_LNG = 126.9780

        fun intent(context: Context, title: String, lat: Double?, lng: Double?, query: String): Intent =
            Intent(context, MapPickActivity::class.java).putExtra(TITLE, title).putExtra(QUERY, query).apply {
                if (lat != null && lng != null) {
                    putExtra(LAT, lat)
                    putExtra(LNG, lng)
                }
            }
    }

    private var mapView: MapView? = null
    private var mapReady = mutableStateOf<Boolean?>(null) // null 준비 중, false 실패
    private var mapError = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(TITLE) ?: "위치"
        val startLat = if (intent.hasExtra(LAT)) intent.getDoubleExtra(LAT, SEOUL_LAT) else SEOUL_LAT
        val startLng = if (intent.hasExtra(LNG)) intent.getDoubleExtra(LNG, SEOUL_LNG) else SEOUL_LNG
        val query = intent.getStringExtra(QUERY) ?: ""
        lifecycleScope.launch {
            val key = withContext(Dispatchers.IO) {
                runCatching { ApiClient.get(this@MapPickActivity).mapKey()["key"]?.jsonPrimitive?.content }.getOrNull()
            }
            val ok = !key.isNullOrBlank() && runCatching {
                if (!KakaoMapSdk.isInitialized()) KakaoMapSdk.init(applicationContext, key)
                true
            }.getOrElse { e -> mapError.value = "지도를 준비하지 못했어요: ${e.javaClass.simpleName}"; false }
            if (key.isNullOrBlank()) mapError.value = "지도 키가 없어요(관리 화면 '지도 연결'에 네이티브 앱 키를 넣어 주세요). 검색으로는 고를 수 있어요."
            mapReady.value = ok
        }
        setContent {
            Surface(Modifier.fillMaxSize()) {
                PickScreen(title, query, startLat, startLng)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching { mapView?.resume() }
    }

    override fun onPause() {
        runCatching { mapView?.pause() }
        super.onPause()
    }

    private fun finishWith(lat: Double, lng: Double, name: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(LAT, lat).putExtra(LNG, lng).putExtra(NAME, name))
        finish()
    }

    @Composable
    private fun PickScreen(title: String, initialQuery: String, startLat: Double, startLng: Double) {
        val scope = rememberCoroutineScope()
        var q by remember { mutableStateOf(initialQuery) }
        var results by remember { mutableStateOf<List<PlaceCandidateDto>>(emptyList()) }
        var searching by remember { mutableStateOf(false) }
        var center by remember { mutableStateOf(startLat to startLng) }
        var chosenName by remember { mutableStateOf("") }
        var kakaoMap by remember { mutableStateOf<KakaoMap?>(null) }
        var keepName by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        val ready by mapReady
        val error by mapError

        fun search() {
            if (q.isBlank()) return
            searching = true
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching { ApiClient.get(this@MapPickActivity).searchPlaces(q.trim(), center.first, center.second).places }
                }
                searching = false
                r.onSuccess { results = it; message = if (it.isEmpty()) "찾지 못했어요. 다른 이름으로 찾거나 지도에서 직접 고르세요." else null }
                    .onFailure { message = ApiClient.errorMessage(it) }
            }
        }

        fun choose(c: PlaceCandidateDto) {
            center = c.lat to c.lng
            chosenName = c.name
            results = emptyList()
            val map = kakaoMap
            if (map != null) {
                keepName = true
                runCatching { map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(c.lat, c.lng), 17)) }
            }
        }

        Column(Modifier.fillMaxSize()) {
            Text("$title 위치", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it }, label = { Text("장소 이름으로 찾기") }, modifier = Modifier.weight(1f), singleLine = true)
                Spacer(Modifier.padding(2.dp))
                Button(onClick = { search() }, enabled = !searching) { Text(if (searching) "…" else "찾기") }
            }
            message?.let { Text(it, color = PLACE_ERR, modifier = Modifier.padding(horizontal = 12.dp)) }
            results.forEach { c ->
                Column(Modifier.fillMaxWidth().clickable { choose(c) }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(c.name, fontWeight = FontWeight.Bold)
                    if (c.address.isNotBlank()) Text(c.address, style = MaterialTheme.typography.bodySmall)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (ready) {
                    true -> {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                MapView(ctx).also { view ->
                                    mapView = view
                                    runCatching {
                                        view.start(object : MapLifeCycleCallback() {
                                            override fun onMapDestroy() {}
                                            override fun onMapError(e: Exception) {
                                                mapError.value = "지도를 불러오지 못했어요(${e.message ?: e.javaClass.simpleName}). " +
                                                    "카카오 콘솔의 Android 키 해시·카카오맵 사용 설정을 확인해 주세요. 검색으로는 고를 수 있어요."
                                            }
                                        }, object : KakaoMapReadyCallback() {
                                            override fun onMapReady(map: KakaoMap) {
                                                kakaoMap = map
                                                map.setOnCameraMoveEndListener { _, pos, _ ->
                                                    center = pos.position.latitude to pos.position.longitude
                                                    if (keepName) keepName = false else chosenName = ""
                                                }
                                            }

                                            override fun getPosition(): LatLng = LatLng.from(startLat, startLng)
                                            override fun getZoomLevel(): Int = 16
                                        })
                                    }.onFailure { e -> mapError.value = "지도를 시작하지 못했어요: ${e.javaClass.simpleName}" }
                                }
                            },
                        )
                        // 가운데 핀: 이 아래 위치가 저장된다.
                        Text("📍", fontSize = 34.sp, modifier = Modifier.align(Alignment.Center).padding(bottom = 34.dp))
                    }
                    false -> Box(Modifier.fillMaxSize().background(Color(0xFFF1F3F5)))
                    null -> Text("지도 준비 중…", modifier = Modifier.align(Alignment.Center))
                }
                error?.let {
                    Text(it, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                        .background(PLACE_ERR.copy(alpha = 0.9f), RoundedCornerShape(8.dp)).padding(8.dp))
                }
            }
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (chosenName.isNotBlank()) "고른 곳: $chosenName" else "지도를 움직여 핀을 맞추거나 위에서 찾아 고르세요",
                    style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { finishWith(center.first, center.second, chosenName) },
                        enabled = ready == true || chosenName.isNotBlank()) { Text("이 위치로") }
                    OutlinedButton(onClick = { finish() }) { Text("취소") }
                }
            }
        }
    }
}
