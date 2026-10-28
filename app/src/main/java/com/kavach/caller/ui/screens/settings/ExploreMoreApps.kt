package com.kavach.caller.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * "Explore more apps by us" - a cross-promotion list shown at the end of Settings.
 *
 * The list is generated per app and never contains the app it ships in, so a user
 * inside this app can only ever be sent to a *different* app of ours.
 * Tapping a row opens the app's page (or its APK download) in the browser.
 */
data class ExploreApp(
    val name: String,
    val tagline: String,
    val url: String,
    val action: String,
)

object ExploreApps {
    val all: List<ExploreApp> = listOf(
        ExploreApp(
            name = "Chronos",
            tagline = "Exact alarms with holiday skipping, world clock, timer presets, lap stopwatch",
            url = "https://github.com/merry8989-uk/chronos-clock/releases/latest/download/app-release.apk",
            action = "Download APK",
        ),
        ExploreApp(
            name = "Universal AI Calculator",
            tagline = "Standard, scientific, unit converter, programmer, date tools + 47 calculators",
            url = "https://github.com/merry8989-uk/universal-ai-calculator/releases/latest/download/app-release.apk",
            action = "Download APK",
        ),
        ExploreApp(
            name = "ProGallery",
            tagline = "Full-featured gallery: MediaStore indexing, duplicate detection, photo/video editing",
            url = "https://github.com/merry8989-uk/pro-gallery-android/releases/download/v1.60.0/pro-gallery-debug.apk",
            action = "Download APK",
        ),
        ExploreApp(
            name = "Low Distraction Launcher",
            tagline = "Minimal text-only launcher, no icons, no feeds, no internet access",
            url = "https://github.com/merry8989-uk/lowdistraction-launcher/releases/download/v1.39/lowdistraction-v1.39.apk",
            action = "Download APK",
        ),
        ExploreApp(
            name = "FloatScript",
            tagline = "Transparent floating teleprompter for Android",
            url = "https://github.com/merry8989-uk/floatscript/releases/download/v1.3.0/FloatScript-v1.3.0.apk",
            action = "Download APK",
        ),
        ExploreApp(
            name = "Camara",
            tagline = "Camera + editor: filters, stickers, beauty tools, teleprompter, image & video editing",
            url = "https://github.com/merry8989-uk/camara-app/releases/download/1.11.0/camara-1.11.0.apk",
            action = "Download APK",
        ),
        ExploreApp(
            name = "Powerbase",
            tagline = "Self-hosted diagnostics client: dashboards, log explorer, crash groups, devices, alerts",
            url = "https://github.com/merry8989-uk/powerbase-app/releases/latest/download/app-release.apk",
            action = "Download APK",
        ),
        ExploreApp(
            name = "Local AI Keyboard",
            tagline = "Offline privacy-first keyboard with on-device LLM for writing and corrections",
            url = "https://github.com/merry8989-uk/local-ai-keyboard",
            action = "View repo",
        ),
        ExploreApp(
            name = "Arrow Puzzle Advanced",
            tagline = "12,000 guaranteed-solvable arrow puzzles (6,000 Classic + 6,000 Escape)",
            url = "https://github.com/merry8989-uk/arrow-puzzle-advanced/releases/download/v5.11/ArrowPuzzleAdvanced-5.11.apk",
            action = "Download APK",
        ),
        ExploreApp(
            name = "New Arrow Puzzle",
            tagline = "Offline HTML5 arrow-clearing game, 12,000 verified levels, hard mode from 25",
            url = "https://merry8989-uk.github.io/new-arrow-puzzle/",
            action = "Open",
        ),
        ExploreApp(
            name = "PDF Toolkit",
            tagline = "Merge, split, rotate, watermark, convert, protect PDFs in the browser. No uploads",
            url = "https://github.com/merry8989-uk/pdf-toolkit/releases/latest/download/pdf-toolkit-1.1.0.apk",
            action = "Download APK",
        ),
        ExploreApp(
            name = "Doc Viewer & Editor",
            tagline = "Open, edit, convert, compress, merge docs, images, sheets, PDFs, ebooks",
            url = "https://merry8989-uk.github.io/doc-viewer-editor/",
            action = "Open",
        ),
        ExploreApp(
            name = "Dhoondh",
            tagline = "Multi-tab search engine: AI, images, videos, papers, books, PDF, Drive, Archive",
            url = "https://merry8989-uk.github.io/dhoondh/",
            action = "Open",
        ),
        ExploreApp(
            name = "Pulse",
            tagline = "Social feed for text, photos and video. Single-file static site",
            url = "https://merry8989-uk.github.io/pulse-feed/",
            action = "Open",
        ),
        ExploreApp(
            name = "KaamSetu",
            tagline = "Job vacancy portal with employer posting and admin moderation",
            url = "https://merry8989-uk.github.io/kaamsetu-job-portal/",
            action = "Open",
        ),
        ExploreApp(
            name = "48 Laws of Power",
            tagline = "Hindi notes, mindmap, examples and usage for the 48 laws",
            url = "https://merry8989-uk.github.io/48-laws-of-power/",
            action = "Open",
        ),
        ExploreApp(
            name = "On This Day",
            tagline = "Daily bilingual Hindi + English history briefing, static site",
            url = "https://merry8989-uk.github.io/on-this-day/",
            action = "Open",
        ),
        ExploreApp(
            name = "USB Screen Mirror",
            tagline = "Low-latency Android-to-Windows USB mirroring, scrcpy wrapper with GUI",
            url = "https://github.com/merry8989-uk/usb-screen-mirror/releases/latest/download/PhoneMirrorServer.apk",
            action = "Download APK",
        ),
        ExploreApp(
            name = "Sangam",
            tagline = "India-hosted social + video platform on its own stack",
            url = "https://github.com/merry8989-uk/sangam",
            action = "View repo",
        ),
        ExploreApp(
            name = "Powerbase Server",
            tagline = "Self-hosted observability: log ingestion, explorer, traces, metrics, alerting",
            url = "https://github.com/merry8989-uk/powerbase-server",
            action = "View repo",
        ),
    )
}

@Composable
fun ExploreMoreAppsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (ExploreApps.all.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Explore more apps by us",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Other apps we build. Free, no ads, no accounts - same as this one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        ExploreApps.all.forEach { app ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(app.url))
                        runCatching { context.startActivity(intent) }
                    },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = app.tagline,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = app.action,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
