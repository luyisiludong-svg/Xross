#!/usr/bin/env python3
"""
Synthesizes the Default_V2 theme pack's UI sounds from scratch (numpy only), so the pack ships
no third-party audio. Every sound is made here, so the output belongs to the project.

  python3 tools/audio/synth_ui_sounds.py <pack>/sounds

Writes 48 kHz 16-bit stereo WAVs, one per SoundRole file name (theme/engine ThemeManifest.kt),
plus opening.wav for the startup cue. Levels follow tools/audio/relevel-default-sounds.sh:
short effects peak at -3 dBFS; system_ng, trophy, opening and gameboot are set to -16 LUFS with
the true peak at or below -1 dBTP (measured with ffmpeg when it's on PATH, else peak -3 dBFS).
The balance between roles is the pack's audio.soundGains, not the file levels.

The sound: soft FM bells and sine blips with a short synthetic room, in the spirit of a console
menu: quiet ticks for movement, rising notes for "in / yes", falling ones for "out / back".
Output is deterministic (fixed noise seeds), so rerunning it gives the same files.
"""
import os
import re
import shutil
import subprocess
import sys
import wave

import numpy as np

SR = 48000


def t_axis(dur):
    return np.arange(int(SR * dur)) / SR


def note(name):
    """'A4' → 440 Hz; sharps as 'F#4'."""
    m = re.fullmatch(r"([A-G])(#?)(-?\d)", name)
    semis = {"C": -9, "D": -7, "E": -5, "F": -4, "G": -2, "A": 0, "B": 2}[m.group(1)] + (1 if m.group(2) else 0)
    return 440.0 * 2 ** ((semis + 12 * (int(m.group(3)) - 4)) / 12)


def env(t, attack, tau):
    """Linear attack, then exponential decay with time constant tau."""
    a = np.clip(t / max(attack, 1e-6), 0, 1)
    return a * np.exp(-np.maximum(t - attack, 0) / tau)


def tail(x, fade=0.012):
    """Fades a voice out over its last `fade` seconds, so cutting it off at `dur` can't click."""
    n = min(len(x), int(SR * fade))
    x[-n:] *= np.linspace(1, 0, n) ** 2
    return x


def bell(f, dur, tau, ratio=3.5, index=1.2, attack=0.002):
    """A soft FM bell: the modulation fades faster than the tone, so it starts bright and mellows."""
    t = t_axis(dur)
    i = index * np.exp(-t / (tau * 0.35))
    tone = np.sin(2 * np.pi * f * t + i * np.sin(2 * np.pi * f * ratio * t))
    return tail(tone * env(t, attack, tau))


def blip(f, dur, tau, overtone=0.15, attack=0.001):
    t = t_axis(dur)
    s = np.sin(2 * np.pi * f * t) + overtone * np.sin(2 * np.pi * 2 * f * t) * np.exp(-t / (tau * 0.5))
    return tail(s * env(t, attack, tau))


def glide(f0, f1, dur, tau, glide_time):
    t = t_axis(dur)
    k = np.clip(t / glide_time, 0, 1)
    f = f0 * (f1 / f0) ** (k * k * (3 - 2 * k))  # smoothstep in pitch
    phase = 2 * np.pi * np.cumsum(f) / SR
    return tail((np.sin(phase) + 0.12 * np.sin(2 * phase)) * env(t, 0.003, tau))


def additive(f, t, brightness, harmonics=14, detune_cents=0.0, phase=0.0):
    """A band-limited saw-like tone whose upper harmonics follow brightness(t) in 0..1."""
    f = f * 2 ** (detune_cents / 1200)
    out = np.zeros_like(t)
    for n in range(1, harmonics + 1):
        if f * n > SR * 0.45:
            break
        amp = (1.0 / n) * brightness ** (n - 1)
        out += amp * np.sin(2 * np.pi * f * n * t + phase * n)
    return out


def at(dst, src, start):
    """Mixes mono or stereo src into stereo dst at start seconds."""
    i = int(start * SR)
    src = src if src.ndim == 2 else np.stack([src, src], axis=1)
    n = min(len(src), len(dst) - i)
    part = src[:n].copy()
    if n < len(src):  # cut short by the buffer's end: fade rather than click
        k = min(n, int(SR * 0.012))
        part[-k:] *= (np.linspace(1, 0, k) ** 2)[:, None]
    dst[i:i + n] += part
    return dst


def silence(dur):
    return np.zeros((int(SR * dur), 2))


def spectral_lowpass(x, cutoff, slope=2.0):
    """Zero-phase gentle lowpass done in the frequency domain (per channel)."""
    n = len(x)
    freqs = np.fft.rfftfreq(n, 1 / SR)
    gain = 1 / (1 + (freqs / cutoff) ** (2 * slope)) ** 0.5
    if x.ndim == 1:
        return np.fft.irfft(np.fft.rfft(x) * gain, n)
    return np.stack([np.fft.irfft(np.fft.rfft(x[:, c]) * gain, n) for c in range(x.shape[1])], axis=1)


