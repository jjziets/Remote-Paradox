package com.remoteparadox.watch.tile

import android.util.Log
import androidx.wear.protolayout.*
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.*
import androidx.wear.protolayout.LayoutElementBuilders.*
import androidx.wear.protolayout.ModifiersBuilders.*
import androidx.wear.protolayout.ResourceBuilders.*
import androidx.wear.protolayout.TimelineBuilders.*
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.remoteparadox.watch.data.AlarmStatus
import com.remoteparadox.watch.data.ApiClient
import com.remoteparadox.watch.data.WatchTokenStore
import kotlinx.coroutines.runBlocking

private const val TAG = "StatusTile"
private const val RESOURCES_VERSION = "1"

private const val COLOR_GREEN = 0xCC2E7D32.toInt()
private const val COLOR_RED = 0xCCC62828.toInt()
private const val COLOR_AMBER = 0xCCF9A825.toInt()
private const val COLOR_PULSE_RED = 0xFFB71C1C.toInt()
private const val COLOR_DARK_BG = 0xFF1A1A1A.toInt()
private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
private const val COLOR_BEVEL_LIGHT = 0x33FFFFFF.toInt()
private const val CORNER_RADIUS = 16f

class StatusTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<Tile> {
        val tokenStore = WatchTokenStore(this)

        if (!tokenStore.isLoggedIn) {
            return Futures.immediateFuture(buildNotLoggedInTile())
        }

        val status = fetchStatusBlocking(tokenStore)

        val layout = if (status == null) {
            buildErrorLayout("Offline")
        } else {
            buildStatusLayout(status)
        }

        val tile = Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(10_000)
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

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<Resources> {
        return Futures.immediateFuture(
            Resources.Builder().setVersion(RESOURCES_VERSION).build()
        )
    }

    private fun fetchStatusBlocking(tokenStore: WatchTokenStore): AlarmStatus? {
        return try {
            runBlocking {
                val api = ApiClient.create(tokenStore.baseUrl!!, tokenStore.certFingerprint.orEmpty())
                val resp = api.alarmStatus(tokenStore.bearerHeader)
                if (resp.isSuccessful) resp.body() else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tile fetch failed: ${e.message}")
            null
        }
    }

    private fun buildStatusLayout(status: AlarmStatus): LayoutElement {
        val parts = status.partitions
        if (parts.isEmpty()) return buildErrorLayout("No areas")

        val clickable = Clickable.Builder()
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName("$packageName.MainActivity")
                            .build()
                    )
                    .build()
            )
            .build()

        val panicClickable = Clickable.Builder()
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName("$packageName.MainActivity")
                            .addKeyToExtraMapping(
                                "action",
                                ActionBuilders.AndroidStringExtra.Builder()
                                    .setValue("panic")
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return if (parts.size == 1) {
            buildSinglePartitionTile(parts[0], clickable, panicClickable)
        } else {
            buildDualPartitionTile(parts[0], parts[1], clickable, panicClickable)
        }
    }

