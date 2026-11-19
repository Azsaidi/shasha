using System.Runtime.InteropServices;
using LibVLCSharp.Shared;
using NAudio.Wave;

namespace Shasha.Player;

/// <summary>
/// "Clearer sound" for dull, muffled soundtracks.
///
/// An equaliser can only turn up treble that is in the file. Badly encoded episodes have had their
/// top end cut off, so there is nothing to turn up. This rebuilds it instead: cuts the boomy low
/// end, squares the upper mids (scaled to their own level) to make smooth harmonics an octave up
/// and mixes those in (an exciter) to recreate the missing "air", lifts the speech range and the
/// treble, and rounds off peaks so the result never clips. The exciter tracks the background
/// noise and only works above it, so hiss isn't turned into "rain".
/// Level 0 leaves the sound untouched. Same processing as the phone apps.
/// </summary>
public sealed class ClarityDsp
{
    int _level = -1;
    double _rate;
    Biquad[] _lowCut = Array.Empty<Biquad>(), _exciteIn = Array.Empty<Biquad>(), _exciteOut = Array.Empty<Biquad>(),
             _exciteOut2 = Array.Empty<Biquad>(), _presence = Array.Empty<Biquad>(), _treble = Array.Empty<Biquad>(),
             _topCut = Array.Empty<Biquad>();
    readonly float[] _envelope = new float[2], _noiseFloor = { 1, 1 };
    float _attack, _release, _floorRise;
    float _mix, _outGain = 1;

    public void Reset() => _level = -1;

    /// <summary>Processes interleaved stereo samples in place.</summary>
    public void Process(Span<float> samples, int level, double rate)
    {
        if (level <= 0) return;
        if (level != _level || rate != _rate) Build(level, rate);
        for (var i = 0; i + 1 < samples.Length; i += 2)
        {
            for (var c = 0; c < 2; c++)
            {
                var y = _lowCut[c].Run(samples[i + c]);
                var upper = _exciteIn[c].Run(y);
                // How loud the upper mids are, and the background noise level under them.
                var a = MathF.Abs(upper);
                var k = a > _envelope[c] ? _attack : _release;
                var env = MathF.Max(k * _envelope[c] + (1 - k) * a, 1e-6f);
                _envelope[c] = env;
                _noiseFloor[c] = env < _noiseFloor[c] ? env : _noiseFloor[c] + (env - _noiseFloor[c]) * _floorRise;
                var harmonics = _exciteOut2[c].Run(_exciteOut[c].Run(upper * upper / env));
                // Only add them while the sound stands clearly above the noise (about 10 dB).
                var threshold = MathF.Max(3 * _noiseFloor[c], 3e-4f);
                var gate = env * env / (env * env + threshold * threshold);
                y += harmonics * _mix * gate;
                y = _presence[c].Run(y);
                y = _treble[c].Run(y);
                y = _topCut[c].Run(y);
                samples[i + c] = Limit(y * _outGain);
            }
        }
    }

    void Build(int level, double rate)
    {
        _level = level;
        _rate = rate;
        var strong = level >= 2;
        Biquad[] Two(Func<Biquad> make) => new[] { make(), make() };
        _lowCut = Two(() => Biquad.HighPass(rate, 80));
        _exciteIn = Two(() => Biquad.HighPass(rate, 2500));
        _exciteOut = Two(() => Biquad.HighPass(rate, 5000));
        _exciteOut2 = Two(() => Biquad.HighPass(rate, 5000));
        _presence = Two(() => Biquad.Peak(rate, 3000, 0.7, strong ? 3 : 2));
        _treble = Two(() => Biquad.HighShelf(rate, 5000, strong ? 6 : 4));
        _topCut = Two(() => Biquad.LowPass(rate, 16000));
        _envelope[0] = _envelope[1] = 0;
        _noiseFloor[0] = _noiseFloor[1] = 1;
        _attack = (float)Math.Exp(-1 / (0.002 * rate));
        _release = (float)Math.Exp(-1 / (0.06 * rate));
        _floorRise = (float)(1 / (4 * rate));
        _mix = strong ? 1.4f : 0.8f;
        _outGain = strong ? 0.8f : 0.85f;
    }

