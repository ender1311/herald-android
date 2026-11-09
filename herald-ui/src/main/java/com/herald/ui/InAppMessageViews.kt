package com.herald.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.herald.sdk.InAppButton
import com.herald.sdk.InAppMessage
import com.herald.sdk.InAppStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What a message view can do. Default and custom views report every user action through it so Herald logs
 * the interaction and frees the screen.
 */
interface InAppMessageActions {
    /** Logs a button click and performs its action (opens the deep link or URL), then dismisses. */
    fun buttonClicked(button: InAppButton)

    /** Logs a click on the message body (no button), opens [url] when given, then dismisses. */
    fun bodyClicked(url: String? = null)

    /** Logs a click with a button id without dismissing (the HTML bridge's `herald.click(id)`). */
    fun clicked(buttonId: String)

    /** Takes the message down. A dismiss is logged only when nothing was clicked. */
    fun dismiss()
}

/** Slideups come from the bottom unless the message says "top". */
internal val InAppMessage.slidesFromTop: Boolean get() = content.slideFrom.equals("top", ignoreCase = true)

/** Message colors after applying the message's style overrides on top of the theme. */
internal data class MessageColors(
    val background: Color,
    val text: Color,
    val button: Color,
    val buttonText: Color,
    val cornerRadius: Dp,
)

/** Parses "#RRGGBB" or "#AARRGGBB"; null (use the theme) when absent or invalid. */
internal fun parseHexColor(hex: String?): Color? =
    hex?.let { runCatching { Color(AndroidColor.parseColor(it.trim())) }.getOrNull() }

@Composable
internal fun messageColors(style: InAppStyle, theme: HeraldTheme): MessageColors {
    val colors = theme.colors()
    return MessageColors(
        background = parseHexColor(style.backgroundColor) ?: colors.surface,
        text = parseHexColor(style.textColor) ?: colors.onSurface,
        button = parseHexColor(style.buttonColor) ?: colors.accent,
        buttonText = parseHexColor(style.buttonTextColor) ?: colors.onAccent,
        cornerRadius = style.cornerRadius?.dp ?: theme.cornerRadius,
    )
}

@Composable
private fun MessageText(
    message: InAppMessage,
    colors: MessageColors,
    theme: HeraldTheme,
    align: TextAlign,
    headerInset: Dp = 0.dp,
) {
    message.content.header?.let {
        Text(
            it,
            style = theme.typography.title,
            color = colors.text,
            textAlign = align,
            modifier = Modifier.fillMaxWidth().padding(horizontal = headerInset),
        )
    }
    message.content.body?.let {
        if (message.content.header != null) Spacer(Modifier.height(6.dp))
        Text(it, style = theme.typography.body, color = colors.text, textAlign = align, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun MessageButtons(message: InAppMessage, colors: MessageColors, theme: HeraldTheme, actions: InAppMessageActions) {
    if (message.content.buttons.isEmpty()) return
    Spacer(Modifier.height(theme.spacing))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message.content.buttons.forEachIndexed { index, button ->
            val primary = index == 0
            Button(
                onClick = { actions.buttonClicked(button) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(colors.cornerRadius),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (primary) colors.button else Color.Transparent,
                    contentColor = if (primary) colors.buttonText else colors.text,
                ),
            ) { Text(button.text, style = theme.typography.button) }
        }
    }
}

@Composable
private fun CloseButton(tint: Color, actions: InAppMessageActions, modifier: Modifier = Modifier) {
    IconButton(onClick = actions::dismiss, modifier = modifier.size(48.dp)) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.herald_close), tint = tint)
    }
}

/** A banner at the top or bottom edge. Tap opens the first button's action; swipe sideways to dismiss. */
@Composable
fun SlideupMessageView(message: InAppMessage, actions: InAppMessageActions, theme: HeraldTheme) {
    val colors = messageColors(message.style, theme)
    val dismissLabel = stringResource(R.string.herald_dismiss)
    var dragX by remember { mutableFloatStateOf(0f) }
    val first = message.content.buttons.firstOrNull()
    // Only the edge the banner sits on: the view is wrap-content at that edge.
    val edge = if (message.slidesFromTop) WindowInsetsSides.Top else WindowInsetsSides.Bottom
    val insets = WindowInsets.safeDrawing.only(edge + WindowInsetsSides.Horizontal)
    Box(Modifier.fillMaxWidth().windowInsetsPadding(insets).padding(theme.spacing / 2)) {
        Column(
            Modifier
                .offset { IntOffset(dragX.roundToInt(), 0) }
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(colors.cornerRadius))
                .background(colors.background)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (abs(dragX) > size.width / 3f) actions.dismiss() else dragX = 0f },
                        onDragCancel = { dragX = 0f },
                    ) { _, delta -> dragX += delta }
                }
                .clickable(role = Role.Button) { if (first != null) actions.buttonClicked(first) else actions.dismiss() }
                .semantics { customActions = listOf(CustomAccessibilityAction(dismissLabel) { actions.dismiss(); true }) }
                .padding(theme.spacing),
        ) {
            MessageText(message, colors, theme, TextAlign.Start)
            first?.let {
                Spacer(Modifier.height(4.dp))
                Text(it.text, style = theme.typography.button, color = colors.text)
            }
        }
    }
}

