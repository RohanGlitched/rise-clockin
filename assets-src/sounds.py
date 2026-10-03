"""Synthesises Rise's sounds: the dawn alarm loop, the punch-clock stamp and a soft tick."""
import numpy as np, wave

SR = 44100

def env(n, a=0.005, r=0.6):
    t = np.arange(n) / SR
    return np.minimum(1, t / a) * np.exp(-t / r)

def bell(freq, dur, r=0.9, bright=1.0):
    n = int(SR * dur); t = np.arange(n) / SR
    s = (np.sin(2*np.pi*freq*t) + 0.45*bright*np.sin(2*np.pi*freq*2.76*t)*np.exp(-t/0.25)
         + 0.25*np.sin(2*np.pi*freq*2*t) + 0.12*bright*np.sin(2*np.pi*freq*5.4*t)*np.exp(-t/0.08))
    return s * env(n, 0.004, r)

def mix(total, events):
    out = np.zeros(int(SR * total))
    for start, sig, gain in events:
        i = int(SR * start); j = min(len(out), i + len(sig))
        out[i:j] += sig[: j - i] * gain
    return out

def write(name, x):
    x = x / (np.max(np.abs(x)) + 1e-9) * 0.89
    with wave.open(name, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x * 32767).astype(np.int16).tobytes())

# Dawn alarm: a rising pentatonic phrase that climbs each bar, 4.8 s loop.
notes = [523.25, 587.33, 659.25, 783.99, 880.0, 1046.5]
ev = []
pattern = [0, 2, 4, 3, 5, 4, 2, 4]
for bar in range(2):
    for k, p in enumerate(pattern):
        t = bar * 2.4 + k * 0.3
        f = notes[p] * (1.0 if bar == 0 else 1.12246)
        ev.append((t, bell(f, 1.4, 0.7), 0.8 if k % 2 == 0 else 0.6))
    ev.append((bar * 2.4, bell(notes[0] / 2, 2.2, 1.2, 0.3), 0.5))
write("rise_alarm.wav", mix(4.8, ev))

# Punch-clock stamp: a hard mechanical thunk plus a short ratchet and a bell ding.
n = int(SR * 0.9); t = np.arange(n) / SR
thunk = (np.sin(2*np.pi*70*t) * np.exp(-t/0.06) + 0.6*np.random.randn(n) * np.exp(-t/0.012))
ratchet = np.zeros(n)
for k in range(5):
    i = int(SR * (0.05 + k * 0.018))
    ratchet[i:i+300] += np.random.randn(300) * np.exp(-np.arange(300)/40) * 0.5
ding = mix(0.9, [(0.12, bell(1567.98, 0.78, 0.35), 0.35)])
write("rise_stamp.wav", thunk + ratchet + ding)

# Soft tick for the dial.
n = int(SR * 0.04); t = np.arange(n) / SR
write("rise_tick.wav", np.random.randn(n) * np.exp(-t/0.004) + np.sin(2*np.pi*2400*t)*np.exp(-t/0.006))