    /// <summary>Leaves quiet sound alone and rounds off peaks instead of clipping them.</summary>
    static float Limit(float x)
    {
        var a = MathF.Abs(x);
        if (a <= 0.7f) return x;
        var shaped = 0.7f + 0.3f * MathF.Tanh((a - 0.7f) / 0.3f);
        return x < 0 ? -shaped : shaped;
    }

    /// <summary>One second-order filter (RBJ audio cookbook), with its own memory.</summary>
    sealed class Biquad
    {
        readonly float _b0, _b1, _b2, _a1, _a2;
        float _z1, _z2;

        Biquad(double b0, double b1, double b2, double a0, double a1, double a2)
        {
            _b0 = (float)(b0 / a0); _b1 = (float)(b1 / a0); _b2 = (float)(b2 / a0);
            _a1 = (float)(a1 / a0); _a2 = (float)(a2 / a0);
        }

        public float Run(float x)
        {
            var y = _b0 * x + _z1;
            _z1 = _b1 * x - _a1 * y + _z2;
            _z2 = _b2 * x - _a2 * y;
            return y;
        }

        static double Omega(double rate, double hz) => 2 * Math.PI * Math.Min(hz, rate * 0.45) / rate;

        public static Biquad HighPass(double rate, double hz, double q = 0.707)
        {
            var w = Omega(rate, hz); var alpha = Math.Sin(w) / (2 * q); var c = Math.Cos(w);
            return new Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha);
        }

        public static Biquad LowPass(double rate, double hz, double q = 0.707)
        {
            var w = Omega(rate, hz); var alpha = Math.Sin(w) / (2 * q); var c = Math.Cos(w);
            return new Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha);
        }

        public static Biquad Peak(double rate, double hz, double q, double db)
        {
            var a = Math.Pow(10, db / 40); var w = Omega(rate, hz); var alpha = Math.Sin(w) / (2 * q); var c = Math.Cos(w);
            return new Biquad(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a);
        }

        public static Biquad HighShelf(double rate, double hz, double db)
        {
            var a = Math.Pow(10, db / 40); var w = Omega(rate, hz); var c = Math.Cos(w);
            var s = 2 * Math.Sqrt(a) * Math.Sin(w) / 2 * Math.Sqrt(2.0);
            return new Biquad(
                a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
                (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s);
        }
    }
}

/// <summary>
/// Takes VLC's decoded sound, runs it through <see cref="ClarityDsp"/> and plays it with WASAPI,
/// each piece when VLC says it's due, so it stays in sync with the picture.
/// </summary>
public sealed class ClarityAudio : ISampleProvider, IDisposable
{
    const int Rate = 48000;

    [DllImport("libvlc", CallingConvention = CallingConvention.Cdecl)]
    static extern long libvlc_clock();

    sealed class Chunk
    {
        public long Pts;
        public float[] Samples = Array.Empty<float>();
        public int Read;
        public int Frames => Samples.Length / 2;
    }

    readonly object _lock = new();
    readonly List<Chunk> _chunks = new();
    readonly ClarityDsp _dsp = new();
    readonly WasapiOut _out;
    long? _pausedAt;
    float _volume = 1;
    bool _muted;
    readonly long _latency;

    // Kept so the garbage collector doesn't free the callbacks LibVLC holds.
    readonly MediaPlayer.LibVLCAudioPlayCb _play;
    readonly MediaPlayer.LibVLCAudioPauseCb _pause;
    readonly MediaPlayer.LibVLCAudioResumeCb _resume;
    readonly MediaPlayer.LibVLCAudioFlushCb _flush;
    readonly MediaPlayer.LibVLCAudioDrainCb _drain;
    readonly MediaPlayer.LibVLCVolumeCb _volumeCb;

    public WaveFormat WaveFormat { get; } = WaveFormat.CreateIeeeFloatWaveFormat(Rate, 2);

