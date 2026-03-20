package com.remoteparadox.watch.tile

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

private const val RESOURCES_VERSION = "1"
private const val COLOR_PANIC_RED = 0xCCC62828.toInt()
private const val COLOR_DARK_BG = 0xFF1A1A1A.toInt()
private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
private const val COLOR_BEVEL = 0x33FFFFFF.toInt()

class PanicTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<Tile> {
        val clickable = Clickable.Builder()
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName("com.remoteparadox.watch.MainActivity")
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

        val corner = Corner.Builder().setRadius(dp(20f)).build()

        val layout = Box.Builder()
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
                Box.Builder()
                    .setWidth(expand())
                    .setHeight(expand())
                    .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                    .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
                    .setModifiers(
                        Modifiers.Builder()
                            .setBackground(
                                Background.Builder()
                                    .setColor(argb(COLOR_PANIC_RED))
                                    .setCorner(corner)
                                    .build()
                            )
                            .setBorder(
                                Border.Builder()
                                    .setWidth(dp(1.5f))
                                    .setColor(argb(COLOR_BEVEL))
                                    .build()
                            )
                            .setClickable(clickable)
                            .setPadding(
                                Padding.Builder().setAll(dp(8f)).build()
                            )
                            .build()
                    )
                    .addContent(
                        Column.Builder()
                            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                            .addContent(
                                Text.Builder()
                                    .setText("PANIC")
                                    .setFontStyle(
                                        FontStyle.Builder()
                                            .setSize(sp(32f))
                                            .setColor(argb(COLOR_WHITE))
                                            .setWeight(FONT_WEIGHT_BOLD)
                                            .build()
                                    )
                                    .build()
                            )
                            .addContent(Spacer.Builder().setHeight(dp(4f)).build())
                            .addContent(
                                Text.Builder()
                                    .setText("Tap for emergency")
                                    .setFontStyle(
                                        FontStyle.Builder()
                                            .setSize(sp(12f))
                                            .setColor(argb(COLOR_WHITE))
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        val tile = Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(300_000)
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
}
