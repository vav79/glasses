package ru.vasev.glasses.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ru.vasev.glasses.R
import ru.vasev.glasses.llm.ModelTag

/** Метка модели у ответа на языке экрана: «Claude Sonnet 5.5», «DeepSeek Flash · запасная», «… · для фото». */
@Composable
fun modelLabel(tag: ModelTag): String = when {
    tag.viaFallback -> stringResource(R.string.model_fallback_suffix, tag.title)
    tag.autoVision -> stringResource(R.string.model_for_photo_suffix, tag.title)
    else -> tag.title
}
