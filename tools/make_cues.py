"""감각 예보 효과음 다섯 개를 합성해 app/src/main/res/raw에 WAV로 씁니다.

사건이 건드리는 감각 묶음마다 소리 하나입니다. 사인파에 천천히 시작하고 천천히 사라지는 모양을 줘서
소리에 예민한 아이가 놀라지 않게 했습니다. 외부 음원을 쓰지 않아 사용 조건이 없습니다.

    python3 tools/make_cues.py
"""
import wave
from pathlib import Path

import numpy as np

SR = 22050
OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/raw"
TWO_PI = 2 * np.pi
PEAK = 0.45

C4, E4, G4, A3 = 261.63, 329.63, 392.00, 220.00


def t_of(sec):
    return np.arange(int(sec * SR)) / SR


def raised_cos(n):
    return 0.5 - 0.5 * np.cos(np.pi * np.arange(n) / max(n, 1))


def mallet(f, dur=0.7, tau=0.20, attack=0.015):
    """둥근 타악기 한 번. 기음에 짧게 사라지는 배음을 조금 섞습니다."""
    t = t_of(dur)
    x = (np.sin(TWO_PI * f * t)
         + 0.20 * np.sin(TWO_PI * 2 * f * t) * np.exp(-t / (tau * 0.5))
         + 0.06 * np.sin(TWO_PI * 4 * f * t) * np.exp(-t / (tau * 0.25)))
    env = np.exp(-t / tau)
    a = int(attack * SR)
    env[:a] *= raised_cos(a)
    return x * env


def place(parts, total):
    out = np.zeros(int(total * SR))
    for start, sig in parts:
        i = int(start * SR)
        n = min(len(sig), len(out) - i)
        out[i:i + n] += sig[:n]
    return out


def glide(f0, f1, dur=1.0, fade_in=0.12, fade_out=0.45):
    """천천히 낮아지는 소리. 주파수를 지수로 줄입니다."""
    t = t_of(dur)
    f = f0 * (f1 / f0) ** (t / dur)
    phase = TWO_PI * np.cumsum(f) / SR
    x = np.sin(phase) + 0.15 * np.sin(2 * phase)
    env = np.ones_like(t)
    a, r = int(fade_in * SR), int(fade_out * SR)
    env[:a] = raised_cos(a)
    env[-r:] = raised_cos(r)[::-1]
    return x * env


def swell(freqs, dur=1.0):
    """작게 시작해 커졌다 작아지는 화음."""
    t = t_of(dur)
    x = sum(np.sin(TWO_PI * f * t) for f in freqs)
    return x * np.sin(np.pi * t / dur) ** 2


# 파일 이름은 core/RoadEvent.kt의 Sense 이름을 소문자로 쓴 것입니다. 화음은 길게 이어져 크게 들려서 조금 줄입니다.
CUES = {
    "motion": (place([(0.0, mallet(A3)), (0.24, mallet(A3))], 1.0), PEAK),
    "light_and_sound": (glide(440, 220), PEAK),
    "sound": (swell([C4, E4, G4]), PEAK * 0.8),
    "stop": (place([(0.0, mallet(G4, 0.6)), (0.26, mallet(E4, 0.6)), (0.52, mallet(C4, 0.8))], 1.3), PEAK),
    "change": (place([(0.0, mallet(C4, 0.6)), (0.26, mallet(G4, 0.8))], 1.06), PEAK),
}


def write_wav(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for name, (sig, peak) in CUES.items():
        path = OUT / f"cue_{name}.wav"
        write_wav(path, sig / np.max(np.abs(sig)) * peak)
        print(path.relative_to(OUT.parent.parent.parent.parent.parent), f"{len(sig) / SR:.2f}초")
