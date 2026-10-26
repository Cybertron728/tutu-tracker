package com.deliverytracker.mobile

data class DeliveryOrder(
    val appId: String,            // "zomato", "swiggy", "blinkit", "zepto", "jiomart"
    val brandName: String,        // "Zomato", "Swiggy", "Blinkit", "Zepto", "JioMart"
    val colorHex: Int,            // Color ARGB as signed 32-bit Int (Android convention)
    val remainingMinutes: Int,
    val totalMinutes: Int,
    val progress: Float,          // 0.0f (just started) to 1.0f (arrived)
    val status: String,           // "Out for delivery", "Preparing food", etc.
    val title: String,            // Restaurant / store name
    val isActive: Boolean,
    val otp: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val orderKey: String = ""
) {
    val key: String
        get() = if (orderKey.isNotEmpty()) orderKey else "${appId}_${title.lowercase().trim().replace(Regex("[^a-z0-9]"), "_")}"
    companion object {
        const val APP_ZOMATO = "zomato"
        const val APP_SWIGGY = "swiggy"
        const val APP_BLINKIT = "blinkit"
        const val APP_ZEPTO = "zepto"
        const val APP_JIOMART = "jiomart"

        // Android Color ints are signed 32-bit; .toInt() handles the 0xFF__ overflow correctly
        val COLOR_ZOMATO  = 0xFFE23744.toInt()
        val COLOR_SWIGGY  = 0xFFFC8019.toInt()
        val COLOR_BLINKIT = 0xFFF8CB46.toInt()
        val COLOR_ZEPTO   = 0xFF8027EA.toInt()
        val COLOR_JIOMART = 0xFF008ECC.toInt()
        val COLOR_IDLE    = 0xFF888888.toInt()
    }
}