    /// <summary>0 = untouched, 1 = clearer, 2 = much clearer.</summary>
    public volatile int Level;

    public ClarityAudio(MediaPlayer player)
    {
        _play = OnPlay;
        _pause = (_, _) => SetPaused(true);
        _resume = (_, _) => SetPaused(false);
        _flush = (_, _) => Flush();
        _drain = _ => { };
        _volumeCb = (_, volume, mute) => { lock (_lock) { _volume = volume; _muted = mute; } };
        player.SetAudioFormat("FL32", Rate, 2);
        player.SetAudioCallbacks(_play, _pause, _resume, _flush, _drain);
        player.SetVolumeCallback(_volumeCb);
        const int latencyMs = 80;
        _out = new WasapiOut(NAudio.CoreAudioApi.AudioClientShareMode.Shared, true, latencyMs);
        _latency = latencyMs * 1000L;
        _out.Init(this);
        _out.Play();
    }

    public void Dispose()
    {
        try { _out.Stop(); } catch { }
        _out.Dispose();
    }

    void OnPlay(IntPtr data, IntPtr samples, uint count, long pts)
    {
        if (count == 0) return;
        var copy = new float[count * 2];
        Marshal.Copy(samples, copy, 0, copy.Length);
        _dsp.Process(copy, Level, Rate);
        lock (_lock)
        {
            var chunk = new Chunk { Pts = pts, Samples = copy };
            // A piece that arrives while paused was dated before the pause: keep it in line.
            if (_pausedAt is long paused) chunk.Pts += libvlc_clock() - paused;
            _chunks.Add(chunk);
            if (_chunks.Count > 400) _chunks.RemoveRange(0, _chunks.Count - 400);
        }
    }

    void SetPaused(bool paused)
    {
        var now = libvlc_clock();
        lock (_lock)
        {
            if (paused) { _pausedAt ??= now; return; }
            if (_pausedAt is not long at) return;
            // Everything waiting is now due that much later.
            var shift = now - at;
            foreach (var c in _chunks) c.Pts += shift;
            _pausedAt = null;
        }
    }

    void Flush()
    {
        lock (_lock) _chunks.Clear();
        _dsp.Reset();
    }

    /// <summary>Audio thread: fills the buffer with what's due now.</summary>
    public int Read(float[] buffer, int offset, int count)
    {
        var frames = count / 2;
        var now = libvlc_clock() + _latency;
        var written = 0;
        lock (_lock)
        {
            if (_pausedAt == null && _chunks.Count > 0)
            {
                // Line up with the clock: skip what's late, wait for what's early.
                var head = _chunks[0];
                var drift = head.Pts + (long)(head.Read * 1_000_000.0 / Rate) - now;
                if (drift > 40_000)
                {
                    written = Math.Min(frames, (int)(drift / 1_000_000.0 * Rate));
                }
                else if (drift < -40_000)
                {
                    var skip = (int)(-drift / 1_000_000.0 * Rate);
                    while (skip > 0 && _chunks.Count > 0)
                    {
                        var c = _chunks[0];
                        var left = c.Frames - c.Read;
                        if (skip >= left) { _chunks.RemoveAt(0); skip -= left; }
                        else { c.Read += skip; skip = 0; }
                    }
                }
                Array.Clear(buffer, offset, written * 2);
                var gain = _muted ? 0 : _volume;
                while (written < frames && _chunks.Count > 0)
                {
                    var c = _chunks[0];
                    var take = Math.Min(frames - written, c.Frames - c.Read);
                    var src = c.Read * 2;
                    var dst = offset + written * 2;
                    for (var i = 0; i < take * 2; i++) buffer[dst + i] = c.Samples[src + i] * gain;
                    c.Read += take;
                    written += take;
                    if (c.Read >= c.Frames) _chunks.RemoveAt(0);
                }
            }
        }
        if (written < frames) Array.Clear(buffer, offset + written * 2, (frames - written) * 2);
        return count;
    }
}
