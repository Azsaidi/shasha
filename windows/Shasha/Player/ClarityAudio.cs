using System.Runtime.InteropServices;
using LibVLCSharp.Shared;
using NAudio.Wave;

namespace Shasha.Player;

/// <summary>
/// "Clearer sound" for dull, muffled soundtracks. Same processing as the phone apps.
///
/// Dialogue sits in the middle of a stereo mix; music and effects are spread to the sides. So the
/// sound is split into its middle (L+R) and sides (L−R), and only the middle is worked on: less
/// boxy low-mid "mud", a lift where speech is understood (2–3 kHz), an exciter that recreates the
/// "air" a bad encode cut off (only above the background noise, so hiss isn't turned into "rain"),
/// and a little treble. The middle is then turned up against the sides; music on the sides is left
/// alone. A smooth limiter (gain riding, not clipping) keeps peaks in check without buzzing.
/// Level 0 leaves the sound untouched.
/// </summary>
public sealed class ClarityDsp
{
    int _level = -1;
    double _rate;
    Biquad _lowCutL = Biquad.HighPass(48000, 80), _lowCutR = Biquad.HighPass(48000, 80), _mud = Biquad.HighPass(48000, 80),
           _presence = Biquad.HighPass(48000, 80), _treble = Biquad.HighPass(48000, 80), _exciteIn = Biquad.HighPass(48000, 80),
           _exciteOut = Biquad.HighPass(48000, 80), _exciteOut2 = Biquad.HighPass(48000, 80),
           _topCutL = Biquad.HighPass(48000, 80), _topCutR = Biquad.HighPass(48000, 80);
    float _envelope, _noiseFloor = 1, _attack, _release, _floorRise, _limiterRelease;
    float _mix, _midGain = 1, _sideGain = 1, _outGain = 1, _gain = 1;

    public void Reset() => _level = -1;

    /// <summary>Processes interleaved stereo samples in place.</summary>
    public void Process(Span<float> samples, int level, double rate)
    {
        if (level <= 0) return;
        if (level != _level || rate != _rate) Build(level, rate);
        for (var i = 0; i + 1 < samples.Length; i += 2)
        {
            var l = _lowCutL.Run(samples[i]);
            var r = _lowCutR.Run(samples[i + 1]);
            var mid = (l + r) * 0.5f;
            var side = (l - r) * 0.5f * _sideGain;
            mid = _mud.Run(mid);
            mid = _presence.Run(mid);
            // Exciter: square the upper mids (scaled to their level) for smooth harmonics an octave up.
            var upper = _exciteIn.Run(mid);
            var a = MathF.Abs(upper);
            var k = a > _envelope ? _attack : _release;
            var env = MathF.Max(k * _envelope + (1 - k) * a, 1e-6f);
            _envelope = env;
            _noiseFloor = env < _noiseFloor ? env : _noiseFloor + (env - _noiseFloor) * _floorRise;
            var harmonics = _exciteOut2.Run(_exciteOut.Run(upper * upper / env));
            // Only while the sound stands clearly above the background noise (about 10 dB).
            var threshold = MathF.Max(3 * _noiseFloor, 3e-4f);
            mid += harmonics * _mix * (env * env / (env * env + threshold * threshold));
            mid = _treble.Run(mid) * _midGain;
            var outL = _topCutL.Run(mid + side) * _outGain;
            var outR = _topCutR.Run(mid - side) * _outGain;
            // Smooth limiter: drop the gain at once on a peak, bring it back over ~150 ms.
            var peak = MathF.Max(MathF.Abs(outL), MathF.Abs(outR));
            var target = peak > 0.89f ? 0.89f / peak : 1f;
            if (target < _gain) _gain = target; else _gain += (target - _gain) * _limiterRelease;
            samples[i] = Math.Clamp(outL * _gain, -1f, 1f);
            samples[i + 1] = Math.Clamp(outR * _gain, -1f, 1f);
        }
    }

