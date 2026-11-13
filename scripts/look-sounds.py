#!/usr/bin/env python3
"""Synthesizes each look's interface sounds into app/src/main/res/raw/ (needs numpy, scipy and ffmpeg).

Three per look: `tap` (a button or card), `tab` (changing tabs) and `done` (an episode or chapter finished, a moment).
Everything is made here from sine, square and noise, so there are no sample packs or licences to carry.
Run it again after changing a sound: scripts/look-sounds.py
"""
import os
import subprocess
import tempfile

import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, sosfilt

RATE = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")
rng = np.random.default_rng(7)


def t(seconds):
    return np.arange(int(RATE * seconds)) / RATE


def env(n, attack=0.002, decay=0.08, curve=1.0):
    """Fast attack, exponential decay."""
    x = np.arange(n) / RATE
    a = np.clip(x / max(attack, 1e-4), 0, 1)
    return a * np.exp(-x / decay) ** curve


def sine(freq, seconds, decay=0.1, attack=0.002):
    x = t(seconds)
    f = np.broadcast_to(freq, x.shape) if np.ndim(freq) else np.full(x.shape, freq)
    phase = 2 * np.pi * np.cumsum(f) / RATE
    return np.sin(phase) * env(len(x), attack, decay)


def square(freq, seconds, decay=0.2, duty=0.5):
    x = t(seconds)
    s = np.where((x * freq) % 1 < duty, 1.0, -1.0)
    return s * env(len(x), 0.001, decay) * 0.5


def noise(seconds, decay=0.05, low=None, high=None, attack=0.001):
    n = rng.standard_normal(int(RATE * seconds))
    if low and high:
        n = sosfilt(butter(2, [low, high], "bandpass", fs=RATE, output="sos"), n)
    elif high:
        n = sosfilt(butter(2, high, "lowpass", fs=RATE, output="sos"), n)
    elif low:
        n = sosfilt(butter(2, low, "highpass", fs=RATE, output="sos"), n)
    return n / (np.abs(n).max() + 1e-9) * env(len(n), attack, decay)


def sweep_noise(seconds, f0, f1, decay=0.2, attack=0.03):
    """Noise through a moving band: a swish."""
    n = rng.standard_normal(int(RATE * seconds))
    out = np.zeros_like(n)
    block = 512
    for i in range(0, len(n), block):
        f = f0 + (f1 - f0) * i / len(n)
        sos = butter(2, [max(80, f * 0.6), min(RATE / 2 - 100, f * 1.4)], "bandpass", fs=RATE, output="sos")
        out[i:i + block] = sosfilt(sos, n[i:i + block])
    out /= np.abs(out).max() + 1e-9
    x = np.arange(len(n)) / RATE
    shape = np.clip(x / attack, 0, 1) * np.clip((seconds - x) / (seconds * 0.6), 0, 1)
    return out * shape * np.exp(-x / decay)


def bell(freq, seconds, decay=0.4, partials=((1, 1), (2.76, 0.4), (5.4, 0.2))):
    return sum(sine(freq * k, seconds, decay / (1 + i * 0.8)) * a for i, (k, a) in enumerate(partials))


def at(total, *parts):
    """Mixes (start seconds, sound) pairs into one clip [total] seconds long."""
    out = np.zeros(int(RATE * total))
    for start, s in parts:
        i = int(RATE * start)
        j = min(len(out), i + len(s))
        out[i:j] += s[: j - i]
    return out


def mix(*sounds):
    """Plays sounds of any length together."""
    return at(max(len(x) for x in sounds) / RATE, *[(0, x) for x in sounds])


def notes(freqs, step, voice, total=None):
    total = total or step * len(freqs) + 0.3
    return at(total, *[(i * step, voice(f)) for i, f in enumerate(freqs)])


def thud(freq=110, seconds=0.25, decay=0.07):
    x = t(seconds)
    f = freq * (1 + 1.5 * np.exp(-x / 0.015))
    return sine(f, seconds, decay, 0.001)


