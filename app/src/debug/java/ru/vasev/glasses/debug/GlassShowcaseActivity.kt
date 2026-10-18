package ru.vasev.glasses.debug

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import ru.vasev.glasses.R
import ru.vasev.glasses.ui.glass.Contrast
import ru.vasev.glasses.ui.glass.GlassBackground
import ru.vasev.glasses.ui.glass.GlassBackgroundPalette
import ru.vasev.glasses.ui.glass.rememberBackgroundMotionAllowed
import ru.vasev.glasses.ui.theme.GlassesTheme
import kotlin.math.max
import kotlin.math.min

/**
 * Витрина вариантов «стеклянного» интерфейса (только debug): четыре варианта рядом на общем фоне,
 * касание — вариант на весь экран. `adb shell am start -n ru.vasev.glasses/.debug.GlassShowcaseActivity`
 */
class GlassShowcaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Showcase() }
    }
}

private enum class Variant(@StringRes val title: Int, @StringRes val desc: Int) {
    LIGHT(R.string.gs_light, R.string.gs_light_desc),
    FROSTED(R.string.gs_frosted, R.string.gs_frosted_desc),
    LIQUID(R.string.gs_liquid, R.string.gs_liquid_desc),
    HYBRID(R.string.gs_hybrid, R.string.gs_hybrid_desc),
}

private enum class ThemeChoice(@StringRes val title: Int) {
    SYSTEM(R.string.gs_theme_system),
    LIGHT(R.string.gs_theme_light),
    DARK(R.string.gs_theme_dark),
}

/** Параметры стекла варианта: размытие, нижняя граница плотности подложки, блик, зерно. */
private data class Spec(val blur: Dp, val floor: Float, val edge: Float, val noise: Float)

private fun spec(variant: Variant, less: Boolean): Spec {
    val base = when (variant) {
        Variant.LIGHT, Variant.HYBRID -> Spec(blur = 12.dp, floor = 0.14f, edge = 0.6f, noise = 0f)
        Variant.FROSTED -> Spec(blur = 30.dp, floor = 0.52f, edge = 0.3f, noise = 0.12f)
        Variant.LIQUID -> Spec(blur = 4.dp, floor = 0.08f, edge = 0.5f, noise = 0f)
    }
    // Без размытия (Android 11 и ниже) подложка должна быть плотнее, иначе за текстом видно фон чётко.
    val noBlur = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) 0.25f else 0f
    return if (less) {
        base.copy(blur = base.blur * 1.5f, floor = (base.floor + 0.3f + noBlur).coerceAtMost(0.92f))
    } else {
        base.copy(floor = (base.floor + noBlur).coerceAtMost(0.92f))
    }
}

/** Всё, что нужно стеклу внутри одного мини-экрана. */
private class GlassEnv(
    val variant: Variant,
    val spec: Spec,
    val haze: HazeState,
    val background: LayerBackdrop,
    val all: Backdrop,
    val palette: GlassBackgroundPalette,
    val dark: Boolean,
)

