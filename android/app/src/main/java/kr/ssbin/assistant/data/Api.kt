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

    // v3.5 기록·기억
    @POST("v1/outcome-cards/{id}")
    suspend fun submitCard(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @POST("v1/outcome-cards/{id}/later")
    suspend fun cardLater(@Path("id") id: Long): JsonObject

    @POST("v1/day-cards/{day}")
    suspend fun submitDayCard(@Path("day") day: String, @Body body: JsonObject): JsonObject

    @GET("v1/memory/profile")
    suspend fun memoryProfile(): ProfileResponse

    @PATCH("v1/memory/profile")
    suspend fun patchProfile(@Body body: JsonObject): JsonObject

    @GET("v1/memory/items")
    suspend fun memoryItems(@Query("kind") kind: String? = null, @Query("category") category: String? = null,
                            @Query("query") query: String? = null): MemoryItemsResponse

    @PATCH("v1/memory/items/{id}")
    suspend fun patchMemoryItem(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @DELETE("v1/memory/items/{id}")
    suspend fun deleteMemoryItem(@Path("id") id: Long): JsonObject

    @POST("v1/memory/items/{id}/wrong")
    suspend fun wrongMemoryItem(@Path("id") id: Long): JsonObject

    @GET("v1/patterns/summary")
    suspend fun patternSummary(): PatternSummaryDto

    @GET("v1/facts/blocks")
    suspend fun factBlocks(@Query("day") day: String): FactDayResponse

    @PATCH("v1/facts/blocks/{id}")
    suspend fun patchFact(@Path("id") id: Long, @Body body: JsonObject): JsonObject

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

    /** v3.3 §2: 진행 중 고정 일정 [지금 끝내기] */
    @POST("v1/fixed_blocks/{id}/end_now")
    suspend fun endFixedNow(@Path("id") id: Long): JsonObject

    // v3.3 §3 집 주소·이동수단, §4 자료
    @GET("v1/travel")
    suspend fun travel(): TravelStatusDto

    @PUT("v1/travel")
    suspend fun putTravel(@Body body: JsonObject): TravelStatusDto

    @retrofit2.http.Multipart
    @POST("v1/documents")
    suspend fun uploadDocument(
        @retrofit2.http.Part file: okhttp3.MultipartBody.Part,
        @retrofit2.http.Part("goal_id") goalId: okhttp3.RequestBody? = null,
        @retrofit2.http.Part("from_chat") fromChat: okhttp3.RequestBody? = null,
        @retrofit2.http.Part("message") message: okhttp3.RequestBody? = null,
    ): JsonObject

    @GET("v1/documents")
    suspend fun documents(@Query("goal_id") goalId: Long? = null): DocumentsResponse

    @GET("v1/documents/{id}")
    suspend fun document(@Path("id") id: Long): DocumentDto

    @retrofit2.http.Streaming
    @GET("v1/documents/{id}/file")
    suspend fun documentFile(@Path("id") id: Long): okhttp3.ResponseBody

    @DELETE("v1/documents/{id}")
    suspend fun deleteDocument(@Path("id") id: Long): JsonObject

    // 장소 탭: 일정 장소 지도에서 고르기, 오는 길 분 직접 고치기
    @GET("v1/places")
    suspend fun places(): PlacesResponse

    @GET("v1/places/search")
    suspend fun searchPlaces(@Query("q") q: String, @Query("lat") lat: Double? = null, @Query("lng") lng: Double? = null): PlaceSearchResponse

    @PUT("v1/blocks/{id}/place")
    suspend fun setPlace(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @PUT("v1/blocks/{id}/travel")
    suspend fun setTravel(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    /** v3.4: 이동 블록의 이동 시간만 고치기 {minutes: 숫자 | null(자동으로 되돌리기)} */
    @PATCH("v1/travel_blocks/{id}")
    suspend fun patchTravelBlock(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @GET("v1/app/map_key")
    suspend fun mapKey(): JsonObject

    // v3.2 달력·마감·처리 중 표시·블록 기록·반복 일정
    @GET("v1/calendar")
    suspend fun calendar(@Query("from") from: String, @Query("to") to: String): CalendarResponse

    @GET("v1/tasks/{id}")
    suspend fun task(@Path("id") id: Long): TaskDetailDto

    @PATCH("v1/tasks/{id}")
    suspend fun patchTaskDeadline(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @GET("v1/turns/{id}/status")
    suspend fun turnStatus(@Path("id") id: Long): TurnStatusDto

    @GET("v1/blocks/{id}")
    suspend fun block(@Path("id") id: Long): BlockDetailDto

    /** 블록 결과를 직접 바꾸기 {status: done | partial | missed} */
    @PATCH("v1/blocks/{id}/result")
    suspend fun patchBlockResult(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @POST("v1/fixed_series")
    suspend fun createSeries(@Body body: SeriesRequest): JsonObject

    @PATCH("v1/fixed_series/{id}")
    suspend fun patchSeries(
        @Path("id") id: Long,
        @Body body: SeriesRequest,
        @Query("scope") scope: String,
        @Query("from_date") fromDate: String? = null,
    ): JsonObject

    @DELETE("v1/fixed_series/{id}")
    suspend fun deleteSeries(
        @Path("id") id: Long,
        @Query("scope") scope: String,
        @Query("from_date") fromDate: String? = null,
    ): JsonObject

    // 앱 안 업데이트
    @GET("v1/app/latest")
    suspend fun appLatest(): AppLatestDto

    @retrofit2.http.Streaming
    @GET("v1/app/apk")
    suspend fun appApk(): okhttp3.ResponseBody

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
            .writeTimeout(120, TimeUnit.SECONDS) // 자료 올리기(최대 20MB)
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
