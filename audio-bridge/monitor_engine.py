"""Per-bodypack DSP. Runs on the dedicated FOSA DSP worker, never in the UI loop.

18 (or alternate device N) inputs -> personal mix -> monitor gain -> linked
sample-peak brickwall limiter -> master/fade -> stereo output. No network/I/O.
The ceiling bounds DIGITAL samples; it is not a headphone SPL guarantee.
"""
import math
from collections import deque
import numpy as np
from mixer import RATE, gains

CEILING_DB = -1.0
CEILING = 10 ** (CEILING_DB / 20)

def db(value):
    return round(20 * math.log10(max(float(value), 1e-6)), 2)

class MonitorEngine:
    def __init__(self, channels=18):
        self.previous = np.zeros((channels, 2), np.float32)
        self.boost = 1.0
        self.master = 0.0  # fade in after every transport reconnect
        self.reduction = 1.0
        self.peak_hold = 0.0
        self.invalid = 0
        self.history = deque(maxlen=1000)  # exactly 5 s at 48 kHz / 240
        self.telemetry = {}
        self.ramp = np.linspace(0, 1, 240, dtype=np.float32)[:, None]

    def process(self, samples, mix, allowed, voice=None, panic=False):
        n = len(samples)
        if samples.ndim != 2 or samples.shape[1] != len(mix['channels']):
            raise ValueError('Input/mix channel count mismatch')
        invalid = not np.isfinite(samples).all() or (voice is not None and not np.isfinite(voice).all())
        if invalid:
            self.invalid += 1
            samples = np.zeros_like(samples)
            voice = None
        raw_mix = dict(mix, master=1.0, muteAll=False)
        target = gains(raw_mix, allowed, voice is not None)
        ramp = self.ramp if n == 240 else np.linspace(0, 1, n, dtype=np.float32)[:, None]
        previous = self.previous * np.asarray(allowed, np.float32)[:, None]
        output = (samples @ previous) * (1-ramp) + (samples @ target)*ramp
        self.previous = target
        if voice is not None:
            output += voice
        if mix.get('mono'):
            output[:] = np.mean(output, axis=1)[:, None]
        peak = float(np.max(np.abs(output)))
        rms = float(np.sqrt(np.mean(output.astype(np.float64)**2)))
        self.history.append((peak, rms))
        boost = 10 ** (mix.get('monitorGainDb', 0) / 20)
        output *= self.boost + (boost-self.boost)*ramp
        self.boost = boost
        # Linked L/R, instantaneous attenuation and 100 ms exponential recovery.
        # A backwards look across this already captured 5 ms block smooths attack
        # BEFORE peaks, without adding another audio block to the pipeline.
        envelope = np.max(np.abs(output), axis=1)
        wanted = np.minimum(1., CEILING / np.maximum(envelope, 1e-12))
        attack = math.exp(-1 / (RATE * .001))
        for i in range(n-2, -1, -1):
            wanted[i] = min(wanted[i], 1-(1-wanted[i+1])*attack)
        release = math.exp(-1/(RATE*.1))
        attenuation = np.empty(n, np.float32)
        level = self.reduction
        for i in range(n):
            level = min(float(wanted[i]), 1-(1-level)*release)
            attenuation[i] = level
        self.reduction = level
        output *= attenuation[:, None]
        target_master = 0. if panic or invalid or mix.get('muteAll') else mix['master']
        output *= self.master + (target_master-self.master)*ramp
        self.master = target_master
        # Final numerical guard, also bounds pathological finite input magnitudes.
        np.nan_to_num(output, copy=False, nan=0., posinf=0., neginf=0.)
        np.clip(output, -CEILING, CEILING, out=output)
        lr = np.max(np.abs(output), axis=0)
        self.peak_hold = max(float(max(lr)), self.peak_hold * .995)
        self.telemetry = {'mixPeakDb': db(peak), 'mixRmsDb': db(rms),
            'monitorGainDb': mix.get('monitorGainDb', 0), 'masterDb': db(mix['master']),
            'outputPeakDb': db(max(lr)), 'outputLDb': db(lr[0]), 'outputRDb': db(lr[1]),
            'peakHoldDb': db(self.peak_hold), 'limiterReductionDb': round(-db(min(attenuation)), 2),
            'ceilingDb': CEILING_DB, 'invalidBlocks': self.invalid,
            'muted': bool(panic or mix.get('muteAll')), 'method': 'MEASURED_DIGITAL'}
        return output

    def recommend(self, max_gain=12):
        history = list(self.history)
        if len(history) < 600:
            return {'available': False, 'reason': 'Écoute active : analyse requise pendant au moins 3 secondes.'}
        peak = max(p for p, _ in history)
        rms = math.sqrt(sum(r*r for _, r in history)/len(history))
        if db(rms) < -60:
            return {'available': False, 'reason': 'Signal trop faible ou silence : aucune amplification proposée.'}
        # Aim -18 dBFS RMS, never exceed -3 dBFS highest observed peak.
        gain = max(0., min(max_gain, -18-db(rms), -3-db(peak)))
        return {'available': True, 'seconds': len(history)*.005, 'averageDb': db(rms),
                'peakDb': db(peak), 'recommendedGainDb': round(gain, 1),
                'headroomDb': 3, 'applied': False}
