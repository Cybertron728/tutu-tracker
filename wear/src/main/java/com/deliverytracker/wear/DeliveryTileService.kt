package com.deliverytracker.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.ARC_ANCHOR_START
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class DeliveryTileService : TileService() {

    companion object {
        private const val RESOURCES_VERSION = "1"
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val state = DeliveryDataRepository.getDeliveryState(this)

        val tile = if (!state.isActive) {
            buildIdleTile()
        } else {
            buildActiveTile(state)
        }

        return Futures.immediateFuture(tile)
    }

    private fun buildIdleTile(): TileBuilders.Tile {
        val trackColor = argb(0xFF27272A.toInt())

        val clickAction = ModifiersBuilders.Clickable.Builder()
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()

        val modifiers = ModifiersBuilders.Modifiers.Builder()
            .setClickable(clickAction)
            .build()

        // Subtle empty track arc only
        val progressArc = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(degrees(0f))
            .setAnchorType(ARC_ANCHOR_START)
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(360f))
                    .setColor(trackColor)
                    .setThickness(dp(10f))
                    .build()
            )
            .build()

        val centerColumn = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            // Header
            .addContent(
                Text.Builder(this, "DELIVERY TRACKER")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(argb(0xFF71717A.toInt()))
                    .build()
            )
            // Neutral Dash
            .addContent(
                Text.Builder(this, "--")
                    .setTypography(Typography.TYPOGRAPHY_DISPLAY1)
                    .setColor(argb(0xFF52525B.toInt()))
                    .build()
            )
            // Idle Status
            .addContent(
                Text.Builder(this, "No active orders")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(argb(0xFFA1A1AA.toInt()))
                    .build()
            )
            .build()

        val rootBox = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(modifiers)
            .addContent(progressArc)
            .addContent(centerColumn)
            .build()

        val layout = LayoutElementBuilders.Layout.Builder().setRoot(rootBox).build()
        val timelineEntry = TimelineBuilders.TimelineEntry.Builder().setLayout(layout).build()
        val timeline = TimelineBuilders.Timeline.Builder().addTimelineEntry(timelineEntry).build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(60_000L)
            .build()
    }

    private fun buildActiveTile(state: WatchDeliveryState): TileBuilders.Tile {
        val brandColor = argb(state.colorHex and 0x00FFFFFF or (0xFF shl 24))
        val trackColor = argb(0xFF27272A.toInt())

        // Compute angle for circular progress (360 degrees * progress)
        val progressAngle = (360f * state.progress.coerceIn(0.05f, 1.0f))

        val clickAction = ModifiersBuilders.Clickable.Builder()
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()

        val modifiers = ModifiersBuilders.Modifiers.Builder()
            .setClickable(clickAction)
            .build()

        val progressArcBuilder = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(degrees(0f))
            .setAnchorType(ARC_ANCHOR_START)
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(progressAngle.coerceIn(0f, 360f)))
                    .setColor(brandColor)
                    .setThickness(dp(10f))
                    .build()
            )

        val trackDegrees = 360f - progressAngle
        if (trackDegrees > 0.5f) {
            progressArcBuilder.addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(trackDegrees))
                    .setColor(trackColor)
                    .setThickness(dp(10f))
                    .build()
            )
        }
        val progressArc = progressArcBuilder.build()

        val isAtLocation = state.remainingMinutes <= 0 ||
            state.status.contains("location", ignoreCase = true) ||
            state.status.contains("doorstep", ignoreCase = true) ||
            state.status.contains("gate", ignoreCase = true) ||
            state.status.contains("arrived", ignoreCase = true)
        val isNear = state.status.contains("near", ignoreCase = true)

        val statusColor = if (isAtLocation || isNear) argb(0xFF4CAF50.toInt()) else argb(0xFFA1A1AA.toInt())
        val minutesText = "${state.remainingMinutes}m"

        val centerColumnBuilder = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            // Brand Name Header
            .addContent(
                Text.Builder(this, state.brandName.uppercase())
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(brandColor)
                    .build()
            )
            // Big Minutes
            .addContent(
                Text.Builder(this, minutesText)
                    .setTypography(Typography.TYPOGRAPHY_DISPLAY1)
                    .setColor(argb(0xFFFFFFFF.toInt()))
                    .build()
            )
            // Status Text
            .addContent(
                Text.Builder(this, state.status)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(statusColor)
                    .build()
            )

        // +1 more order badge
        if (state.otherOrdersCount > 0) {
            val moreText = "+${state.otherOrdersCount} more order"
            centerColumnBuilder.addContent(
                Text.Builder(this, moreText)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(argb(0xFFFFA726.toInt()))
                    .build()
            )
        }

        val centerColumn = centerColumnBuilder.build()

        val rootBox = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(modifiers)
            .addContent(progressArc)
            .addContent(centerColumn)
            .build()

        val layout = LayoutElementBuilders.Layout.Builder().setRoot(rootBox).build()
        val timelineEntry = TimelineBuilders.TimelineEntry.Builder().setLayout(layout).build()
        val timeline = TimelineBuilders.Timeline.Builder().addTimelineEntry(timelineEntry).build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(60_000L)
            .build()
    }

    @Deprecated("Deprecated in Java")
    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()
        return Futures.immediateFuture(resources)
    }
}