    private fun buildPanicCircle(clickable: Clickable): LayoutElement {
        val circleCorner = Corner.Builder().setRadius(dp(18f)).build()
        return Box.Builder()
            .setWidth(dp(36f))
            .setHeight(dp(36f))
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(COLOR_AMBER))
                            .setCorner(circleCorner)
                            .build()
                    )
                    .setBorder(
                        Border.Builder()
                            .setWidth(dp(2f))
                            .setColor(argb(0xFFFFD54F.toInt()))
                            .build()
                    )
                    .setClickable(clickable)
                    .build()
            )
            .addContent(
                Text.Builder()
                    .setText("!")
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(20f))
                            .setColor(argb(0xFF000000.toInt()))
                            .setWeight(FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun buildSinglePartitionTile(
        partition: com.remoteparadox.watch.data.PartitionInfo,
        clickable: Clickable,
        panicClickable: Clickable,
    ): LayoutElement {
        val bgColor = modeToColor(partition.mode)
        val textColor = modeToTextColor(partition.mode)
        val label = modeToLabel(partition.mode)
        val triggeredZones = partition.zones
            .filter { it.alarm || it.wasInAlarm }
            .joinToString(", ") { it.name }

        val columnBuilder = Column.Builder()
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .addContent(
                Text.Builder()
                    .setText(partition.name)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(30f))
                            .setColor(argb(textColor))
                            .setWeight(FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .addContent(Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(
                Text.Builder()
                    .setText(label)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(16f))
                            .setColor(argb(textColor))
                            .build()
                    )
                    .build()
            )

        if (triggeredZones.isNotBlank()) {
            columnBuilder
                .addContent(Spacer.Builder().setHeight(dp(4f)).build())
                .addContent(
                    Text.Builder()
                        .setText(triggeredZones)
                        .setFontStyle(
                            FontStyle.Builder()
                                .setSize(sp(13f))
                                .setColor(argb(textColor))
                                .build()
                        )
                        .setMaxLines(2)
                        .build()
                )
        }

        val corner = Corner.Builder().setRadius(dp(CORNER_RADIUS)).build()

        val statusCard = Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
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
                    .setPadding(
                        Padding.Builder()
                            .setAll(dp(8f))
                            .build()
                    )
                    .setClickable(clickable)
                    .build()
            )
            .addContent(columnBuilder.build())
            .build()

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_START)
            .setVerticalAlignment(VERTICAL_ALIGN_BOTTOM)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(COLOR_DARK_BG))
                            .build()
                    )
                    .build()
            )
            .addContent(statusCard)
            .addContent(
                Box.Builder()
                    .setWidth(expand())
                    .setHeight(expand())
                    .setHorizontalAlignment(HORIZONTAL_ALIGN_START)
                    .setVerticalAlignment(VERTICAL_ALIGN_BOTTOM)
                    .setModifiers(
                        Modifiers.Builder()
                            .setPadding(
                                Padding.Builder()
                                    .setStart(dp(16f))
                                    .setBottom(dp(16f))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(buildPanicCircle(panicClickable))
                    .build()
            )
            .build()
    }

    private fun buildDualPartitionTile(
        top: com.remoteparadox.watch.data.PartitionInfo,
        bottom: com.remoteparadox.watch.data.PartitionInfo,
        clickable: Clickable,
        panicClickable: Clickable,
    ): LayoutElement {
        val zones = Column.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                Modifiers.Builder()
                    .setClickable(clickable)
                    .build()
            )
            .addContent(buildHalfPartition(top, weight(1f)))
            .addContent(Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(buildHalfPartition(bottom, weight(1f)))
            .build()

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(COLOR_DARK_BG))
                            .build()
                    )
                    .setPadding(
                        Padding.Builder().setAll(dp(4f)).build()
                    )
                    .build()
            )
            .addContent(zones)
            .addContent(
                Box.Builder()
                    .setWidth(expand())
                    .setHeight(expand())
                    .setHorizontalAlignment(HORIZONTAL_ALIGN_START)
                    .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
                    .setModifiers(
                        Modifiers.Builder()
                            .setPadding(
                                Padding.Builder()
                                    .setStart(dp(8f))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(buildPanicCircle(panicClickable))
                    .build()
            )
            .build()
    }

    private fun buildHalfPartition(
        partition: com.remoteparadox.watch.data.PartitionInfo,
        height: ContainerDimension,
    ): LayoutElement {
        val bgColor = modeToColor(partition.mode)
        val textColor = modeToTextColor(partition.mode)
        val label = modeToLabel(partition.mode)
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
                            .setSize(sp(20f))
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
                            .setSize(sp(12f))
                            .setColor(argb(textColor))
                            .build()
                    )
                    .build()
            )

        if (triggeredZones.isNotBlank()) {
            columnBuilder.addContent(
                Text.Builder()
                    .setText(triggeredZones)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(11f))
                            .setColor(argb(textColor))
                            .build()
                    )
                    .setMaxLines(1)
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
                    .build()
            )
            .addContent(columnBuilder.build())
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
        "triggered" -> COLOR_PULSE_RED
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
