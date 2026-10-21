package com.kavach.caller.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Kavach's theme packs (F11.2).
 *
 * Each pack fills the Material 3 roles that Kavach actually draws with, so a
 * screen never falls back to a grey default. Packs marked [isLight] use a light
 * scheme regardless of the system setting.
 */
enum class ThemePack(val label: String, val isLight: Boolean = false) {
    AURORA("Aurora"),
    CARBON("Carbon"),
    NEON_NOIR("Neon Noir"),
    SUNSET("Sunset"),
    EMERALD("Emerald"),
    DAYLIGHT("Daylight", isLight = true),
    SAKURA("Sakura", isLight = true),
    DYNAMIC("Match my wallpaper"),
}

fun themePackFromKey(key: String): ThemePack =
    runCatching { ThemePack.valueOf(key) }.getOrDefault(ThemePack.AURORA)

/** A dark scheme built from one pack's colours. */
private fun darkPack(
    bg: Color,
    surface: Color,
    surfaceHigh: Color,
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    secondary: Color,
    tertiary: Color,
    text: Color,
    muted: Color,
    outline: Color,
    error: Color,
) = darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = text,
    secondary = secondary,
    onSecondary = onPrimary,
    secondaryContainer = surfaceHigh,
    onSecondaryContainer = text,
    tertiary = tertiary,
    onTertiary = onPrimary,
    tertiaryContainer = surfaceHigh,
    onTertiaryContainer = text,
    background = bg,
    onBackground = text,
    surface = surface,
    onSurface = text,
    surfaceVariant = surfaceHigh,
    onSurfaceVariant = muted,
    surfaceContainer = surfaceHigh,
    surfaceContainerHigh = surfaceHigh,
    outline = outline,
    outlineVariant = outline,
    error = error,
    onError = Color(0xFF3A0000),
    errorContainer = Color(0xFF5C1414),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = text,
    inverseOnSurface = bg,
)

private fun lightPack(
    bg: Color,
    surface: Color,
    surfaceHigh: Color,
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    secondary: Color,
    text: Color,
    muted: Color,
    outline: Color,
    error: Color,
) = lightColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = text,
    secondary = secondary,
    onSecondary = onPrimary,
    secondaryContainer = surfaceHigh,
    onSecondaryContainer = text,
    tertiary = secondary,
    onTertiary = onPrimary,
    background = bg,
    onBackground = text,
    surface = surface,
    onSurface = text,
    surfaceVariant = surfaceHigh,
    onSurfaceVariant = muted,
    surfaceContainer = surfaceHigh,
    surfaceContainerHigh = surfaceHigh,
    outline = outline,
    outlineVariant = outline,
    error = error,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = text,
    inverseOnSurface = surface,
)

private fun auroraScheme() = darkPack(
    bg = AuroraBg, surface = AuroraSurface, surfaceHigh = AuroraSurfaceHigh,
    primary = AuroraCyan, onPrimary = AuroraOnCyan, primaryContainer = AuroraCyanContainer,
    secondary = AuroraViolet, tertiary = AuroraMagenta,
    text = AuroraText, muted = AuroraMuted, outline = AuroraOutline, error = AuroraError,
)

private fun carbonScheme() = darkPack(
    bg = CarbonBg, surface = CarbonSurface, surfaceHigh = CarbonSurfaceHigh,
    primary = CarbonAmber, onPrimary = CarbonOnAmber, primaryContainer = CarbonAmberContainer,
    secondary = CarbonAmber, tertiary = Color(0xFFE0E0E0),
    text = CarbonText, muted = CarbonMuted, outline = CarbonOutline, error = CarbonError,
)

private fun noirScheme() = darkPack(
    bg = NoirBg, surface = NoirSurface, surfaceHigh = NoirSurfaceHigh,
    primary = NoirPink, onPrimary = NoirOnPink, primaryContainer = NoirPinkContainer,
    secondary = NoirBlue, tertiary = Color(0xFFB388FF),
    text = NoirText, muted = NoirMuted, outline = NoirOutline, error = NoirError,
)

private fun sunsetScheme() = darkPack(
    bg = SunsetBg, surface = SunsetSurface, surfaceHigh = SunsetSurfaceHigh,
    primary = SunsetCoral, onPrimary = SunsetOnCoral, primaryContainer = SunsetCoralContainer,
    secondary = SunsetTangerine, tertiary = Color(0xFFFF5CA8),
    text = SunsetText, muted = SunsetMuted, outline = SunsetOutline, error = SunsetError,
)

private fun emeraldScheme() = darkPack(
    bg = EmeraldBg, surface = EmeraldSurface, surfaceHigh = EmeraldSurfaceHigh,
    primary = EmeraldMint, onPrimary = EmeraldOnMint, primaryContainer = EmeraldMintContainer,
    secondary = EmeraldLime, tertiary = Color(0xFF57D9E8),
    text = EmeraldText, muted = EmeraldMuted, outline = EmeraldOutline, error = EmeraldError,
)

private fun daylightScheme() = lightPack(
    bg = DaylightBg, surface = DaylightSurface, surfaceHigh = DaylightSurfaceHigh,
    primary = DaylightPrimary, onPrimary = DaylightOnPrimary,
    primaryContainer = DaylightPrimaryContainer, secondary = DaylightSecondary,
    text = DaylightText, muted = DaylightMuted, outline = DaylightOutline, error = DaylightError,
)

private fun sakuraScheme() = lightPack(
    bg = SakuraBg, surface = SakuraSurface, surfaceHigh = SakuraSurfaceHigh,
    primary = SakuraPrimary, onPrimary = SakuraOnPrimary,
    primaryContainer = SakuraPrimaryContainer, secondary = SakuraSecondary,
    text = SakuraText, muted = SakuraMuted, outline = SakuraOutline, error = SakuraError,
)

@Composable
fun KavachTheme(
    pack: ThemePack = ThemePack.AURORA,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme: ColorScheme = when (pack) {
        ThemePack.AURORA -> auroraScheme()
        ThemePack.CARBON -> carbonScheme()
        ThemePack.NEON_NOIR -> noirScheme()
        ThemePack.SUNSET -> sunsetScheme()
        ThemePack.EMERALD -> emeraldScheme()
        ThemePack.DAYLIGHT -> daylightScheme()
        ThemePack.SAKURA -> sakuraScheme()
        ThemePack.DYNAMIC ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                auroraScheme()
            }
    }
    MaterialTheme(colorScheme = scheme, typography = KavachTypography, content = content)
}
