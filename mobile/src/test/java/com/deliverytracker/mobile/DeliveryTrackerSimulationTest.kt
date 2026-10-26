package com.deliverytracker.mobile

import android.app.Notification
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeliveryTrackerSimulationTest {

    @Before
    fun setUp() {
        DeliveryNotificationListenerService.activeOrders.clear()
        DeliveryNotificationListenerService.lastTrackedOrder = null
    }

    private fun createMockNotification(title: String, text: String, subText: String = ""): Notification {
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, title)
            putCharSequence(Notification.EXTRA_TEXT, text)
            if (subText.isNotEmpty()) {
                putCharSequence(Notification.EXTRA_SUB_TEXT, subText)
            }
        }
        val notification = Notification()
        notification.extras = extras
        return notification
    }

    @Test
    fun testPromoNotificationFilteredOut() {
        val notif = createMockNotification("Swiggy Deals", "Flat 50% OFF! Save up to Rs 100 with code CRAVINGS")
        val order = DeliveryParser.parse("in.swiggy.android", notif, "swiggy_promo_1")
        assertNull("Promotional notifications should be ignored", order)
    }

    @Test
    fun testTwoSimultaneousSwiggyOrdersCoexist() {
        val notif1 = createMockNotification("Truffles Burgers", "Order is on the way. Arriving in 25 mins")
        val order1 = DeliveryParser.parse("in.swiggy.android", notif1, "0|in.swiggy.android|101|null|1000")
        assertNotNull(order1)
        assertEquals(25, order1!!.remainingMinutes)
        assertEquals("Truffles Burgers", order1.title)

        val notif2 = createMockNotification("Swiggy Instamart", "Delivery partner heading towards you. Delivering in 8 mins")
        val order2 = DeliveryParser.parse("in.swiggy.android", notif2, "0|in.swiggy.android|102|null|1000")
        assertNotNull(order2)
        assertEquals(8, order2!!.remainingMinutes)
        assertEquals("Swiggy Instamart", order2.title)

        // Add both to active orders
        DeliveryNotificationListenerService.updateOrder(order1)
        DeliveryNotificationListenerService.updateOrder(order2)

        val activeList = DeliveryNotificationListenerService.getActiveOrdersList()
        assertEquals("Both Swiggy orders should be actively tracked", 2, activeList.size)
        // Check sorting by remaining minutes
        assertEquals("Instamart (8m) should be first", "Swiggy Instamart", activeList[0].title)
        assertEquals("Truffles (25m) should be second", "Truffles Burgers", activeList[1].title)
    }

    @Test
    fun testNearbyRestaurantTwoMinuteWarningDoesNotTriggerFalseArrival() {
        // Restaurant is close to home: order is out for delivery with 2 mins ETA
        val notif = createMockNotification("Truffles Burgers", "Order out for delivery. Arriving in 2 mins")
        val order = DeliveryParser.parse("in.swiggy.android", notif, "swiggy_near_test")
        assertNotNull(order)

        assertEquals("Should parse 2 mins remaining", 2, order!!.remainingMinutes)
        assertTrue("isNear should be true for 2 min warning", order.isNear)
        assertFalse("isArrived MUST be false for 2 min warning", order.isArrived)
        assertFalse("Status text should not falsely say doorstep", order.status.contains("doorstep", ignoreCase = true))
        assertEquals("Arriving in 2m", order.status)
    }

    @Test
    fun testActualArrivalTriggersArrivedStateAndExtractsOtp() {
        val notif = createMockNotification("Biryani Blues", "Captain has arrived at your doorstep! Share OTP 4821 with rider")
        val order = DeliveryParser.parse("in.swiggy.android", notif, "swiggy_arrival_test")
        assertNotNull(order)

        assertTrue("isArrived should be true when captain at doorstep", order!!.isArrived)
        assertFalse("isNear should be false when arrived", order.isNear)
        assertEquals("OTP should be cleanly extracted", "4821", order.otp)
        assertEquals("At your location!", order.status)
    }

    @Test
    fun testDismissingOneSwiggyOrderLeavesTheOtherActive() {
        val notif1 = createMockNotification("Truffles Burgers", "Arriving in 20 mins")
        val order1 = DeliveryParser.parse("in.swiggy.android", notif1, "key_order_1")!!

        val notif2 = createMockNotification("Swiggy Instamart", "Arriving in 5 mins")
        val order2 = DeliveryParser.parse("in.swiggy.android", notif2, "key_order_2")!!

        DeliveryNotificationListenerService.updateOrder(order1)
        DeliveryNotificationListenerService.updateOrder(order2)

        assertEquals(2, DeliveryNotificationListenerService.getActiveOrdersList().size)

        // Dismiss Order 1 only
        DeliveryNotificationListenerService.activeOrders.remove(order1.key)

        val remaining = DeliveryNotificationListenerService.getActiveOrdersList()
        assertEquals(1, remaining.size)
        assertEquals("Swiggy Instamart", remaining[0].title)
        assertEquals(5, remaining[0].remainingMinutes)
    }

    @Test
    fun testDeliveredNotificationMarksOrderInactive() {
        val notif = createMockNotification("Truffles Burgers", "Order delivered to you. Enjoy your meal!")
        val order = DeliveryParser.parse("in.swiggy.android", notif, "key_delivered")
        assertNotNull(order)
        assertFalse("Delivered order should be marked inactive", order!!.isActive)
        assertEquals("No active orders", order.status)
    }
}
