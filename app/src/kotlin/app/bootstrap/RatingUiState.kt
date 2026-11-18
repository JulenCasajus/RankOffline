package org.rankoffline.app


/** The editor operates on an item or a group without changing its catalog identity. */
data class RatingEditorState(
    val entry: MediaListEntry,
    val draft: RatingDraft? = null
) {
    val key: CatalogEntryKey get() = entry.key
    val isGroup: Boolean get() = entry.kind == CatalogEntryKind.GROUP
}

internal fun RatingEditorState?.matches(entry: MediaListEntry): Boolean =
    this?.key == entry.key
