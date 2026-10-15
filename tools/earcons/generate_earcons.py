"""Generates deepAssist's feedback sounds ("A · Yumuşak" set) into app/src/main/res/raw.

Kalimba-like tones: a fundamental plus soft 2nd and slightly detuned 3rd
partials, a 5 ms attack and an exponential decay. Standard library only:

    python3 -I tools/earcons/generate_earcons.py
"""
import math
import os
import random
import struct
import wave

RATE = 44100
KALIMBA = [(1.0, 1.0), (2.0, 0.35), (3.01, 0.12)]
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "res", "raw")


def tone(buf, start, freq, dur, gain=0.25, partials=KALIMBA, attack=0.005):
    first = int(start * RATE)
    n = int((dur + 0.02) * RATE)
    _grow(buf, first + n)
    for i in range(n):
        t = i / RATE
        if t < attack:
            env = 0.0001 * (gain / 0.0001) ** (t / attack)
        else:
            # exponential fall from gain to 0.0001 over the rest of dur
            k = min(1.0, (t - attack) / max(1e-6, dur - attack))
            env = gain * (0.0001 / gain) ** k
        s = sum(a * math.sin(2 * math.pi * freq * r * t) for r, a in partials)
        buf[first + i] += env * s


def noise(buf, start, dur, gain, center):
    """Band-passed noise burst with a quadratic fade (camera shutter)."""
    first = int(start * RATE)
    n = int(dur * RATE)
    _grow(buf, first + n + 10)
    # RBJ band-pass biquad, Q = 0.9
    w0 = 2 * math.pi * center / RATE
    alpha = math.sin(w0) / (2 * 0.9)
    b0, b2 = alpha, -alpha
    a0, a1, a2 = 1 + alpha, -2 * math.cos(w0), 1 - alpha
    x1 = x2 = y1 = y2 = 0.0
    rng = random.Random(7)
    for i in range(n):
        x = (rng.random() * 2 - 1) * (1 - i / n) ** 2
        y = (b0 * x + b2 * x2 - a1 * y1 - a2 * y2) / a0
        x2, x1, y2, y1 = x1, x, y1, y
        buf[first + i] += gain * y


def _grow(buf, size):
    if len(buf) < size:
        buf.extend([0.0] * (size - len(buf)))


def write(name, buf, peak):
    top = max(abs(v) for v in buf) or 1.0
    scale = peak / top
    frames = b"".join(struct.pack("<h", int(max(-1, min(1, v * scale)) * 32767)) for v in buf)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(frames)


def main():
    os.makedirs(OUT, exist_ok=True)
    sounds = {}

    b = []  # listening started: rising E5 → B5
    tone(b, 0.00, 659, 0.35)
    tone(b, 0.09, 988, 0.40)
    sounds["earcon_listen_start"] = (b, 0.55)

    b = []  # listening ended: falling B5 → E5, quieter
    tone(b, 0.00, 988, 0.28, gain=0.18)
    tone(b, 0.08, 659, 0.35, gain=0.18)
    sounds["earcon_listen_end"] = (b, 0.40)

    b = []  # action done: G5 B5 D6
    tone(b, 0.00, 784, 0.30)
    tone(b, 0.07, 988, 0.30)
    tone(b, 0.14, 1175, 0.50)
    sounds["earcon_success"] = (b, 0.50)

    b = []  # error: D4 → C4
    tone(b, 0.00, 294, 0.30, gain=0.30)
    tone(b, 0.16, 262, 0.45, gain=0.30)
    sounds["earcon_error"] = (b, 0.60)

    b = []  # camera shutter
    noise(b, 0.00, 0.04, 0.6, 3200)
    noise(b, 0.07, 0.05, 0.4, 2200)
    sounds["earcon_shutter"] = (b, 0.50)

    b = []  # screen agent step
    tone(b, 0.00, 1760, 0.07, gain=0.07)
    sounds["earcon_tick"] = (b, 0.22)

    for name, (buf, peak) in sounds.items():
        write(name, buf, peak)
        print(f"{name}.wav {len(buf) / RATE:.2f}s")


if __name__ == "__main__":
    main()
