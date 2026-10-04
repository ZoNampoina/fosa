import sys
import unittest
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from mixer import default_mix, clean_mix
from monitor_engine import MonitorEngine, CEILING

class MonitorTests(unittest.TestCase):
    def fixture(self, signal=.05):
        m=default_mix();m['master']=1
        for row in m['channels']:row['gain']=0
        m['channels'][0].update(gain=1,pan=-1)
        x=np.zeros((240,18),np.float32);x[:,0]=signal
        dsp=MonitorEngine();dsp.process(x,m,[True]*18)
        return dsp,m,x

    def test_boost_master_and_stereo_are_separate_real_dsp_stages(self):
        dsp,m,x=self.fixture()
        baseline=dsp.process(x,m,[True]*18)[-1,0]
        m['monitorGainDb']=6
        y=dsp.process(x,m,[True]*18)
        self.assertAlmostEqual(y[-1,0]/baseline,10**(.3),places=5)
        self.assertFalse(y[:,1].any())
        m['master']=.5
        y=dsp.process(x,m,[True]*18)
        self.assertAlmostEqual(y[-1,0],baseline*10**(.3)*.5,places=6)
        self.assertEqual(dsp.telemetry['monitorGainDb'],6)
        self.assertAlmostEqual(dsp.telemetry['mixPeakDb'],-26.02,places=2)
        self.assertEqual(dsp.telemetry['method'],'MEASURED_DIGITAL')

    def test_limiter_bounds_impulses_and_preserves_stereo_ratio(self):
        dsp,m,x=self.fixture(1)
        m['monitorGainDb']=12;m['master']=.5;m['channels'][0]['pan']=0
        dsp.process(x,m,[True]*18)  # finish the intentional 5 ms pan/master transition
        for _ in range(30):
            y=dsp.process(x,m,[True]*18)
            self.assertLessEqual(np.max(np.abs(y)),CEILING+1e-6)
            self.assertTrue(np.allclose(y[:,0],y[:,1]))
        self.assertLessEqual(np.max(abs(y)),CEILING*.5+1e-6)
        self.assertGreater(dsp.telemetry['limiterReductionDb'],9)
        self.assertLessEqual(dsp.telemetry['outputPeakDb'],-7.01)

    def test_invalid_samples_silence_and_fade_recovery(self):
        dsp,m,x=self.fixture();x[:,0]=np.nan
        self.assertFalse(dsp.process(x,m,[True]*18).any())
        self.assertEqual(dsp.invalid,1)
        x[:,0]=.2;y=dsp.process(x,m,[True]*18)
        self.assertEqual(y[0,0],0);self.assertGreater(y[-1,0],.19)
        x[:,0]=np.inf
        self.assertFalse(dsp.process(x,m,[True]*18).any())

    def test_panic_mute_and_changes_have_short_bounded_fades(self):
        dsp,m,x=self.fixture(.1)
        y=dsp.process(x,m,[True]*18,panic=True)
        self.assertAlmostEqual(y[0,0],.1,places=6);self.assertEqual(y[-1,0],0)
        self.assertTrue(np.all(np.diff(y[:,0])<=0))
        self.assertFalse(dsp.process(x,m,[True]*18,panic=True).any())
        y=dsp.process(x,m,[True]*18)
        self.assertEqual(y[0,0],0)

    def test_mono_permissions_and_forbidden_solo(self):
        dsp,m,x=self.fixture(.1);m['mono']=True
        y=dsp.process(x,m,[True]*18)
        self.assertTrue(np.allclose(y[:,0],y[:,1]))
        m['channels'][1]['solo']=True
        allowed=[True]*18;allowed[1]=False
        self.assertTrue(dsp.process(x,m,allowed).any())
        allowed[0]=False
        self.assertFalse(dsp.process(x,m,allowed).any())

    def test_auto_level_requires_history_avoids_silence_and_keeps_headroom(self):
        dsp,m,x=self.fixture(.05)
        self.assertFalse(dsp.recommend()['available'])
        for _ in range(600):dsp.process(x,m,[True]*18)
        advice=dsp.recommend(6)
        self.assertTrue(advice['available']);self.assertFalse(advice['applied'])
        self.assertLessEqual(advice['recommendedGainDb'],6)
        self.assertLessEqual(advice['peakDb']+advice['recommendedGainDb'],-3)
        dsp.history.clear()
        for _ in range(600):dsp.process(np.zeros_like(x),m,[True]*18)
        self.assertFalse(dsp.recommend()['available'])

    def test_boost_is_finite_and_bounded(self):
        m=default_mix(2);m['monitorGainDb']=999
        self.assertEqual(clean_mix(m,2)['monitorGainDb'],12)
        m['monitorGainDb']=float('nan')
        self.assertEqual(clean_mix(m,2)['monitorGainDb'],0)
