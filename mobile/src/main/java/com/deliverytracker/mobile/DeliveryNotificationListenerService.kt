package com.deliverytracker.mobile

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DeliveryNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "DeliveryListener"

        // Active orders map keyed by unique orderKey (supports multiple orders from same app like Swiggy)
        val activeOrders = java.util.concurrent.ConcurrentHashMap<String, DeliveryOrder>()

        // @Volatile ensures cross-thread visibility on binder/notification threads
        @Volatile var lastTrackedOrder: DeliveryOrder? = null
        @Volatile var onOrderUpdatedListener: ((DeliveryOrder) -> Unit)? = null
        @Volatile var onActiveOrdersChangedListener: ((List<DeliveryOrder>) -> Unit)? = null

        fun getActiveOrdersList(): List<DeliveryOrder> {
            return activeOrders.values.filter { it.isActive }.sortedBy { it.remainingMinutes }
        }

        fun updateOrder(order: DeliveryOrder) {
            val key = order.key
            if (order.isActive) {
                activeOrders[key] = order
            } else {
                activeOrders.remove(key)
            }
            val list = getActiveOrdersList()
            lastTrackedOrder = list.firstOrNull() ?: order
            onOrderUpdatedListener?.invoke(lastTrackedOrder ?: order)
            onActiveOrdersChangedListener?.invoke(list)
        }

        fun clearAllOrders(context: android.content.Context) {
            activeOrders.clear()
            val clearedOrder = DeliveryOrder(
                appId = "idle",
                brandName = "Idle",
                colorHex = DeliveryOrder.COLOR_IDLE,
                remainingMinutes = 0,
                totalMinutes = 0,
                progress = 0f,
                status = "No active orders",
                title = "None",
                isActive = false
            )
            lastTrackedOrder = null
            onOrderUpdatedListener?.invoke(clearedOrder)
            onActiveOrdersChangedListener?.invoke(emptyList())
            WearSyncManager.syncOrderToWatch(context.applicationContext, clearedOrder, emptyList())
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        val order = DeliveryParser.parse(sbn) ?: return

        Log.i(TAG, "Captured delivery update: ${order.brandName} (${order.title}) - ${order.remainingMinutes} mins [key: ${order.key}]")
        updateOrder(order)

        // Push primary order and multi-order roster to Galaxy Watch
        val list = getActiveOrdersList()
        val primary = list.firstOrNull() ?: order
        WearSyncManager.syncOrderToWatch(applicationContext, primary, list)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        val sbnKey = sbn?.key ?: return
        val packageName = sbn.packageName ?: return
        if (!DeliveryParser.isDeliveryApp(packageName)) return

        // Locate order matching this notification key or app/title
        val matchingOrder = activeOrders[sbnKey]
            ?: activeOrders.values.firstOrNull { it.orderKey == sbnKey }
            ?: run {
                val parsed = DeliveryParser.parse(sbn)
                if (parsed != null) {
                    activeOrders[parsed.key] ?: activeOrders.values.firstOrNull {
                        it.appId == parsed.appId && it.title.equals(parsed.title, ignoreCase = true)
                    }
                } else {
                    val appOrders = activeOrders.values.filter { packageName.contains(it.appId, ignoreCase = true) }
                    if (appOrders.size == 1) appOrders.first() else null
                }
            }

        matchingOrder ?: return
        val orderKeyToRemove = matchingOrder.key
        val currentTimestamp = matchingOrder.timestamp

        serviceScope.launch {
            delay(1500L)

            // If a new update arrived in the meantime for this specific order, do not clear
            val latestForOrder = activeOrders[orderKeyToRemove]
            if (latestForOrder != null && latestForOrder.isActive && latestForOrder.timestamp > currentTimestamp) {
                Log.d(TAG, "Replacement notification arrived for ${latestForOrder.brandName} (${latestForOrder.title}); skipping dismissal clear")
                return@launch
            }

            // Re-verify if this specific notification or order is still active in system tray
            val updatedActive = try { activeNotifications } catch (e: Exception) { null }
            val stillHasThisNotif = updatedActive?.any { it.key == sbnKey } == true
            if (stillHasThisNotif) {
                Log.d(TAG, "Active notification confirmed present after delay for $orderKeyToRemove; keeping active state")
                return@launch
            }

            Log.i(TAG, "Delivery notification dismissed for $orderKeyToRemove — removing from active orders")
            activeOrders.remove(orderKeyToRemove)
            val remainingList = getActiveOrdersList()
            val nextPrimary = remainingList.firstOrNull()

            if (nextPrimary != null) {
                lastTrackedOrder = nextPrimary
                onOrderUpdatedListener?.invoke(nextPrimary)
                onActiveOrdersChangedListener?.invoke(remainingList)
                WearSyncManager.syncOrderToWatch(applicationContext, nextPrimary, remainingList)
            } else {
                val clearedOrder = DeliveryOrder(
                    appId = "idle",
                    brandName = "Idle",
                    colorHex = DeliveryOrder.COLOR_IDLE,
                    remainingMinutes = 0,
                    totalMinutes = 0,
                    progress = 0f,
                    status = "No active orders",
                    title = "None",
                    isActive = false
                )
                lastTrackedOrder = null
                onOrderUpdatedListener?.invoke(clearedOrder)
                onActiveOrdersChangedListener?.invoke(emptyList())
                WearSyncManager.syncOrderToWatch(applicationContext, clearedOrder, emptyList())
            }
        }
    }
}
