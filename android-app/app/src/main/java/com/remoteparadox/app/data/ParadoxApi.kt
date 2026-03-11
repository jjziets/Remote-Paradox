package com.remoteparadox.app.data

import retrofit2.Response
import retrofit2.http.*

interface ParadoxApi {
    @POST("auth/login")
    suspend fun login(@Body req: LoginRequest): Response<LoginResponse>

    @POST("auth/register")
    suspend fun register(@Body req: RegisterRequest): Response<RegisterResponse>

    @GET("alarm/status")
    suspend fun alarmStatus(@Header("Authorization") auth: String): Response<AlarmStatus>

    @POST("alarm/arm-away")
    suspend fun armAway(
        @Header("Authorization") auth: String,
        @Body req: ArmRequest,
    ): Response<ActionResult>

    @POST("alarm/arm-stay")
    suspend fun armStay(
        @Header("Authorization") auth: String,
        @Body req: ArmRequest,
    ): Response<ActionResult>

    @POST("alarm/disarm")
    suspend fun disarm(
        @Header("Authorization") auth: String,
        @Body req: ArmRequest,
    ): Response<ActionResult>

    @POST("alarm/bypass")
    suspend fun bypassZone(
        @Header("Authorization") auth: String,
        @Body req: BypassRequest,
    ): Response<ActionResult>

    @GET("alarm/history")
    suspend fun zoneHistory(
        @Header("Authorization") auth: String,
        @Query("limit") limit: Int = 50,
    ): Response<ZoneHistoryResponse>

    @GET("alarm/logs")
    suspend fun auditLogs(
        @Header("Authorization") auth: String,
        @Query("limit") limit: Int = 50,
    ): Response<AuditLogResponse>

    @GET("health")
    suspend fun health(): Response<HealthResponse>
}