SOUNDS = {
    # Tankōbon: brush on paper, a panel cut, an ink stamp.
    "tankobon": {
        "tap": mix(noise(0.06, 0.012, 1800, 7000) * 0.6, thud(260, 0.06, 0.02) * 0.5),
        "tab": sweep_noise(0.22, 600, 4500, 0.2) * 0.6 + at(0.22, (0.12, thud(150, 0.1, 0.03) * 0.6)),
        "done": at(0.6, (0, thud(120, 0.3, 0.08)), (0, noise(0.08, 0.02, 400, 3000) * 0.5), (0.1, bell(988, 0.5, 0.3) * 0.25)),
    },
    # Modern: soft pops and a two-note chime.
    "modern": {
        "tap": sine(np.linspace(900, 1300, int(RATE * 0.05)), 0.05, 0.015) * 0.7,
        "tab": sine(np.linspace(500, 900, int(RATE * 0.09)), 0.09, 0.03) * 0.6,
        "done": notes([1047, 1319], 0.09, lambda f: sine(f, 0.4, 0.15) * 0.5),
    },
    # Genga: a pencil tick, sheets flipped on the peg bar, a pencil scribble and tap.
    "genga": {
        "tap": noise(0.025, 0.006, 3000, 12000) * 0.7,
        "tab": at(0.2, (0, noise(0.05, 0.015, 1500, 9000) * 0.7), (0.083, noise(0.05, 0.015, 1500, 9000) * 0.6)),
        "done": at(0.45, *[(i * 0.045, noise(0.04, 0.012, 2500, 10000) * 0.5) for i in range(5)], (0.27, thud(400, 0.08, 0.02) * 0.6)),
    },
    # Bunko: soft paper, a page turning, a book closing.
    "bunko": {
        "tap": noise(0.05, 0.015, 600, 3500) * 0.5,
        "tab": sweep_noise(0.32, 3000, 900, 0.25, 0.08) * 0.55,
        "done": at(0.5, (0, sweep_noise(0.18, 2500, 800, 0.2) * 0.4), (0.16, thud(90, 0.3, 0.06) * 0.8), (0.16, noise(0.06, 0.02, 200, 1500) * 0.3)),
    },
    # Shin'ya: a CRT button, channel static, the test-card tone.
    "shinya": {
        "tap": mix(square(1600, 0.03, 0.01) * 0.4, noise(0.02, 0.004, 2000, 9000) * 0.3),
        "tab": at(0.25, (0, noise(0.16, 0.12, 300, 9000) * 0.45), (0.15, thud(70, 0.1, 0.03) * 0.6)),
        "done": at(0.45, (0, sine(1000, 0.4, 2.0, 0.005) * np.clip((0.4 - t(0.4)) / 0.03, 0, 1) * 0.35)),
    },
    # JRPG: the menu cursor, a menu opening, a level-up fanfare.
    "jrpg": {
        "tap": square(1319, 0.045, 0.03, 0.25) * 0.45,
        "tab": notes([880, 1319], 0.05, lambda f: square(f, 0.06, 0.04, 0.25) * 0.4, 0.16),
        "done": notes([523, 659, 784, 1047], 0.075, lambda f: square(f, 0.2, 0.12, 0.25) * 0.38, 0.55),
    },
    # Eki: a split flap, the flaps running, the station chime.
    "eki": {
        "tap": mix(noise(0.02, 0.004, 1200, 6000) * 0.7, thud(500, 0.03, 0.008) * 0.3),
        "tab": at(0.26, *[(i * 0.045, noise(0.02, 0.004, 1200, 6000) * (0.7 - i * 0.08)) for i in range(5)]),
        "done": at(0.9, (0, bell(784, 0.6, 0.45) * 0.4), (0.28, bell(659, 0.6, 0.45) * 0.4)),
    },
    # Konbini: the register key, the receipt printer, the barcode scanner.
    "konbini": {
        "tap": sine(2400, 0.05, 0.05, 0.002) * np.clip((0.05 - t(0.05)) / 0.005, 0, 1) * 0.3,
        "tab": at(0.27, *[(i * 0.025, noise(0.015, 0.005, 2000, 8000) * 0.45) for i in range(10)]),
        "done": at(0.3, (0, sine(1800, 0.14, 2.0, 0.002) * np.clip((0.14 - t(0.14)) / 0.01, 0, 1) * 0.35)),
    },
    # Stamp: a soft thud, the stamp coming down, a stamp with paper.
    "stamp": {
        "tap": mix(thud(160, 0.08, 0.025) * 0.7, noise(0.03, 0.006, 300, 2500) * 0.2),
        "tab": mix(thud(95, 0.2, 0.06) * 0.9, noise(0.05, 0.015, 200, 2000) * 0.4),
        "done": at(0.45, (0, thud(80, 0.3, 0.09)), (0, noise(0.07, 0.02, 200, 2500) * 0.5), (0.18, thud(140, 0.1, 0.03) * 0.3)),
    },
    # Glass: a tink, an airy swish, a crystal chime.
    "glass": {
        "tap": bell(2637, 0.12, 0.05, ((1, 1), (2.32, 0.3))) * 0.3,
        "tab": sweep_noise(0.22, 5000, 8000, 0.15, 0.06) * 0.25 + at(0.22, (0.05, bell(1760, 0.17, 0.08) * 0.2)),
        "done": at(0.8, (0, bell(1568, 0.6, 0.35, ((1, 1), (2.32, 0.35), (4.1, 0.15))) * 0.3), (0.12, bell(2093, 0.6, 0.35, ((1, 1), (2.32, 0.35))) * 0.3)),
    },
}


def main():
    os.makedirs(OUT, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for look, kinds in SOUNDS.items():
            for kind, wave in kinds.items():
                wave = np.asarray(wave, dtype=np.float64)
                peak = np.abs(wave).max()
                if peak > 0.95:
                    wave = wave / peak * 0.95
                # A few ms of fade so nothing ends on a click.
                fade = min(len(wave), int(RATE * 0.004))
                wave[-fade:] *= np.linspace(1, 0, fade)
                wav = os.path.join(tmp, f"{look}_{kind}.wav")
                wavfile.write(wav, RATE, (wave * 32767).astype(np.int16))
                out = os.path.join(OUT, f"look_{look}_{kind}.ogg")
                subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav, "-c:a", "libvorbis", "-q:a", "4", out], check=True)
    print("Saved in", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
