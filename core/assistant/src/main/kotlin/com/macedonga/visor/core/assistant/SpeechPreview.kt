package com.macedonga.visor.core.assistant

import android.content.Context
import java.util.Locale

/**
 * The one thing a voice preview should answer: what does this voice sound like. So it synthesises a
 * fixed sentence with whatever the wearer chose and says it on this phone's own speaker. No model is
 * asked, no glasses are needed, and nothing the wearer typed can change what is said — a preview that
 * went through the model would be testing the model, and would cost a call every time it was pressed.
 */
/**
 * One fixed sample, not copy: it carries Chinese, English and digits so a single press shows whether
 * the voice renders each of them. A voice that answers only the English half is the bug this whole
 * control exists for.
 */
const val PREVIEW_LINE = "你好。Hello。一二三四五。"

suspend fun previewSpeech(
    config: AssistantConfig,
    context: Context,
    line: String = PREVIEW_LINE,
) {
    val voice = Voice(context)
    try {
        when (config.tts) {
            // The frames make their own voice, so there is nothing here to make: say so rather than
            // silently playing something else and calling it a preview of the frames.
            TtsPath.GLASSES -> throw SpeechError("the frames make this voice themselves; ask them something to hear it")
            TtsPath.CLOUD -> {
                val audio = cloudSpeech(config, line)
                voice.play(audio.samples, audio.rate)
            }
            TtsPath.PHONE -> voice.speakAloud(line, Locale.getDefault())
        }
    } finally {
        // Only the engine is shut here; a track already playing is released by [Voice.play] itself.
        voice.close()
    }
}
