package com.deliverytracker.mobile

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

object WearSyncManager {

    private const val TAG = "WearSyncManager"
    const val PATH_DELIVERY = "/delivery_tracker"

    // Single managed scope: SupervisorJob means one failed send won't cancel sibling launches
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun syncOrderToWatch(context: Context, order: DeliveryOrder, allOrders: List<DeliveryOrder> = emptyList()) {
        val activeList = if (allOrders.isNotEmpty()) {
            allOrders.filter { it.isActive }.sortedBy { it.remainingMinutes }
        } else if (order.isActive) {
            listOf(order)
        } else {
            emptyList()
        }

        val primary = activeList.firstOrNull() ?: order
        val otherOrdersCount = maxOf(0, activeList.size - 1)
        val allOrdersJson = serializeOrders(activeList)

        scope.launch {
            // 1. DataClient: Persistent synced state
            try {
                val putDataMapReq = PutDataMapRequest.create(PATH_DELIVERY).apply {
                    dataMap.putString("app_id", primary.appId)
                    dataMap.putString("brand_name", primary.brandName)
                    dataMap.putInt("color_hex", primary.colorHex)  // Int (signed ARGB)
                    dataMap.putInt("remaining_mins", primary.remainingMinutes)
                    dataMap.putInt("total_mins", primary.totalMinutes)
                    dataMap.putFloat("progress", primary.progress)
                    dataMap.putString("status", primary.status)
                    dataMap.putString("title", primary.title)
                    dataMap.putBoolean("is_active", primary.isActive)
                    dataMap.putString("otp", primary.otp ?: "")
                    dataMap.putInt("other_orders_count", otherOrdersCount)
                    dataMap.putString("all_orders_json", allOrdersJson)
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                }

                val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
                val result = Wearable.getDataClient(context).putDataItem(putDataReq).await()
                Log.d(TAG, "DataClient synced: ${primary.brandName} - ${primary.remainingMinutes}m (+${otherOrdersCount} more), uri: ${result.uri}")
            } catch (e: Exception) {
                Log.e(TAG, "DataClient sync error", e)
            }

            // 2. MessageClient: Direct Bluetooth wake-up
            try {
                val json = JSONObject().apply {
                    put("app_id", primary.appId)
                    put("brand_name", primary.brandName)
                    put("color_hex", primary.colorHex)
                    put("remaining_mins", primary.remainingMinutes)
                    put("total_mins", primary.totalMinutes)
                    put("progress", primary.progress.toDouble())
                    put("status", primary.status)
                    put("title", primary.title)
                    put("is_active", primary.isActive)
                    put("otp", primary.otp ?: "")
                    put("other_orders_count", otherOrdersCount)
                    put("all_orders_json", allOrdersJson)
                    put("timestamp", System.currentTimeMillis())
                }
                val payload = json.toString().toByteArray(Charsets.UTF_8)

                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(context).sendMessage(node.id, PATH_DELIVERY, payload).await()
                    Log.d(TAG, "MessageClient sent to node ${node.displayName} (+${otherOrdersCount} more)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "MessageClient error", e)
            }
        }
    }

    private fun serializeOrders(orders: List<DeliveryOrder>): String {
        val array = JSONArray()
        for (o in orders) {
            val obj = JSONObject().apply {
                put("app_id", o.appId)
                put("brand_name", o.brandName)
                put("color_hex", o.colorHex)
                put("remaining_mins", o.remainingMinutes)
                put("total_mins", o.totalMinutes)
                put("progress", o.progress.toDouble())
                put("status", o.status)
                put("title", o.title)
                put("is_active", o.isActive)
                put("otp", o.otp ?: "")
                put("timestamp", o.timestamp)
            }
            array.put(obj)
        }
        return array.toString()
    }
}
