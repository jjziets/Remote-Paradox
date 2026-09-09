package com.remoteparadox.watch.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.*
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.*
import androidx.wear.protolayout.LayoutElementBuilders.*
import androidx.wear.protolayout.ModifiersBuilders.*
import androidx.wear.protolayout.ResourceBuilders.*
import androidx.wear.protolayout.TimelineBuilders.*
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.remoteparadox.watch.data.AlarmStatus
import com.remoteparadox.watch.data.ApiClient
import com.remoteparadox.watch.data.RefreshRequest
import com.remoteparadox.watch.data.WatchTokenStore
import com.remoteparadox.watch.data.WatchStatusCache
import com.remoteparadox.watch.data.TileStatus
import com.remoteparadox.watch.data.STATUS_MAX_AGE_MS
import com.remoteparadox.watch.data.loadTileStatus
import com.remoteparadox.watch.data.needsLegacyTokenRefresh
import com.remoteparadox.watch.data.StatusCacheTicket
import kotlinx.coroutines.*

private const val RESOURCES_VERSION = "1"

private const val COLOR_GREEN = 0xCC2E7D32.toInt()
private const val COLOR_RED = 0xCCC62828.toInt()
private const val COLOR_AMBER = 0xCCF9A825.toInt()
private const val COLOR_PULSE_RED = 0xFFB71C1C.toInt()
private const val COLOR_PULSE_BRIGHT = 0xFFFF1744.toInt()
private const val COLOR_PENDING = 0xFF444444.toInt()
private const val COLOR_DARK_BG = 0xFF1A1A1A.toInt()
private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
private const val COLOR_BEVEL_LIGHT = 0x33FFFFFF.toInt()
private const val CORNER_RADIUS = 16f

class StatusTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<Tile> {
        val tokenStore = WatchTokenStore(this)
        if (!tokenStore.isLoggedIn) {
            return Futures.immediateFuture(buildNotLoggedInTile())
        }
        return CallbackToFutureAdapter.getFuture { completer ->
            val job = scope.launch {
                try {
                    val cache = WatchStatusCache(this@StatusTileService, tokenStore)
                    val ticket = cache.capture()
                    val api = cache.ifCurrent(ticket) {
                        ApiClient.create(tokenStore.baseUrl!!, tokenStore.certFingerprint.orEmpty())
                    }
                    if (api == null) {
                        completer.set(buildTile(TileStatus(error = "Status unavailable\nOpen app"), tokenStore))
                        return@launch
                    }
                    val result = loadTileStatus(
                        cached = { cache.read(ticket) },
                        fetch = {
                            val auth = cache.ifCurrent(ticket) { tokenStore.bearerHeader }
                                ?: error("Status request superseded")
                            api.alarmStatus(auth)
                        },
                        refreshAuth = { refreshTokenIfNeeded(api, tokenStore, cache, ticket) },
                        proactiveRefresh = needsLegacyTokenRefresh(tokenStore.refreshToken, tokenStore.tokenAgeMs),
                    )
                    cache.ifCurrent(ticket) {
                        result.snapshot?.let { cache.save(it, ticket) }
                        val snapshot = if (result.snapshot != null) cache.read(ticket) else null
                        completer.set(buildTile(result.copy(snapshot = snapshot), tokenStore))
                    } ?: completer.set(buildTile(TileStatus(error = "Status unavailable\nOpen app"), tokenStore))
                } catch (e: CancellationException) {
                    completer.setCancelled()
                } catch (_: Exception) {
                    completer.set(buildTile(TileStatus(error = "Status unavailable\nOpen app"), tokenStore))
                }
            }
            completer.addCancellationListener({ job.cancel() }, MoreExecutors.directExecutor())
            "Alarm status tile"
        }
    }

    override fun onTileEnterEvent(requestParams: EventBuilders.TileEnterEvent) {
        super.onTileEnterEvent(requestParams)
        getUpdater(this).requestUpdate(StatusTileService::class.java)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildTile(result: TileStatus, tokenStore: WatchTokenStore): Tile {
        val snapshot = result.snapshot?.takeIf { it.isFresh(System.currentTimeMillis()) }
        val status = snapshot?.status
        val layout = when {
            status == null -> buildErrorLayout(result.error ?: "Status stale\nOpen app")
            !status.connected -> buildErrorLayout("Panel disconnected\nOpen app")
            else -> buildStatusLayout(status, tokenStore)
        }
        val timeline = Timeline.Builder()
        val entry = TimelineEntry.Builder().setLayout(Layout.Builder().setRoot(layout).build())
        if (snapshot != null) {
            val expiresAt = snapshot.receivedAt + STATUS_MAX_AGE_MS
            entry.setValidity(TimeInterval.Builder().setStartMillis(snapshot.receivedAt).setEndMillis(expiresAt).build())
            timeline.addTimelineEntry(entry.build())
            timeline.addTimelineEntry(
                TimelineEntry.Builder()
                    .setValidity(TimeInterval.Builder().setStartMillis(expiresAt).setEndMillis(Long.MAX_VALUE).build())
                    .setLayout(Layout.Builder().setRoot(buildErrorLayout("Status stale\nOpen app")).build())
                    .build()
            )
        } else {
            timeline.addTimelineEntry(entry.build())
        }
        return Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(10_000)
            .setTileTimeline(timeline.build())
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<Resources> {
        return Futures.immediateFuture(
            Resources.Builder().setVersion(RESOURCES_VERSION).build()
        )
    }

    private suspend fun refreshTokenIfNeeded(
        api: com.remoteparadox.watch.data.ParadoxApi,
        tokenStore: WatchTokenStore,
        cache: WatchStatusCache,
        ticket: StatusCacheTicket,
    ) {
        val credentials = cache.inSession(ticket) { tokenStore.refreshToken to tokenStore.bearerHeader }
            ?: throw CancellationException("Session changed")
        val (refreshToken, bearer) = credentials
        val resp = if (!refreshToken.isNullOrBlank()) {
            api.refreshToken(RefreshRequest(refreshToken))
        } else {
            api.refreshToken(bearer)
        }
        if (resp.isSuccessful && resp.body() != null) {
            val body = resp.body()!!
            cache.inSession(ticket) {
                tokenStore.token = body.token
                tokenStore.refreshToken = body.refreshToken.ifBlank { tokenStore.refreshToken }
            }
        } else if (!refreshToken.isNullOrBlank()) {
            if (!cache.isSameSession(ticket)) return
            val fallback = api.refreshToken(bearer)
            if (fallback.isSuccessful && fallback.body() != null) {
                val body = fallback.body()!!
                cache.inSession(ticket) {
                    tokenStore.token = body.token
                    tokenStore.refreshToken = body.refreshToken.ifBlank { tokenStore.refreshToken }
                }
            }
        }
    }

    private fun buildStatusLayout(status: AlarmStatus, tokenStore: WatchTokenStore): LayoutElement {
        val parts = status.partitions
        if (parts.isEmpty()) return buildErrorLayout("No areas")

        val activityClass = "com.remoteparadox.watch.MainActivity"

        val panicClickable = buildActionClickable(activityClass, action = "panic")

        return if (parts.size == 1) {
            buildSinglePartitionTile(
                parts[0],
                buildSmartClickable(activityClass, parts[0], tokenStore),
                panicClickable,
                pending = tokenStore.isPendingAction(parts[0].id),
            )
        } else {
            buildDualPartitionTile(
                parts[0], parts[1],
                buildSmartClickable(activityClass, parts[0], tokenStore),
                buildSmartClickable(activityClass, parts[1], tokenStore),
                panicClickable,
                pendingTop = tokenStore.isPendingAction(parts[0].id),
                pendingBottom = tokenStore.isPendingAction(parts[1].id),
            )
        }
    }

    private fun buildSmartClickable(
        activityClass: String,
        partition: com.remoteparadox.watch.data.PartitionInfo,
        tokenStore: WatchTokenStore,
    ): Clickable {
        val isArmed = partition.armed || partition.mode == "triggered"
        val isArming = partition.mode in listOf("arming", "exit_delay")
        val bothEnabled = tokenStore.armAwayEnabled && tokenStore.armStayEnabled
        val isDisarmed = partition.mode == "disarmed"

        if (isDisarmed && !partition.ready) {
            return buildPartitionClickable(activityClass, partition.id)
        }

        return when {
            isArming || isArmed -> buildActionClickable(activityClass, "disarm", partition.id)
            !bothEnabled && tokenStore.armAwayEnabled -> buildActionClickable(activityClass, "arm_away", partition.id)
            !bothEnabled && tokenStore.armStayEnabled -> buildActionClickable(activityClass, "arm_stay", partition.id)
            else -> buildPartitionClickable(activityClass, partition.id)
        }
    }

    private fun buildActionClickable(activityClass: String, action: String, partitionId: Int? = null): Clickable {
        val activityBuilder = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(activityClass)
            .addKeyToExtraMapping(
                "action",
                ActionBuilders.AndroidStringExtra.Builder().setValue(action).build()
            )
        if (partitionId != null) {
            activityBuilder.addKeyToExtraMapping(
                "partition_id",
                ActionBuilders.AndroidIntExtra.Builder().setValue(partitionId).build()
            )
        }
        return Clickable.Builder()
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(activityBuilder.build())
                    .build()
            )
            .build()
    }

    private fun buildPartitionClickable(activityClass: String, partitionId: Int): Clickable {
        return Clickable.Builder()
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(activityClass)
                            .addKeyToExtraMapping(
                                "partition_id",
                                ActionBuilders.AndroidIntExtra.Builder()
                                    .setValue(partitionId)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun buildPanicCircle(clickable: Clickable): LayoutElement {
        val outerCorner = Corner.Builder().setRadius(dp(24f)).build()
        val midCorner = Corner.Builder().setRadius(dp(20f)).build()
        val innerCorner = Corner.Builder().setRadius(dp(16f)).build()

        val innerFace = Box.Builder()
            .setWidth(dp(32f))
            .setHeight(dp(32f))
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(0xFFB71C1C.toInt()))
                            .setCorner(innerCorner)
                            .build()
                    )
                    .build()
            )
            .addContent(
                Text.Builder()
                    .setText("!")
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(20f))
                            .setColor(argb(COLOR_WHITE))
                            .setWeight(FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .build()

        val redBody = Box.Builder()
            .setWidth(dp(40f))
            .setHeight(dp(40f))
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(0xFFE53935.toInt()))
                            .setCorner(midCorner)
                            .build()
                    )
                    .setBorder(
                        Border.Builder()
                            .setWidth(dp(1f))
                            .setColor(argb(0xFFEF5350.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(innerFace)
            .build()

        return Box.Builder()
            .setWidth(dp(48f))
            .setHeight(dp(48f))
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(0xFFFDD835.toInt()))
                            .setCorner(outerCorner)
                            .build()
                    )
                    .setBorder(
                        Border.Builder()
                            .setWidth(dp(1f))
                            .setColor(argb(0xFFFFF176.toInt()))
                            .build()
                    )
                    .setClickable(clickable)
                    .build()
            )
            .addContent(redBody)
            .build()
    }

    private fun buildPanicColumn(panicClickable: Clickable): LayoutElement {
        return Box.Builder()
            .setWidth(weight(1f))
            .setHeight(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .addContent(buildPanicCircle(panicClickable))
            .build()
    }

    private fun buildPartitionCard(
        partition: com.remoteparadox.watch.data.PartitionInfo,
        clickable: Clickable,
        nameSize: Float = 22f,
        statusSize: Float = 13f,
        triggerSize: Float = 11f,
        height: ContainerDimension = expand(),
        pending: Boolean = false,
    ): LayoutElement {
        val bgColor = if (pending) COLOR_PENDING else modeToColor(partition.mode)
        val textColor = modeToTextColor(partition.mode)
        val label = if (pending) "Sending…" else modeToLabel(partition.mode)
        val isDisarmedNotReady = partition.mode == "disarmed" && !partition.ready
        val triggeredZones = partition.zones
            .filter { it.alarm || it.wasInAlarm }
            .joinToString(", ") { it.name }

        val corner = Corner.Builder().setRadius(dp(CORNER_RADIUS)).build()

        val columnBuilder = Column.Builder()
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .addContent(
                Text.Builder()
                    .setText(partition.name)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(nameSize))
                            .setColor(argb(textColor))
                            .setWeight(FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .addContent(
                Text.Builder()
                    .setText(label)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(statusSize))
                            .setColor(argb(textColor))
                            .build()
                    )
                    .build()
            )

        if (isDisarmedNotReady && !pending) {
            columnBuilder.addContent(
                Text.Builder()
                    .setText("Not Ready")
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(triggerSize))
                            .setColor(argb(0xFFFFD54F.toInt()))
                            .setWeight(FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
        }

        if (triggeredZones.isNotBlank()) {
            columnBuilder.addContent(
                Text.Builder()
                    .setText(triggeredZones)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(triggerSize))
                            .setColor(argb(textColor))
                            .build()
                    )
                    .setMaxLines(2)
                    .build()
            )
        }

        return Box.Builder()
            .setWidth(expand())
            .setHeight(height)
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(bgColor))
                            .setCorner(corner)
                            .build()
                    )
                    .setBorder(
                        Border.Builder()
                            .setWidth(dp(1.5f))
                            .setColor(argb(COLOR_BEVEL_LIGHT))
                            .build()
                    )
                    .setClickable(clickable)
                    .build()
            )
            .addContent(columnBuilder.build())
            .build()
    }

    private fun buildSinglePartitionTile(
        partition: com.remoteparadox.watch.data.PartitionInfo,
        clickable: Clickable,
        panicClickable: Clickable,
        pending: Boolean = false,
    ): LayoutElement {
        return Row.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(COLOR_DARK_BG))
                            .build()
                    )
                    .setPadding(Padding.Builder().setAll(dp(4f)).build())
                    .build()
            )
            .addContent(buildPanicColumn(panicClickable))
            .addContent(Spacer.Builder().setWidth(dp(4f)).build())
            .addContent(
                Box.Builder()
                    .setWidth(weight(3f))
                    .setHeight(expand())
                    .addContent(
                        buildPartitionCard(partition, clickable, nameSize = 26f, statusSize = 14f, triggerSize = 12f, pending = pending)
                    )
                    .build()
            )
            .build()
    }

    private fun buildDualPartitionTile(
        top: com.remoteparadox.watch.data.PartitionInfo,
        bottom: com.remoteparadox.watch.data.PartitionInfo,
        topClickable: Clickable,
        bottomClickable: Clickable,
        panicClickable: Clickable,
        pendingTop: Boolean = false,
        pendingBottom: Boolean = false,
    ): LayoutElement {
        val partitions = Column.Builder()
            .setWidth(weight(3f))
            .setHeight(expand())
            .addContent(buildPartitionCard(top, topClickable, nameSize = 18f, statusSize = 11f, triggerSize = 10f, height = weight(1f), pending = pendingTop))
            .addContent(Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(buildPartitionCard(bottom, bottomClickable, nameSize = 18f, statusSize = 11f, triggerSize = 10f, height = weight(1f), pending = pendingBottom))
            .build()

        return Row.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(COLOR_DARK_BG))
                            .build()
                    )
                    .setPadding(Padding.Builder().setAll(dp(4f)).build())
                    .build()
            )
            .addContent(buildPanicColumn(panicClickable))
            .addContent(Spacer.Builder().setWidth(dp(4f)).build())
            .addContent(partitions)
            .build()
    }

    private fun buildNotLoggedInTile(): Tile {
        val layout = buildErrorLayout("Not connected\nOpen app to set up")
        return Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(60_000)
            .setTileTimeline(
                Timeline.Builder()
                    .addTimelineEntry(
                        TimelineEntry.Builder()
                            .setLayout(Layout.Builder().setRoot(layout).build())
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun buildErrorLayout(message: String): LayoutElement {
        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setClickable(
                        Clickable.Builder().setOnClick(
                            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                                ActionBuilders.AndroidActivity.Builder()
                                    .setPackageName(packageName)
                                    .setClassName("com.remoteparadox.watch.MainActivity")
                                    .build()
                            ).build()
                        ).build()
                    )
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(COLOR_DARK_BG))
                            .build()
                    )
                    .build()
            )
            .addContent(
                Text.Builder()
                    .setText(message)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(14f))
                            .setColor(argb(COLOR_WHITE))
                            .build()
                    )
                    .setMaxLines(3)
                    .build()
            )
            .build()
    }

    private fun modeToColor(mode: String): Int = when (mode) {
        "disarmed" -> COLOR_GREEN
        "armed_away", "armed_home" -> COLOR_RED
        "arming", "entry_delay", "exit_delay" -> COLOR_AMBER
        "triggered" -> if (System.currentTimeMillis() / 1000 % 2 == 0L) COLOR_PULSE_RED else COLOR_PULSE_BRIGHT
        else -> COLOR_AMBER
    }

    private fun modeToTextColor(mode: String): Int = when (mode) {
        "arming", "entry_delay", "exit_delay" -> 0xFF000000.toInt()
        else -> COLOR_WHITE
    }

    private fun modeToLabel(mode: String): String = when (mode) {
        "disarmed" -> "Disarmed"
        "armed_away" -> "Armed Away"
        "armed_home" -> "Armed Home"
        "arming" -> "Arming..."
        "triggered" -> "TRIGGERED"
        "entry_delay" -> "Entry Delay"
        "exit_delay" -> "Exit Delay"
        else -> mode
    }
}
