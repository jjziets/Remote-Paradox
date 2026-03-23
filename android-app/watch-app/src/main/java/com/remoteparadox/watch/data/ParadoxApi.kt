package com.remoteparadox.watch.data

import retrofit2.Response
import retrofit2.http.*

interface ParadoxApi {
    @POST("auth/login")
    suspend fun login(@Body req: LoginRequest): Response<LoginResponse>

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

    @POST("auth/refresh")
    suspend fun refreshToken(
        @Header("Authorization") auth: String,
    ): Response<LoginResponse>

    @POST("alarm/panic")
    suspend fun panic(
        @Header("Authorization") auth: String,
        @Body req: PanicRequest,
    ): Response<ActionResult>

    @GET("health")
    suspend fun health(): Response<HealthResponse>
}
