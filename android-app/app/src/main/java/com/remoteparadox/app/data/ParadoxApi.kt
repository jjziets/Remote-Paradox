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

    @POST("alarm/panic")
    suspend fun panic(
        @Header("Authorization") auth: String,
        @Body req: PanicRequest,
    ): Response<ActionResult>

    @GET("alarm/history")
    suspend fun eventHistory(
        @Header("Authorization") auth: String,
        @Query("limit") limit: Int = 50,
    ): Response<EventHistoryResponse>

    @GET("alarm/logs")
    suspend fun auditLogs(
        @Header("Authorization") auth: String,
        @Query("limit") limit: Int = 50,
    ): Response<AuditLogResponse>

    @GET("auth/users")
    suspend fun listUsers(
        @Header("Authorization") auth: String,
    ): Response<UserListResponse>

    @PUT("auth/users/{username}/role")
    suspend fun updateUserRole(
        @Header("Authorization") auth: String,
        @Path("username") username: String,
        @Body req: RoleUpdateRequest,
    ): Response<ActionResult>

    @DELETE("auth/users/{username}")
    suspend fun deleteUser(
        @Header("Authorization") auth: String,
        @Path("username") username: String,
    ): Response<ActionResult>

    @PUT("auth/users/{username}/password")
    suspend fun resetPassword(
        @Header("Authorization") auth: String,
        @Path("username") username: String,
        @Body req: PasswordResetRequest,
    ): Response<ActionResult>

    @POST("auth/invite")
    suspend fun createInvite(
        @Header("Authorization") auth: String,
    ): Response<InviteResponse>

    @GET("system/update-status")
    suspend fun piUpdateStatus(
        @Header("Authorization") auth: String,
    ): Response<PiUpdateStatus>

    @POST("system/check-update")
    suspend fun piCheckUpdate(
        @Header("Authorization") auth: String,
    ): Response<ActionResult>

    @POST("system/apply-update")
    suspend fun piApplyUpdate(
        @Header("Authorization") auth: String,
    ): Response<ActionResult>

    @GET("system/resources")
    suspend fun systemResources(
        @Header("Authorization") auth: String,
    ): Response<SystemResources>

    @GET("system/wifi")
    suspend fun systemWifi(
        @Header("Authorization") auth: String,
    ): Response<WifiInfo>

    @POST("system/reboot")
    suspend fun systemReboot(
        @Header("Authorization") auth: String,
    ): Response<ActionResult>

    @GET("system/ble-clients")
    suspend fun bleClients(
        @Header("Authorization") auth: String,
    ): Response<BleClientsResponse>

    @GET("health")
    suspend fun health(): Response<HealthResponse>
}
