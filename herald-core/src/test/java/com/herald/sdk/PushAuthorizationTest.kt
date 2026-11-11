package com.herald.sdk

import com.herald.sdk.internal.AndroidDeviceContext.Companion.pushAuthorizationValue
import org.junit.Assert.assertEquals
import org.junit.Test

/** D6: Android reports the iOS push authorization names, and not_determined before the 13+ prompt. */
class PushAuthorizationTest {
    @Test
    fun android13NotDeterminedUntilRequested() {
        assertEquals("not_determined", pushAuthorizationValue(35, permissionGranted = false, notificationsEnabled = false, requested = false))
        assertEquals("denied", pushAuthorizationValue(35, permissionGranted = false, notificationsEnabled = false, requested = true))
        assertEquals("authorized", pushAuthorizationValue(33, permissionGranted = true, notificationsEnabled = true, requested = true))
        assertEquals("authorized", pushAuthorizationValue(33, permissionGranted = true, notificationsEnabled = true, requested = false))
        assertEquals("denied", pushAuthorizationValue(33, permissionGranted = true, notificationsEnabled = false, requested = true))
    }

    @Test
    fun beforeAndroid13GrantedUnlessNotificationsAreOff() {
        assertEquals("authorized", pushAuthorizationValue(32, permissionGranted = true, notificationsEnabled = true, requested = false))
        assertEquals("denied", pushAuthorizationValue(26, permissionGranted = true, notificationsEnabled = false, requested = false))
    }
}
