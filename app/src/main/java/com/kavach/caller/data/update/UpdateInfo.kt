package com.kavach.caller.data.update

/** A newer release published on GitHub, ready to offer to the user. */
data class UpdateInfo(
    /** Version name without the leading "v", e.g. "0.3.0". */
    val versionName: String,
    val notes: String,
    /** Direct APK asset URL, or null if the release has no APK attached. */
    val apkUrl: String?,
    /** Release page, opened in a browser as a fallback. */
    val htmlUrl: String,
    val publishedAt: String,
)
