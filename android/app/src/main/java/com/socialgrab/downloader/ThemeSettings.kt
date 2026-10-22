package com.socialgrab.downloader

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

enum class ThemeMode { SISTEMA, CLARO, OSCURO }

data class PaletteColors(
    val primary: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
)

val LightPalette = PaletteColors(
    primary = Color(0xFF7B1FA2),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    text = Color(0xFF000000),
)

val DarkPalette = PaletteColors(
    primary = Color(0xFFAB47BC),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    text = Color(0xFFFFFFFF),
)

fun PaletteColors.toScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White,
        primaryContainer = primary.copy(alpha = 0.18f),
        onPrimaryContainer = text,
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        outline = text.copy(alpha = 0.35f),
    )
}

class ThemeSettings(context: Context) {

    private val prefs = context.getSharedPreferences("theme", Context.MODE_PRIVATE)

    var mode: ThemeMode by mutableStateOf(
        ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) } ?: ThemeMode.SISTEMA,
    )
        private set

    fun selectMode(value: ThemeMode) {
        mode = value
        prefs.edit().putString(KEY_MODE, value.name).apply()
    }

    fun isDark(systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.SISTEMA -> systemDark
        ThemeMode.OSCURO -> true
        ThemeMode.CLARO -> false
    }

    private companion object {
        const val KEY_MODE = "mode"
    }
}
