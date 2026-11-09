package com.herald.sample

import android.content.Intent
import androidx.compose.runtime.Composable

/** Release builds have no Preview screen. */
internal val previewScreen: (@Composable () -> Unit)? = null

/** Release builds have no launch hooks. */
@Suppress("UNUSED_PARAMETER")
internal fun debugLaunchHooks(intent: Intent) = Unit
