"""Audio device policy, independent from model names in the mix/transport engines."""

def describe(sd, windows=False):
    if sd is None:
        return []
    apis = sd.query_hostapis()
    out = []
    for d in sd.query_devices():
        if d['max_input_channels'] < 1:
            continue
        name, driver = d['name'], apis[d['hostapi']]['name']
        asio = 'ASIO' in driver.upper()
        mr18 = 'mr18' in name.lower()
        midas = 'midas' in name.lower() and ('usb' in name.lower() or asio)
        wrapper = 'asio4all' in name.lower()
        wasapi = 'WASAPI' in driver.upper()
        recommended = (mr18 or midas) and asio and not wrapper
        out.append({'id': int(d['index']), 'name': name, 'driver': driver,
            'inputs': int(d['max_input_channels']), 'outputs': int(d.get('max_output_channels', 0)),
            'defaultRate': float(d['default_samplerate']), 'mr18': mr18, 'midasUsb': midas,
            'asio': asio, 'asio4all': wrapper, 'wasapi': wasapi,
            'usable': not windows or asio or wasapi, 'recommended': recommended,
            'priority': 0 if recommended else 2 if wrapper else 1 if asio else 4,
            'label': 'MR18 ASIO — Recommended for Live' if recommended else 'Alternative audio device',
            'supportedBuffers': None,  # PortAudio does not enumerate ASIO buffer sizes.
            'reason': '' if not windows or asio or wasapi else 'ASIO ou WASAPI requis'})
    return out
