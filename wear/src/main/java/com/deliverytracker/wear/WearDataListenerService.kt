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
                    otp = dataMap.getString("otp", ""),
                    isArrived = dataMap.getBoolean("is_arrived", false),
                    isNear = dataMap.getBoolean("is_near", false)
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
                    otp = json.optString("otp", ""),
                    isArrived = json.optBoolean("is_arrived", false),
                    isNear = json.optBoolean("is_near", false)
                )
                applyNewDeliveryState(state)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message payload", e)
            }
        }
    }

    private fun applyNewDeliveryState(state: WatchDeliveryState) {
        Log.d(TAG, "Received delivery update: ${state.brandName} - ${state.remainingMinutes}m remaining (${state.status})")

        // Auto-dismiss arrival alert if parcel is received or cleared
        if (!state.isActive || state.status.contains("delivered", ignoreCase = true)) {
            ArrivalAlertActivity.dismissIfActive()
        }

        // If user manually dismissed this order on the watch, keep watch in idle state
        if (state.isActive && DeliveryDataRepository.isOrderDismissedOnWatch(applicationContext, state)) {
            Log.d(TAG, "Order ${state.brandName} (${state.title}) was dismissed on watch; ignoring update")
            return
        }

        // Trigger arrival (strong) or near (gentle) alert based on status
        val isArrivedAlert = DeliveryDataRepository.checkAndMarkArrivalAlert(applicationContext, state)
        if (isArrivedAlert) {
            triggerStrongArrivalVibration()
            showArrivalPopup(state, isArrived = true)
        } else {
            val isNearAlert = DeliveryDataRepository.checkAndMarkNearAlert(applicationContext, state)
            if (isNearAlert) {
                triggerGentleNearVibration()
                showArrivalPopup(state, isArrived = false)
            }
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

    private fun triggerGentleNearVibration() {
        try {
            // Subtle, gentle buzz for 2-min / near alert (~120ms gentle pulse)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val effect = VibrationEffect.createOneShot(120L, 90)
                vibratorManager?.vibrate(CombinedVibration.createParallel(effect))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                val effect = VibrationEffect.createOneShot(120L, 90)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(120L)
            }
            Log.i(TAG, "Gentle near vibration triggered on watch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to vibrate gentle near", e)
        }
    }

    private fun triggerStrongArrivalVibration() {
        try {
            // Distinct, firm double-pulse vibration pattern (0ms delay, 350ms pulse, 150ms pause, 450ms pulse)
            val timings = longArrayOf(0, 350, 150, 450)
            val amplitudes = intArrayOf(0, 255, 0, 255)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibratorManager?.vibrate(CombinedVibration.createParallel(effect))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(timings, -1)
            }
            Log.i(TAG, "Strong arrival vibration triggered on watch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to vibrate strong arrival", e)
        }
    }

    private fun showArrivalPopup(state: WatchDeliveryState, isArrived: Boolean) {
        val alertMessage = if (isArrived) {
            if (state.brandName.contains("swiggy", ignoreCase = true)) "Captain at your doorstep!" else "Rider at your location!"
        } else {
            if (state.brandName.contains("swiggy", ignoreCase = true)) "Captain is near you!" else "Delivery partner is near you!"
        }

        val intent = Intent(applicationContext, ArrivalAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ArrivalAlertActivity.EXTRA_BRAND_NAME, state.brandName)
            putExtra(ArrivalAlertActivity.EXTRA_COLOR_HEX, state.colorHex)
            putExtra(ArrivalAlertActivity.EXTRA_TITLE, state.title)
            putExtra(ArrivalAlertActivity.EXTRA_MESSAGE, alertMessage)
            putExtra(ArrivalAlertActivity.EXTRA_IS_ARRIVED, isArrived)
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

            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ARRIVAL)
                .setSmallIcon(R.drawable.tile_preview)
                .setContentTitle("${state.brandName.uppercase()} Alert")
                .setContentText(alertMessage)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID_ARRIVAL, notification)
            Log.i(TAG, "Posted fullScreenIntent notification (isArrived=$isArrived) on watch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post arrival notification", e)
        }

        // 2. Also attempt direct startActivity
        try {
            applicationContext.startActivity(intent)
            Log.i(TAG, "Called direct startActivity for ArrivalAlertActivity (isArrived=$isArrived)")
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
