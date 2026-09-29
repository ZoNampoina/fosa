import unittest
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import numpy as np
from mixer import clean_mix, default_mix, gains, render_mix

class MixerTests(unittest.TestCase):
    def test_isolated_gain_pan_and_assignment(self):
        m = default_mix()
        m['master'] = 1
        for c in m['channels']:
            c['gain'] = 0
        m['channels'][2].update(gain=1, pan=-1)
        samples = np.zeros((960, 18), np.float32)
        samples[:, 2] = .25
        output, _, peak = render_mix(samples, m)
        self.assertTrue(np.allclose(output[:,0], .25))
        self.assertTrue(np.allclose(output[:,1], 0))
        allowed = [True]*18
        allowed[2] = False
        self.assertFalse(np.any(render_mix(samples, m, allowed)[0]))
        m['channels'][2]['mute'] = True
        self.assertFalse(np.any(render_mix(samples, m)[0]))

    def test_personal_solo_does_not_change_other_mix(self):
        one, two = default_mix(), default_mix()
        one['channels'][0]['solo'] = True
        self.assertFalse(gains(one)[1:].any())
        self.assertTrue(gains(two)[1:].any())

    def test_ducking_and_mute_all(self):
        m = default_mix()
        music = np.full((960,18), .001, np.float32)
        voice = np.full((960,2), .1, np.float32)
        m['ducking'] = -99
        self.assertTrue(np.allclose(render_mix(music,m,talk_audio=voice)[0], .05))
        m['muteAll'] = True
        self.assertFalse(render_mix(music,m,talk_audio=voice)[0].any())

    def test_untrusted_mix_is_bounded(self):
        m=default_mix()
        m['master']=float('inf')
        m['channels'][0].update(gain=99, pan=-99)
        normalized=clean_mix(m)
        self.assertEqual(normalized['master'], .5)
        self.assertEqual(normalized['channels'][0]['gain'],1)
        self.assertEqual(normalized['channels'][0]['pan'],-1)
        with self.assertRaises(ValueError):
            clean_mix({'channels':[{}]})

if __name__ == '__main__':
    unittest.main()
