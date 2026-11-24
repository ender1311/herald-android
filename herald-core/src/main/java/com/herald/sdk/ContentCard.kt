package com.herald.sdk

enum class ContentCardType(internal val wire: String) {
    CLASSIC("classic"),
    CAPTIONED_IMAGE("captioned_image"),
    IMAGE_ONLY("image_only"),
}

/** A content card synced from Herald. Timestamps are epoch milliseconds. */
data class ContentCard(
    val id: String,
    val type: ContentCardType,
    val title: String?,
    val body: String?,
    val imageUrl: String?,
    val cta: String?,
    val url: String?,
    val extras: Map<String, Any?>,
    val pinned: Boolean,
    val dismissible: Boolean,
    val categories: List<String>,
    val createdAt: Long,
    val expiresAt: Long?,
    val viewed: Boolean,
    val clicked: Boolean,
    val dismissed: Boolean,
    val decisionId: String?,
    /** A video to play in place of the image; [imageUrl] then holds its poster. */
    val videoUrl: String? = null,
    /** The video's poster (the same as [imageUrl]). */
    val videoPosterUrl: String? = null,
    /** Play the video muted and looped while the card is on screen. */
    val videoAutoplay: Boolean = false,
    /** Who made the stock photo or video, for apps that show a credit. */
    val credit: MediaCredit? = null,
)

/** The photographer or creator of a stock photo or video; the links already carry Herald's referral parameters. */
data class MediaCredit(
    val name: String,
    val profileUrl: String?,
    /** The photo or video on its provider's site. */
    val sourceUrl: String?,
)
