package com.macedonga.visor.language

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.macedonga.visor.R
import java.util.Locale

/** The glasses' setting for the assistant's voice, and so its language. */
const val VOICE_KEY = "assistant_voice_id"

private const val PHONE_PREFIX = "3p_"

/** A voice of a phone language: the phone hears and speaks for the glasses. */
fun isPhoneVoice(id: String?): Boolean = id?.startsWith(PHONE_PREFIX) == true

/** A locale's own name, in itself: "Русский (Россия)". */
fun Locale.ownName(): String = getDisplayName(this).replaceFirstChar { it.titlecase(this) }

/** The own name of a voice's language, for a voice outside these tables; the id when its locale has no name. */
fun voiceLanguageName(voiceId: String): String =
    Locale.forLanguageTag(voiceId.removePrefix(PHONE_PREFIX).substringBefore('.').replace('_', '-')).ownName().ifEmpty { voiceId }

/** One of the glasses' voices: its id (the value of assistant_voice_id), its name if it has one, and its tone. */
data class GlassesVoice(val id: String, @StringRes val toneId: Int, val name: String? = null, val ai: Boolean = false) {
    @Composable
    fun tone(): String = stringResource(toneId)

    @Composable
    fun label(): String = name?.let { if (ai) stringResource(R.string.voice_ai_named, it) else it } ?: tone()
}

/**
 * A language the glasses' assistant can run in: recognition, understanding and voice. [pack] is the suffix of
 * Meta's language feature; null for the built-in language and phone-based partner languages.
 */
data class GlassesLanguage(val locale: String, val pack: String?, val voices: List<GlassesVoice>, val defaultVoice: String) {
    val partner: Boolean get() = isPhoneVoice(defaultVoice)
    val javaLocale: Locale get() = Locale.forLanguageTag(locale.replace('_', '-'))

    /** Its own name, in itself: "Italiano (Italia)". */
    fun nativeName(): String = javaLocale.ownName()

    /** Its name in the phone's language. */
    fun localName(): String = javaLocale.getDisplayName(Locale.getDefault()).replaceFirstChar { it.titlecase() }
}

private fun v(id: String, @StringRes tone: Int) = GlassesVoice(id, tone)

private fun named(id: String, name: String, @StringRes pitch: Int, ai: Boolean = true) = GlassesVoice(id, pitch, name, ai)

