package com.macedonga.visor.language

import java.util.Locale

/**
 * One of the glasses' voices: its id (the value of assistant_voice_id), the name the Meta app gives it if any, and
 * its tone. Most languages' voices have no name, only a tone.
 */
data class GlassesVoice(val id: String, val en: String, val it: String, val name: String? = null) {
    fun tone(): String = if (Locale.getDefault().language == "it") it else en
    fun label(): String = name ?: tone()
}

/**
 * A language the glasses' assistant can run in: recognition, understanding and voice. [pack] is the suffix of
 * Meta's language feature; null for the built-in language and phone-based partner languages.
 */
data class GlassesLanguage(val locale: String, val pack: String?, val voices: List<GlassesVoice>, val defaultVoice: String) {
    val partner: Boolean get() = defaultVoice.startsWith("3p_")
    private val javaLocale: Locale get() = Locale.forLanguageTag(locale.replace('_', '-'))

    /** Its own name, in itself: "Italiano (Italia)". */
    fun nativeName(): String = javaLocale.getDisplayName(javaLocale).replaceFirstChar { it.titlecase(javaLocale) }

    /** Its name in the phone's language. */
    fun localName(): String = javaLocale.getDisplayName(Locale.getDefault()).replaceFirstChar { it.titlecase() }
}

private fun v(id: String, en: String, it: String) = GlassesVoice(id, en, it)

private const val HIGHER = "Higher pitch"
private const val LOWER = "Lower pitch"
private fun named(id: String, name: String, pitch: String) =
    GlassesVoice(id, pitch, if (pitch == HIGHER) "Tono più alto" else if (pitch == LOWER) "Tono più basso" else "Tono medio", name)

