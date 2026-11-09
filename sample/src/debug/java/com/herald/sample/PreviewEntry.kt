package com.herald.sample

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.herald.sdk.Herald
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Debug builds: a screen that loads the shared fixtures so every card and message type shows without a server. */
internal val previewScreen: (@Composable () -> Unit)? = { PreviewScreen() }

@Composable
private fun PreviewScreen() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Herald.debugLoadMessages(previewMessages(context)) }
    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Preview messages", style = MaterialTheme.typography.headlineMedium)
        Text("Loads sdks/spec/fixtures/messages.json. The Home tab shows its content cards.")
        listOf("Slideup" to "msg_5", "Modal" to "msg_2", "Full screen" to FULL_ID, "HTML" to "msg_6").forEach { (label, id) ->
            Button(onClick = { Herald.debugShowInAppMessage(id) }, modifier = Modifier.fillMaxWidth()) { Text("Show $label") }
        }
    }
}

private const val FULL_ID = "msg_full_preview"

/** messages.json with local images (the fixture's example.com URLs do not resolve) and a full-screen message added. */
private fun previewMessages(context: Context): String {
    val image = "android.resource://${context.packageName}/drawable/preview_maya"
    val raw = context.assets.open("messages.json").bufferedReader().use { it.readText() }
        .replace("https://example.com/maya.webp", image)
        .replace("https://example.com/banner.webp", image)
    val root = JSONObject(raw)
    val messages = root.getJSONObject("data").getJSONArray("inAppMessages")
    val modal = (0 until messages.length()).map(messages::getJSONObject).first { it.getString("id") == "msg_2" }
    val full = JSONObject(modal.toString()).put("id", FULL_ID).put("type", "full")
    full.getJSONObject("content").put("imageUrl", image)
    messages.put(full)
    return root.toString()
}

/**
 * Launch-extra hooks for end-to-end runs on an emulator, where nothing is tapped:
 * `--es lumenUser <id>` identifies the user with herald.userToken, `--ez lumenLogWorkout true` logs a
 * workout, `--ez lumenClickFirstCard true` logs a click on the first card once cards arrive.
 */
internal fun debugLaunchHooks(intent: Intent) {
    intent.getStringExtra("lumenUser")?.let { userId ->
        Herald.identify(userId, mapOf("plan" to "free"))
        Herald.setUserToken(BuildConfig.HERALD_USER_TOKEN.ifEmpty { null })
    }
    if (intent.getBooleanExtra("lumenLogWorkout", false)) Herald.track("workout_completed", mapOf("minutes" to 32))
    if (intent.getBooleanExtra("lumenClickFirstCard", false)) {
        CoroutineScope(Dispatchers.Main).launch {
            val card = Herald.contentCards.first { it.isNotEmpty() }.first()
            Herald.logClick(card)
            Herald.flush()
        }
    }
}
