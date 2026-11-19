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
import kotlin.math.tanh

/**
 * "Clearer sound" for dull, muffled soundtracks.
 *
 * An equaliser can only turn up treble that is in the file. Badly encoded episodes have had
 * their top end cut off, so there is nothing to turn up. This rebuilds it instead:
 *  - cuts the boomy low end,
 *  - an exciter: takes the upper mids and squares them (scaled to their own level), which
 *    makes smooth harmonics an octave up, and mixes in only the new top end. That brings back
 *    the "air" that was lost. It only works while something is actually there (speech, music):
 *    it tracks the background noise and stays quiet below it, so hiss isn't turned into "rain",
 *  - takes out some boxy low-mid "mud", lifts the presence range where speech sits, and the treble,
 *  - in 5.1 sound, turns up the centre (dialogue) channel and turns down the sub,
 *  - a soft limiter so the louder result never clips.
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
    private lateinit var exciteIn: Array<Biquad>
    private lateinit var exciteOut: Array<Biquad>
    private lateinit var exciteOut2: Array<Biquad>
    private lateinit var mud: Array<Biquad>
    private lateinit var presence: Array<Biquad>
    private lateinit var treble: Array<Biquad>
    private lateinit var topCut: Array<Biquad>
    private lateinit var envelope: FloatArray
    private lateinit var noiseFloor: FloatArray
    private var attack = 0f
    private var release = 0f
    private var floorRise = 0f

    private var exciteMix = 0f
    private var centreGain = 1f
    private var subGain = 1f
    private var outGain = 1f

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
        exciteIn = Array(channels) { Biquad.highPass(sr, 2500.0, 0.707) }
        exciteOut = Array(channels) { Biquad.highPass(sr, 5000.0, 0.707) }
        exciteOut2 = Array(channels) { Biquad.highPass(sr, 5000.0, 0.707) }
        mud = Array(channels) { Biquad.peak(sr, 300.0, 1.0, if (strong) -3.0 else -2.0) }
        presence = Array(channels) { Biquad.peak(sr, 2500.0, 0.9, if (strong) 6.0 else 4.0) }
        treble = Array(channels) { Biquad.highShelf(sr, 6000.0, if (strong) 4.5 else 3.0) }
        topCut = Array(channels) { Biquad.lowPass(sr, 16000.0, 0.707) }
        envelope = FloatArray(channels)
        noiseFloor = FloatArray(channels) { 1f }
        attack = exp(-1.0 / (0.002 * sr)).toFloat()
        release = exp(-1.0 / (0.06 * sr)).toFloat()
        floorRise = (1.0 / (4 * sr)).toFloat()
        exciteMix = if (strong) 0.9f else 0.5f
        centreGain = if (strong) 2.0f else 1.4f
        subGain = if (strong) 0.5f else 0.75f
        outGain = if (strong) 0.8f else 0.85f
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
        // Standard 5.1 / 7.1 order: FL FR FC LFE ...
        val centre = if (ch >= 6) 2 else -1
        val sub = if (ch >= 6) 3 else -1
        while (inputBuffer.remaining() >= 2) {
            for (c in 0 until ch) {
                if (inputBuffer.remaining() < 2) break
                val x = inputBuffer.short / 32768f
                var y: Float
                if (c == sub) {
                    y = x * subGain
                } else {
                    y = lowCut[c].run(x)
                    val upper = exciteIn[c].run(y)
                    // How loud the upper mids are, and the background noise level under them.
                    val a = abs(upper)
                    val k = if (a > envelope[c]) attack else release
                    val env = maxOf(k * envelope[c] + (1 - k) * a, 1e-6f)
                    envelope[c] = env
                    noiseFloor[c] = if (env < noiseFloor[c]) env else noiseFloor[c] + (env - noiseFloor[c]) * floorRise
                    val harmonics = exciteOut2[c].run(exciteOut[c].run(upper * upper / env))
                    // Only add them while the sound stands clearly above the noise (about 10 dB).
                    val threshold = maxOf(3 * noiseFloor[c], 3e-4f)
                    val gate = env * env / (env * env + threshold * threshold)
                    y += harmonics * exciteMix * gate
                    y = mud[c].run(y)
                    y = presence[c].run(y)
                    y = treble[c].run(y)
                    y = topCut[c].run(y)
                    if (c == centre) y *= centreGain
                }
                y = limit(y * outGain)
                out.putShort((y * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        out.flip()
    }

    /** Leaves quiet sound alone and rounds off peaks instead of clipping them. */
    private fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= 0.7f) return x
        val over = (a - 0.7f) / 0.3f
        val shaped = 0.7f + 0.3f * tanh(over.toDouble()).toFloat()
        return if (x < 0) -shaped else shaped
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
