package com.charifmahmoudi.chatgptremote

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.GrantPermissionRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Before
    fun clearState() {
        context.stopService(Intent(context, TunnelService::class.java))
        context.getSharedPreferences("secure_config", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @After
    fun stopService() {
        context.stopService(Intent(context, TunnelService::class.java))
    }

    @Test
    fun firstLaunchShowsTunnelSetupAndStartsForegroundService() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitCondition { ServiceState.current.phase == ServicePhase.NEED_TUNNEL }

            onView(withId(R.id.statusTitle)).check(matches(withText(R.string.status_action_required)))
            onView(withId(R.id.tunnelGroup)).check(matches(isDisplayed()))
            onView(withId(R.id.saveTunnelButton)).check(matches(isDisplayed()))
            onView(withId(R.id.versionText)).check(matches(isDisplayed()))

            val notifications = context.getSystemService(NotificationManager::class.java)
            awaitCondition { notifications.activeNotifications.isNotEmpty() }
            assertEquals("ChatGPT ADB MCP", notifications.activeNotifications.single().notification.extras
                .getString("android.title"))
        }
    }

    @Test
    fun sensitiveWindowFlagIsEnabled() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    activity.window.attributes.flags and
                        android.view.WindowManager.LayoutParams.FLAG_SECURE != 0,
                )
            }
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        assertTrue("Condition was not met before timeout", condition())
    }
}
