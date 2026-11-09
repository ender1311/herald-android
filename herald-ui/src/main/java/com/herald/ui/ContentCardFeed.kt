package com.herald.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.herald.sdk.ContentCard
import com.herald.sdk.ContentCardType
import com.herald.sdk.Herald

/** Default ordering and building blocks for [ContentCardFeed]. */
object ContentCardFeedDefaults {
    /** Pinned cards first, then newest first. */
    val Order: Comparator<ContentCard> =
        compareByDescending<ContentCard> { it.pinned }.thenByDescending { it.createdAt }

    /** Opens the card's url or deep link with ACTION_VIEW. */
    val OpenUrl: (ContentCard) -> Unit = { card -> card.url?.let(Herald::openUrl) }

    /** Default card for [card], chosen by its type. */
    @Composable
    fun Card(card: ContentCard, onClick: () -> Unit) {
        when (card.type) {
            ContentCardType.CLASSIC -> ClassicContentCard(card, onClick)
            ContentCardType.CAPTIONED_IMAGE -> CaptionedImageContentCard(card, onClick)
            ContentCardType.IMAGE_ONLY -> ImageOnlyContentCard(card, onClick)
        }
    }
}

/** Sorts and filters cards for the feed. Pure, so it is unit tested. */
internal fun feedCards(cards: List<ContentCard>, filter: (ContentCard) -> Boolean, order: Comparator<ContentCard>) =
    cards.filter(filter).sortedWith(order)

/** A card counts as seen when at least half of it is on screen. */
internal fun isImpression(visibleHeight: Float, fullHeight: Int) = fullHeight > 0 && visibleHeight / fullHeight >= 0.5f

/**
 * The current user's content cards from [Herald.contentCards], pinned first then newest.
 * Impressions are logged once per card when at least half of it is visible; clicks are logged and then
 * passed to [onCardClick]; dismissible cards can be swiped away (or dismissed with a TalkBack action),
 * which logs the dismissal.
 *
 * @param filter keeps only the cards it returns true for.
 * @param order sort order; [ContentCardFeedDefaults.Order] by default.
 * @param onCardClick runs after the click is logged; opens the card url by default.
 * @param emptyContent shown when there are no cards.
 * @param cardContent draws one card. Call `onClick` when the card is tapped. Use
 *   [ContentCardFeedDefaults.Card] or the per-type cards for types you do not replace.
 */
@Composable
fun ContentCardFeed(
    modifier: Modifier = Modifier,
    filter: (ContentCard) -> Boolean = { true },
    order: Comparator<ContentCard> = ContentCardFeedDefaults.Order,
    onCardClick: (ContentCard) -> Unit = ContentCardFeedDefaults.OpenUrl,
    contentPadding: PaddingValues = PaddingValues(LocalHeraldTheme.current.spacing),
    emptyContent: @Composable () -> Unit = { ContentCardFeedEmpty() },
    cardContent: @Composable (card: ContentCard, onClick: () -> Unit) -> Unit = { card, onClick ->
        ContentCardFeedDefaults.Card(card, onClick)
    },
) {
    val theme = LocalHeraldTheme.current
    val colors = theme.colors()
    val all by Herald.contentCards.collectAsState()
    val cards = remember(all, filter, order) { feedCards(all, filter, order) }
    val impressed = remember { mutableSetOf<String>() }

    if (cards.isEmpty()) {
        Box(modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) { emptyContent() }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.background),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(theme.spacing * 0.75f),
    ) {
        items(cards, key = { it.id }) { card ->
            val onClick = {
                Herald.logClick(card)
                onCardClick(card)
            }
            val tracked = Modifier.onGloballyPositioned { coordinates ->
                if (card.id !in impressed && isImpression(coordinates.boundsInWindow().height, coordinates.size.height)) {
                    impressed += card.id
                    Herald.logImpression(card)
                }
            }
            if (card.dismissible) {
                DismissibleCard(card, tracked) { cardContent(card, onClick) }
            } else {
                Box(tracked) { cardContent(card, onClick) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DismissibleCard(card: ContentCard, modifier: Modifier, content: @Composable () -> Unit) {
    val dismissLabel = stringResource(R.string.herald_dismiss)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) Herald.logDismissal(card)
            value != SwipeToDismissBoxValue.Settled
        },
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {},
        modifier = modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(dismissLabel) { Herald.logDismissal(card); true })
        },
    ) { content() }
}

@Composable
private fun ContentCardFeedEmpty() {
    val theme = LocalHeraldTheme.current
    Text(
        stringResource(R.string.herald_feed_empty),
        style = theme.typography.body,
        color = theme.colors().onSurfaceVariant,
        modifier = Modifier.padding(theme.spacing),
    )
}

/** Shared frame of the default cards: surface, corner radius, border, 48 dp minimum height, button role. */
@Composable
private fun CardFrame(card: ContentCard, onClick: () -> Unit, content: @Composable () -> Unit) {
    val theme = LocalHeraldTheme.current
    val colors = theme.colors()
    val shape = RoundedCornerShape(theme.cornerRadius)
    val clickable = card.url != null
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outline, shape)
            .then(if (clickable) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) { content() }
}

@Composable
private fun CardText(card: ContentCard) {
    val theme = LocalHeraldTheme.current
    val colors = theme.colors()
    Column(Modifier.padding(theme.spacing)) {
        card.title?.let { Text(it, style = theme.typography.title, color = colors.onSurface) }
        card.body?.let {
            if (card.title != null) Spacer(Modifier.height(4.dp))
            Text(it, style = theme.typography.body, color = colors.onSurfaceVariant)
        }
        card.cta?.let {
            Spacer(Modifier.height(theme.spacing / 2))
            Text(it, style = theme.typography.button, color = colors.accent)
        }
    }
}

/** Title, body and call to action. */
@Composable
fun ClassicContentCard(card: ContentCard, onClick: () -> Unit) {
    CardFrame(card, onClick) { CardText(card) }
}

/** Image on top, then title, body and call to action. */
@Composable
fun CaptionedImageContentCard(card: ContentCard, onClick: () -> Unit) {
    CardFrame(card, onClick) {
        Column {
            CardImage(card.imageUrl, description = null, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            CardText(card)
        }
    }
}

/**
 * Image only. TalkBack reads the card title, or `extras["alt"]` when the card has no title.
 */
@Composable
fun ImageOnlyContentCard(card: ContentCard, onClick: () -> Unit) {
    val description = card.title ?: card.extras["alt"] as? String
    CardFrame(card, onClick) {
        CardImage(card.imageUrl, description, Modifier.fillMaxWidth().aspectRatio(2f))
    }
}

/** Loads [url] at the drawn width; shows a flat placeholder while loading or when it fails. */
@Composable
internal fun CardImage(url: String?, description: String?, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val placeholder = LocalHeraldTheme.current.colors().outline
    BoxWithConstraints(modifier.background(placeholder)) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val image = rememberHeraldImage(url, widthPx)
        val semantics = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
        if (image != null) {
            Image(image, contentDescription = description, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Color.Transparent).then(semantics))
        }
    }
}
