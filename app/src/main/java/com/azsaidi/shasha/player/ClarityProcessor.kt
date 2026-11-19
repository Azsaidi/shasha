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
 * Works on 16-bit PCM, any sample rate and channel count. Level 0 passes sound through untouched.
 */
@UnstableApi
class ClarityProcessor : BaseAudioProcessor() {

    @Volatile var level: Int = 0

    private var channels = 0
    private var rate = 0
    private var builtFor = -1

    private lateinit var lowCut: Array<Biquad>
    private lateinit var topCut: Array<Biquad>
    private lateinit var mud: Biquad
    private lateinit var presence: Biquad
    private lateinit var treble: Biquad
    private lateinit var exciteIn: Biquad
    private lateinit var exciteOut: Biquad
    private lateinit var exciteOut2: Biquad
    private var envelope = 0f
    private var noiseFloor = 1f
    private var attack = 0f
    private var release = 0f
    private var floorRise = 0f
    private var limiterRelease = 0f
    private var exciteMix = 0f
    private var midGain = 1f
    private var sideGain = 1f
    private var subGain = 1f
    private var outGain = 1f
    private var gain = 1f
    private var frame = FloatArray(0)
    private lateinit var hiDetect: Biquad
    private var hiEnv = 0f
    private var fullEnv = 0f
    private var tameAttack = 0f
    private var tameRelease = 0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        rate = inputAudioFormat.sampleRate
        builtFor = -1
        return inputAudioFormat
    }

    private fun build(level: Int) {
        builtFor = level
        val strong = level >= 2
        val sr = rate.toDouble()
        lowCut = Array(channels) { Biquad.highPass(sr, 80.0, 0.707) }
        topCut = Array(channels) { Biquad.lowPass(sr, 16000.0, 0.707) }
        mud = Biquad.peak(sr, 300.0, 1.0, if (strong) -3.0 else -2.0)
        presence = Biquad.peak(sr, 2500.0, 0.9, if (strong) 6.0 else 4.0)
        treble = Biquad.highShelf(sr, 6000.0, if (strong) 4.5 else 3.0)
        exciteIn = Biquad.highPass(sr, 2500.0, 0.707)
        exciteOut = Biquad.highPass(sr, 5000.0, 0.707)
        exciteOut2 = Biquad.highPass(sr, 5000.0, 0.707)
        envelope = 0f
        noiseFloor = 1f
        gain = 1f
        attack = exp(-1.0 / (0.002 * sr)).toFloat()
        release = exp(-1.0 / (0.06 * sr)).toFloat()
        floorRise = (1.0 / (4 * sr)).toFloat()
        limiterRelease = (1 - exp(-1.0 / (0.15 * sr))).toFloat()
        hiDetect = Biquad.highPass(sr, 3000.0, 0.707)
        hiEnv = 0f
        fullEnv = 0f
        tameAttack = exp(-1.0 / (0.005 * sr)).toFloat()
        tameRelease = exp(-1.0 / (0.08 * sr)).toFloat()
        exciteMix = if (strong) 0.9f else 0.5f
        midGain = if (strong) 1.25f else 1.12f
        sideGain = if (strong) 0.85f else 1f
        subGain = if (strong) 0.6f else 0.8f
        outGain = if (strong) 0.8f else 0.85f
        frame = FloatArray(channels)
    }

    /** The dialogue part: mud cut, speech lift, exciter, treble, then turned up. */
    private fun dialogue(input: Float): Float {
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
        val bright = treble.run(lifted + harmonics * exciteMix * (env * env / (env * env + threshold * threshold)))
        // Already high-pitched sound (whistles, "s" sounds, strings) gets much less of the lift:
        // that's what made high notes piercing. Dull dialogue still gets all of it.
        val hi = abs(hiDetect.run(base))
        hiEnv = (if (hi > hiEnv) tameAttack else tameRelease) * (hiEnv - hi) + hi
        val full = abs(base)
        fullEnv = (if (full > fullEnv) tameAttack else tameRelease) * (fullEnv - full) + full
        val ratio = hiEnv / maxOf(fullEnv, 1e-6f)
        val tame = (1.25f / (1 + (ratio / 0.4f) * (ratio / 0.4f))).coerceIn(0.15f, 1f)
        return (base + (bright - base) * tame) * midGain
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val lvl = level
        if (lvl <= 0) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        if (builtFor != lvl) build(lvl)
        val ch = channels
        val f = frame
        while (inputBuffer.remaining() >= 2 * ch) {
            for (c in 0 until ch) f[c] = lowCut[c].run(inputBuffer.short / 32768f)
            when {
                ch == 1 -> f[0] = dialogue(f[0])
                ch == 2 -> {
                    // Middle (dialogue) and sides (music, effects).
                    val mid = dialogue((f[0] + f[1]) * 0.5f)
                    val side = (f[0] - f[1]) * 0.5f * sideGain
                    f[0] = mid + side
                    f[1] = mid - side
                }
                else -> {
                    // 5.1 / 7.1 order: FL FR FC LFE ...: dialogue is the centre channel.
                    f[2] = dialogue(f[2])
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
        builtFor = -1
    }

    override fun onReset() {
        builtFor = -1
        channels = 0
        rate = 0
    }

    /** One second-order filter (RBJ audio cookbook), with its own memory. */
    private class Biquad(
        private val b0: Float, private val b1: Float, private val b2: Float,
        private val a1: Float, private val a2: Float,
    ) {
        private var z1 = 0f
        private var z2 = 0f

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
