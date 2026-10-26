package com.deliverytracker.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class WearDataListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WearDataListener"
        const val PATH_DELIVERY = "/delivery_tracker"
        const val CHANNEL_ARRIVAL = "delivery_arrival_channel"
        const val NOTIFICATION_ID_ARRIVAL = 1001
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == PATH_DELIVERY) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val state = WatchDeliveryState(
                    appId = dataMap.getString("app_id", "idle"),
                    brandName = dataMap.getString("brand_name", "Idle"),
                    colorHex = dataMap.getInt("color_hex", 0xFF888888.toInt()),
                    remainingMinutes = dataMap.getInt("remaining_mins", 0),
                    totalMinutes = dataMap.getInt("total_mins", 0),
                    progress = dataMap.getFloat("progress", 0f),
                    status = dataMap.getString("status", "No active orders"),
                    title = dataMap.getString("title", "None"),
                    isActive = dataMap.getBoolean("is_active", false),
                    timestamp = dataMap.getLong("timestamp", System.currentTimeMillis()),
                    otherOrdersCount = dataMap.getInt("other_orders_count", 0),
                    allOrdersJson = dataMap.getString("all_orders_json", ""),
                    otp = dataMap.getString("otp", "")
                )

                applyNewDeliveryState(state)
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        if (messageEvent.path == PATH_DELIVERY) {
            try {
                val jsonString = String(messageEvent.data, Charsets.UTF_8)
                val json = JSONObject(jsonString)
                val state = WatchDeliveryState(
                    appId = json.optString("app_id", "idle"),
                    brandName = json.optString("brand_name", "Idle"),
                    colorHex = json.optInt("color_hex", 0xFF888888.toInt()),
                    remainingMinutes = json.optInt("remaining_mins", 0),
                    totalMinutes = json.optInt("total_mins", 0),
                    progress = json.optDouble("progress", 0.0).toFloat(),
                    status = json.optString("status", "No active orders"),
                    title = json.optString("title", "None"),
                    isActive = json.optBoolean("is_active", false),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    otherOrdersCount = json.optInt("other_orders_count", 0),
                    allOrdersJson = json.optString("all_orders_json", ""),
                    otp = json.optString("otp", "")
                )
                applyNewDeliveryState(state)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message payload", e)
            }
        }
    }

    private fun applyNewDeliveryState(state: WatchDeliveryState) {
        Log.d(TAG, "Received delivery update: ${state.brandName} - ${state.remainingMinutes}m remaining (${state.status})")

        // Trigger arrival alert (single vibration + full-screen popup)
        val shouldAlert = DeliveryDataRepository.checkAndMarkArrivalAlert(applicationContext, state)
        if (shouldAlert) {
            triggerArrivalVibration()
            showArrivalPopup(state)
        }

        // Save delivery state to SharedPreferences
        try {
            DeliveryDataRepository.saveDeliveryState(applicationContext, state)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save delivery state to SharedPreferences", e)
            return
        }

        // Request Tile update
        try {
            TileService.getUpdater(applicationContext).requestUpdate(DeliveryTileService::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request tile update", e)
        }

        // Request Complication update
        try {
            val requester = ComplicationDataSourceUpdateRequester.create(
                applicationContext,
                ComponentName(applicationContext, DeliveryComplicationService::class.java)
            )
            requester.requestUpdateAll()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request complication update", e)
        }
    }

    private fun triggerArrivalVibration() {
        try {
            // Single, clean tactile vibration pulse (450ms)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val effect = VibrationEffect.createOneShot(450L, VibrationEffect.DEFAULT_AMPLITUDE)
                vibratorManager?.vibrate(CombinedVibration.createParallel(effect))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createOneShot(450L, VibrationEffect.DEFAULT_AMPLITUDE)
                    vibrator?.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(450L)
                }
            }
            Log.i(TAG, "Single arrival vibration triggered on watch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to vibrate watch", e)
        }
    }

    private fun showArrivalPopup(state: WatchDeliveryState) {
        val intent = Intent(applicationContext, ArrivalAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ArrivalAlertActivity.EXTRA_BRAND_NAME, state.brandName)
            putExtra(ArrivalAlertActivity.EXTRA_COLOR_HEX, state.colorHex)
            putExtra(ArrivalAlertActivity.EXTRA_TITLE, state.title)
            val msg = if (state.brandName.contains("swiggy", ignoreCase = true)) {
                "Captain is near you!"
            } else {
                "Delivery boy is near you!"
            }
            putExtra(ArrivalAlertActivity.EXTRA_MESSAGE, msg)
        }

        // 1. Post High-Priority Full-Screen Intent Notification
        // This is guaranteed on Wear OS 3/4 even when direct background startActivity is restricted by Android OS
        try {
            createNotificationChannel()

            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                NOTIFICATION_ID_ARRIVAL,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alertText = if (state.brandName.contains("swiggy", ignoreCase = true)) "Captain is near you!" else "Delivery boy is near you!"

            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ARRIVAL)
                .setSmallIcon(R.drawable.tile_preview)
                .setContentTitle("${state.brandName.uppercase()} Alert")
                .setContentText(alertText)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID_ARRIVAL, notification)
            Log.i(TAG, "Posted fullScreenIntent notification for arrival on watch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post arrival notification", e)
        }

        // 2. Also attempt direct startActivity
        try {
            applicationContext.startActivity(intent)
            Log.i(TAG, "Called direct startActivity for ArrivalAlertActivity")
        } catch (e: Exception) {
            Log.w(TAG, "Direct startActivity restricted (fullScreenIntent will handle presentation)", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ARRIVAL,
                "Delivery Arrival Alert",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen alerts when delivery rider arrives"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 450)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }
}
