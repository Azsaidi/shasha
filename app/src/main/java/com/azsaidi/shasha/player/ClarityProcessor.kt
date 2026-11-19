package com.azsaidi.shasha.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Clearer sound" for dull, muffled soundtracks. Same processing as the iPhone and Windows apps.
 *
 * Dialogue sits in the middle of a stereo mix (or in the centre channel of 5.1); music and effects
 * are spread to the sides. So only the dialogue part is worked on: less boxy low-mid "mud", a lift
 * where speech is understood (2–3 kHz), an exciter that recreates the "air" a bad encode cut off
 * (only above the background noise, so hiss isn't turned into "rain"), and a little treble. It is
 * then turned up against the rest; music on the sides is left alone, so it isn't made harsh.
 * A smooth limiter (gain riding, not clipping) keeps peaks in check without buzzing.
 *
 * Works on 16-bit PCM, any sample rate and channel count. No settings: sound passes through untouched.
 */
@UnstableApi
class ClarityProcessor : BaseAudioProcessor() {

    /** null = sound passes through untouched. Can change at any time (the Sound sliders). */
    @Volatile var settings: ClaritySettings? = null

    private var channels = 0
    private var rate = 0
    private var built = false
    private var tuned: ClaritySettings? = null

    private lateinit var lowCut: Array<Biquad>
    private lateinit var topCut: Array<Biquad>
    private lateinit var mud: Biquad
    private lateinit var presence: Biquad
    private lateinit var treble: Biquad
    private lateinit var exciteIn: Biquad
    private lateinit var exciteOut: Biquad
    private lateinit var exciteOut2: Biquad
    private lateinit var hiDetect: Biquad
    private var envelope = 0f
    private var noiseFloor = 1f
    private var attack = 0f
    private var release = 0f
    private var floorRise = 0f
    private var limiterRelease = 0f
    private var hiEnv = 0f
    private var fullEnv = 0f
    private var tameAttack = 0f
    private var tameRelease = 0f
    private var gain = 1f
    private var frame = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        rate = inputAudioFormat.sampleRate
        built = false
        return inputAudioFormat
    }

    /** New format: every filter from scratch. */
    private fun build() {
        built = true
        tuned = null
        val sr = rate.toDouble()
        lowCut = Array(channels) { Biquad.highPass(sr, 80.0, 0.707) }
        topCut = Array(channels) { Biquad.lowPass(sr, 16000.0, 0.707) }
        mud = Biquad.peak(sr, 300.0, 1.0, 0.0)
        presence = Biquad.peak(sr, 2500.0, 0.9, 0.0)
        treble = Biquad.highShelf(sr, 6000.0, 0.0)
        exciteIn = Biquad.highPass(sr, 2500.0, 0.707)
        exciteOut = Biquad.highPass(sr, 5000.0, 0.707)
        exciteOut2 = Biquad.highPass(sr, 5000.0, 0.707)
        hiDetect = Biquad.highPass(sr, 3000.0, 0.707)
        envelope = 0f
        noiseFloor = 1f
        hiEnv = 0f
        fullEnv = 0f
        gain = 1f
        attack = exp(-1.0 / (0.002 * sr)).toFloat()
        release = exp(-1.0 / (0.06 * sr)).toFloat()
        floorRise = (1.0 / (4 * sr)).toFloat()
        limiterRelease = (1 - exp(-1.0 / (0.15 * sr))).toFloat()
        tameAttack = exp(-1.0 / (0.005 * sr)).toFloat()
        tameRelease = exp(-1.0 / (0.08 * sr)).toFloat()
        frame = FloatArray(channels)
    }

    /** New slider values: retune the filters but keep their memory, so moving a slider doesn't click. */
    private fun tune(s: ClaritySettings) {
        tuned = s
        val sr = rate.toDouble()
        mud.retune(Biquad.peak(sr, 300.0, 1.0, -s.mud.toDouble()))
        presence.retune(Biquad.peak(sr, 2500.0, 0.9, s.dialogue.toDouble()))
        treble.retune(Biquad.highShelf(sr, 6000.0, s.treble.toDouble()))
    }

    /** The dialogue part: mud cut, speech lift, exciter, treble, then turned up. */
    private fun dialogue(input: Float, s: ClaritySettings): Float {
        val base = mud.run(input)
        val lifted = presence.run(base)
        // Exciter: square the upper mids (scaled to their level) for smooth harmonics an octave up.
        val upper = exciteIn.run(lifted)
        val a = abs(upper)
        val k = if (a > envelope) attack else release
        val env = maxOf(k * envelope + (1 - k) * a, 1e-6f)
        envelope = env
        noiseFloor = if (env < noiseFloor) env else noiseFloor + (env - noiseFloor) * floorRise
        val harmonics = exciteOut2.run(exciteOut.run(upper * upper / env))
        // Only while the sound stands clearly above the background noise (about 10 dB).
        val threshold = maxOf(3 * noiseFloor, 3e-4f)
        val bright = treble.run(lifted + harmonics * s.air * (env * env / (env * env + threshold * threshold)))
        var amount = 1f
        if (s.tame > 0f) {
            // Already high-pitched sound (whistles, "s" sounds, strings) gets less of the lift.
            val hi = abs(hiDetect.run(base))
            hiEnv = (if (hi > hiEnv) tameAttack else tameRelease) * (hiEnv - hi) + hi
            val full = abs(base)
            fullEnv = (if (full > fullEnv) tameAttack else tameRelease) * (fullEnv - full) + full
            val ratio = hiEnv / maxOf(fullEnv, 1e-6f)
            val tamed = (1.25f / (1 + (ratio / 0.4f) * (ratio / 0.4f))).coerceIn(0.15f, 1f)
            amount = 1 - s.tame * (1 - tamed)
        }
        return (base + (bright - base) * amount) * s.midGain
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val s = settings
        if (s == null) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        if (!built) build()
        if (s != tuned) tune(s)
        val ch = channels
        val f = frame
        val sideGain = s.sideGain
        val subGain = s.subGain
        val outGain = s.outGain
        while (inputBuffer.remaining() >= 2 * ch) {
            for (c in 0 until ch) f[c] = lowCut[c].run(inputBuffer.short / 32768f)
            when {
                ch == 1 -> f[0] = dialogue(f[0], s)
                ch == 2 -> {
                    // Middle (dialogue) and sides (music, effects).
                    val mid = dialogue((f[0] + f[1]) * 0.5f, s)
                    val side = (f[0] - f[1]) * 0.5f * sideGain
                    f[0] = mid + side
                    f[1] = mid - side
                }
                else -> {
                    // 5.1 / 7.1 order: FL FR FC LFE ...: dialogue is the centre channel.
                    f[2] = dialogue(f[2], s)
                    if (ch >= 4) f[3] *= subGain
                    for (c in 0 until ch) if (c != 2 && c != 3) f[c] *= sideGain
                }
            }
            // Smooth limiter across all channels: drop the gain at once on a peak, recover over ~150 ms.
            var peak = 0f
            for (c in 0 until ch) {
                f[c] = topCut[c].run(f[c]) * outGain
                peak = maxOf(peak, abs(f[c]))
            }
            val target = if (peak > 0.89f) 0.89f / peak else 1f
            gain = if (target < gain) target else gain + (target - gain) * limiterRelease
            for (c in 0 until ch) {
                val y = (f[c] * gain).coerceIn(-1f, 1f)
                out.putShort((y * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        // A partial frame at the end (shouldn't happen) passes through.
        while (inputBuffer.remaining() > 0) out.put(inputBuffer.get())
        out.flip()
    }

    override fun onFlush() {
        built = false
    }

    override fun onReset() {
        built = false
        channels = 0
        rate = 0
    }

    /** One second-order filter (RBJ audio cookbook), with its own memory. */
    private class Biquad(
        private var b0: Float, private var b1: Float, private var b2: Float,
        private var a1: Float, private var a2: Float,
    ) {
        private var z1 = 0f
        private var z2 = 0f

        /** Takes another filter's settings, keeping this one's memory. */
        fun retune(o: Biquad) {
            b0 = o.b0; b1 = o.b1; b2 = o.b2; a1 = o.a1; a2 = o.a2
        }

        fun run(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        companion object {
            private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
                Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())

            fun highPass(rate: Double, hz: Double, q: Double): Biquad {
                val w = 2 * PI * hz.coerceAtMost(rate * 0.45) / rate
                val alpha = sin(w) / (2 * q)
                val c = cos(w)
                return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }

            fun lowPass(rate: Double, hz: Double, q: Double): Biquad {
                val w = 2 * PI * hz.coerceAtMost(rate * 0.45) / rate
                val alpha = sin(w) / (2 * q)
                val c = cos(w)
                return make((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }

            fun peak(rate: Double, hz: Double, q: Double, db: Double): Biquad {
                val a = 10.0.pow(db / 40)
                val w = 2 * PI * hz.coerceAtMost(rate * 0.45) / rate
                val alpha = sin(w) / (2 * q)
                val c = cos(w)
                return make(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
            }

            fun highShelf(rate: Double, hz: Double, db: Double): Biquad {
                val a = 10.0.pow(db / 40)
                val w = 2 * PI * hz.coerceAtMost(rate * 0.45) / rate
                val c = cos(w)
                val alpha = sin(w) / 2 * sqrt(2.0)
                val s = 2 * sqrt(a) * alpha
                return make(
                    a * ((a + 1) + (a - 1) * c + s),
                    -2 * a * ((a - 1) + (a + 1) * c),
                    a * ((a + 1) + (a - 1) * c - s),
                    (a + 1) - (a - 1) * c + s,
                    2 * ((a - 1) - (a + 1) * c),
                    (a + 1) - (a - 1) * c - s,
                )
            }
        }
    }
}

/**
 * The "Clearer sound" sliders. Clearer and Much clearer are the tuned presets; any change makes a
 * custom setting, saved per series, film or channel.
 */
data class ClaritySettings(
    /** Lift where speech is understood (2.5 kHz), dB 0..10. */
    val dialogue: Float,
    /** Exciter: rebuilds the top end a bad encode cut off, 0..1.5. */
    val air: Float,
    /** Treble shelf above 6 kHz, dB 0..8. */
    val treble: Float,
    /** Cut of boxy low-mid "mud" at 300 Hz, dB 0..6. */
    val mud: Float,
    /** Dialogue (middle of the mix) against music and effects (sides), 0..1. */
    val focus: Float,
    /** How much already high-pitched sound is held back from the lift, 0..1 (0 = off). */
    val tame: Float,
) {
    // Gains along the "Dialogue vs music" slider; 0.25 and 0.5 are exactly the presets' values.
    val midGain: Float get() = along(focus, 1f, 1.12f, 1.25f, 1.5f)
    val sideGain: Float get() = along(focus, 1f, 1f, 0.85f, 0.6f)
    val subGain: Float get() = along(focus, 1f, 0.8f, 0.6f, 0.5f)
    val outGain: Float get() = along(focus, 0.9f, 0.85f, 0.8f, 0.7f)

    fun toJson(): String = org.json.JSONObject()
        .put("dialogue", dialogue.toDouble()).put("air", air.toDouble()).put("treble", treble.toDouble())
        .put("mud", mud.toDouble()).put("focus", focus.toDouble()).put("tame", tame.toDouble())
        .toString()

    companion object {
        val CLEARER = ClaritySettings(dialogue = 4f, air = 0.5f, treble = 3f, mud = 2f, focus = 0.25f, tame = 0f)
        val MUCH_CLEARER = ClaritySettings(dialogue = 6f, air = 0.9f, treble = 4.5f, mud = 3f, focus = 0.5f, tame = 0f)

        fun fromJson(text: String): ClaritySettings? = runCatching {
            val o = org.json.JSONObject(text)
            ClaritySettings(
                o.getDouble("dialogue").toFloat(), o.getDouble("air").toFloat(), o.getDouble("treble").toFloat(),
                o.getDouble("mud").toFloat(), o.getDouble("focus").toFloat(), o.optDouble("tame", 0.0).toFloat(),
            )
        }.getOrNull()

        /** Straight lines between values at focus 0, 0.25, 0.5 and 1. */
        private fun along(f: Float, v0: Float, v1: Float, v2: Float, v3: Float): Float {
            val x = f.coerceIn(0f, 1f)
            return when {
                x <= 0.25f -> v0 + (v1 - v0) * x / 0.25f
                x <= 0.5f -> v1 + (v2 - v1) * (x - 0.25f) / 0.25f
                else -> v2 + (v3 - v2) * (x - 0.5f) / 0.5f
            }
        }
    }
}
