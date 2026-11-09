package org.rankoffline.app

import android.content.*
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*

/** App information, plus attribution declared by installed packs (never guessed by media type). */
@Composable
internal fun LicenceAboutScreen(installedManifests: List<PackManifest>, onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context.packageName) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }.getOrDefault("")
    }
    SettingsSubscreen(stringResource(R.string.settings_about_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = floatingNavigationContentPadding(bottom = 24.dp)
        ) {
            item {
                AboutCard("RankOffline", MaterialTheme.colorScheme.onSurface) {
                    Text(stringResource(R.string.about_version, version), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AboutBody(stringResource(R.string.about_summary_multimedia))
                    AboutBody(stringResource(R.string.about_rankoffline_licence))
                    AttributionLink(stringResource(R.string.about_source_code), RankOfflineLegalInfo.SOURCE_CODE_URL)
                }
            }
            items(installedManifests.distinctBy { it.packId }.sortedBy { it.displayName }, key = { it.packId.value }) { manifest ->
                AboutCard(manifest.displayName, MaterialTheme.colorScheme.onSurface) {
                    val attribution = manifest.attribution
                    AboutBody(attribution.publisher.name)
                    attribution.sources.forEach { source ->
                        Text(source.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        source.url?.let { PackAttributionUrl(source.name, it) }
                        source.notices.forEach { AboutBody(it) }
                    }
                    attribution.licences.forEach { licence ->
                        AboutBody(licence.name)
                        licence.url?.let { PackAttributionUrl(licence.name, it) }
                    }
                    attribution.datasets.forEach { dataset ->
                        Text(dataset.name, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 8.dp))
                        dataset.notices.forEach { AboutBody(it) }
                    }
                    attribution.notices.forEach { AboutBody(it) }
                }
            }
            item {
                AboutCard(stringResource(R.string.about_open_source_software), MaterialTheme.colorScheme.onSurface) {
                    AboutBody(stringResource(R.string.about_software_licences))
                    AttributionLink("GPL-3.0", RankOfflineLegalInfo.GPL_URL)
                    AttributionLink("Apache-2.0", RankOfflineLegalInfo.APACHE_URL)
                }
            }
            item {
                AboutCard(stringResource(R.string.about_legal_trademarks), MaterialTheme.colorScheme.onSurface) {
                    AboutBody(stringResource(R.string.about_non_affiliation))
                    AboutBody(stringResource(R.string.about_rankoffline_trademarks))
                    AttributionLink(
                        stringResource(R.string.about_legal_trademarks),
                        RankOfflineLegalInfo.TRADEMARKS_URL
                    )
                }
            }
        }
    }
}

/** Pack-supplied URLs are untrusted metadata, so render them as text, not as trusted app links. */
@Composable
private fun PackAttributionUrl(label: String, url: String) {
    Text("$label: $url", color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun DonationSettingsScreen(onBack: () -> Unit) {
    SettingsSubscreen(stringResource(R.string.settings_donation_title), onBack) {
        val bottomClearance = LocalFloatingNavigationClearance.current
        Column(
            Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + bottomClearance),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AboutCard(stringResource(R.string.settings_donation_title), MaterialTheme.colorScheme.primary) {
                AboutBody(stringResource(R.string.settings_donation_body))
            }
        }
    }
}

@Composable
private fun AboutCard(
    title: String,
    titleColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = titleColor)
            content()
        }
    }
}

@Composable
private fun AboutBody(text: String, topPadding: androidx.compose.ui.unit.Dp = 8.dp) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = topPadding)
    )
}

@Composable
private fun AttributionLink(label: String, url: String) {
    val context = LocalContext.current
    TextButton(
        onClick = { openTrustedUrl(context, url) },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp).size(14.dp)
        )
    }
}

private fun openTrustedUrl(context: Context, url: String) {
    if (url !in RankOfflineLegalInfo.trustedUrls) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    runCatching { context.startActivity(intent) }
}
