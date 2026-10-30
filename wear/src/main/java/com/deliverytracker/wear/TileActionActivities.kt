package com.deliverytracker.wear

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

abstract class BaseTileActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executeAction()
        overridePendingTransition(0, 0)
        finish()
    }

    abstract fun executeAction()

    protected fun requestTileUpdate() {
        try {
            TileService.getUpdater(applicationContext).requestUpdate(DeliveryTileService::class.java)
        } catch (e: Exception) {
            // Ignored
        }
    }

    protected fun requestComplicationUpdate() {
        try {
            val requester = ComplicationDataSourceUpdateRequester.create(
                applicationContext,
                ComponentName(applicationContext, DeliveryComplicationService::class.java)
            )
            requester.requestUpdateAll()
        } catch (e: Exception) {
            // Ignored
        }
    }
}

class ClearPromptActivity : BaseTileActionActivity() {
    override fun executeAction() {
        DeliveryDataRepository.setTileConfirmingClear(this, true)
        requestTileUpdate()
    }
}

class ClearConfirmActivity : BaseTileActionActivity() {
    override fun executeAction() {
        val currentState = DeliveryDataRepository.getDeliveryState(this)
        DeliveryDataRepository.markOrderDismissedOnWatch(this, currentState)

        val idleState = WatchDeliveryState(
            appId = "idle",
            brandName = "Idle",
            colorHex = 0xFF888888.toInt(),
            remainingMinutes = 0,
            totalMinutes = 0,
            progress = 0f,
            status = "No active orders",
            title = "None",
            isActive = false,
            timestamp = System.currentTimeMillis()
        )
        DeliveryDataRepository.saveDeliveryState(this, idleState)
        ArrivalAlertActivity.dismissIfActive()
        requestTileUpdate()
        requestComplicationUpdate()
    }
}

class ClearCancelActivity : BaseTileActionActivity() {
    override fun executeAction() {
        DeliveryDataRepository.setTileConfirmingClear(this, false)
        requestTileUpdate()
    }
}
