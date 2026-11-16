#!/usr/bin/env python3
"""Generate the lobby's original, seamless rain/wind bed (requires host NumPy).

This is synthesized noise and water-like grains, not a recording or a music loop.
Only the generated PCM WAV ships on Android; NumPy is not an app dependency.
The frequency-domain layers are exactly periodic, so there is no crossfade dip.
"""

import argparse
import hashlib
import io
import json
from pathlib import Path
import wave

import numpy as np

RATE = 24000
SECONDS = 20
FRAMES = RATE * SECONDS
SEED = 1672670


def synthesize():
    rng = np.random.default_rng(SEED)
    frequency = np.fft.rfftfreq(FRAMES, 1.0 / RATE)
    safe_frequency = np.maximum(frequency, 1.0)
    time = np.arange(FRAMES, dtype=np.float64) / RATE

    def layer(low, high, tilt, rms):
        noise = rng.standard_normal(FRAMES)
        # Two smooth corners and a pink tilt keep the rain soft instead of hissy.
        high_pass = (safe_frequency / low) ** 2 / (1 + (safe_frequency / low) ** 2)
        low_pass = 1 / np.sqrt(1 + (safe_frequency / high) ** 8)
        shape = high_pass * low_pass / safe_frequency ** tilt
        shape[0] = 0
        signal = np.fft.irfft(np.fft.rfft(noise) * shape, n=FRAMES)
        return signal * (rms / np.sqrt(np.mean(signal * signal)))

    rain = layer(190, 3400, 0.33, 0.080)
    wind = layer(32, 390, 0.55, 0.039)
    air = layer(240, 1250, 0.32, 0.022)
    # Every modulation contains a whole number of cycles in this 20-second loop.
    gust = 0.80 + 0.13 * np.sin(2 * np.pi * time / SECONDS + 1.2)
    gust += 0.07 * np.sin(4 * np.pi * time / SECONDS - 0.7)
    rainfall = 0.92 + 0.05 * np.sin(6 * np.pi * time / SECONDS + 0.4)
    signal = rain * rainfall + wind * gust + air * (0.8 + gust * 0.2)

    # Quiet, irregular, damped grains suggest rain meeting the wet pavement.
    # Wrap each grain so late grains never get cut off at the loop boundary.
    for _ in range(720):
        length = int(rng.uniform(0.009, 0.026) * RATE)
        grain_time = np.arange(length, dtype=np.float64) / RATE
        tone = rng.uniform(750, 3400)
        decay = rng.uniform(0.0025, 0.0070)
        envelope = (1 - np.exp(-grain_time / 0.00065)) * np.exp(-grain_time / decay)
        grain = np.sin(2 * np.pi * (tone * grain_time - 420 * grain_time * grain_time))
        grain += rng.standard_normal(length) * 0.32
        grain *= envelope * rng.uniform(0.012, 0.034)
        start = int(rng.integers(0, FRAMES))
        indices = (start + np.arange(length)) % FRAMES
        signal[indices] += grain

    signal -= np.mean(signal)
    signal *= 0.108 / np.sqrt(np.mean(signal * signal))
    peak = float(np.max(np.abs(signal)))
    if peak > 0.55:
        signal *= 0.55 / peak
    pcm = np.rint(signal * 32767).astype('<i2')
    # Select an adjacent equal quantized pair as the seam. This preserves the
    # periodic waveform and gives the first/last samples an exact zero step.
    step = np.abs(np.diff(pcm.astype(np.int32)))
    seam = int(np.argmin(step)) + 1
    pcm = np.roll(pcm, -seam)
    return pcm


def encode(pcm):
    output = io.BytesIO()
    with wave.open(output, 'wb') as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(RATE)
        wav.writeframes(pcm.tobytes())
    return output.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default = Path(__file__).resolve().parents[1] / 'app/src/main/assets/tsoa_lobby_rain.wav'
    parser.add_argument('--output', type=Path, default=default)
    parser.add_argument('--check', action='store_true', help='Validate the existing asset without writing it')
    args = parser.parse_args()
    pcm = synthesize()
    content = encode(pcm)
    normalized = pcm.astype(np.float64) / 32768.0
    rms = float(np.sqrt(np.mean(normalized * normalized)))
    peak = float(np.max(np.abs(normalized)))
    endpoint_step = abs(int(pcm[0]) - int(pcm[-1]))
    assert FRAMES == len(pcm) and 0.09 < rms < 0.13
    assert peak <= 0.551 and endpoint_step <= 1
    assert np.count_nonzero(np.abs(pcm.astype(np.int32)) >= 32767) == 0
    if args.check:
        if args.output.read_bytes() != content:
            raise SystemExit('The audio asset differs from this generator output')
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(content)
    print(json.dumps({'asset': str(args.output), 'sha256': hashlib.sha256(content).hexdigest(),
                      'bytes': len(content), 'seconds': SECONDS, 'rate': RATE, 'frames': len(pcm),
                      'channels': 1, 'pcm_bits': 16, 'rms': round(rms, 6),
                      'peak': round(peak, 6), 'clipped_samples': 0,
                      'loop_endpoint_step_lsb': endpoint_step, 'check': args.check}, indent=2))


if __name__ == '__main__':
    main()
