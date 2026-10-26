package com.deliverytracker.wear

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.view.MotionEventCompat
import com.deliverytracker.wear.databinding.ActivityArrivalAlertBinding
import java.lang.ref.WeakReference

class ArrivalAlertActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ArrivalAlertActivity"
        const val EXTRA_BRAND_NAME = "brand_name"
        const val EXTRA_COLOR_HEX = "color_hex"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_IS_ARRIVED = "is_arrived"
        private const val NEAR_COUNTDOWN_DURATION_MS = 10000L

        @Volatile
        private var activeAlertRef: WeakReference<ArrivalAlertActivity>? = null

        fun dismissIfActive() {
            activeAlertRef?.get()?.dismissAlert()
        }
    }

    private lateinit var binding: ActivityArrivalAlertBinding
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeAlertRef = WeakReference(this)

        val isArrived = intent.getBooleanExtra(EXTRA_IS_ARRIVED, false)

        // Wake screen and display over keyguard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lockDuration = if (isArrived) 30000L else (NEAR_COUNTDOWN_DURATION_MS + 1000L)
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "DeliveryTracker:ArrivalWakeLock"
            ).apply {
                acquire(lockDuration)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }

        // Dismiss any notification representation since full-screen alert is active
        try {
            androidx.core.app.NotificationManagerCompat.from(this).cancel(1001)
        } catch (e: Exception) {
            Log.e(TAG, "Error canceling notification", e)
        }

        binding = ActivityArrivalAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val brandName = intent.getStringExtra(EXTRA_BRAND_NAME) ?: "Delivery"
        val colorHex = intent.getIntExtra(EXTRA_COLOR_HEX, 0xFFFC8019.toInt())
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val customMessage = intent.getStringExtra(EXTRA_MESSAGE)

        val alertMessage = customMessage ?: if (isArrived) {
            if (brandName.contains("swiggy", ignoreCase = true)) "Captain at your doorstep!" else "Rider at your location!"
        } else {
            if (brandName.contains("swiggy", ignoreCase = true)) "Captain is near you!" else "Delivery partner is near you!"
        }

        binding.txtAlertBrand.text = brandName.uppercase()
        binding.txtAlertBrand.setTextColor(colorHex)
        binding.txtAlertMessage.text = alertMessage
        binding.txtAlertTitle.text = title
        binding.txtSecondsRemaining.setTextColor(colorHex)

        // Configure edge-to-edge countdown ring
        binding.circularCountdownView.setStrokeWidthDp(8f)
        binding.circularCountdownView.setProgress(1.0f, colorHex)

        // Tap root layout to dismiss
        binding.rootAlertLayout.setOnClickListener {
            dismissAlert()
        }

        binding.btnDismiss.setOnClickListener {
            dismissAlert()
        }

        if (isArrived) {
            // Arrived mode: No timer countdown, shows "Dismiss" button, stays on screen until dismissed or delivered
            binding.txtSecondsRemaining.visibility = View.GONE
            binding.btnDismiss.visibility = View.VISIBLE
            binding.txtDismissHint.text = "Tap button or screen to dismiss"
        } else {
            // Near mode: 10s countdown ring, then auto-dismisses
            binding.txtSecondsRemaining.visibility = View.VISIBLE
            binding.btnDismiss.visibility = View.GONE
            binding.txtDismissHint.text = "Tap to dismiss"
            startNearRingCountdown(colorHex)
        }
    }

    private fun startNearRingCountdown(brandColor: Int) {
        ringAnimator = ValueAnimator.ofFloat(1.0f, 0.0f).apply {
            duration = NEAR_COUNTDOWN_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val ratio = animation.animatedValue as Float
                binding.circularCountdownView.setProgress(ratio, brandColor)

                // Update second counter (10s -> 1s)
                val seconds = Math.ceil(ratio * (NEAR_COUNTDOWN_DURATION_MS / 1000.0)).toInt().coerceIn(1, 10)
                binding.txtSecondsRemaining.text = "${seconds}s"
            }
            doOnEnd {
                dismissAlert()
            }
            start()
        }
    }

    // Rotary Bezel interaction on Galaxy Watch 4 Classic: Rotating bezel dismisses alert immediately
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && RotaryHelper.isFromRotaryEncoder(event)) {
            dismissAlert()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    fun dismissAlert() {
        if (!isFinishing && !isDestroyed) {
            ringAnimator?.cancel()
            finish()
        }
    }

    override fun onDestroy() {
        if (activeAlertRef?.get() == this) {
            activeAlertRef = null
        }
        ringAnimator?.cancel()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
        super.onDestroy()
    }
}

private object RotaryHelper {
    fun isFromRotaryEncoder(event: MotionEvent): Boolean {
        return MotionEventCompat.isFromSource(event, 0x00400000) // ViewConfiguration.SOURCE_ROTARY_ENCODER
    }
}
