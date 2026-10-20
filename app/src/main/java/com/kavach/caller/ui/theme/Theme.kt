package com.kavach.caller.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Kavach's theme packs (F11.2). */
enum class ThemePack { AURORA, CARBON, DAYLIGHT, NEON_NOIR, DYNAMIC }

private fun auroraScheme() = darkColorScheme(
    primary = AuroraCyan,
    secondary = AuroraViolet,
    tertiary = AuroraMagenta,
    background = AuroraBg,
    surface = AuroraSurface,
    onBackground = AuroraText,
    onSurface = AuroraText,
    onSurfaceVariant = AuroraMuted,
)

private fun carbonScheme() = darkColorScheme(
    primary = CarbonAmber,
    secondary = CarbonAmber,
    background = CarbonBg,
    surface = CarbonSurface,
    onBackground = CarbonText,
    onSurface = CarbonText,
)

private fun noirScheme() = darkColorScheme(
    primary = NoirPink,
    secondary = NoirBlue,
    background = NoirBg,
    surface = NoirSurface,
    onBackground = Color(0xFFF3E9FF),
    onSurface = Color(0xFFF3E9FF),
)

private fun daylightScheme() = lightColorScheme(
    primary = DaylightPrimary,
    background = DaylightBg,
    surface = DaylightSurface,
    onBackground = DaylightText,
    onSurface = DaylightText,
)

@Composable
fun KavachTheme(
    pack: ThemePack = ThemePack.AURORA,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when (pack) {
        ThemePack.AURORA -> auroraScheme()
        ThemePack.CARBON -> carbonScheme()
        ThemePack.NEON_NOIR -> noirScheme()
        ThemePack.DAYLIGHT -> daylightScheme()
        ThemePack.DYNAMIC ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else auroraScheme()
    }
    MaterialTheme(colorScheme = scheme, typography = KavachTypography, content = content)
}
