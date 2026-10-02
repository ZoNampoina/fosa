"""Pure DSP and validated mix model. No hardware or network side effects."""
import math
import numpy as np

CHANNELS = 18
RATE = 48000
FRAME = 960  # aiortc's Opus packet duration is 20 ms; never advertise 5 ms.

def number(value, default, low, high):
    try:
        value = float(value)
        return max(low, min(high, value)) if math.isfinite(value) else default
    except (TypeError, ValueError):
        return default

def default_mix():
    return {"master": 0.5, "muteAll": False, "ducking": -6,
            "channels": [{"gain": 0.5, "pan": 0, "mute": False, "solo": False}
                         for _ in range(CHANNELS)]}

def clean_mix(value):
    if not isinstance(value, dict):
        raise ValueError("Mix invalide")
    rows = value.get("channels")
    if not isinstance(rows, list) or len(rows) != CHANNELS:
        raise ValueError("Le mix doit contenir exactement 18 canaux")
    out = default_mix()
    out["master"] = number(value.get("master"), .5, 0, 1)
    out["muteAll"] = value.get("muteAll") is True
    out["ducking"] = value.get("ducking") if value.get("ducking") in (0, -3, -6, -12, -99) else -6
    out["channels"] = [{"gain": number(c.get("gain"), .5, 0, 1),
                        "pan": number(c.get("pan"), 0, -1, 1),
                        "mute": c.get("mute") is True,
                        "solo": c.get("solo") is True} for c in rows if isinstance(c, dict)]
    if len(out["channels"]) != CHANNELS:
        raise ValueError("Canal invalide")
    return out

def gains(mix, allowed=None, talk=False):
    solo = any(c["solo"] for c in mix["channels"])
    matrix = np.zeros((CHANNELS, 2), dtype=np.float32)
    if mix["muteAll"]:
        return matrix
    duck = (0 if mix["ducking"] == -99 else 10 ** (mix["ducking"] / 20)) if talk else 1
    for i, c in enumerate(mix["channels"]):
        if c["mute"] or (solo and not c["solo"]) or (allowed is not None and not allowed[i]):
            continue
        angle = (c["pan"] + 1) * math.pi / 4
        gain = c["gain"] * mix["master"] * duck
        matrix[i] = gain * math.cos(angle), gain * math.sin(angle)
    return matrix

def render_mix(samples, mix, allowed=None, talk_audio=None, previous=None):
    target = gains(mix, allowed, talk_audio is not None)
    out = samples @ target
    if previous is not None and not np.array_equal(previous, target):
        if allowed is not None:
            # A newly forbidden input must not survive in the smoothing ramp.
            previous = previous * np.asarray(allowed, dtype=np.float32)[:, None]
        n = min(240, len(samples))
        ramp = np.linspace(0, 1, n, dtype=np.float32)[:, None]
        out[:n] = (samples[:n] @ previous) * (1 - ramp) + out[:n] * ramp
    if talk_audio is not None and not mix["muteAll"]:
        out += talk_audio * mix["master"]
    # Fast peak limiter with immediate attack. Gain recovery handled per listener.
    peak = float(np.max(np.abs(out))) if out.size else 0
    return out, target, peak
