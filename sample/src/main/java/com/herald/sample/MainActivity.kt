package com.herald.sample

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.herald.sdk.Herald
import com.herald.sdk.SubscriptionChannel
import com.herald.sdk.SubscriptionState
import com.herald.ui.ContentCardFeed

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Herald.handleNotificationIntent(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                LumenApp(onEnablePush = { Herald.requestPushAuthorization(this) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Herald.handleNotificationIntent(intent)
    }

    @Deprecated("Herald.requestPushAuthorization uses the platform permission API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != Herald.PUSH_PERMISSION_REQUEST_CODE) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Herald.setSubscription(SubscriptionChannel.PUSH, if (granted) SubscriptionState.OPTED_IN else SubscriptionState.UNSUBSCRIBED)
    }
}

private enum class Tab(val label: String) { Home("Home"), Settings("Settings"), Preview("Preview") }

@Composable
private fun LumenApp(onEnablePush: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val tabs = Tab.entries.filter { it != Tab.Preview || previewScreen != null }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = {}, label = { Text(t.label) })
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Home -> HomeScreen()
                Tab.Settings -> SettingsScreen(onEnablePush)
                Tab.Preview -> previewScreen?.invoke()
            }
        }
    }
}

@Composable
private fun HomeScreen() {
    Column {
        Text("Lumen", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp))
        Button(
            onClick = { Herald.track("workout_completed", mapOf("minutes" to 32)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text("Log workout") }
        ContentCardFeed()
    }
}

@Composable
private fun SettingsScreen(onEnablePush: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = onEnablePush, modifier = Modifier.fillMaxWidth()) { Text("Enable push notifications") }
        Button(onClick = { Herald.identify("u_demo") }, modifier = Modifier.fillMaxWidth()) { Text("Identify as u_demo") }
        OutlinedButton(onClick = { Herald.reset() }, modifier = Modifier.fillMaxWidth()) { Text("Reset") }
        Text("Endpoint: ${BuildConfig.HERALD_ENDPOINT}", style = MaterialTheme.typography.bodySmall)
    }
}
