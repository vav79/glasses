package ru.vasev.glasses.ui.glass

import android.app.UiModeManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.graphics.BlurMaskFilter
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.max

/** Слой «под стеклом»: фон и прокручиваемое содержимое экрана — источники размытия для панелей. */
object GlassLayer {
    const val BACKGROUND = 0f
    const val CONTENT = 1f
}

/** Формы стекла: пилюли для панелей и кнопок, крупные скругления для карточек и пузырей. */
object GlassShapes {
    val pill = RoundedCornerShape(percent = 50)
    val card = RoundedCornerShape(24.dp)
}

/**
 * Окружение стекла на экране: общее состояние размытия, палитра фона и «меньше прозрачности».
 * [denser] — стекло плотнее: настройка приложения или системные «Текст высокой контрастности» /
 * повышенный контраст (Android 14+).
 */
@Immutable
class GlassEnvironment(
    val haze: HazeState,
    val palette: GlassBackgroundPalette,
    val denser: Boolean,
)

val LocalGlass = staticCompositionLocalOf<GlassEnvironment?> { null }

@Composable
fun ProvideGlass(environment: GlassEnvironment, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalGlass provides environment, content = content)

/** Включены ли в системе «Текст высокой контрастности» или повышенный контраст — тогда стекло плотнее. */
@Composable
fun rememberSystemPrefersDenserGlass(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val highTextContrast = runCatching {
            Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
        }.getOrDefault(false)
        val contrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(UiModeManager::class.java).contrast
        } else {
            0f
        }
        highTextContrast || contrast > 0f
    }
}

/**
 * Лёгкое стекло (выбрано на витрине): слабое размытие, прозрачная подложка, яркий блик по краю,
 * тонкая обводка, мягкая тень. Плотность подложки не ниже нужной для контраста 4.5:1 текста [text]
 * против всего, что может оказаться позади ([Contrast.minTintAlpha]).
 *
 * [blur] = true — панель поверх прокручиваемого содержимого: размывает фон и содержимое под собой.
 * false — карточки и пузыри на самом фоне: под ними только гладкий градиент, размытие которого выглядит
 * так же, как он сам, — поэтому без прохода размытия (так прокрутка чата остаётся плавной).
 * На Android 11 и ниже размытия нет — подложка плотнее.
 */
@Composable
fun Modifier.glass(
    shape: Shape = GlassShapes.card,
    tint: Color = MaterialTheme.colorScheme.surface,
    text: Color = MaterialTheme.colorScheme.onSurface,
    blur: Boolean = false,
    elevation: Dp = 6.dp,
): Modifier {
    val env = LocalGlass.current ?: return this.clip(shape).background(tint)
    val alpha = glassAlpha(env, tint, text, overContent = blur)
    val edge = Brush.linearGradient(
        listOf(Color.White.copy(alpha = EDGE), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = EDGE * 0.4f)),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
    val base = this
        // Мягкая тень только снаружи: системная (elevation) и dropShadow просвечивают сквозь стекло и делают его серым.
        .then(if (elevation > 0.dp) Modifier.outerShadow(shape, radius = elevation * 2, offsetY = elevation / 2) else Modifier)
        .clip(shape)
    val blurred = if (blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        base.hazeBlur(
            input = HazeInput.Sources(env.haze),
            style = HazeBlurStyle {
                blurRadius(if (env.denser) BLUR * 1.5f else BLUR)
                noiseFactor(0f)
                colorEffects(emptyList())
                backgroundColor(env.palette.base)
            },
            performanceMode = HazePerformanceMode.Performance,
        )
    } else {
        base
    }
    return blurred
        .background(tint.copy(alpha = alpha))
        .border(0.8.dp, edge, shape)
}

/**
 * Акцентный цвет (обычно primary из обоев) на стекле: у некоторых палитр он недостаточно контрастен
 * даже на сплошной подложке — тогда подтемняется к [text] ровно до 4.5:1.
 */
@Composable
fun glassAccent(
    accent: Color = MaterialTheme.colorScheme.primary,
    tint: Color = MaterialTheme.colorScheme.surface,
    text: Color = MaterialTheme.colorScheme.onSurface,
    overContent: Boolean = false,
): Color {
    val env = LocalGlass.current ?: return accent
    val behind = behind(env, overContent)
    val alpha = glassAlpha(env, tint, text, overContent)
    val k = Contrast.minMixToward(rgb(accent), rgb(text), rgb(tint), alpha.toDouble(), behind)
    return lerp(accent, text, k.toFloat())
}

@Composable
private fun glassAlpha(env: GlassEnvironment, tint: Color, text: Color, overContent: Boolean): Float {
    val behind = behind(env, overContent)
    // Цветное стекло (собеседник / я, ошибка) несёт смысл цветом — оно плотнее нейтрального, иначе
    // на тёмном фоне оттенки не различить.
    val colored = tint != MaterialTheme.colorScheme.surface
    // Расчёт контраста — перебор, поэтому запоминается: пузыри чата не пересчитывают его при прокрутке.
    return remember(env, tint, text, behind, colored) {
        val floor = ((if (colored) COLORED_FLOOR else FLOOR) +
            (if (env.denser) DENSER else 0f) +
            (if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) NO_BLUR else 0f)).coerceAtMost(MAX_FLOOR)
        // Потолок — только у «вкусовой» плотности: нужная для контраста 4.5:1 не урезается.
        val needed = Contrast.minTintAlpha(rgb(text), rgb(tint), behind)
        max(floor.toDouble(), needed).toFloat()
    }
}