@Composable
private fun Showcase() {
    var theme by rememberSaveable { mutableStateOf(ThemeChoice.SYSTEM) }
    var less by rememberSaveable { mutableStateOf(false) }
    var animate by rememberSaveable { mutableStateOf(true) }
    var open by rememberSaveable { mutableStateOf<Variant?>(null) }
    val dark = when (theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    val motion = animate && rememberBackgroundMotionAllowed()
    // Иконки строки состояния — по выбранной на витрине теме, а не по системной.
    val activity = LocalActivity.current as? ComponentActivity
    LaunchedEffect(dark) {
        val style = if (dark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    GlassesTheme(dark) {
        BackHandler(enabled = open != null) { open = null }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val current = open
                if (current != null) {
                    Mini(current, less, motion, dark, full = true, onOpen = {})
                    return@Column
                }
                Text(stringResource(R.string.gs_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.gs_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    Text(stringResource(R.string.gs_no_blur, Build.VERSION.SDK_INT), style = MaterialTheme.typography.bodySmall)
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeChoice.entries.forEachIndexed { i, choice ->
                        SegmentedButton(
                            selected = choice == theme,
                            onClick = { theme = choice },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeChoice.entries.size),
                            icon = {},
                        ) { Text(stringResource(choice.title), maxLines = 1) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Toggle(stringResource(R.string.gs_less_transparency), less) { less = it }
                    Toggle(stringResource(R.string.gs_animate_background), animate) { animate = it }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mini(Variant.LIGHT, less, motion, dark, full = false, modifier = Modifier.weight(1f)) { open = Variant.LIGHT }
                    Mini(Variant.FROSTED, less, motion, dark, full = false, modifier = Modifier.weight(1f)) { open = Variant.FROSTED }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mini(Variant.LIQUID, less, motion, dark, full = false, modifier = Modifier.weight(1f)) { open = Variant.LIQUID }
                    Mini(Variant.HYBRID, less, motion, dark, full = false, modifier = Modifier.weight(1f)) { open = Variant.HYBRID }
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(6.dp))
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.scale(0.8f))
    }
}

/** Мини-экран «Ассистента» в стиле варианта: фон, прокручиваемый чат под стеклом, вкладки, карточка, капля. */
@Composable
private fun Mini(
    variant: Variant,
    less: Boolean,
    motion: Boolean,
    dark: Boolean,
    full: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val palette = GlassBackgroundPalette.fromScheme()
    val haze = rememberHazeState()
    val background = rememberLayerBackdrop()
    val list = rememberLayerBackdrop()
    val all = rememberCombinedBackdrop(background, list)
    val env = GlassEnv(variant, spec(variant, less), haze, background, all, palette, dark)
    val usesBackdrop = variant == Variant.LIQUID || variant == Variant.HYBRID

    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(variant.title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(stringResource(variant.desc), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.gs_contrast, "%.1f".format(minContrast(env))),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // В сетке — уменьшенная копия экрана телефона (плотность 0.45), на весь экран — как есть.
        val density = LocalDensity.current
        val scaled = if (full) density else Density(density.density * 0.45f, density.fontScale)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(if (full) 0.dp else 20.dp))
                .then(if (full) Modifier else Modifier.clickable(onClick = onOpen)),
        ) {
            CompositionLocalProvider(LocalDensity provides scaled) {
                GlassBackground(
                    Modifier
                        .matchParentSize()
                        .hazeSource(haze, zIndex = 0f)
                        .then(if (usesBackdrop) Modifier.layerBackdrop(background) else Modifier),
                    animate = motion,
                    palette = palette,
                )
                ChatUnderGlass(
                    env,
                    Modifier
                        .matchParentSize()
                        .hazeSource(haze, zIndex = 1f)
                        .then(if (variant == Variant.LIQUID) Modifier.layerBackdrop(list) else Modifier),
                )
                TabPill(env, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                Controls(env, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
            }
        }
    }
}

/** Цвета, которые могут оказаться позади стекла: фон и (для панелей поверх чата) размытые пузыри с текстом. */
private fun behindBackground(env: GlassEnv): List<Contrast.Rgb> = env.palette.all.map { Contrast.Rgb.of(it.toArgb()) }

@Composable
private fun behindBars(env: GlassEnv): List<Contrast.Rgb> {
    val c = MaterialTheme.colorScheme
    val bubble = c.surface.copy(alpha = env.spec.floor).compositeOver(env.palette.base)
    // Размытый текст пузыря — примерно 30 % цвета текста поверх пузыря.
    val blurredText = lerp(bubble, c.onSurface, 0.3f)
    return behindBackground(env) + listOf(bubble, blurredText).map { Contrast.Rgb.of(it.toArgb()) }
}

/** Плотность подложки: не ниже варианта и не ниже нужной для контраста 4.5:1 основного текста против всего, что позади. */
private fun tintAlpha(env: GlassEnv, tint: Color, text: Color, behind: List<Contrast.Rgb>): Float {
    val needed = Contrast.minTintAlpha(Contrast.Rgb.of(text.toArgb()), Contrast.Rgb.of(tint.toArgb()), behind)
    return max(env.spec.floor.toDouble(), needed).toFloat()
}

/** Акцентный цвет (primary), подтемнённый к [text] ровно настолько, чтобы на этой подложке был контраст 4.5:1. */
private fun accent(env: GlassEnv, accent: Color, tint: Color, text: Color, behind: List<Contrast.Rgb>): Color {
    val alpha = tintAlpha(env, tint, text, behind)
    val k = Contrast.minMixToward(
        Contrast.Rgb.of(accent.toArgb()), Contrast.Rgb.of(text.toArgb()), Contrast.Rgb.of(tint.toArgb()), alpha.toDouble(), behind,
    )
    return lerp(accent, text, k.toFloat())
}

@Composable
private fun minContrast(env: GlassEnv): Double {
    val c = MaterialTheme.colorScheme
    val behind = behindBars(env)
    val alpha = tintAlpha(env, c.surface, c.onSurface, behind)
    return listOf(c.onSurface, accent(env, c.primary, c.surface, c.onSurface, behind)).minOf {
        Contrast.worst(Contrast.Rgb.of(it.toArgb()), Contrast.Rgb.of(c.surface.toArgb()), alpha.toDouble(), behind)
    }
}

/**
 * Стекло варианта. Haze: размытие того, что позади, подложка, блик по краю, тонкая обводка, мягкая тень.
 * Backdrop (жидкое стекло и капля гибрида): размытие + преломление по краю + насыщенность.
 */
@Composable
private fun Modifier.glass(
    env: GlassEnv,
    shape: Shape,
    tint: Color,
    text: Color,
    overList: Boolean,
    refract: Boolean = false,
): Modifier {
    val behind = if (overList) behindBars(env) else behindBackground(env)
    val alpha = tintAlpha(env, tint, text, behind)
    val edge = Brush.linearGradient(
        listOf(Color.White.copy(alpha = env.spec.edge), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = env.spec.edge * 0.4f)),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
    val liquid = env.variant == Variant.LIQUID || (env.variant == Variant.HYBRID && refract)
    if (liquid) {
        val backdrop = if (overList && env.variant == Variant.LIQUID) env.all else env.background
        return this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(env.spec.blur.toPx())
                val corner = if (shape is CornerBasedShape) min(size.minDimension / 2f, 28.dp.toPx()) else 0f
                if (corner > 0f) lens(refractionHeight = corner * 0.9f, refractionAmount = size.minDimension * 0.35f, chromaticAberration = true)
            },
            onDrawSurface = { drawRect(tint.copy(alpha = alpha)) },
        ).border(0.8.dp, edge, shape)
    }
    return this
        .shadow(8.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
        .clip(shape)
        .hazeBlur(
            input = HazeInput.Sources(env.haze),
            style = HazeBlurStyle {
                blurRadius(env.spec.blur)
                noiseFactor(env.spec.noise)
                colorEffects(emptyList())
                backgroundColor(env.palette.base)
            },
        )
        .background(tint.copy(alpha = alpha))
        .border(0.8.dp, edge, shape)
}

@Composable
private fun ChatUnderGlass(env: GlassEnv, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 84.dp, bottom = 150.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().glass(env, RoundedCornerShape(22.dp), c.surface, c.onSurface, overList = false).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_sym_visibility), null, tint = c.onSurface, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.gs_card), color = c.onSurface, fontSize = 15.sp)
            }
        }
        repeat(4) { i ->
            item {
                val q = if (i % 2 == 0) R.string.gs_question else R.string.gs_filler_q
                val a = if (i % 2 == 0) R.string.gs_answer else R.string.gs_filler_a
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Text(
                            stringResource(q),
                            color = c.onPrimaryContainer,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .glass(env, RoundedCornerShape(24.dp), c.primaryContainer, c.onPrimaryContainer, overList = false)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    Column(
                        Modifier
                            .widthIn(max = 300.dp)
                            .glass(env, RoundedCornerShape(24.dp), c.surface, c.onSurface, overList = false)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(stringResource(a), color = c.onSurface, fontSize = 16.sp)
                        Text(stringResource(R.string.answer_more), color = accent(env, c.primary, c.surface, c.onSurface, behindBackground(env)), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TabPill(env: GlassEnv, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val selectedColor = accent(env, c.primary, c.surface, c.onSurface, behindBars(env))
    Row(
        modifier.glass(env, CircleShape, c.surface, c.onSurface, overList = true).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(R.drawable.ic_sym_assistant, R.drawable.ic_sym_g_translate, R.drawable.ic_sym_settings).forEachIndexed { i, icon ->
            val selected = i == 0
            Box(
                Modifier
                    .clip(CircleShape)
                    .then(if (selected) Modifier.background(c.primary.copy(alpha = 0.16f)) else Modifier)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Icon(painterResource(icon), null, tint = if (selected) selectedColor else c.onSurface, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun Controls(env: GlassEnv, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        SideButton(env, R.drawable.ic_sym_visibility)
        MicDrop(env)
        SideButton(env, R.drawable.ic_sym_add_comment)
    }
}

@Composable
private fun SideButton(env: GlassEnv, @DrawableRes icon: Int) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.size(58.dp).glass(env, CircleShape, c.surface, c.onSurface, overList = true),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), null, tint = c.onSurface, modifier = Modifier.size(26.dp)) }
}

/** Кнопка микрофона — стеклянная капля; «слушаю / думаю / говорю» — свечением и пульсацией внутри. */
@Composable
private fun MicDrop(env: GlassEnv) {
    val c = MaterialTheme.colorScheme
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            phase = (phase + 1) % 3
        }
    }
    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (phase == 1) 2_400 else 1_100, easing = LinearEasing), RepeatMode.Reverse),
        label = "mic-pulse",
    )
    val glow = when (phase) {
        0 -> c.primary
        1 -> c.tertiary
        else -> c.secondary
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(96.dp)
                .glass(env, CircleShape, c.primaryContainer, c.onPrimaryContainer, overList = true, refract = true)
                .drawBehind {
                    // Свечение внутри капли: «слушаю» — пульс, «думаю» — медленное дыхание, «говорю» — волна.
                    val strength = 0.25f + 0.45f * pulse
                    drawCircle(
                        Brush.radialGradient(
                            listOf(glow.copy(alpha = strength), glow.copy(alpha = 0f)),
                            center = center,
                            radius = size.minDimension * (0.35f + 0.2f * pulse),
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_sym_mic), null, tint = c.onPrimaryContainer, modifier = Modifier.size(40.dp))
        }
        Text(
            stringResource(listOf(R.string.gs_listening, R.string.gs_thinking, R.string.gs_speaking)[phase]),
            color = c.onSurface,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp).glass(env, CircleShape, c.surface, c.onSurface, overList = true).padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}