    void Build(int level, double rate)
    {
        _level = level;
        _rate = rate;
        var strong = level >= 2;
        _lowCutL = Biquad.HighPass(rate, 80);
        _lowCutR = Biquad.HighPass(rate, 80);
        _mud = Biquad.Peak(rate, 300, 1, strong ? -3 : -2);
        _presence = Biquad.Peak(rate, 2500, 0.9, strong ? 6 : 4);
        _exciteIn = Biquad.HighPass(rate, 2500);
        _exciteOut = Biquad.HighPass(rate, 5000);
        _exciteOut2 = Biquad.HighPass(rate, 5000);
        _treble = Biquad.HighShelf(rate, 6000, strong ? 4.5 : 3);
        _topCutL = Biquad.LowPass(rate, 16000);
        _topCutR = Biquad.LowPass(rate, 16000);
        _envelope = 0;
        _noiseFloor = 1;
        _gain = 1;
        _attack = (float)Math.Exp(-1 / (0.002 * rate));
        _release = (float)Math.Exp(-1 / (0.06 * rate));
        _floorRise = (float)(1 / (4 * rate));
        _limiterRelease = (float)(1 - Math.Exp(-1 / (0.15 * rate)));
        _mix = strong ? 0.9f : 0.5f;
        _midGain = strong ? 1.25f : 1.12f;
        _sideGain = strong ? 0.85f : 1f;
        _outGain = strong ? 0.8f : 0.85f;
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

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    delegate long ClockFn();
    static ClockFn? _clockFn;

    /// <summary>
    /// LibVLC's clock (µs), which VLC dates the sound with. Taken from the libvlc.dll VLC already
    /// loaded; if that fails, the Windows performance counter, which is what VLC's clock reads.
    /// </summary>
    static long libvlc_clock()
    {
        if (_clockFn == null)
        {
            try
            {
                var path = System.IO.Path.Combine(AppContext.BaseDirectory, "libvlc", "win-x64", "libvlc.dll");
                var lib = NativeLibrary.Load(System.IO.File.Exists(path) ? path : "libvlc.dll");
                _clockFn = Marshal.GetDelegateForFunctionPointer<ClockFn>(NativeLibrary.GetExport(lib, "libvlc_clock"));
                Core.Trace.Write($"clarity: clock from libvlc ({_clockFn()} vs counter {CounterClock()})");
            }
            catch (Exception e)
            {
                _clockFn = CounterClock;
                Core.Trace.Write("clarity: clock from counter, libvlc_clock unavailable: " + e.Message);
            }
        }
        return _clockFn();
    }

    static long CounterClock() =>
        (long)(System.Diagnostics.Stopwatch.GetTimestamp() * (1_000_000.0 / System.Diagnostics.Stopwatch.Frequency));

    /// <summary>
    /// True when VLC's timestamps don't match the clock at all: then the sound simply plays in the
    /// order it arrives, with a small buffer, instead of waiting for times that never come.
    /// </summary>
    bool _freeRun;
    int _traced;

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
        // No volume callback: VLC then applies the volume to the samples itself.
        const int latencyMs = 80;
        _out = new WasapiOut(NAudio.CoreAudioApi.AudioClientShareMode.Shared, true, latencyMs);
        _latency = latencyMs * 1000L;
        _out.Init(this);
        _out.Play();
        _out.PlaybackStopped += (_, e) => Core.Trace.Write("clarity: sound device stopped " + e.Exception?.Message);
        Core.Trace.Write($"clarity: sound device ready ({_out.OutputWaveFormat})");
    }

    public void Dispose()
    {
        try { _out.Stop(); } catch { }
        _out.Dispose();
    }

    void OnPlay(IntPtr data, IntPtr samples, uint count, long pts)
    {
        if (count == 0) return;
        try { Receive(samples, count, pts); } catch { }
    }

    void Receive(IntPtr samples, uint count, long pts)
    {
        var clock = libvlc_clock();
        if (_traced < 8)
        {
            _traced++;
            Core.Trace.Write($"clarity: piece {count} frames, pts {pts}, clock {clock}, early by {(pts - clock) / 1000} ms");
        }
        if (_traced == 1 && Math.Abs(pts - clock) > 10_000_000)
        {
            _freeRun = true;
            Core.Trace.Write("clarity: timestamps don't match the clock, playing in arrival order");
        }
        var copy = new float[count * 2];
        Marshal.Copy(samples, copy, 0, copy.Length);
        var before = Peak(copy);
        _dsp.Process(copy, Level, Rate);
        if (_traced <= 8 || (_pieces++ % 500 == 0 && _pieces < 5000))
            Core.Trace.Write($"clarity: level in {before:0.000} out {Peak(copy):0.000} (setting {Level})");
        lock (_lock)
        {
            var chunk = new Chunk { Pts = pts, Samples = copy };
            // A piece that arrives while paused was dated before the pause: keep it in line.
            if (_pausedAt is long paused) chunk.Pts += clock - paused;
            _chunks.Add(chunk);
            if (_chunks.Count > 400) _chunks.RemoveRange(0, _chunks.Count - 400);
        }
    }

    int _pieces;