/** Что может оказаться позади: цвета фона, а под панелями — ещё пузыри и размытый текст. */
@Composable
private fun behind(env: GlassEnvironment, overContent: Boolean): List<Contrast.Rgb> {
    val background = env.palette.all.map(::rgb)
    if (!overContent) return background
    val c = MaterialTheme.colorScheme
    val bubbles = listOf(c.surface, c.primaryContainer, c.tertiaryContainer).map { it.copy(alpha = FLOOR).compositeOver(env.palette.base) }
    val blurredText = bubbles.map { lerp(it, c.onSurface, 0.3f) }
    return background + (bubbles + blurredText).map(::rgb)
}

private fun rgb(color: Color) = Contrast.Rgb.of(color.toArgb())

private val BLUR = 12.dp
private const val FLOOR = 0.14f
private const val COLORED_FLOOR = 0.45f
private const val DENSER = 0.3f
private const val NO_BLUR = 0.25f
private const val MAX_FLOOR = 0.9f
private const val EDGE = 0.6f
private val SHADOW = Color.Black.copy(alpha = 0.14f)

/**
 * Корень «стеклянного» экрана: анимированный фон из цветов обоев (источник размытия [GlassLayer.BACKGROUND])
 * и окружение стекла для всех `Modifier.glass` внутри. [animate] = false — фон застывает
 * (режим перевода; энергосбережение и «Убрать анимацию» учитываются здесь же).
 */
@Composable
fun GlassRoot(denser: Boolean, animate: Boolean, content: @Composable () -> Unit) {
    val haze = rememberHazeState()
    val palette = GlassBackgroundPalette.fromScheme()
    val systemDenser = rememberSystemPrefersDenserGlass()
    val environment = remember(haze, palette, denser, systemDenser) { GlassEnvironment(haze, palette, denser || systemDenser) }
    val motion = animate && rememberBackgroundMotionAllowed()
    // Цвет текста по умолчанию — onSurface: без непрозрачного Surface он был бы чёрным и в тёмной теме.
    ProvideGlass(environment) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Box(Modifier.fillMaxSize()) {
                GlassBackground(
                    Modifier.matchParentSize().hazeSource(haze, zIndex = GlassLayer.BACKGROUND),
                    animate = motion,
                    palette = palette,
                )
                content()
            }
        }
    }
}

/** Прокручиваемое содержимое экрана — источник размытия для панелей поверх него (вкладки, кнопки внизу). */
@Composable
fun Modifier.glassContentSource(): Modifier {
    val env = LocalGlass.current ?: return this
    return this.hazeSource(env.haze, zIndex = GlassLayer.CONTENT)
}

/**
 * Цвет текста прямо на фоне (заголовки, подсказки): второстепенный onSurfaceVariant на пятнах градиента
 * бывает ниже 4.5:1 — тогда он подтемняется (в тёмной теме — осветляется) к onSurface ровно до 4.5:1.
 */
@Composable
fun glassBackgroundText(color: Color = MaterialTheme.colorScheme.onSurfaceVariant): Color {
    val env = LocalGlass.current ?: return color
    val onSurface = MaterialTheme.colorScheme.onSurface
    val behind = env.palette.all.map(::rgb)
    val k = remember(color, onSurface, behind) {
        Contrast.minMixToward(rgb(color), rgb(onSurface), rgb(env.palette.base), 0.0, behind)
    }
    return lerp(color, onSurface, k.toFloat())
}

/**
 * Тень стекла только снаружи формы: размытый контур, из которого вырезана сама форма. Под полупрозрачной
 * подложкой тени нет — иначе стекло выглядело бы грязно-серым.
 * Размытие контура дорогое, поэтому тень рисуется один раз в картинку (при смене размера), а в каждом
 * кадре картинка только копируется — иначе прокрутка чата с десятком пузырей нагружала бы видеокарту.
 */
private fun Modifier.outerShadow(shape: Shape, radius: Dp, offsetY: Dp): Modifier = drawWithCache {
    val blur = radius.toPx()
    val dy = offsetY.toPx()
    val pad = (blur * 2 + dy).toInt() + 1
    val width = size.width.toInt() + pad * 2
    val height = size.height.toInt() + pad * 2
    val image = if (width > 0 && height > 0 && size.width > 0f && size.height > 0f) {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ALPHA_8)
        val canvas = android.graphics.Canvas(bitmap)
        val outline = shape.createOutline(size, layoutDirection, this)
        val inside = Path().apply {
            addOutline(outline)
            translate(Offset(pad.toFloat(), pad.toFloat()))
        }
        val shadow = Path().apply {
            addOutline(outline)
            translate(Offset(pad.toFloat(), pad + dy))
        }
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(shadow.asAndroidPath(), paint)
        // Вырезать саму форму: под стеклом тени нет.
        canvas.drawPath(inside.asAndroidPath(), android.graphics.Paint().apply {
            isAntiAlias = true
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        })
        bitmap.asImageBitmap()
    } else {
        null
    }
    val tint = ColorFilter.tint(SHADOW)
    onDrawBehind {
        image?.let { drawImage(it, topLeft = Offset(-pad.toFloat(), -pad.toFloat()), colorFilter = tint) }
    }
}
