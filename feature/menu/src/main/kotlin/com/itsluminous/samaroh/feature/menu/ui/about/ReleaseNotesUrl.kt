package com.itsluminous.samaroh.feature.menu.ui.about

/**
 * Derives this build's GitHub release-notes URL from the source-repo URL (shared
 * catalog, non-translatable) and the package `versionName`. Releases are tagged
 * `v<versionName>` (see AGENTS.md release process), so the About screen's version row
 * always lands on THIS build's release with zero per-release edits. A blank
 * `versionName` (PackageInfo lookup failed) falls back to the releases list.
 */
fun releaseNotesUrl(
    sourceUrl: String,
    versionName: String,
): String {
    val base = sourceUrl.trimEnd('/')
    return if (versionName.isBlank()) "$base/releases" else "$base/releases/tag/v$versionName"
}
