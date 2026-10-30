package com.deliverytracker.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.deliverytracker.mobile.databinding.ActivityMainBinding
import com.deliverytracker.mobile.databinding.BottomSheetDevToolsBinding
import com.deliverytracker.mobile.databinding.ItemActiveDeliveryCardBinding
import com.google.android.gms.wearable.Wearable
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var devTapCount = 0
    private var lastTapTimestamp = 0L

    companion object {
        const val PKG_SWIGGY = "in.swiggy.android"
        const val PKG_ZOMATO = "com.application.zomato"
        const val PKG_BLINKIT = "com.grofers.customerapp"
        const val PKG_ZEPTO = "com.zeptoconsumerapp"
        const val PKG_JIOMART = "com.jio.jiomart"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        updatePermissionStatus()
        refreshWatchStatus()
        renderActiveOrders(DeliveryNotificationListenerService.getActiveOrdersList())

        DeliveryNotificationListenerService.onActiveOrdersChangedListener = { orders ->
            runOnUiThread {
                renderActiveOrders(orders)
            }
        }
    }

    override fun onDestroy() {
        DeliveryNotificationListenerService.onActiveOrdersChangedListener = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        refreshWatchStatus()
        renderActiveOrders(DeliveryNotificationListenerService.getActiveOrdersList())
    }

    private fun setupListeners() {
        binding.btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.cardWatchStatus.setOnClickListener {
            refreshWatchStatus()
        }

        // Hidden Easter Egg: Tap "Delivery Hub" 7 times to reveal Developer & Simulation Tools
        // Only accessible in debug builds; silently ignored in production.
        binding.txtAppTitle.setOnClickListener {
            if (!BuildConfig.DEBUG) return@setOnClickListener
            val now = System.currentTimeMillis()
            if (now - lastTapTimestamp > 2000L) {
                devTapCount = 0
            }
            lastTapTimestamp = now
            devTapCount++

            when {
                devTapCount >= 7 -> {
                    devTapCount = 0
                    Toast.makeText(this, "🛠️ Developer Tools Unlocked!", Toast.LENGTH_SHORT).show()
                    showDevToolsBottomSheet()
                }
                devTapCount >= 4 -> {
                    val remaining = 7 - devTapCount
                    Toast.makeText(this, "You are $remaining steps away from Developer Tools", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderActiveOrders(orders: List<DeliveryOrder>) {
        val activeList = orders.filter { it.isActive }
        binding.layoutActiveOrders.removeAllViews()

        if (activeList.isEmpty()) {
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.headerActiveDeliveries.visibility = View.GONE
            binding.txtActiveDeliveriesCount.text = "ACTIVE DELIVERIES (0)"
        } else {
            binding.layoutEmptyState.visibility = View.GONE
            binding.headerActiveDeliveries.visibility = View.VISIBLE
            binding.txtActiveDeliveriesCount.text = "ACTIVE DELIVERIES (${activeList.size})"

            for (order in activeList) {
                val cardBinding = ItemActiveDeliveryCardBinding.inflate(
                    LayoutInflater.from(this),
                    binding.layoutActiveOrders,
                    false
                )

                // Brand badge
                cardBinding.txtBrandBadge.text = order.brandName.uppercase()
                cardBinding.txtBrandBadge.backgroundTintList = ColorStateList.valueOf(order.colorHex)

                // ETA display
                if (order.remainingMinutes <= 0 || order.isArrived) {
                    cardBinding.txtEtaValue.text = "At Doorstep"
                    cardBinding.txtEtaValue.setTextColor(Color.parseColor("#4CAF50"))
                } else {
                    cardBinding.txtEtaValue.text = "${order.remainingMinutes} mins"
                    cardBinding.txtEtaValue.setTextColor(Color.WHITE)
                }

                // Title and status
                cardBinding.txtOrderTitle.text = order.title
                cardBinding.txtOrderStatus.text = order.status

                // Progress Bar
                val progressPercent = (order.progress * 100).toInt().coerceIn(0, 100)
                cardBinding.progressBarOrder.progress = progressPercent
                cardBinding.progressBarOrder.progressTintList = ColorStateList.valueOf(order.colorHex)

                // OTP Display
                if (!order.otp.isNullOrEmpty()) {
                    cardBinding.txtOtpBadge.visibility = View.VISIBLE
                    cardBinding.txtOtpBadge.text = "🔑 OTP: ${order.otp}"
                    cardBinding.txtOtpBadge.setOnClickListener {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Delivery OTP", order.otp)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, "Copied OTP: ${order.otp}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    cardBinding.txtOtpBadge.visibility = View.GONE
                }

                // Tapping the card opens the delivery app
                cardBinding.root.setOnClickListener {
                    openDeliveryApp(order.appId, order.brandName)
                }

                binding.layoutActiveOrders.addView(cardBinding.root)
            }
        }
    }

    private fun showDevToolsBottomSheet() {
        val sheetDialog = BottomSheetDialog(this)
        val sheetBinding = BottomSheetDevToolsBinding.inflate(layoutInflater)
        sheetDialog.setContentView(sheetBinding.root)

        // Populate watch status inside bottom sheet
        lifecycleScope.launch {
            try {
                val nodes = withContext(Dispatchers.IO) {
                    Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                }
                if (nodes.isNotEmpty()) {
                    val nodeNames = nodes.joinToString { cleanWatchName(it.displayName) }
                    sheetBinding.txtSheetWatchStatus.text = "Connected: $nodeNames"
                    sheetBinding.txtSheetWatchStatus.setTextColor(Color.parseColor("#4CAF50"))
                } else {
                    sheetBinding.txtSheetWatchStatus.text = "No Galaxy Watch connected (Bluetooth)"
                    sheetBinding.txtSheetWatchStatus.setTextColor(Color.parseColor("#FFA726"))
                }
            } catch (e: Exception) {
                sheetBinding.txtSheetWatchStatus.text = "Watch check error: ${e.message}"
            }
        }

        sheetBinding.btnRefreshWatchStatus.setOnClickListener {
            sheetBinding.txtSheetWatchStatus.text = "Refreshing connection..."
            refreshWatchStatus()
            lifecycleScope.launch {
                try {
                    val nodes = withContext(Dispatchers.IO) {
                        Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                    }
                    if (nodes.isNotEmpty()) {
                        val nodeNames = nodes.joinToString { cleanWatchName(it.displayName) }
                        sheetBinding.txtSheetWatchStatus.text = "Connected: $nodeNames"
                        sheetBinding.txtSheetWatchStatus.setTextColor(Color.parseColor("#4CAF50"))
                    } else {
                        sheetBinding.txtSheetWatchStatus.text = "No Galaxy Watch connected"
                        sheetBinding.txtSheetWatchStatus.setTextColor(Color.parseColor("#FFA726"))
                    }
                } catch (e: Exception) {
                    sheetBinding.txtSheetWatchStatus.text = "Error: ${e.message}"
                }
            }
        }

        // Test Simulation Actions
        sheetBinding.btnSheetTestFourOrders.setOnClickListener {
            val zomato1 = DeliveryOrder(
                appId = DeliveryOrder.APP_ZOMATO,
                brandName = "Zomato",
                colorHex = DeliveryOrder.COLOR_ZOMATO,
                remainingMinutes = 30,
                totalMinutes = 45,
                progress = 0.33f,
                status = "Preparing your food",
                title = "Biryani Blues",
                isActive = true,
                orderKey = "zomato_biryani_blues",
                otp = "3819"
            )
            val zomato2 = DeliveryOrder(
                appId = DeliveryOrder.APP_ZOMATO,
                brandName = "Zomato",
                colorHex = DeliveryOrder.COLOR_ZOMATO,
                remainingMinutes = 18,
                totalMinutes = 35,
                progress = 0.48f,
                status = "Rider picked up order",
                title = "Bakingo Cakes & Desserts",
                isActive = true,
                orderKey = "zomato_bakingo"
            )
            val swiggy1 = DeliveryOrder(
                appId = DeliveryOrder.APP_SWIGGY,
                brandName = "Swiggy",
                colorHex = DeliveryOrder.COLOR_SWIGGY,
                remainingMinutes = 25,
                totalMinutes = 40,
                progress = 0.37f,
                status = "Order confirmed",
                title = "Truffles Burgers & Shakes",
                isActive = true,
                orderKey = "swiggy_truffles",
                otp = "4821"
            )
            val swiggy2 = DeliveryOrder(
                appId = DeliveryOrder.APP_SWIGGY,
                brandName = "Swiggy",
                colorHex = DeliveryOrder.COLOR_SWIGGY,
                remainingMinutes = 8,
                totalMinutes = 15,
                progress = 0.75f,
                status = "Delivery partner on way",
                title = "Swiggy Instamart",
                isActive = true,
                orderKey = "swiggy_instamart"
            )
            DeliveryNotificationListenerService.updateOrder(zomato1)
            DeliveryNotificationListenerService.updateOrder(zomato2)
            DeliveryNotificationListenerService.updateOrder(swiggy1)
            DeliveryNotificationListenerService.updateOrder(swiggy2)
            renderActiveOrders(DeliveryNotificationListenerService.getActiveOrdersList())
            Toast.makeText(this, "Simulating 4 simultaneous orders (2 Zomato + 2 Swiggy)", Toast.LENGTH_SHORT).show()
        }

        sheetBinding.btnSheetTestZomato.setOnClickListener {
            val testOrder = DeliveryOrder(
                appId = DeliveryOrder.APP_ZOMATO,
                brandName = "Zomato",
                colorHex = DeliveryOrder.COLOR_ZOMATO,
                remainingMinutes = 14,
                totalMinutes = 30,
                progress = 0.53f,
                status = "Out for delivery",
                title = "Biryani Blues",
                isActive = true,
                otp = "3819"
            )
            triggerTestOrder(testOrder)
        }

        sheetBinding.btnSheetTestSwiggy.setOnClickListener {
            val testOrder = DeliveryOrder(
                appId = DeliveryOrder.APP_SWIGGY,
                brandName = "Swiggy",
                colorHex = DeliveryOrder.COLOR_SWIGGY,
                remainingMinutes = 22,
                totalMinutes = 35,
                progress = 0.37f,
                status = "Delivery partner on way",
                title = "Truffles Burgers & Shakes",
                isActive = true,
                otp = "4821"
            )
            triggerTestOrder(testOrder)
        }

        sheetBinding.btnSheetTestBlinkit.setOnClickListener {
            val testOrder = DeliveryOrder(
                appId = DeliveryOrder.APP_BLINKIT,
                brandName = "Blinkit",
                colorHex = DeliveryOrder.COLOR_BLINKIT,
                remainingMinutes = 7,
                totalMinutes = 12,
                progress = 0.42f,
                status = "Dispatched from store",
                title = "Blinkit Quick Mart",
                isActive = true,
                otp = "9204"
            )
            triggerTestOrder(testOrder)
        }

        sheetBinding.btnSheetTestZepto.setOnClickListener {
            val testOrder = DeliveryOrder(
                appId = DeliveryOrder.APP_ZEPTO,
                brandName = "Zepto",
                colorHex = DeliveryOrder.COLOR_ZEPTO,
                remainingMinutes = 6,
                totalMinutes = 10,
                progress = 0.40f,
                status = "Rider zooming to you",
                title = "Zepto Cafe & Mart",
                isActive = true,
                otp = "1182"
            )
            triggerTestOrder(testOrder)
        }

        sheetBinding.btnSheetTestJioMart.setOnClickListener {
            val testOrder = DeliveryOrder(
                appId = DeliveryOrder.APP_JIOMART,
                brandName = "JioMart",
                colorHex = DeliveryOrder.COLOR_JIOMART,
                remainingMinutes = 25,
                totalMinutes = 45,
                progress = 0.44f,
                status = "Out for delivery",
                title = "Reliance Fresh Mart",
                isActive = true
            )
            triggerTestOrder(testOrder)
        }

        sheetBinding.btnSheetTestCaptainNear.setOnClickListener {
            val nearOrder = DeliveryOrder(
                appId = DeliveryOrder.APP_SWIGGY,
                brandName = "Swiggy",
                colorHex = DeliveryOrder.COLOR_SWIGGY,
                remainingMinutes = 2,
                totalMinutes = 35,
                progress = 0.95f,
                status = "Captain is near you!",
                title = "Truffles Burgers & Shakes",
                isActive = true,
                otp = "4821",
                isNear = true,
                isArrived = false
            )
            triggerTestOrder(nearOrder)
        }

        sheetBinding.btnSheetTestArrival.setOnClickListener {
            val arrivalOrder = DeliveryOrder(
                appId = DeliveryOrder.APP_ZOMATO,
                brandName = "Zomato",
                colorHex = DeliveryOrder.COLOR_ZOMATO,
                remainingMinutes = 0,
                totalMinutes = 30,
                progress = 1.0f,
                status = "Rider at your location!",
                title = "Biryani Blues",
                isActive = true,
                otp = "3819",
                isNear = false,
                isArrived = true
            )
            triggerTestOrder(arrivalOrder)
        }

        sheetBinding.btnSheetClearOrders.setOnClickListener {
            DeliveryNotificationListenerService.clearAllOrders(applicationContext)
            renderActiveOrders(emptyList())
            Toast.makeText(this, "Cleared all active orders", Toast.LENGTH_SHORT).show()
            sheetDialog.dismiss()
        }

        sheetDialog.show()
    }

    private fun triggerTestOrder(order: DeliveryOrder) {
        DeliveryNotificationListenerService.updateOrder(order)
        val list = DeliveryNotificationListenerService.getActiveOrdersList()
        renderActiveOrders(list)
        WearSyncManager.syncOrderToWatch(applicationContext, order, list)
        Toast.makeText(this, "Sent ${order.brandName} (${order.remainingMinutes}m) to Galaxy Watch!", Toast.LENGTH_SHORT).show()
    }

    private fun refreshWatchStatus() {
        lifecycleScope.launch {
            try {
                val nodes = withContext(Dispatchers.IO) {
                    Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                }
                if (nodes.isNotEmpty()) {
                    val nodeNames = nodes.joinToString { cleanWatchName(it.displayName) }
                    binding.txtWatchStatusTitle.text = "$nodeNames Connected"
                    binding.txtWatchStatusSubtitle.text = "Watch is connected and orders will be displayed there"
                    binding.txtWatchPingBadge.text = "ONLINE"
                    binding.txtWatchPingBadge.setTextColor(Color.parseColor("#4CAF50"))
                    binding.txtWatchPingBadge.setBackgroundColor(Color.parseColor("#224CAF50"))
                    binding.imgWatchStatus.imageTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                } else {
                    binding.txtWatchStatusTitle.text = "Galaxy Watch Standby"
                    binding.txtWatchStatusSubtitle.text = "Tap to verify Bluetooth connection"
                    binding.txtWatchPingBadge.text = "STANDBY"
                    binding.txtWatchPingBadge.setTextColor(Color.parseColor("#FFA726"))
                    binding.txtWatchPingBadge.setBackgroundColor(Color.parseColor("#22FFA726"))
                    binding.imgWatchStatus.imageTintList = ColorStateList.valueOf(Color.parseColor("#FFA726"))
                }
            } catch (e: Exception) {
                binding.txtWatchStatusTitle.text = "Watch Check"
                binding.txtWatchStatusSubtitle.text = e.message ?: "Unable to query wearable nodes"
            }
        }
    }

    private fun cleanWatchName(rawName: String): String {
        return rawName.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
    }

    private fun updatePermissionStatus() {
        val isGranted = isNotificationServiceEnabled()
        if (isGranted) {
            binding.cardPermission.visibility = View.GONE
        } else {
            binding.cardPermission.visibility = View.VISIBLE
        }
    }

    private fun openDeliveryApp(appId: String, brandName: String) {
        val candidates = when (appId.lowercase()) {
            DeliveryOrder.APP_SWIGGY -> listOf("in.swiggy.android", "in.swiggy.android.toing")
            DeliveryOrder.APP_ZOMATO -> listOf("com.application.zomato", "com.application.zomato.district")
            DeliveryOrder.APP_BLINKIT -> listOf("com.grofers.customerapp")
            DeliveryOrder.APP_ZEPTO -> listOf("com.zeptoconsumerapp")
            DeliveryOrder.APP_JIOMART -> listOf("com.jpl.jiomart", "com.jio.jiomart")
            else -> emptyList()
        }

        var launched = false
        for (pkg in candidates) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                launched = true
                break
            }
        }

        if (!launched) {
            val fallbackPkg = candidates.firstOrNull() ?: return
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$fallbackPkg")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$fallbackPkg")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (flat != null && flat.isNotEmpty()) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == pkgName) {
                    return true
                }
            }
        }
        return false
    }
}