def room(x, decay, wet, damp=6000.0, seed=1):
    """Convolves with a synthetic, slightly different stereo impulse response (decaying filtered noise)."""
    rng = np.random.default_rng(seed)
    n_ir = int(SR * decay)
    t = np.arange(n_ir) / SR
    out = np.zeros((len(x) + n_ir, 2))
    dry = x if x.ndim == 2 else np.stack([x, x], axis=1)
    out[:len(dry)] += dry * (1 - wet * 0.5)
    for c in range(2):
        ir = rng.standard_normal(n_ir) * np.exp(-6.9 * t / decay)  # -60 dB at `decay`
        ir[: int(SR * 0.008)] *= np.linspace(0, 1, int(SR * 0.008))  # short pre-delay ramp
        ir = spectral_lowpass(ir, damp)
        ir /= np.sqrt(np.sum(ir ** 2)) + 1e-12
        m = len(dry) + n_ir
        size = 1 << (m - 1).bit_length()
        y = np.fft.irfft(np.fft.rfft(dry[:, c], size) * np.fft.rfft(ir, size), size)[:m]
        out[:, c] += wet * y
    return out


def finish(x, length=None, fade_out=0.006):
    """Trims to length, and ramps both ends so no sound starts or stops with a click."""
    if length is not None:
        x = x[: int(SR * length)]
    x = x.copy()
    n_in, n_out = int(SR * 0.0015), int(SR * fade_out)
    x[:n_in] *= np.linspace(0, 1, n_in)[:, None]
    x[-n_out:] *= np.linspace(1, 0, n_out)[:, None] ** 2
    return x


# ---------------------------------------------------------------- the sounds


def cursor():
    s = at(silence(0.07), blip(note("B6"), 0.07, 0.011, overtone=0.1) * 0.9, 0)
    return finish(room(s, 0.12, 0.10, damp=5000), 0.09, 0.02)


def category_switch():
    s = silence(0.12)
    at(s, blip(note("E6"), 0.12, 0.022, overtone=0.12), 0)
    at(s, blip(note("E5"), 0.12, 0.03, overtone=0.0) * 0.35, 0)
    return finish(room(s, 0.18, 0.12, damp=5000), 0.15, 0.03)


def confirm():
    s = silence(0.3)
    at(s, bell(note("E6"), 0.3, 0.06, index=0.9) * 0.8, 0)
    at(s, bell(note("B6"), 0.3, 0.08, index=0.9), 0.045)
    return finish(room(s, 0.3, 0.15), 0.32, 0.05)


def option():
    s = silence(0.25)
    at(s, bell(note("A5"), 0.25, 0.07, ratio=2.0, index=0.8), 0)
    at(s, bell(note("E6"), 0.25, 0.06, ratio=2.0, index=0.6) * 0.6, 0.012)
    return finish(room(s, 0.28, 0.15), 0.3, 0.05)


def back():
    s = silence(0.3)
    at(s, bell(note("B5"), 0.3, 0.06, index=0.7) * 0.85, 0)
    at(s, bell(note("E5"), 0.3, 0.08, index=0.6), 0.05)
    return finish(room(s, 0.3, 0.14, damp=4500), 0.32, 0.05)


def drill_in():
    s = at(silence(0.18), glide(note("A5"), note("E6"), 0.18, 0.05, 0.07), 0)
    return finish(room(s, 0.2, 0.12), 0.2, 0.04)


def drill_out():
    s = at(silence(0.18), glide(note("E6"), note("A5"), 0.18, 0.05, 0.07), 0)
    return finish(room(s, 0.2, 0.12, damp=4500), 0.2, 0.04)


def ok():
    s = silence(0.45)
    for k, (n, gain) in enumerate([("C6", 0.8), ("E6", 0.8), ("G6", 0.9), ("C7", 0.5)]):
        at(s, bell(note(n), 0.45, 0.12, index=1.0) * gain, 0.022 * k)
    return finish(room(s, 0.45, 0.2), 0.5, 0.08)


def system_ng():
    """Two low, slightly dissonant thuds: dull, not alarming."""
    s = silence(0.7)
    for start in (0.0, 0.17):
        t = t_axis(0.4)
        tone = additive(note("G3"), t, 0.35, harmonics=8) + 0.8 * additive(note("G#3"), t, 0.35, harmonics=8)
        at(s, tail(tone * env(t, 0.004, 0.07), 0.05), start)
    s = spectral_lowpass(s, 1400)
    return finish(room(s, 0.35, 0.12, damp=2500), 0.7, 0.1)


def trophy():
    s = silence(1.4)
    arp = ["G5", "C6", "E6", "G6", "C7"]
    for k, n in enumerate(arp):
        at(s, bell(note(n), 1.2, 0.35, index=1.4) * (0.7 + 0.06 * k), 0.07 * k)
    # A quiet shimmer under the last note.
    t = t_axis(1.0)
    pad = sum(np.sin(2 * np.pi * note(n) * t) for n in ("C6", "E6", "G6")) / 3
    at(s, pad * env(t, 0.15, 0.35) * 0.25, 0.3)
    return finish(room(s, 1.2, 0.35, seed=3), 1.4, 0.3)