/** From the Meta app's voice table; only languages Meta has a pack for on these glasses, and the built-in one. */
val GLASSES_LANGUAGES: List<GlassesLanguage> = listOf(
    GlassesLanguage(
        "en_US", null,
        // Meta AI's voices, as the Meta app lists them for English.
        listOf(
            named("en_US.mai_01", "Aria", HIGHER), named("en_US.mai_03", "Helio", LOWER),
            named("en_US.60", "Awkwafina (AI voice)", HIGHER), named("en_US.pf06", "Deepika Padukone (AI voice)", "Medium pitch"),
            named("en_US.63", "John Cena (AI voice)", LOWER), named("en_US.pf02", "Judi Dench (AI voice)", HIGHER),
            named("en_US.61", "Keegan-Michael Key (AI voice)", LOWER), named("en_US.62", "Kristen Bell (AI voice)", HIGHER),
        ),
        "en_US.mai_01",
    ),
    GlassesLanguage(
        "it_IT", "it_it",
        listOf(v("it_IT.0", "High, clear", "Tono alto, chiaro"), v("it_IT.1", "Midrange, energetic", "Tono medio, energico"), v("it_IT.2", "Low, calm", "Tono basso, calmo")),
        "it_IT.1",
    ),
    GlassesLanguage("en_GB", "en_gb", listOf(v("en_GB.0", "Midrange, precise", "Tono medio, deciso"), v("en_GB.1", "Low, enthusiastic", "Tono basso, entusiasta")), "en_GB.1"),
    GlassesLanguage("en_AU", "en_au", listOf(v("en_AU.0", "Midrange, direct", "Tono medio, diretto"), v("en_AU.1", "Low, easygoing", "Tono basso, rilassato")), "en_AU.0"),
    GlassesLanguage("en_IN", "en_in", listOf(v("en_IN.1", "Medium-high, cheerful", "Tono medio-alto, allegro"), v("en_IN.3", "Medium-low, deep", "Tono medio-basso, profondo")), "en_IN.1"),
    GlassesLanguage("fr_FR", "fr_fr", listOf(v("fr_FR.0", "Midrange, clear", "Tono medio, chiaro"), v("fr_FR.1", "Low, warm", "Tono basso, caldo")), "fr_FR.0"),
    GlassesLanguage("de_DE", "de_de", listOf(v("de_DE.0", "High", "Tono alto"), v("de_DE.1", "Low", "Tono basso")), "de_DE.0"),
    GlassesLanguage("es_ES", "es_es", listOf(v("es_ES.3", "High", "Tono alto"), v("es_ES.2", "Medium", "Tono medio"), v("es_ES.4", "Low", "Tono basso")), "es_ES.2"),
    GlassesLanguage("es_US", "es_us", listOf(v("es_US.2", "High", "Tono alto"), v("es_US.1", "Low", "Tono basso")), "es_US.2"),
    GlassesLanguage(
        "pt_BR", "pt_br",
        listOf(
            v("pt_BR.1", "High, soothing", "Tono alto, rilassante"), v("pt_BR.2", "Medium, warm", "Tono medio, caldo"),
            v("pt_BR.3", "Medium-low, firm", "Tono medio-basso, risoluto"), v("pt_BR.4", "Low, energetic", "Tono basso, energico"),
        ),
        "pt_BR.1",
    ),
    GlassesLanguage("nl_NL", "nl_nl", listOf(v("nl_NL.0", "Moderately high, energized", "Tono moderatamente alto, energico"), v("nl_NL.2", "Medium, neutral", "Tono medio, neutro")), "nl_NL.0"),
    GlassesLanguage("tr_TR", "tr_tr", listOf(v("tr_TR.0", "Default", "Predefinita")), "tr_TR.0"),
    GlassesLanguage("hi_IN", "hi_in", listOf(v("hi_IN.5", "Medium-low, deep", "Tono medio-basso, profondo")), "hi_IN.5"),
    GlassesLanguage("ja_JP", "ja_jp", listOf(v("ja_JP.0", "Medium-low, warm, narrative", "Tono medio-basso, caldo, narrativo"), v("ja_JP.1", "Medium", "Tono medio")), "ja_JP.0"),
    GlassesLanguage("ko_KR", "ko_kr", listOf(v("ko_KR.0", "Medium, soft, narrative", "Tono medio, morbido, narrativo"), v("ko_KR.1", "Low, deep", "Tono basso, profondo")), "ko_KR.0"),
    GlassesLanguage("zh_CN", "zh_cn", listOf(v("zh_CN.0", "High, gentle", "Tono acuto, gentile"), v("zh_CN.1", "Low, deep", "Tono basso, profondo")), "zh_CN.0"),
)

val PARTNER_LANGUAGES: List<GlassesLanguage> = listOf(
    "ar_EG", "bn_IN", "cs_CZ", "da_DK", "de_DE", "el_GR", "en_AU", "en_GB", "en_IN", "en_US",
    "es_ES", "es_US", "fi_FI", "fil_PH", "fr_FR", "gu_IN", "he_IL", "hi_IN", "hr_HR", "hu_HU",
    "it_IT", "ja_JP", "kn_IN", "ko_KR", "ml_IN", "ms_MY", "nl_NL", "pa_IN", "pl_PL", "pt_BR",
    "ru_RU", "sv_SE", "te_IN", "tr_TR", "vi_VN", "yue_CN", "zh_CN", "zh_HK",
).map { locale ->
    val id = "3p_$locale.0"
    GlassesLanguage(locale, null, listOf(v(id, "Phone", "Telefono")), id)
}

/** The native or partner language a confirmed voice id belongs to. */
fun languageOfVoice(voiceId: String?): GlassesLanguage? {
    val locale = voiceId?.removePrefix("3p_")?.substringBefore('.') ?: return null
    val languages = if (voiceId.startsWith("3p_")) PARTNER_LANGUAGES else GLASSES_LANGUAGES
    return languages.firstOrNull { it.locale == locale }
}

fun unavailableLanguageSettings(voiceId: String?): Set<String> =
    if (voiceId?.startsWith("3p_") == true) setOf("tts_speak_speed") else emptySet()
