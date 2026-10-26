package com.deliverytracker.wear

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.view.MotionEventCompat
import com.deliverytracker.wear.databinding.ActivityArrivalAlertBinding

class ArrivalAlertActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ArrivalAlertActivity"
        const val EXTRA_BRAND_NAME = "brand_name"
        const val EXTRA_COLOR_HEX = "color_hex"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        private const val COUNTDOWN_DURATION_MS = 5000L
    }

    private lateinit var binding: ActivityArrivalAlertBinding
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "DeliveryTracker:ArrivalWakeLock"
            ).apply {
                acquire(COUNTDOWN_DURATION_MS + 1000L)
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

        val alertMessage = customMessage ?: if (brandName.contains("swiggy", ignoreCase = true)) {
            "Captain is near you!"
        } else {
            "Delivery boy is near you!"
        }

        binding.txtAlertBrand.text = brandName.uppercase()
        binding.txtAlertBrand.setTextColor(colorHex)
        binding.txtAlertMessage.text = alertMessage
        binding.txtAlertTitle.text = title
        binding.txtSecondsRemaining.setTextColor(colorHex)

        // Configure edge-to-edge countdown ring
        binding.circularCountdownView.setStrokeWidthDp(8f)
        binding.circularCountdownView.setProgress(1.0f, colorHex)

        // Tap anywhere on the watch screen to dismiss immediately
        binding.rootAlertLayout.setOnClickListener {
            dismissAlert()
        }

        // Start smooth 5-second countdown animation
        startRingCountdown(colorHex)
    }

    private fun startRingCountdown(brandColor: Int) {
        ringAnimator = ValueAnimator.ofFloat(1.0f, 0.0f).apply {
            duration = COUNTDOWN_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val ratio = animation.animatedValue as Float
                binding.circularCountdownView.setProgress(ratio, brandColor)

                // Update second counter (5s -> 1s)
                val seconds = Math.ceil(ratio * 5.0).toInt().coerceIn(1, 5)
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

    private fun dismissAlert() {
        if (!isFinishing && !isDestroyed) {
            ringAnimator?.cancel()
            finish()
        }
    }

    override fun onDestroy() {
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
