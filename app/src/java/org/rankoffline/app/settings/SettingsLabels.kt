package org.rankoffline.app

import androidx.compose.runtime.*
import androidx.compose.ui.res.*

/** User and pack-authored labels are already plain text; never guess a media type. */
@Composable
internal fun localizedCategoryName(category: RatingCategory): String = category.name

@Composable
internal fun localizedCategoryDescription(category: RatingCategory): String = category.description

@Composable
internal fun localizedProfileName(profile: RatingProfile): String = profile.name

@Composable
internal fun localizedBonusName(bonus: AdditiveBonus): String =
    if (bonus.name == AppRatingRules.additiveBonus.name)
        stringResource(R.string.bonus_personal_taste)
    else bonus.name

@Composable
internal fun CatalogSortOrder.criterionLabel(): String = when (this) {
    CatalogSortOrder.POPULARITY,
    CatalogSortOrder.POPULARITY_REVERSE -> stringResource(R.string.sort_popularity)
    CatalogSortOrder.TITLE_ASC,
    CatalogSortOrder.TITLE_DESC -> stringResource(R.string.sort_title_ascending)
}

@Composable
internal fun RankingSortOrder.localizedLabel(): String = stringResource(
    when (this) {
        RankingSortOrder.FINAL_SCORE -> R.string.sort_final_score
        RankingSortOrder.QUALITY_SCORE -> R.string.sort_quality_score
        RankingSortOrder.PERSONAL_TASTE -> R.string.sort_personal_taste
        RankingSortOrder.CATEGORY_SCORE -> R.string.sort_category_score
    }
)

@Composable
internal fun rankingSortLabel(
    sortOrder: RankingSortOrder,
    categoryId: String?,
    categories: List<RatingCategory>
): String {
    if (sortOrder != RankingSortOrder.CATEGORY_SCORE) return sortOrder.localizedLabel()
    val category = categories.firstOrNull { it.id == categoryId }
    return category?.let { localizedCategoryName(it) } ?: stringResource(R.string.sort_category_score)
}

