package dev.fujitransfer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.fujitransfer.R
import java.util.Random

/**
 * The look from the "Fuji Transfer — giao diện mới" canvas: a grainy paper ground, one warm
 * orange accent, Inter Tight for the interface and JetBrains Mono for camera data. Comes in a
 * dark and a light version; the photo viewer stays black in both.
 */
@Immutable
data class FilmColors(
    val dark: Boolean,
    /** Page ground. */
    val paper: Color,
    /** Primary text. */
    val ink: Color,
    /** Secondary text, metadata. */
    val inkSoft: Color,
    /** Borders and dividers. */
    val hairline: Color,
    /** Cards, chips, icon buttons. */
    val film: Color,
    /** Something raised on a card: the chevron circle, the tab indicator. */
    val raised: Color,
    /** Text on an unselected chip. */
    val chipText: Color,
    /** Perforations on the preparing screen's strip. */
    val sprocket: Color,
    /** A photo frame still waiting for its thumbnail. */
    val frameEmpty: Color,
    /** The accent: buttons, rings, progress. */
    val accent: Color,
    /** The accent as text on the page, dark enough to read on a light ground. */
    val accentText: Color,
    /** Text and icons on the accent. */
    val onAccent: Color,
    /** The tab bar. */
    val bar: Color,
    val error: Color,
    val errorBg: Color,
    val errorText: Color,
)

private val Orange = Color(0xFFFF9D28)

private val Dark = FilmColors(
    dark = true,
    paper = Color(0xFF0E0E0E),
    ink = Color(0xFFF2EFEA),
    inkSoft = Color(0xFFA8A49C),
    hairline = Color(0xFF2A2A2A),
    film = Color(0xFF161616),
    raised = Color(0xFF242424),
    chipText = Color(0xFFD9D5CD),
    sprocket = Color(0xFF2A2A2A),
    frameEmpty = Color(0xFF1E1E1E),
    accent = Orange,
    accentText = Orange,
    onAccent = Color(0xFF140C02),
    bar = Color(0xFF141414),
    error = Color(0xFFF09595),
    errorBg = Color(0xFF2A1515),
    errorText = Color(0xFFF7C1C1),
)

private val Light = FilmColors(
    dark = false,
    paper = Color(0xFFF3F0EA),
    ink = Color(0xFF161512),
    inkSoft = Color(0xFF6E6A62),
    hairline = Color(0xFFDAD5CB),
    film = Color(0xFFFFFFFF),
    raised = Color(0xFFE8E3DA),
    chipText = Color(0xFF3B3934),
    sprocket = Color(0xFFDAD5CB),
    frameEmpty = Color(0xFFE4DFD6),
    accent = Orange,
    accentText = Color(0xFFA85400),
    onAccent = Color(0xFF140C02),
    bar = Color(0xFFFAF8F4),
    error = Color(0xFFB3261E),
    errorBg = Color(0xFFF9DEDC),
    errorText = Color(0xFF8C1D18),
)

/** Light, dark, or whatever the phone uses; kept between launches. */
enum class ThemeMode(val label: String) { System("Theo điện thoại"), Light("Sáng"), Dark("Tối") }

/** The chosen [ThemeMode], read once and saved on change. */
object ThemeChoice {
    private const val PREFS = "look"
    private var loaded = false
    var mode by mutableStateOf(ThemeMode.System)
        private set

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val saved = context.getSharedPreferences(PREFS, 0).getString("theme", null)
        mode = ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.System
    }

    fun set(context: Context, m: ThemeMode) {
        mode = m
        context.getSharedPreferences(PREFS, 0).edit().putString("theme", m.name).apply()
    }

    /** Light ↔ dark from what is showing now (the system choice is reachable from the menu). */
    fun toggle(context: Context, darkNow: Boolean) = set(context, if (darkNow) ThemeMode.Light else ThemeMode.Dark)
}

val LocalFilm = staticCompositionLocalOf { Dark }

/** The film grain laid over the page ground. */
val LocalGrain = staticCompositionLocalOf<Brush> { SolidColor(Color.Transparent) }

/**
 * A tile of film grain: specks of light and dark at low opacity, each 2×2 px so it reads as
 * grain rather than screen noise, drawn a little larger again on dense screens.
 */
