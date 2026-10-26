package com.deliverytracker.wear

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.deliverytracker.wear.databinding.ActivityMainBinding
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private var ordersList: List<WatchDeliveryState> = emptyList()
    private var currentOrderIndex: Int = 0
    private lateinit var gestureDetector: GestureDetector
    private val autoAdvanceHandler = Handler(Looper.getMainLooper())
    private var autoAdvanceRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGestures()
        loadOrders()
    }

    override fun onResume() {
        super.onResume()
        loadOrders()
    }

    override fun onPause() {
        super.onPause()
        autoAdvanceRunnable?.let { autoAdvanceHandler.removeCallbacks(it) }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (ordersList.size > 1) {
                    advanceToOrder(currentOrderIndex + 1, slideDirection = 1)
                    return true
                }
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (ordersList.size > 1 && e1 != null) {
                    val diffX = e2.x - e1.x
                    val diffY = e2.y - e1.y
                    if (abs(diffX) > abs(diffY) && abs(diffX) > 40 && abs(velocityX) > 100) {
                        if (diffX < 0) {
                            // Swipe left -> Next order
                            advanceToOrder(currentOrderIndex + 1, slideDirection = 1)
                        } else {
                            // Swipe right -> Previous order
                            advanceToOrder(currentOrderIndex - 1, slideDirection = -1)
                        }
                        return true
                    }
                }
                return false
            }
        })

        // Tapping root or the pill triggers next order
        binding.root.setOnClickListener {
            if (ordersList.size > 1) {
                advanceToOrder(currentOrderIndex + 1, slideDirection = 1)
            }
        }

        binding.txtMoreOrdersPill.setOnClickListener {
            if (ordersList.size > 1) {
                advanceToOrder(currentOrderIndex + 1, slideDirection = 1)
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun advanceToOrder(targetIndex: Int, slideDirection: Int) {
        if (ordersList.size <= 1) return
        val newIndex = (targetIndex % ordersList.size + ordersList.size) % ordersList.size
        if (newIndex == currentOrderIndex) return

        autoAdvanceRunnable?.let { autoAdvanceHandler.removeCallbacks(it) }
        vibrateBezelTick()

        // Card deck slide transition
        val containerWidth = if (binding.orderContentContainer.width > 0) {
            binding.orderContentContainer.width.toFloat()
        } else {
            300f
        }
        val slideOutX = if (slideDirection > 0) -containerWidth * 0.45f else containerWidth * 0.45f
        val slideInStartX = -slideOutX

        binding.orderContentContainer.animate()
            .translationX(slideOutX)
            .alpha(0.0f)
            .setDuration(120)
            .withEndAction {
                currentOrderIndex = newIndex
                renderCurrentOrder()
                binding.orderContentContainer.translationX = slideInStartX
                binding.orderContentContainer.animate()
                    .translationX(0f)
                    .alpha(1.0f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }

    private fun loadOrders() {
        val primaryState = DeliveryDataRepository.getDeliveryState(this)
        ordersList = parseAllOrders(primaryState)
        currentOrderIndex = 0
        renderCurrentOrder()
    }

    private fun parseAllOrders(primary: WatchDeliveryState): List<WatchDeliveryState> {
        if (!primary.isActive) return listOf(primary)
        if (primary.allOrdersJson.isEmpty()) return listOf(primary)

        val list = mutableListOf<WatchDeliveryState>()
        try {
            val array = JSONArray(primary.allOrdersJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    WatchDeliveryState(
                        appId = obj.optString("app_id", "idle"),
                        brandName = obj.optString("brand_name", "Order"),
                        colorHex = obj.optInt("color_hex", 0xFF888888.toInt()),
                        remainingMinutes = obj.optInt("remaining_mins", 0),
                        totalMinutes = obj.optInt("total_mins", 0),
                        progress = obj.optDouble("progress", 0.0).toFloat(),
                        status = obj.optString("status", ""),
                        title = obj.optString("title", ""),
                        isActive = obj.optBoolean("is_active", true),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        otp = obj.optString("otp", "")
                    )
                )
            }
        } catch (e: Exception) {
            list.clear()
            list.add(primary)
        }

        return if (list.isNotEmpty()) list else listOf(primary)
    }

    private fun renderCurrentOrder() {
        if (ordersList.isEmpty()) return
        val state = ordersList[currentOrderIndex.coerceIn(0, ordersList.size - 1)]

        if (!state.isActive) {
            binding.txtBrandBadge.text = "DELIVERY TRACKER"
            binding.txtBrandBadge.setTextColor(0xFF71717A.toInt())
            binding.txtMinutes.text = "--"
            binding.txtStatus.text = "No active orders"
            binding.txtStatus.setTextColor(0xFFA1A1AA.toInt())
            binding.txtTitle.text = "Waiting for notifications"
            binding.txtMoreOrdersPill.visibility = View.GONE
            binding.txtOtpWatch.visibility = View.GONE
            binding.circularProgressView.setProgress(0f, 0xFF27272A.toInt())
            return
        }

        val isAtLocation = state.isArrived || state.remainingMinutes <= 0 ||
            state.status.contains("location", ignoreCase = true) ||
            state.status.contains("doorstep", ignoreCase = true) ||
            state.status.contains("gate", ignoreCase = true) ||
            state.status.contains("arrived", ignoreCase = true)

        val isNear = state.isNear || state.status.contains("near", ignoreCase = true) || state.remainingMinutes <= 2

        binding.txtBrandBadge.text = state.brandName.uppercase()
        binding.txtBrandBadge.setTextColor(state.colorHex)
        binding.txtTitle.text = state.title

        when {
            isAtLocation -> {
                val arrivalTime = timeFormat.format(Date(state.timestamp))
                binding.txtMinutes.text = "0"
                binding.txtStatus.text = "At location! ($arrivalTime)"
                binding.txtStatus.setTextColor(0xFF4CAF50.toInt())
                binding.circularProgressView.setProgress(1.0f, state.colorHex)

                // If other ongoing orders exist, auto-transition after 3 seconds
                if (ordersList.size > 1) {
                    autoAdvanceRunnable?.let { autoAdvanceHandler.removeCallbacks(it) }
                    val runnable = Runnable {
                        advanceToOrder(currentOrderIndex + 1, slideDirection = 1)
                    }
                    autoAdvanceRunnable = runnable
                    autoAdvanceHandler.postDelayed(runnable, 3000L)
                }
            }
            isNear -> {
                binding.txtMinutes.text = state.remainingMinutes.toString()
                binding.txtStatus.text = state.status
                binding.txtStatus.setTextColor(0xFF4CAF50.toInt())
                binding.circularProgressView.setProgress(0.95f, state.colorHex)
            }
            else -> {
                binding.txtMinutes.text = state.remainingMinutes.toString()
                binding.txtStatus.text = state.status
                binding.txtStatus.setTextColor(0xFFCCCCCC.toInt())
                binding.circularProgressView.setProgress(state.progress, state.colorHex)
            }
        }

        // Show Multi-order Paging Pill: "X of Y • Tap to switch"
        if (ordersList.size > 1) {
            binding.txtMoreOrdersPill.visibility = View.VISIBLE
            binding.txtMoreOrdersPill.text = "${currentOrderIndex + 1} of ${ordersList.size} • Tap to switch"
        } else {
            binding.txtMoreOrdersPill.visibility = View.GONE
        }

        // Show OTP Badge if available
        if (state.otp.isNotEmpty()) {
            binding.txtOtpWatch.visibility = View.VISIBLE
            binding.txtOtpWatch.text = "OTP: ${state.otp}"
        } else {
            binding.txtOtpWatch.visibility = View.GONE
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_SCROLL && ordersList.size > 1) {
            val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (abs(delta) > 0.3f) {
                if (delta > 0) {
                    advanceToOrder(currentOrderIndex + 1, slideDirection = 1)
                } else {
                    advanceToOrder(currentOrderIndex - 1, slideDirection = -1)
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun vibrateBezelTick() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(20L)
            }
        } catch (e: Exception) {
            // Ignore haptic error
        }
    }
}
