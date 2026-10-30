package com.deliverytracker.mobile

import android.app.Notification
import android.os.Bundle
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object DeliveryParser {

    // Supports single numbers "10 mins" and ranges "5-6 mins", "5 - 6 mins", "5 to 6 mins"
    private val timePattern1 = Pattern.compile("(?i)(?:arriving\\s+in|delivering\\s+in|delivery\\s+in|delivery\\s+by|around|reaching\\s+in)\\s*(?:(\\d+)\\s*(?:-|to)\\s*)?(\\d+)\\s*(?:mins?|minutes?)")
    private val timePattern2 = Pattern.compile("(?i)(?:(\\d+)\\s*(?:-|to)\\s*)?(\\d+)\\s*(?:mins?|minutes?)\\s*(?:remaining|left|to\\s+go|delivery|away)")
    private val timePattern3 = Pattern.compile("(?i)(?:order|items?|captain|rider|partner)\\s*(?:is\\s+)?(?:arriving|reaching)\\s*(?:in)?\\s*(?:(\\d+)\\s*(?:-|to)\\s*)?(\\d+)\\s*(?:mins?|minutes?)")
    private val timePatternFallback = Pattern.compile("(?i)(?:(\\d+)\\s*(?:-|to)\\s*)?(\\d+)\\s*(?:mins?|minutes?)")

    // Clock time ETA e.g. "Delivering by 6:38 PM", "Delivery by 6:38 PM", "Arriving by 6:38"
    private val clockTimePattern = Pattern.compile("(?i)(?:delivering\\s+by|delivery\\s+by|arriving\\s+by|reaching\\s+by|estimated\\s+by)\\s*(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?")

    // Explicit delivery completion patterns (matches when an order has finished / been delivered)
    private val deliveredPattern = Pattern.compile(
        "(?i)(?:" +
        "(?:arrived|delivered)\\s+in\\s+\\d+\\s*(?:mins?|minutes?|secs?|seconds?)|" +
        "(?:order|items?|package)\\s+(?:has\\s+been\\s+|is\\s+|was\\s+)?delivered\\b(?!\\s+(?:by|in|around))|" +
        "delivered\\s+to\\s+(?:you|your|door|doorstep|gate|location)|" +
        "(?:successfully|completed)\\s+delivered|" +
        "delivered\\s+successfully|" +
        "order\\s+completed|" +
        "enjoy\\s+your\\s+(?:meal|food|order|items|groceries)|" +
        "hope\\s+you\\s+(?:enjoy|enjoyed|like|liked|love|loved)|" +
        "rate\\s+your\\s+(?:delivery|order|partner|rider|captain|experience)|" +
        "how\\s+was\\s+your\\s+(?:delivery|order|food|meal)|" +
        "(?:^|\\b)delivered[\\s!.]" +
        ")"
    )

    // Arrived at location patterns (strictly requires rider/order context)
    private val arrivedLocationPattern = Pattern.compile(
        "(?i)(?:captain|rider|delivery\\s+(?:partner|agent|boy)|partner|valet|order|delivery)\\s+(?:has\\s+)?(?:arrived|reached|at\\s+(?:your\\s+)?(?:door|doorstep|gate|building|location))"
    )

    // Captain / delivery partner is near patterns (strictly requires rider/order context)
    private val nearLocationPattern = Pattern.compile(
        "(?i)(?:captain|rider|delivery\\s+(?:partner|agent|boy)|partner|valet|order|delivery)\\s+(?:is\\s+)?(?:near|around|approaching|reaching\\s+soon|almost\\s+there)"
    )

    // Active in-transit patterns
    private val onTheWayPattern = Pattern.compile(
        "(?i)(?:captain|rider|delivery\\s+(?:partner|agent|boy)|partner|valet)\\s+(?:is\\s+)?(?:on\\s+(?:the|his)\\s+way|heading\\s+towards|picked\\s+up)"
    )

    // Promotional & marketing blacklist keywords
    private val promoPattern = Pattern.compile(
        "(?i)\\b(?:discount|offers?|deals?|cashback|coupon|promo|flat\\s+\\d+%?|\\d+%\\s+off|save\\s+(?:up\\s+to\\s+)?(?:rs|₹|\\d+)|use\\s+code|festive|sale|hurry|win\\s+a|free\\s+(?:gift|sample|cashback)|curated\\s+for\\s+you|missed\\s+you)\\b"
    )

    // Active order verification keywords
    private val activeOrderVerificationPattern = Pattern.compile(
        "(?i)\\b(?:order\\s+(?:#|id|placed|confirmed|dispatched|packed|on\\s+the\\s+way|delivered)|delivering\\s+(?:in|by)|arriving\\s+(?:in|by)|out\\s+for\\s+delivery|on\\s+the\\s+way|picked\\s+up|delivery\\s+partner|captain|rider|doorstep)\\b"
    )

    // OTP detection pattern
    private val otpPattern = Pattern.compile("(?i)(?:share\\s+)?(?:otp|code)[:\\s]+(?:is\\s+)?([0-9]{4,6})|(?:otp|code)\\s*[:=-]\\s*([0-9]{4,6})")

    // Track total duration per app to compute accurate progress
    private val appMaxMinutes = ConcurrentHashMap<String, Int>()

    fun isDeliveryApp(packageName: String): Boolean {
        return packageName.contains("zomato", ignoreCase = true) ||
               packageName.contains("swiggy", ignoreCase = true) ||
               packageName.contains("grofers", ignoreCase = true) ||
               packageName.contains("blinkit", ignoreCase = true) ||
               packageName.contains("zeptoconsumerapp", ignoreCase = true) ||
               packageName.contains("zepto", ignoreCase = true) ||
               packageName.contains("jiomart", ignoreCase = true)
    }

    fun parse(sbn: android.service.notification.StatusBarNotification): DeliveryOrder? {
        val notification = sbn.notification ?: return null
        return parse(sbn.packageName, notification, sbn.key)
    }

    fun parse(packageName: String, notification: Notification, sbnKey: String = ""): DeliveryOrder? {
        val (appId, brandName, colorHex, defaultTotal) = when {
            packageName.contains("zomato", ignoreCase = true) ->
                Tuple4(DeliveryOrder.APP_ZOMATO, "Zomato", DeliveryOrder.COLOR_ZOMATO, 30)
            packageName.contains("swiggy", ignoreCase = true) ->
                Tuple4(DeliveryOrder.APP_SWIGGY, "Swiggy", DeliveryOrder.COLOR_SWIGGY, 35)
            packageName.contains("grofers", ignoreCase = true) || packageName.contains("blinkit", ignoreCase = true) ->
                Tuple4(DeliveryOrder.APP_BLINKIT, "Blinkit", DeliveryOrder.COLOR_BLINKIT, 12)
            packageName.contains("zeptoconsumerapp", ignoreCase = true) || packageName.contains("zepto", ignoreCase = true) ->
                Tuple4(DeliveryOrder.APP_ZEPTO, "Zepto", DeliveryOrder.COLOR_ZEPTO, 10)
            packageName.contains("jiomart", ignoreCase = true) ->
                Tuple4(DeliveryOrder.APP_JIOMART, "JioMart", DeliveryOrder.COLOR_JIOMART, 45)
            else -> return null
        }

        val extras = notification.extras ?: Bundle()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val ticker = notification.tickerText?.toString() ?: ""

        val combinedText = "$title $text $subText $bigText $ticker".trim()
        if (combinedText.isEmpty()) return null

        // Drop marketing / promotional notifications (e.g. JioMart / Swiggy / Zomato ads)
        if (promoPattern.matcher(combinedText).find() && !activeOrderVerificationPattern.matcher(combinedText).find()) {
            return null
        }

        val orderTitle = if (title.isNotEmpty()) title else brandName
        val orderKey = if (sbnKey.isNotEmpty()) sbnKey else "${appId}_${orderTitle.lowercase().trim().replace(Regex("[^a-z0-9]"), "_")}"

        // Check if genuine delivery completed
        if (deliveredPattern.matcher(combinedText).find()) {
            appMaxMinutes.remove(orderKey)
            return DeliveryOrder(
                appId = "idle",
                brandName = "Idle",
                colorHex = DeliveryOrder.COLOR_IDLE,
                remainingMinutes = 0,
                totalMinutes = 0,
                progress = 0f,
                status = "No active orders",
                title = "None",
                isActive = false,
                orderKey = orderKey
            )
        }

        // Check proximity / location triggers (strictly requires delivery context)
        val isAtLocation = arrivedLocationPattern.matcher(combinedText).find()
        val isNearLocation = nearLocationPattern.matcher(combinedText).find()
        val isOnTheWay = onTheWayPattern.matcher(combinedText).find()

        // 1. Chronometer countdown check (future ETA timestamp only)
        var minutes: Int? = null
        val whenTime = notification.`when`
        if (whenTime > System.currentTimeMillis() + 30000L) {
            val diffMillis = whenTime - System.currentTimeMillis()
            val computedMins = Math.round(diffMillis / 60000.0).toInt()
            if (computedMins in 1..120) {
                minutes = computedMins
            }
        }

        // 2. Fallback to Regex extraction on notification text (single number or ranges like 5-6 mins)
        if (minutes == null) {
            minutes = extractMinutes(combinedText)
        }

        // 3. Fallback to clock time ETA ("Delivering by 6:38 PM")
        if (minutes == null) {
            minutes = extractClockTimeMinutes(combinedText)
        }

        // 4. Proximity fallbacks when minute is not explicitly mentioned in text
        if (minutes == null) {
            when {
                isAtLocation -> minutes = 0
                isNearLocation -> minutes = 1
                isOnTheWay -> minutes = 3
            }
        }

        if (minutes == null) return null

        // Atomically update max total minutes per order - merge() avoids TOCTOU race
        val totalMinutes = appMaxMinutes.merge(orderKey, minutes) { old, new -> maxOf(old, new) }!!

        val isArrived = isAtLocation || minutes == 0
        val remainingMinutes = if (isArrived) 0 else minutes
        val isNear = !isArrived && (isNearLocation || remainingMinutes in 1..2)

        val progress = when {
            isArrived || remainingMinutes == 0 -> 1.0f
            isNearLocation -> 0.95f
            totalMinutes > 0 -> ((totalMinutes - remainingMinutes).toFloat() / totalMinutes.toFloat()).coerceIn(0.05f, 0.98f)
            else -> 0.5f
        }

        val statusText = when {
            isArrived -> "At your location!"
            isNearLocation -> if (appId == DeliveryOrder.APP_SWIGGY) "Captain is near you!" else "Delivery partner is near you!"
            remainingMinutes <= 2 -> "Arriving in ${remainingMinutes}m"
            remainingMinutes <= 5 -> "Almost there"
            text.isNotEmpty() -> text.take(30)
            else -> "Out for delivery"
        }

        val otp = run {
            val matcher = otpPattern.matcher(combinedText)
            if (matcher.find()) {
                matcher.group(1) ?: matcher.group(2)
            } else null
        }

        return DeliveryOrder(
            appId = appId,
            brandName = brandName,
            colorHex = colorHex,
            remainingMinutes = remainingMinutes,
            totalMinutes = totalMinutes,
            progress = progress,
            status = statusText,
            title = orderTitle,
            isActive = true,
            otp = otp,
            orderKey = orderKey,
            isArrived = isArrived,
            isNear = isNear
        )
    }

    private fun extractMinutes(text: String): Int? {
        val patterns = listOf(timePattern1, timePattern2, timePattern3, timePatternFallback)
        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val g1 = matcher.group(1)?.toIntOrNull()
                val g2 = matcher.group(2)?.toIntOrNull()
                val mins = when {
                    g1 != null && g2 != null -> maxOf(g1, g2)
                    g2 != null -> g2
                    g1 != null -> g1
                    else -> null
                }
                if (mins != null && mins in 1..120) return mins
            }
        }
        return null
    }

    private fun extractClockTimeMinutes(text: String): Int? {
        val matcher = clockTimePattern.matcher(text)
        if (matcher.find()) {
            val hourStr = matcher.group(1) ?: return null
            val minStr = matcher.group(2) ?: return null
            val ampm = matcher.group(3)?.lowercase()

            var hour = hourStr.toIntOrNull() ?: return null
            val minute = minStr.toIntOrNull() ?: return null

            if (ampm != null) {
                if (ampm == "pm" && hour < 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
            } else {
                val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (hour < 12 && currentHour >= 12) {
                    hour += 12
                }
            }

            val targetCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val diffMillis = targetCal.timeInMillis - System.currentTimeMillis()
            val computedMins = Math.round(diffMillis / 60000.0).toInt()
            if (computedMins in 1..180) {
                return computedMins
            }
        }
        return null
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
