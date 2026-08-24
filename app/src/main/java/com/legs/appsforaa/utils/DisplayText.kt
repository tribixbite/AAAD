package com.legs.appsforaa.utils

import androidx.core.text.HtmlCompat

/**
 * Converts untrusted catalog and repository copy to display-only text.
 *
 * GitHub descriptions are normally plain text, but user-added catalogs can contain tags or HTML
 * entities. Rendering the resulting String prevents markup, links, or styling from reaching the UI.
 */
fun String.toDisplayText(): String =
    HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
