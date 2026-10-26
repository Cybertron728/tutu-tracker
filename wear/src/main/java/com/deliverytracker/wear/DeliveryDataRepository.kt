package com.deliverytracker.wear

import android.content.Context

data class WatchDeliveryState(
    val appId: String,
    val brandName: String,
    val colorHex: Int,            // Signed 32-bit ARGB (Android convention), matches mobile DeliveryModel
    val remainingMinutes: Int,
    val totalMinutes: Int,
    val progress: Float,
    val status: String,
    val title: String,
    val isActive: Boolean,
    val timestamp: Long,
    val otherOrdersCount: Int = 0,
    val allOrdersJson: String = "",
    val otp: String = "",
    val isArrived: Boolean = false,
    val isNear: Boolean = false
)

object DeliveryDataRepository {

    private const val PREF_NAME = "wear_delivery_prefs"
    private const val KEY_LAST_ARRIVAL_ALERT_TS = "last_arrival_alert_ts"
    private const val KEY_LAST_NEAR_ALERT_TS = "last_near_alert_ts"
    private val lock = Any()  // Guards SharedPreferences against concurrent writes from binder threads

    fun saveDeliveryState(context: Context, state: WatchDeliveryState) {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().also { editor ->
                if (!state.isActive) {
                    // Reset to clean idle state when order is completed or cleared
                    editor.putString("app_id", "idle")
                    editor.putString("brand_name", "Idle")
                    editor.putInt("color_hex", 0xFF888888.toInt())
                    editor.putInt("remaining_mins", 0)
                    editor.putInt("total_mins", 0)
                    editor.putFloat("progress", 0f)
                    editor.putString("status", "No active orders")
                    editor.putString("title", "None")
                    editor.putBoolean("is_active", false)
                    editor.putInt("other_orders_count", 0)
                    editor.putString("all_orders_json", "")
                    editor.putString("otp", "")
                    editor.putBoolean("is_arrived", false)
                    editor.putBoolean("is_near", false)
                    editor.putLong("timestamp", System.currentTimeMillis())
                } else {
                    editor.putString("app_id", state.appId)
                    editor.putString("brand_name", state.brandName)
                    editor.putInt("color_hex", state.colorHex)   // Int (signed ARGB)
                    editor.putInt("remaining_mins", state.remainingMinutes)
                    editor.putInt("total_mins", state.totalMinutes)
                    editor.putFloat("progress", state.progress)
                    editor.putString("status", state.status)
                    editor.putString("title", state.title)
                    editor.putBoolean("is_active", true)
                    editor.putInt("other_orders_count", state.otherOrdersCount)
                    editor.putString("all_orders_json", state.allOrdersJson)
                    editor.putString("otp", state.otp)
                    editor.putBoolean("is_arrived", state.isArrived)
                    editor.putBoolean("is_near", state.isNear)
                    editor.putLong("timestamp", state.timestamp)
                }
                editor.apply()
            }
        }
    }

    fun getDeliveryState(context: Context): WatchDeliveryState {
        val prefs = synchronized(lock) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
        return WatchDeliveryState(
            appId = prefs.getString("app_id", "idle") ?: "idle",
            brandName = prefs.getString("brand_name", "Idle") ?: "Idle",
            colorHex = prefs.getInt("color_hex", 0xFF888888.toInt()),
            remainingMinutes = prefs.getInt("remaining_mins", 0),
            totalMinutes = prefs.getInt("total_mins", 0),
            progress = prefs.getFloat("progress", 0f),
            status = prefs.getString("status", "No active orders") ?: "No active orders",
            title = prefs.getString("title", "None") ?: "None",
            isActive = prefs.getBoolean("is_active", false),
            timestamp = prefs.getLong("timestamp", System.currentTimeMillis()),
            otherOrdersCount = prefs.getInt("other_orders_count", 0),
            allOrdersJson = prefs.getString("all_orders_json", "") ?: "",
            otp = prefs.getString("otp", "") ?: "",
            isArrived = prefs.getBoolean("is_arrived", false),
            isNear = prefs.getBoolean("is_near", false)
        )
    }

    fun isArrived(state: WatchDeliveryState): Boolean {
        if (!state.isActive) return false
        if (state.isArrived) return true
        return (state.remainingMinutes <= 0 ||
               state.status.contains("location", ignoreCase = true) ||
               state.status.contains("doorstep", ignoreCase = true) ||
               state.status.contains("gate", ignoreCase = true) ||
               state.status.contains("arrived", ignoreCase = true)) &&
               !state.isNear
    }

    fun isNear(state: WatchDeliveryState): Boolean {
        if (!state.isActive || isArrived(state)) return false
        if (state.isNear) return true
        return state.remainingMinutes in 1..2 ||
               state.status.contains("near", ignoreCase = true)
    }

    /**
     * Atomically checks if an arrival alert should be triggered (strong vibration + persistent popup).
     * Deduplicates DataClient and MessageClient races within 2500ms.
     */
    fun checkAndMarkArrivalAlert(context: Context, state: WatchDeliveryState): Boolean {
        if (!isArrived(state)) return false

        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val lastAlertTime = prefs.getLong(KEY_LAST_ARRIVAL_ALERT_TS, 0L)
            val lastAppId = prefs.getString("last_arrival_app_id", "") ?: ""
            val lastTimestamp = prefs.getLong("last_arrival_state_ts", 0L)
            val now = System.currentTimeMillis()

            if (state.timestamp == lastTimestamp || (state.appId == lastAppId && (now - lastAlertTime < 2500L))) {
                return false
            }

            prefs.edit()
                .putString("last_arrival_app_id", state.appId)
                .putLong(KEY_LAST_ARRIVAL_ALERT_TS, now)
                .putLong("last_arrival_state_ts", state.timestamp)
                .apply()
            return true
        }
    }

    /**
     * Atomically checks if a near alert should be triggered (gentle vibration + 10s auto-dismiss popup).
     * Deduplicates DataClient and MessageClient races within 2500ms.
     */
    fun checkAndMarkNearAlert(context: Context, state: WatchDeliveryState): Boolean {
        if (!isNear(state)) return false

        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val lastAlertTime = prefs.getLong(KEY_LAST_NEAR_ALERT_TS, 0L)
            val lastAppId = prefs.getString("last_near_app_id", "") ?: ""
            val lastTimestamp = prefs.getLong("last_near_state_ts", 0L)
            val now = System.currentTimeMillis()

            if (state.timestamp == lastTimestamp || (state.appId == lastAppId && (now - lastAlertTime < 2500L))) {
                return false
            }

            prefs.edit()
                .putString("last_near_app_id", state.appId)
                .putLong(KEY_LAST_NEAR_ALERT_TS, now)
                .putLong("last_near_state_ts", state.timestamp)
                .apply()
            return true
        }
    }
}