def gameboot():
    """A 3 s swell: a widening pad that brightens, a soft whoosh, then a bell as the game starts."""
    dur = 3.0
    t = t_axis(dur)
    rise = np.clip(t / 2.1, 0, 1)
    brightness = 0.15 + 0.7 * rise ** 1.5
    amp = np.clip(t / 1.8, 0, 1) ** 2 * np.clip((dur - t) / 0.9, 0, 1) ** 1.5
    left, right = np.zeros_like(t), np.zeros_like(t)
    for i, n in enumerate(["D3", "A3", "D4", "F#4", "A4", "E5"]):
        f = note(n)
        left += additive(f, t, brightness, detune_cents=-4 + i, phase=0.3 * i)
        right += additive(f, t, brightness, detune_cents=4 - i, phase=0.7 * i)
    pad = np.stack([left, right], axis=1) * amp[:, None] / 6
    s = silence(dur) + pad
    # Whoosh: noise whose band rises with the swell.
    rng = np.random.default_rng(7)
    noise = rng.standard_normal((len(t), 2))
    noise = spectral_lowpass(noise, 3000) - spectral_lowpass(noise, 600)
    s += noise * (np.clip((t - 1.0) / 1.0, 0, 1) * np.clip((2.3 - t) / 0.3, 0, 1))[:, None] * 0.12
    for k, n in enumerate(["D6", "A6", "D7"]):
        at(s, bell(note(n), 1.0, 0.4, index=1.1) * 0.35, 2.0 + 0.03 * k)
    return finish(room(s, 1.6, 0.35, seed=5), dur, 0.25)


def opening():
    """Startup: a slow Cmaj9 pad with a few sparse bells, about 4.5 s."""
    dur = 4.5
    t = t_axis(dur)
    amp = np.clip(t / 1.4, 0, 1) ** 1.5 * np.clip((dur - t) / 1.6, 0, 1) ** 1.5
    brightness = 0.12 + 0.25 * np.clip(t / 2.5, 0, 1)
    left, right = np.zeros_like(t), np.zeros_like(t)
    for i, n in enumerate(["C3", "G3", "D4", "E4", "B4"]):
        f = note(n)
        left += additive(f, t, brightness, harmonics=10, detune_cents=-3 + i, phase=0.5 * i)
        right += additive(f, t, brightness, harmonics=10, detune_cents=3 - i, phase=0.9 * i)
    s = np.stack([left, right], axis=1) * amp[:, None] / 5
    for start, n, pan in [(0.6, "G5", 0.3), (1.1, "D6", 0.7), (1.6, "E6", 0.4), (2.2, "B5", 0.6), (2.9, "G6", 0.5)]:
        b = bell(note(n), 1.4, 0.45, index=0.9) * 0.22
        at(s, np.stack([b * (1 - pan), b * pan], axis=1) * 1.4, start)
    return finish(room(s, 2.2, 0.45, damp=5000, seed=9), dur, 0.4)


SOUNDS = {
    "cursor.wav": (cursor, "peak"),
    "category_switch.wav": (category_switch, "peak"),
    "confirm.wav": (confirm, "peak"),
    "option.wav": (option, "peak"),
    "back.wav": (back, "peak"),
    "drill_in.wav": (drill_in, "peak"),
    "drill_out.wav": (drill_out, "peak"),
    "ok.wav": (ok, "peak"),
    "system_ng.wav": (system_ng, "lufs"),
    "trophy.wav": (trophy, "lufs"),
    "gameboot.wav": (gameboot, "lufs"),
    "opening.wav": (opening, "lufs"),
}

PEAK_DB, LUFS, TP_CEILING = -3.0, -16.0, -1.0


def write(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def measure(path):
    """(integrated LUFS, true peak dBTP) from ffmpeg's ebur128 filter."""
    out = subprocess.run(["ffmpeg", "-nostats", "-hide_banner", "-i", path, "-af", "ebur128=peak=true", "-f", "null", "-"],
                         capture_output=True, text=True).stderr
    summary = out[out.rfind("Summary:"):]
    i = float(re.search(r"I:\s+(-?[\d.]+) LUFS", summary).group(1))
    tp = float(re.search(r"Peak:\s+(-?[\d.]+) dBFS", summary).group(1))
    return i, tp


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    have_ffmpeg = shutil.which("ffmpeg") is not None
    for name, (fn, mode) in SOUNDS.items():
        x = fn()
        x = x / (np.max(np.abs(x)) + 1e-12) * 10 ** (PEAK_DB / 20)
        path = os.path.join(out_dir, name)
        write(path, x)
        if mode == "lufs" and have_ffmpeg:
            i, tp = measure(path)
            gain_db = min(LUFS - i, TP_CEILING - tp)
            x = x * 10 ** (gain_db / 20)
            write(path, x)
        level = "%.1f LUFS / %.1f dBTP" % measure(path) if mode == "lufs" and have_ffmpeg else "peak %.1f dBFS" % (20 * np.log10(np.max(np.abs(x))))
        print(f"{name:22s} {len(x) / SR:5.2f} s  {level}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
