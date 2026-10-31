package kr.ssbin.assistant.data

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface AssistantApi {
    @POST("v1/devices/register")
    suspend fun register(@Body body: RegisterRequest): RegisterResponse

    @POST("v1/devices/heartbeat")
    suspend fun heartbeat(@Body body: HeartbeatRequest): JsonObject

    @GET("v1/sync")
    suspend fun sync(): Map<String, String>

    @POST("v1/actions")
    suspend fun action(@Body body: JsonObject): JsonObject

    @GET("v1/admin/tasks")
    suspend fun tasks(): TasksResponse

    @POST("v1/admin/tasks")
    suspend fun createTask(@Body body: CreateTaskRequest): JsonObject

    @PATCH("v1/admin/tasks/{id}")
    suspend fun patchTask(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @DELETE("v1/admin/tasks/{id}")
    suspend fun deleteTask(@Path("id") id: Long): JsonObject

    @GET("v1/settings")
    suspend fun settings(): SettingsResponse

    @POST("v1/messages")
    suspend fun postMessage(@Body body: PostMessageRequest): PostMessageResponse

    @GET("v1/messages")
    suspend fun messages(@Query("after_id") afterId: Long = 0): MessagesResponse

    @POST("v1/messages/{id}/read")
    suspend fun markRead(@Path("id") id: Long): JsonObject

    @POST("v1/confirmations/{id}")
    suspend fun confirm(@Path("id") id: Long, @Body body: ConfirmRequest): JsonObject

    @GET("v1/timeline")
    suspend fun timeline(): TimelineResponse

    @GET("v1/work_products/{id}")
    suspend fun workProduct(@Path("id") id: Long): WorkProductDto

    /** 진행 중 개입 상태(FCM 이 없을 때 협상 결과 확인용). */
    @GET("v1/admin/status")
    suspend fun status(): JsonObject

    @PUT("v1/settings")
    suspend fun putSetting(@Body body: JsonObject): JsonObject

    // v3.1 목표 탭(§3.7)·고정 블록(§4.4)
    @GET("v1/goals")
    suspend fun goals(): GoalsResponse

    @GET("v1/goals/{id}/blocks")
    suspend fun goalBlocks(@Path("id") id: Long): GoalBlocksResponse

    @PATCH("v1/goals/{id}")
    suspend fun patchGoal(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @POST("v1/goals/{id}/complete")
    suspend fun completeGoal(@Path("id") id: Long): JsonObject

    @POST("v1/goals/{id}/cancel")
    suspend fun cancelGoal(@Path("id") id: Long): JsonObject

    @POST("v1/fixed_blocks")
    suspend fun createFixedBlock(@Body body: FixedBlockRequest): JsonObject

    @PATCH("v1/fixed_blocks/{id}")
    suspend fun patchFixedBlock(@Path("id") id: Long, @Body body: FixedBlockRequest): JsonObject

    @DELETE("v1/fixed_blocks/{id}")
    suspend fun deleteFixedBlock(@Path("id") id: Long): JsonObject

    // AI 모델 선택(제공자 anthropic | factchat)
    @GET("v1/admin/agent")
    suspend fun agent(): JsonObject

    @POST("v1/admin/llm")
    suspend fun saveLlm(@Body body: JsonObject): JsonObject

    @POST("v1/admin/llm_key")
    suspend fun saveLlmKey(@Body body: JsonObject): JsonObject

    @GET("v1/admin/llm/models")
    suspend fun llmModels(@Query("provider") provider: String): JsonObject

    @POST("v1/admin/llm/test")
    suspend fun testLlm(): JsonObject
}

object ApiClient {
    private var cachedKey: String? = null
    private var cached: AssistantApi? = null

    @Synchronized
    fun get(context: Context): AssistantApi {
        val prefs = Prefs.get(context)
        val base = prefs.serverUrl.trimEnd('/') + "/"
        val token = prefs.apiToken
        val key = "$base|$token"
        cached?.let { if (cachedKey == key) return it }

        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder().header("Authorization", "Bearer $token").build()
                )
            }
            .build()
        val api = Retrofit.Builder()
            .baseUrl(base)
            .client(http)
            .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AssistantApi::class.java)
        cachedKey = key
        cached = api
        return api
    }

    /** 서버의 {"error": "..."} 사유를 사람이 읽을 문장으로. */
    fun errorMessage(e: Throwable): String {
        if (e is HttpException) {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            val reason = runCatching {
                AppJson.parseToJsonElement(body ?: "").let { (it as JsonObject)["error"]?.jsonPrimitive?.content }
            }.getOrNull()
            return reason ?: "서버 오류 (${e.code()})"
        }
        return e.message ?: e.javaClass.simpleName
    }
}