/** The glasses' own languages: en_US built in, the others from a Meta pack. */
val GLASSES_LANGUAGES: List<GlassesLanguage> = listOf(
    GlassesLanguage(
        "en_US", null,
        listOf(
            named("en_US.mai_01", "Aria", R.string.voice_pitch_higher, ai = false), named("en_US.mai_03", "Helio", R.string.voice_pitch_lower, ai = false),
            named("en_US.60", "Awkwafina", R.string.voice_pitch_higher), named("en_US.pf06", "Deepika Padukone", R.string.voice_pitch_medium),
            named("en_US.63", "John Cena", R.string.voice_pitch_lower), named("en_US.pf02", "Judi Dench", R.string.voice_pitch_higher),
            named("en_US.61", "Keegan-Michael Key", R.string.voice_pitch_lower), named("en_US.62", "Kristen Bell", R.string.voice_pitch_higher),
        ),
        "en_US.mai_01",
    ),
    GlassesLanguage(
        "it_IT", "it_it",
        listOf(v("it_IT.0", R.string.voice_tone_high_clear), v("it_IT.1", R.string.voice_tone_mid_energetic), v("it_IT.2", R.string.voice_tone_low_calm)),
        "it_IT.1",
    ),
    GlassesLanguage("en_GB", "en_gb", listOf(v("en_GB.0", R.string.voice_tone_mid_precise), v("en_GB.1", R.string.voice_tone_low_enthusiastic)), "en_GB.1"),
    GlassesLanguage("en_AU", "en_au", listOf(v("en_AU.0", R.string.voice_tone_mid_direct), v("en_AU.1", R.string.voice_tone_low_easygoing)), "en_AU.0"),
    GlassesLanguage("en_IN", "en_in", listOf(v("en_IN.1", R.string.voice_tone_midhigh_cheerful), v("en_IN.3", R.string.voice_tone_midlow_deep)), "en_IN.1"),
    GlassesLanguage("fr_FR", "fr_fr", listOf(v("fr_FR.0", R.string.voice_tone_mid_clear), v("fr_FR.1", R.string.voice_tone_low_warm)), "fr_FR.0"),
    GlassesLanguage("de_DE", "de_de", listOf(v("de_DE.0", R.string.voice_tone_high), v("de_DE.1", R.string.voice_tone_low)), "de_DE.0"),
    GlassesLanguage("es_ES", "es_es", listOf(v("es_ES.3", R.string.voice_tone_high), v("es_ES.2", R.string.voice_tone_medium), v("es_ES.4", R.string.voice_tone_low)), "es_ES.2"),
    GlassesLanguage("es_US", "es_us", listOf(v("es_US.2", R.string.voice_tone_high), v("es_US.1", R.string.voice_tone_low)), "es_US.2"),
    GlassesLanguage(
        "pt_BR", "pt_br",
        listOf(
            v("pt_BR.1", R.string.voice_tone_high_soothing), v("pt_BR.2", R.string.voice_tone_medium_warm),
            v("pt_BR.3", R.string.voice_tone_midlow_firm), v("pt_BR.4", R.string.voice_tone_low_energetic),
        ),
        "pt_BR.1",
    ),
    GlassesLanguage("nl_NL", "nl_nl", listOf(v("nl_NL.0", R.string.voice_tone_modhigh_energized), v("nl_NL.2", R.string.voice_tone_medium_neutral)), "nl_NL.0"),
    GlassesLanguage("tr_TR", "tr_tr", listOf(v("tr_TR.0", R.string.voice_tone_default)), "tr_TR.0"),
    GlassesLanguage("hi_IN", "hi_in", listOf(v("hi_IN.5", R.string.voice_tone_midlow_deep)), "hi_IN.5"),
    GlassesLanguage("ja_JP", "ja_jp", listOf(v("ja_JP.0", R.string.voice_tone_midlow_warm_narrative), v("ja_JP.1", R.string.voice_tone_medium)), "ja_JP.0"),
    GlassesLanguage("ko_KR", "ko_kr", listOf(v("ko_KR.0", R.string.voice_tone_medium_soft_narrative), v("ko_KR.1", R.string.voice_tone_low_deep)), "ko_KR.0"),
    GlassesLanguage("zh_CN", "zh_cn", listOf(v("zh_CN.0", R.string.voice_tone_high_gentle), v("zh_CN.1", R.string.voice_tone_low_deep)), "zh_CN.0"),
)

val PARTNER_LANGUAGES: List<GlassesLanguage> = listOf(
    "ar_EG", "bn_IN", "cs_CZ", "da_DK", "de_DE", "el_GR", "en_AU", "en_GB", "en_IN", "en_US",
    "es_ES", "es_US", "fi_FI", "fil_PH", "fr_FR", "gu_IN", "he_IL", "hi_IN", "hr_HR", "hu_HU",
    "it_IT", "ja_JP", "kn_IN", "ko_KR", "ml_IN", "ms_MY", "nl_NL", "pa_IN", "pl_PL", "pt_BR",
    "ru_RU", "sv_SE", "te_IN", "tr_TR", "vi_VN", "yue_CN", "zh_CN", "zh_HK",
).map { locale ->
    val id = "$PHONE_PREFIX$locale.0"
    GlassesLanguage(locale, null, listOf(v(id, R.string.ai_voice_phone)), id)
}

/** The native or partner language a confirmed voice id belongs to. */
fun languageOfVoice(voiceId: String?): GlassesLanguage? {
    val locale = voiceId?.removePrefix(PHONE_PREFIX)?.substringBefore('.') ?: return null
    val languages = if (isPhoneVoice(voiceId)) PARTNER_LANGUAGES else GLASSES_LANGUAGES
    return languages.firstOrNull { it.locale == locale }
}

fun unavailableLanguageSettings(voiceId: String?): Set<String> =
    if (isPhoneVoice(voiceId)) setOf("tts_speak_speed") else emptySet()
