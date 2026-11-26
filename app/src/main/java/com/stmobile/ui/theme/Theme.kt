package com.stmobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Gold,
    onPrimary = Ink,
    primaryContainer = GoldDim,
    onPrimaryContainer = Parchment,
    secondary = Violet,
    onSecondary = Ink,
    secondaryContainer = ColorTokens.VioletContainer,
    onSecondaryContainer = Parchment,
    tertiary = Ember,
    onTertiary = Ink,
    background = Ink,
    onBackground = Parchment,
    surface = InkElevated,
    onSurface = Parchment,
    surfaceVariant = InkSurface,
    onSurfaceVariant = ParchmentDim,
    outline = InkOutline,
    outlineVariant = ColorTokens.OutlineVariant,
    error = ColorTokens.Error,
    onError = Ink
)

private val LightColors = lightColorScheme(
    primary = GoldLight,
    onPrimary = ParchmentLight,
    primaryContainer = ColorTokens.GoldPale,
    onPrimaryContainer = InkLight,
    secondary = VioletLight,
    onSecondary = ParchmentLight,
    tertiary = Ember,
    background = ParchmentLight,
    onBackground = InkLight,
    surface = ColorTokens.SurfaceLight,
    onSurface = InkLight,
    surfaceVariant = ColorTokens.SurfaceVariantLight,
    onSurfaceVariant = ColorTokens.OnSurfaceVariantLight,
    outline = ColorTokens.OutlineLight,
    error = ColorTokens.Error
)

private object ColorTokens {
    val VioletContainer = androidx.compose.ui.graphics.Color(0xFF3A3350)
    val OutlineVariant = androidx.compose.ui.graphics.Color(0xFF2A2633)
    val Error = androidx.compose.ui.graphics.Color(0xFFCF6679)
    val GoldPale = androidx.compose.ui.graphics.Color(0xFFEBDCBB)
    val SurfaceLight = androidx.compose.ui.graphics.Color(0xFFFBF6EC)
    val SurfaceVariantLight = androidx.compose.ui.graphics.Color(0xFFEDE4D2)
    val OnSurfaceVariantLight = androidx.compose.ui.graphics.Color(0xFF5C5145)
    val OutlineLight = androidx.compose.ui.graphics.Color(0xFFC9BBA2)
}

@Composable
fun TaverniteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = TaverniteTypography,
        content = content
    )
}
