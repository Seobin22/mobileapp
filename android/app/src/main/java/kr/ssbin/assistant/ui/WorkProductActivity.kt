package kr.ssbin.assistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.WorkProductDto

/**
 * 작업물 뷰어(빌드문서 v3 §8.2). 비서가 미리 만든 조사 요약·체크리스트·초안을 마크다운으로 보여주고, 아래에 출처 링크.
 * 개입 화면 [시작] 때 블록에 붙은 작업물이 있으면 바로 열리고, 채팅의 "작업물#번호"를 눌러도 열린다.
 */
class WorkProductActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ID = "work_product_id"

        fun intent(context: Context, id: Long): Intent =
            Intent(context, WorkProductActivity::class.java).putExtra(EXTRA_ID, id)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val markwon = Markwon.create(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFD6336C))) {
                var product by remember { mutableStateOf<WorkProductDto?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(id) {
                    val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(applicationContext).workProduct(id) } }
                    r.onSuccess { product = it }.onFailure { error = ApiClient.errorMessage(it) }
                }
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text(product?.title ?: "작업물") },
                        actions = { TextButton(onClick = { finish() }) { Text("닫기") } },
                    )
                }) { padding ->
                    Column(
                        Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                    ) {
                        error?.let { Text(it, color = Color(0xFFC92A2A)) }
                        product?.let { p ->
                            AndroidView(
                                factory = { ctx -> TextView(ctx).apply { textSize = 16f } },
                                update = { view -> markwon.setMarkdown(view, p.content_md) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (p.sources.isNotEmpty()) {
                                Text("출처", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
                                p.sources.forEach { s ->
                                    Text(
                                        s.title?.takeIf { it.isNotBlank() } ?: s.url,
                                        color = Color(0xFF1C64C8),
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable { openUrl(s.url) }
                                            .padding(vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
