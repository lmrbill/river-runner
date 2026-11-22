package com.riverrunner.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.riverrunner.R
import com.riverrunner.SoundOut
import kotlin.random.Random

/** Short game sounds (synthesised by tools/make_sounds.py) played through a SoundPool. */
class SoundPlayer(context: Context) : SoundOut {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids: Map<Sound, Int> = mapOf(
        Sound.DEAL to R.raw.sfx_deal,
        Sound.FLIP to R.raw.sfx_flip,
        Sound.CHIP to R.raw.sfx_chip,
        Sound.COLLECT to R.raw.sfx_collect,
        Sound.CHECK to R.raw.sfx_check,
        Sound.FOLD to R.raw.sfx_fold,
        Sound.TURN to R.raw.sfx_turn,
        Sound.WIN to R.raw.sfx_win,
        Sound.ALLIN to R.raw.sfx_allin,
        Sound.BLINDS to R.raw.sfx_blinds,
        Sound.CLICK to R.raw.sfx_click,
        Sound.UNLOCK to R.raw.sfx_unlock,
    ).mapValues { (_, res) -> pool.load(context, res, 1) }

    override var enabled = true

    /** [volume] 0..1; a small random pitch change keeps repeated sounds from feeling mechanical. */
    override fun play(sound: Sound, volume: Float, varyPitch: Boolean) {
        if (!enabled) return
        val id = ids[sound] ?: return
        val rate = if (varyPitch) 0.94f + Random.nextFloat() * 0.12f else 1f
        val v = volume.coerceIn(0f, 1f)
        pool.play(id, v, v, 1, 0, rate)
    }

    override fun release() = pool.release()
}