/** A centered dialog over a scrim, with optional image, header, body and buttons. Tapping the scrim dismisses. */
@Composable
fun ModalMessageView(message: InAppMessage, actions: InAppMessageActions, theme: HeraldTheme) {
    val colors = messageColors(message.style, theme)
    Box(
        Modifier
            .fillMaxSize()
            .background(theme.colors().scrim)
            // Tap gestures, not clickable: no unlabeled full-screen node for TalkBack (Close and back still work).
            .pointerInput(Unit) { detectTapGestures { actions.dismiss() } }
            .safeDrawingPadding()
            .padding(theme.spacing * 1.5f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(colors.cornerRadius))
                .background(colors.background)
                // Swallows taps so they do not reach the scrim.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                message.content.imageUrl?.let {
                    CardImage(it, description = null, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                }
                Column(Modifier.padding(theme.spacing * 1.5f), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Keeps a long centered header clear of the 48 dp close button.
                    MessageText(message, colors, theme, TextAlign.Center, headerInset = 32.dp)
                    MessageButtons(message, colors, theme, actions)
                }
            }
            CloseButton(colors.text, actions, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** Covers the screen: image on top, header, body, buttons, and a close button. */
@Composable
fun FullScreenMessageView(message: InAppMessage, actions: InAppMessageActions, theme: HeraldTheme) {
    val colors = messageColors(message.style, theme)
    Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            message.content.imageUrl?.let {
                CardImage(it, description = null, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
            }
            Column(Modifier.padding(theme.spacing * 1.5f)) {
                Spacer(Modifier.height(if (message.content.imageUrl == null) 48.dp else 0.dp))
                MessageText(message, colors, theme, TextAlign.Start)
                MessageButtons(message, colors, theme, actions)
            }
        }
        CloseButton(colors.text, actions, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * Renders `content.html` in a WebView. JavaScript is on for this view only, with a `herald` bridge:
 * `herald.close()` dismisses and `herald.click(id)` logs a button click. File and content access are off,
 * and a link the user taps goes to the system (logged as a click, then the message closes). Navigations
 * without a tap (redirects, refreshes, frames) are blocked. If the renderer dies, the message closes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlMessageView(message: InAppMessage, actions: InAppMessageActions, theme: HeraldTheme) {
    val colors = messageColors(message.style, theme)
    val background = colors.background.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxSize().background(colors.background).safeDrawingPadding(),
        factory = { context ->
            // WebView throws while its package is missing or updating: show nothing and close the message.
            val webView = try {
                WebView(context)
            } catch (e: Exception) {
                Log.w("Herald", "WebView unavailable; HTML message closed", e)
                return@AndroidView View(context).also { it.post { actions.dismiss() } }
            }
            webView.apply {
                setBackgroundColor(background)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // Redirects, meta refreshes and frame loads are blocked silently; only a tap is a click.
                        if (isUserNavigation(request.isForMainFrame, request.hasGesture())) {
                            actions.bodyClicked(request.url.toString())
                        }
                        return true
                    }

                    // Without this, a renderer crash or kill takes the host app down with it.
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        Log.w("Herald", "HTML message renderer gone; message closed")
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.tag = DESTROYED
                        view.destroy()
                        view.post { actions.dismiss() }
                        return true
                    }
                }
                addJavascriptInterface(HtmlBridge(this, actions), "herald")
                loadDataWithBaseURL(null, message.content.html.orEmpty(), "text/html", "utf-8", null)
            }
        },
        onRelease = { if (it is WebView && it.tag !== DESTROYED) it.destroy() },
    )
}

private val DESTROYED = Any()

/** Only a main-frame navigation the user started (a link tap) counts as a click and leaves the app. */
internal fun isUserNavigation(isMainFrame: Boolean, hasGesture: Boolean) = isMainFrame && hasGesture

/** The `herald` object the HTML message's JavaScript sees. Calls arrive on a WebView thread. */
private class HtmlBridge(private val view: WebView, private val actions: InAppMessageActions) {
    @JavascriptInterface
    fun close() {
        view.post { actions.dismiss() }
    }

    @JavascriptInterface
    fun click(id: String?) {
        val buttonId = id?.takeIf { it.isNotBlank() } ?: return
        view.post { actions.clicked(buttonId) }
    }
}
