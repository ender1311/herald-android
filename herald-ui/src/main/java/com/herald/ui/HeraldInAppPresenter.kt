package com.herald.ui

import android.app.Activity
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.herald.sdk.ButtonActionType
import com.herald.sdk.Herald
import com.herald.sdk.InAppButton
import com.herald.sdk.InAppMessage
import com.herald.sdk.InAppMessageCallbacks
import com.herald.sdk.InAppMessagePresenter
import com.herald.sdk.InAppMessageType
import kotlinx.coroutines.delay

/** Draws one message type. Report every user action through the [InAppMessageActions]. */
typealias InAppMessageContent = @Composable (message: InAppMessage, actions: InAppMessageActions) -> Unit

/**
 * Herald's default in-app message presenter. It draws messages in an overlay on the resumed activity's
 * window (activities must extend `ComponentActivity`, as every Compose and AppCompat activity does).
 * If the activity is destroyed while a message is up (rotation, finish), the overlay is removed and
 * Herald shows the message again on the next activity.
 *
 * Install it once after `Herald.configure`: `HeraldInAppPresenter.install(theme)`.
 *
 * @param theme colors, type, radius and spacing; style fields on a message override it.
 * @param customViews replaces the default view for a message type.
 */
class HeraldInAppPresenter @JvmOverloads constructor(
    var theme: HeraldTheme = HeraldTheme(),
    var customViews: Map<InAppMessageType, InAppMessageContent> = emptyMap(),
) : InAppMessagePresenter {

    private var current: Session? = null

    override fun present(activity: Activity, message: InAppMessage, callbacks: InAppMessageCallbacks): Boolean {
        if (current != null) return false
        if (activity !is ComponentActivity || activity.isFinishing || activity.isDestroyed) return false
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return false
        val decor = activity.window?.decorView as? ViewGroup ?: return false
        current = Session(activity, decor, message, callbacks)
        return true
    }

    override fun dismissCurrent() {
        current?.tearDown()
    }

    companion object {
        /** Creates a presenter and sets it as [Herald.inAppMessagePresenter]. */
        @JvmStatic
        @JvmOverloads
        fun install(
            theme: HeraldTheme = HeraldTheme(),
            customViews: Map<InAppMessageType, InAppMessageContent> = emptyMap(),
        ): HeraldInAppPresenter = HeraldInAppPresenter(theme, customViews).also { Herald.inAppMessagePresenter = it }

        /** Animations are off when the system animator duration scale is 0 (Remove animations). */
        internal fun reducedMotion(activity: Activity): Boolean =
            Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    /** One message on screen: its overlay view, back handling and lifecycle observer. Main thread only. */
    private inner class Session(
        private val activity: ComponentActivity,
        private val decor: ViewGroup,
        private val message: InAppMessage,
        private val callbacks: InAppMessageCallbacks,
    ) : InAppMessageActions {
        private val blocking = message.type != InAppMessageType.SLIDEUP
        private val reducedMotion = reducedMotion(activity)
        private val visible = MutableTransitionState(reducedMotion).apply { targetState = true }
        private var dismissed = false
        private var removed = false
        private val hiddenSiblings = mutableListOf<Pair<View, Int>>()

        private val back = object : OnBackPressedCallback(blocking) {
            override fun handleOnBackPressed() = dismiss()
        }

        private val lifecycleObserver = LifecycleEventObserver { _, event ->
            // Herald's activity callbacks free the screen and queue the message for the next activity.
            if (event == Lifecycle.Event.ON_DESTROY) tearDown()
        }

        private val view = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { Host() }
        }

        init {
            val params = if (blocking) {
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            } else {
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (message.slidesFromTop) Gravity.TOP else Gravity.BOTTOM,
                )
            }
            if (blocking) {
                // TalkBack stays inside the message while it covers the app.
                for (i in 0 until decor.childCount) {
                    val child = decor.getChildAt(i)
                    hiddenSiblings += child to child.importantForAccessibility
                    child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
            }
            decor.addView(view, params)
            activity.onBackPressedDispatcher.addCallback(activity, back)
            activity.lifecycle.addObserver(lifecycleObserver)
        }

        @Composable
        private fun Host() {
            CompositionLocalProvider(LocalHeraldTheme provides theme) {
                LaunchedEffect(Unit) { callbacks.onImpression() }
                AutoDismiss()
                AnimatedVisibility(visibleState = visible, enter = enter(), exit = exit()) {
                    val block = if (blocking) Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } } else Modifier
                    Box(block) {
                        val content = customViews[message.type] ?: defaultContent(message.type)
                        content(message, this@Session)
                    }
                }
                if (!visible.targetState && visible.isIdle) LaunchedEffect(Unit) { tearDown() }
            }
        }

        @Composable
        private fun AutoDismiss() {
            val seconds = message.content.dismissAfterSeconds ?: return
            if (message.type != InAppMessageType.SLIDEUP || seconds <= 0) return
            val accessibility = LocalAccessibilityManager.current
            val timeout = remember(accessibility) {
                val ms = (seconds * 1000).toLong()
                // Longer (or no) timeout when the user needs it, for example with TalkBack on.
                accessibility?.calculateRecommendedTimeoutMillis(ms, containsIcons = false, containsText = true, containsControls = true) ?: ms
            }
            if (timeout == Long.MAX_VALUE) return
            LaunchedEffect(Unit) {
                delay(timeout)
                dismiss()
            }
        }

        private fun enter(): EnterTransition = when {
            reducedMotion -> EnterTransition.None
            message.type == InAppMessageType.SLIDEUP ->
                slideInVertically { if (message.slidesFromTop) -it else it } + fadeIn()
            message.type == InAppMessageType.MODAL -> fadeIn() + scaleIn(initialScale = 0.92f)
            else -> fadeIn()
        }

        private fun exit(): ExitTransition = when {
            reducedMotion -> ExitTransition.None
            message.type == InAppMessageType.SLIDEUP ->
                slideOutVertically { if (message.slidesFromTop) -it else it } + fadeOut()
            message.type == InAppMessageType.MODAL -> fadeOut() + scaleOut(targetScale = 0.92f)
            else -> fadeOut()
        }

        override fun buttonClicked(button: InAppButton) {
            if (dismissed) return
            callbacks.onClick(button.id)
            if (button.action.type != ButtonActionType.CLOSE) button.action.value?.let(Herald::openUrl)
            dismiss()
        }

        override fun bodyClicked(url: String?) {
            if (dismissed) return
            callbacks.onClick(null)
            url?.let(Herald::openUrl)
            dismiss()
        }

        override fun clicked(buttonId: String) {
            if (!dismissed) callbacks.onClick(buttonId)
        }

        override fun dismiss() {
            if (dismissed) return
            dismissed = true
            back.remove()
            // The screen is free for the next message while this one animates out.
            if (current === this) current = null
            callbacks.onDismiss()
            if (reducedMotion) tearDown() else visible.targetState = false
        }

        /** Removes the overlay without reporting anything. Safe to call more than once. */
        fun tearDown() {
            if (removed) return
            removed = true
            back.remove()
            activity.lifecycle.removeObserver(lifecycleObserver)
            hiddenSiblings.forEach { (child, previous) -> child.importantForAccessibility = previous }
            decor.removeView(view)
            if (current === this) current = null
        }
    }
}

private fun defaultContent(type: InAppMessageType): InAppMessageContent = when (type) {
    InAppMessageType.SLIDEUP -> { message, actions -> SlideupMessageView(message, actions, LocalHeraldTheme.current) }
    InAppMessageType.MODAL -> { message, actions -> ModalMessageView(message, actions, LocalHeraldTheme.current) }
    InAppMessageType.FULL -> { message, actions -> FullScreenMessageView(message, actions, LocalHeraldTheme.current) }
    InAppMessageType.HTML -> { message, actions -> HtmlMessageView(message, actions, LocalHeraldTheme.current) }
}