private fun grain(dark: Boolean, density: Float): Brush {
    val n = 128
    val r = Random(1923)
    val px = IntArray(n * n)
    for (y in 0 until n step 2) for (x in 0 until n step 2) {
        // Most specks faint, a few stronger: closer to film than flat noise.
        val v = r.nextFloat()
        val a = (v * v * if (dark) 16 else 18).toInt()
        val rgb = if (r.nextBoolean()) 0xFFFFFF else 0x000000
        val c = (a shl 24) or rgb
        px[y * n + x] = c; px[y * n + x + 1] = c; px[(y + 1) * n + x] = c; px[(y + 1) * n + x + 1] = c
    }
    val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    val scale = (density / 2f).coerceAtLeast(1f)
    shader.setLocalMatrix(Matrix().apply { setScale(scale, scale) })
    return ShaderBrush(shader)
}

/** The page ground: paper colour with grain on top. */
fun Modifier.paper(): Modifier = composed {
    val f = LocalFilm.current
    val g = LocalGrain.current
    background(f.paper).drawBehind { drawRect(g) }
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Interface and headings. */
val Sans = FontFamily(
    variable(R.font.inter_tight, 400), variable(R.font.inter_tight, 500),
    variable(R.font.inter_tight, 600), variable(R.font.inter_tight, 700),
)

/** Camera data: file names, dates, exposure, counts. */
val Mono = FontFamily(variable(R.font.jetbrains_mono, 400), variable(R.font.jetbrains_mono, 500))

/** Big screen titles. */
val Display = Sans

/** Text and icons on top of a photo, whatever the theme. */
val OnPhoto = Color(0xFFF2EFEA)
val OnPhotoSoft = Color(0xFFC9C5BD)

/** Whether the app is showing its dark version right now. */
@Composable
fun isDarkLook(): Boolean = when (ThemeChoice.mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun FilmTheme(dark: Boolean = isDarkLook(), content: @Composable () -> Unit) {
    val f = if (dark) Dark else Light
    val context = LocalContext.current
    val grain = remember(dark) { grain(dark, context.resources.displayMetrics.density) }
    val container = if (dark) Color(0xFF3A2610) else Color(0xFFFFE2BF)
    val onContainer = if (dark) Orange else Color(0xFF5A2D00)
    val scheme = if (dark) darkColorScheme(
        primary = f.accent, onPrimary = f.onAccent,
        primaryContainer = container, onPrimaryContainer = onContainer,
        secondary = f.accent, onSecondary = f.onAccent,
        secondaryContainer = container, onSecondaryContainer = onContainer,
        background = f.paper, onBackground = f.ink,
        surface = f.paper, onSurface = f.ink, onSurfaceVariant = f.inkSoft,
        surfaceContainerLowest = f.paper, surfaceContainerLow = Color(0xFF121212),
        surfaceContainer = f.film, surfaceContainerHigh = Color(0xFF1C1C1C),
        surfaceContainerHighest = f.raised,
        outline = f.hairline, outlineVariant = f.hairline,
        errorContainer = f.errorBg, onErrorContainer = f.errorText,
    ) else lightColorScheme(
        primary = f.accentText, onPrimary = Color.White,
        primaryContainer = container, onPrimaryContainer = onContainer,
        secondary = f.accentText, onSecondary = Color.White,
        secondaryContainer = container, onSecondaryContainer = onContainer,
        background = f.paper, onBackground = f.ink,
        surface = f.paper, onSurface = f.ink, onSurfaceVariant = f.inkSoft,
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F6F1),
        surfaceContainer = f.film, surfaceContainerHigh = Color(0xFFF0ECE5),
        surfaceContainerHighest = f.raised,
        outline = f.hairline, outlineVariant = f.hairline,
        errorContainer = f.errorBg, onErrorContainer = f.errorText,
    )
    val base = Typography()
    val type = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Sans), displayMedium = base.displayMedium.copy(fontFamily = Sans),
        displaySmall = base.displaySmall.copy(fontFamily = Sans),
        headlineLarge = base.headlineLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontFamily = Sans, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = Sans),
        bodyLarge = base.bodyLarge.copy(fontFamily = Sans), bodyMedium = base.bodyMedium.copy(fontFamily = Sans),
        bodySmall = base.bodySmall.copy(fontFamily = Sans),
        labelLarge = base.labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontFamily = Sans),
        labelSmall = base.labelSmall.copy(fontFamily = Mono, letterSpacing = 0.5.sp),
    )
    CompositionLocalProvider(LocalFilm provides f, LocalGrain provides grain) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

/** Small mono caption, e.g. "ĐÃ KẾT NỐI · 1.822 ẢNH" or "27.10 · 9 ẢNH". */
val captionStyle = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.6.sp)

/** Screen title: "X-S20", "Đã lưu". */
val titleStyle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.6).sp)