    static float Peak(float[] samples)
    {
        var p = 0f;
        foreach (var v in samples) p = float.IsNaN(v) ? float.NaN : MathF.Max(p, MathF.Abs(v));
        return p;
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

    bool _started;
    double _drift;
    int _reads;
    int _starts;

    /// <summary>
    /// Audio thread: fills the buffer with what's due now. Plays pieces back to back while in sync,
    /// lines up with VLC's clock when starting, and only re-aligns (with a fade) after drifting more
    /// than 80 ms, so there are no clicks.
    /// </summary>
    public int Read(float[] buffer, int offset, int count)
    {
        var frames = count / 2;
        var written = 0;
        const int fadeFrames = 256;
        try
        {
            var now = libvlc_clock() + _latency;
            lock (_lock)
            {
                if (_pausedAt != null || _chunks.Count == 0) { _started = false; return Silence(); }
                var head = _chunks[0];
                var lead = (double)(head.Pts + (long)(head.Read * 1_000_000.0 / Rate) - now);
                if (_freeRun)
                {
                    // Start once ~100 ms is waiting, then just play in order.
                    var buffered = 0;
                    foreach (var ch in _chunks) buffered += ch.Frames - ch.Read;
                    lead = _started ? 0 : (buffered >= Rate / 10 ? 0 : 1e9);
                }
                var fadeIn = 0;
                var fadeOut = false;
                if (!_started)
                {
                    if (lead > 0)
                    {
                        var wait = (int)(lead / 1_000_000 * Rate);
                        if (wait >= frames) return Silence();
                        Array.Clear(buffer, offset, wait * 2);
                        written = wait;
                    }
                    else if (lead < -20_000)
                    {
                        Skip((int)(-lead / 1_000_000 * Rate));
                        if (_chunks.Count == 0) return Silence();
                    }
                    _started = true;
                    _drift = 0;
                    fadeIn = fadeFrames;
                    if (_starts++ < 10) Core.Trace.Write($"clarity: start, {lead / 1000:0} ms from due, {_chunks.Count} pieces waiting");
                }
                else
                {
                    _drift = _drift * 0.95 + lead * 0.05;
                    if (Math.Abs(_drift) > 80_000)
                    {
                        fadeOut = true;
                        _started = false;
                        if (_starts < 10) Core.Trace.Write($"clarity: re-align, {_drift / 1000:0} ms out");
                    }
                }
                var gain = _muted ? 0 : _volume;
                var first = written;
                var played = 0;
                var limit = fadeOut ? Math.Min(frames, written + fadeFrames) : frames;
                while (written < limit && _chunks.Count > 0)
                {
                    var c = _chunks[0];
                    var take = Math.Min(limit - written, c.Frames - c.Read);
                    var src = c.Read * 2;
                    var dst = offset + written * 2;
                    for (var i = 0; i < take; i++)
                    {
                        var g = played < fadeIn ? gain * played / fadeIn : gain;
                        buffer[dst + i * 2] = c.Samples[src + i * 2] * g;
                        buffer[dst + i * 2 + 1] = c.Samples[src + i * 2 + 1] * g;
                        played++;
                    }
                    c.Read += take;
                    written += take;
                    if (c.Read >= c.Frames) _chunks.RemoveAt(0);
                }
                var ranOut = written < frames && !fadeOut;
                if (ranOut) _started = false;
                if (fadeOut || ranOut)
                {
                    // Fade the end of what was written so stopping doesn't click.
                    var n = Math.Min(fadeFrames, written - first);
                    for (var k = 0; k < n; k++)
                    {
                        var at = offset + (written - n + k) * 2;
                        var g = (float)(n - k) / n;
                        buffer[at] *= g;
                        buffer[at + 1] *= g;
                    }
                }
            }
        }
        catch (Exception e)
        {
            // Never let the sound device stop: play silence for this pass instead.
            if (_starts < 10) { _starts++; Core.Trace.Write("clarity: read failed " + e.Message); }
        }
        if (written < frames) Array.Clear(buffer, offset + written * 2, (frames - written) * 2);
        if (_reads++ % 400 == 0 && _reads < 4000)
        {
            var peak = 0f;
            for (var i = offset; i < offset + count; i++) peak = MathF.Max(peak, MathF.Abs(buffer[i]));
            Core.Trace.Write($"clarity: to speakers {peak:0.000}, gain {(_muted ? 0 : _volume):0.00}, started {_started}, {_chunks.Count} waiting");
        }
        return count;

        int Silence()
        {
            Array.Clear(buffer, offset, count);
            written = frames;
            return count;
        }
    }

    void Skip(int frames)
    {
        while (frames > 0 && _chunks.Count > 0)
        {
            var c = _chunks[0];
            var left = c.Frames - c.Read;
            if (frames >= left) { _chunks.RemoveAt(0); frames -= left; }
            else { c.Read += frames; frames = 0; }
        }
    }
}
