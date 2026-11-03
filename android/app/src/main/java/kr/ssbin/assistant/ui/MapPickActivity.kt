package kr.ssbin.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.PlaceCandidateDto
import java.security.MessageDigest

/**
 * 지도에서 위치 고르기(카카오 지도 SDK v2, 2026-11-04 사용자 요청). 열면 지도부터 전체 화면으로 뜬다.
 * - 가운데 📍 아래가 고를 위치. 지도를 끌거나 원하는 곳을 누르면 그곳이 가운데로 온다.
 * - 위쪽 검색: 결과를 지도에 번호 핀으로 모두 찍고(목록도 함께), 핀이나 목록을 누르면 그곳으로 간다.
 * - 지도 키(네이티브 앱 키)는 서버에서 받는다. 인증 오류면 이 휴대폰의 키 해시와 키 앞자리를 보여 준다(콘솔 등록값과 비교용).
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
        private val PINK = Color(0xFFD6336C)
        private val ERR = Color(0xFFC92A2A)

        fun intent(context: Context, title: String, lat: Double?, lng: Double?, query: String): Intent =
            Intent(context, MapPickActivity::class.java).putExtra(TITLE, title).putExtra(QUERY, query).apply {
                if (lat != null && lng != null) {
                    putExtra(LAT, lat)
                    putExtra(LNG, lng)
                }
            }
    }

    private var mapView: MapView? = null
    private val mapReady = mutableStateOf<Boolean?>(null) // null 준비 중, false 지도 없이(검색만)
    private val mapError = mutableStateOf<String?>(null)
    private var keyPrefix = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val title = intent.getStringExtra(TITLE) ?: "위치"
        val hasStart = intent.hasExtra(LAT)
        val startLat = if (hasStart) intent.getDoubleExtra(LAT, SEOUL_LAT) else SEOUL_LAT
        val startLng = if (hasStart) intent.getDoubleExtra(LNG, SEOUL_LNG) else SEOUL_LNG
        val query = intent.getStringExtra(QUERY) ?: ""
        lifecycleScope.launch {
            val key = withContext(Dispatchers.IO) {
                runCatching { ApiClient.get(this@MapPickActivity).mapKey()["key"]?.jsonPrimitive?.content }.getOrNull()
            }
            if (key.isNullOrBlank()) {
                mapError.value = "지도 키가 없어요(관리 화면 '지도 연결'에 네이티브 앱 키를 넣어 주세요). 검색으로는 고를 수 있어요."
                mapReady.value = false
                return@launch
            }
            keyPrefix = key.take(4)
            mapReady.value = runCatching {
                if (!KakaoMapSdk.isInitialized()) KakaoMapSdk.init(applicationContext, key)
                true
            }.getOrElse { e -> mapError.value = "지도를 준비하지 못했어요: ${e.javaClass.simpleName}"; false }
        }
        setContent {
            Surface(Modifier.fillMaxSize()) { PickScreen(title, query, startLat, startLng, !hasStart) }
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

    /** 이 앱의 서명 키 해시(SHA-1 base64). 카카오 콘솔에 등록한 값과 같아야 지도가 뜬다. */
    private fun keyHash(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val sig = info.signingInfo!!.apkContentsSigners[0].toByteArray()
        Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest(sig), Base64.NO_WRAP)
    }.getOrDefault("?")

    private fun finishWith(lat: Double, lng: Double, name: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(LAT, lat).putExtra(LNG, lng).putExtra(NAME, name))
        finish()
    }

    /** 번호가 적힌 분홍 원 핀(선택된 핀은 진하게). */
    private fun pinBitmap(number: Int, selected: Boolean): Bitmap {
        val size = (if (selected) 40 else 32) * resources.displayMetrics.density
        val bmp = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (selected) 0xFFA61E4D.toInt() else 0xFFD6336C.toInt()
        c.drawCircle(size / 2, size / 2, size / 2 - 2, p)
        p.color = 0xFFFFFFFF.toInt()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        c.drawCircle(size / 2, size / 2, size / 2 - 2, p)
        p.style = Paint.Style.FILL
        p.textSize = size * 0.5f
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        c.drawText(number.toString(), size / 2, size / 2 - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    @Composable
    private fun PickScreen(title: String, initialQuery: String, startLat: Double, startLng: Double, searchFirst: Boolean) {
        val scope = rememberCoroutineScope()
        var q by remember { mutableStateOf(initialQuery) }
        var results by remember { mutableStateOf<List<PlaceCandidateDto>>(emptyList()) }
        var showList by remember { mutableStateOf(true) }
        var searching by remember { mutableStateOf(false) }
        var center by remember { mutableStateOf(startLat to startLng) }
        var chosenName by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf(-1) }
        var kakaoMap by remember { mutableStateOf<KakaoMap?>(null) }
        var keepName by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        val ready by mapReady
        val error by mapError

        fun drawPins(list: List<PlaceCandidateDto>, sel: Int) {
            val map = kakaoMap ?: return
            runCatching {
                val layer = map.labelManager?.layer ?: return
                layer.removeAll()
                list.forEachIndexed { i, c ->
                    layer.addLabel(
                        LabelOptions.from(LatLng.from(c.lat, c.lng))
                            .setStyles(LabelStyles.from(LabelStyle.from(pinBitmap(i + 1, i == sel)).setAnchorPoint(0.5f, 0.5f)))
                            .setClickable(true).setTag(i)
                    )
                }
            }
        }

        fun choose(i: Int) {
            val c = results.getOrNull(i) ?: return
            selected = i
            center = c.lat to c.lng
            chosenName = c.name
            showList = false
            drawPins(results, i)
            kakaoMap?.let { map ->
                keepName = true
                runCatching { map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(c.lat, c.lng), 17)) }
            }
        }

        fun search() {
            if (q.isBlank()) return
            searching = true
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching { ApiClient.get(this@MapPickActivity).searchPlaces(q.trim(), center.first, center.second).places }
                }
                searching = false
                r.onSuccess { list ->
                    results = list
                    selected = -1
                    showList = true
                    message = if (list.isEmpty()) "찾지 못했어요. 다른 이름으로 찾거나 지도에서 직접 고르세요." else null
                    drawPins(list, -1)
                    if (list.size > 1) {
                        keepName = true
                        runCatching {
                            kakaoMap?.moveCamera(CameraUpdateFactory.fitMapPoints(
                                list.map { LatLng.from(it.lat, it.lng) }.toTypedArray(), 120))
                        }
                    } else if (list.size == 1) choose(0)
                }.onFailure { message = ApiClient.errorMessage(it) }
            }
        }

        Box(Modifier.fillMaxSize()) {
            // 지도: 화면 전체
            when (ready) {
                true -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        MapView(ctx).also { view ->
                            mapView = view
                            runCatching {
                                view.start(object : MapLifeCycleCallback() {
                                    override fun onMapDestroy() {}
                                    override fun onMapError(e: Exception) {
                                        mapError.value = "지도를 불러오지 못했어요(${e.message ?: e.javaClass.simpleName}). " +
                                            "콘솔의 네이티브 앱 키 Android 설정과 비교해 주세요 → 패키지 $packageName, " +
                                            "키 해시 ${keyHash()}, 넣은 키 앞자리 $keyPrefix… 검색으로는 고를 수 있어요."
                                    }
                                }, object : KakaoMapReadyCallback() {
                                    override fun onMapReady(map: KakaoMap) {
                                        kakaoMap = map
                                        mapError.value = null
                                        map.setOnCameraMoveEndListener { _, pos, _ ->
                                            center = pos.position.latitude to pos.position.longitude
                                            if (keepName) keepName = false else { chosenName = ""; selected = -1 }
                                        }
                                        // 원하는 곳을 누르면 그곳이 가운데(핀 위치)로 온다.
                                        map.setOnMapClickListener { m, latLng, _, _ ->
                                            runCatching { m.moveCamera(CameraUpdateFactory.newCenterPosition(latLng)) }
                                        }
                                        map.setOnLabelClickListener { _, _, label ->
                                            (label.tag as? Int)?.let { choose(it) }
                                            true
                                        }
                                        if (results.isNotEmpty()) drawPins(results, selected)
                                    }

                                    override fun getPosition(): LatLng = LatLng.from(startLat, startLng)
                                    override fun getZoomLevel(): Int = 16
                                })
                            }.onFailure { e -> mapError.value = "지도를 시작하지 못했어요: ${e.javaClass.simpleName}" }
                        }
                    },
                )
                false -> Box(Modifier.fillMaxSize().background(Color(0xFFF1F3F5)))
                null -> Text("지도 준비 중…", modifier = Modifier.align(Alignment.Center))
            }
            // 가운데 핀: 이 아래 위치가 저장된다.
            if (ready == true) {
                Text("📍", fontSize = 36.sp, modifier = Modifier.align(Alignment.Center).padding(bottom = 36.dp))
            }

            // 위쪽 떠 있는 검색·선택 카드
            Column(
                Modifier.align(Alignment.TopCenter).systemBarsPadding().imePadding().padding(10.dp).fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(14.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("$title 위치", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(q, { q = it }, label = { Text("장소 이름으로 찾기") }, modifier = Modifier.weight(1f),
                        singleLine = true)
                    Spacer(Modifier.padding(2.dp))
                    Button(onClick = { search() }, enabled = !searching) { Text(if (searching) "…" else "찾기") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { finishWith(center.first, center.second, chosenName) },
                        enabled = ready == true || chosenName.isNotBlank()) { Text("이 위치로") }
                    OutlinedButton(onClick = { finish() }) { Text("취소") }
                    Text(if (chosenName.isNotBlank()) chosenName else "지도를 움직이거나 눌러 📍를 맞추세요",
                        fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f))
                }
                message?.let { Text(it, color = ERR, fontSize = 13.sp) }
                if (results.isNotEmpty()) {
                    if (showList) {
                        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                            results.forEachIndexed { i, c ->
                                Column(Modifier.fillMaxWidth().clickable { choose(i) }.padding(vertical = 5.dp)) {
                                    Text("${i + 1}. ${c.name}", fontWeight = FontWeight.Bold,
                                        color = if (i == selected) PINK else Color.Unspecified)
                                    if (c.address.isNotBlank()) Text(c.address, fontSize = 12.sp, color = Color(0xFF6C757D))
                                }
                            }
                        }
                    }
                    TextButton(onClick = { showList = !showList }) {
                        Text(if (showList) "목록 접기 (지도 핀으로 보기)" else "검색 결과 ${results.size}개 목록 보기")
                    }
                }
            }
            error?.let {
                Text(it, color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(12.dp)
                        .background(ERR.copy(alpha = 0.92f), RoundedCornerShape(10.dp)).padding(10.dp))
            }
        }
        // 처음 열 때 위치가 없으면 일정 이름으로 한 번 찾아 둔다(지도는 그대로 보인다).
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (searchFirst && initialQuery.isNotBlank()) search()
        }
    }
}
