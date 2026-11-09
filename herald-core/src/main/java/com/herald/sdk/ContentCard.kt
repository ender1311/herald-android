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
)
