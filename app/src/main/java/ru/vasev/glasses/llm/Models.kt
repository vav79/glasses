package ru.vasev.glasses.llm

/** Провайдер ИИ. Он же — слот ключа в [ru.vasev.glasses.security.ApiKeyStore]. */
enum class ProviderId(val title: String) {
    ANTHROPIC("Anthropic"),
    DEEPSEEK("DeepSeek"),

    /** Клиент готов (OpenAI-совместимый), но моделей в каталоге пока нет — см. [ProviderConfigs.OPENAI]. */
    OPENAI("OpenAI"),
}

/** Кто спрашивает: от роли зависят модель по умолчанию, лимит токенов и глубина размышлений. */
enum class Role { ASSISTANT, TRANSLATION }

/** Глубина размышлений: [NONE] — выключить, где модель это позволяет; [LOW] — минимальная. */
enum class Effort { NONE, LOW }

data class ModelSpec(
    val id: String,
    val provider: ProviderId,
    val title: String,
    /** Принимает ли картинки. Для моделей без фото снимки в истории заменяются на «[фото]». */
    val vision: Boolean,
)

object ModelCatalog {
    val CLAUDE_SONNET = ModelSpec("claude-sonnet-5-5", ProviderId.ANTHROPIC, "Claude Sonnet 5.5", vision = true)
    val CLAUDE_HAIKU = ModelSpec("claude-haiku-5-5", ProviderId.ANTHROPIC, "Claude Haiku 5.5", vision = true)

    /** DeepSeek-V4.1-Flash. Старые имена deepseek-v4-flash / -vision-exp выведены из работы. */
    val DEEPSEEK_FLASH = ModelSpec("deepseek-flash", ProviderId.DEEPSEEK, "DeepSeek Flash", vision = true)

    /** DeepSeek-V4-Pro-0813: только текст, картинка даёт 400. */
    val DEEPSEEK_PRO = ModelSpec("deepseek-v4-pro", ProviderId.DEEPSEEK, "DeepSeek V4-Pro", vision = false)

    val all = listOf(CLAUDE_SONNET, CLAUDE_HAIKU, DEEPSEEK_FLASH, DEEPSEEK_PRO)

    /** Запасная модель при сбое Claude: видит фото, быстрая и дешёвая. */
    val FALLBACK = DEEPSEEK_FLASH

    fun default(role: Role): ModelSpec = when (role) {
        Role.ASSISTANT -> CLAUDE_SONNET
        Role.TRANSLATION -> CLAUDE_HAIKU
    }

    fun byId(id: String?): ModelSpec? = all.firstOrNull { it.id == id }

    /** Модель того же провайдера, которая видит фото (для «Что я вижу» при выбранной текстовой модели). */
    fun visionFor(model: ModelSpec): ModelSpec? =
        if (model.vision) model else all.firstOrNull { it.provider == model.provider && it.vision }
}

/** Отличия OpenAI-совместимых API в мелочах. */
enum class Dialect {
    /** `max_tokens`, `reasoning_effort: none|low|high|max`, авто-кэш с полями `prompt_cache_hit_tokens`. */
    DEEPSEEK,

    /** `max_completion_tokens`, `reasoning_effort: minimal|low|…`, кэш в `prompt_tokens_details.cached_tokens`. */
    OPENAI,
}

/** Настройка OpenAI-совместимого провайдера: подключить нового = добавить запись и модели в каталог. */
data class ProviderConfig(val provider: ProviderId, val baseUrl: String, val dialect: Dialect)

object ProviderConfigs {
    val DEEPSEEK = ProviderConfig(ProviderId.DEEPSEEK, "https://api.deepseek.com", Dialect.DEEPSEEK)
    val OPENAI = ProviderConfig(ProviderId.OPENAI, "https://api.openai.com/v1", Dialect.OPENAI)
}
