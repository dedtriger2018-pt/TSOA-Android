#!/usr/bin/env python3
"""Prepare the original CC0 field recording as a small, seamless stereo PCM loop.

Generation requires host FFmpeg and NumPy; --check uses only Python's standard
library. Neither FFmpeg nor NumPy ships in the Android app. No synthetic rain is
added. The original source is verified before processing.
"""
import argparse
import array
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import wave

ROOT = Path(__file__).resolve().parents[1]
SOURCE_URL = 'https://upload.wikimedia.org/wikipedia/commons/b/b6/Light_Rain_Distant_Thunder_July_5th_2016.wav'
SOURCE_SHA = 'aa85a4aa26b261b8d8d9da52df3cb1c7f09aa86f5253e835ff5e15513e2451cb'
RATE = 24000
START = 38
EXCERPT_SECONDS = 34
CROSSFADE_SECONDS = 4


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inspect(data):
    with wave.open(io.BytesIO(data), 'rb') as wav:
        rate, channels, width, frames = wav.getframerate(), wav.getnchannels(), wav.getsampwidth(), wav.getnframes()
        raw = wav.readframes(frames)
    if (rate, channels, width, frames) != (RATE, 2, 2, 30 * RATE):
        raise SystemExit('Unexpected stereo PCM loop format')
    values = array.array('h', raw)
    if sys.byteorder != 'little':
        values.byteswap()
    peak = max(abs(v) for v in values) / 32768.0
    rms = (sum(v * v for v in values) / len(values)) ** 0.5 / 32768.0
    clipped = sum(abs(v) >= 32767 for v in values)
    if clipped or not 0.05 < rms < 0.13 or peak > 0.621 or len(raw) > 5 * 1024 * 1024:
        raise SystemExit('PCM exceeds the loudness, clipping or memory budget')
    return {'sha256': digest(data), 'bytes': len(data), 'pcm_bytes': len(raw),
            'sample_rate': rate, 'channels': channels, 'pcm_bits': width * 8,
            'frames': frames, 'seconds': frames / rate, 'rms': round(rms, 8),
            'peak': round(peak, 8), 'clipped_samples': clipped,
            'seam_step_lsb': [abs(values[c] - values[-channels + c]) for c in range(channels)]}


def prepare(source):
    import numpy as np
    if digest(source.read_bytes()) != SOURCE_SHA:
        raise SystemExit('The source differs from the verified CC0 field recording')
    if not shutil.which('ffmpeg'):
        raise SystemExit('Install host FFmpeg before preparing this asset')
    command = ['ffmpeg', '-v', 'error', '-ss', str(START), '-t', str(EXCERPT_SECONDS),
               '-i', str(source), '-af', 'highpass=f=65,lowpass=f=9000',
               '-ac', '2', '-ar', str(RATE), '-f', 'f32le', '-']
    raw = subprocess.run(command, check=True, capture_output=True).stdout
    signal = np.frombuffer(raw, dtype='<f4').reshape(-1, 2).astype(np.float64)
    if len(signal) != EXCERPT_SECONDS * RATE:
        raise SystemExit('The source excerpt is incomplete')
    fade = CROSSFADE_SECONDS * RATE
    # Body [4s,30s) then a 4s equal-power blend from the tail into the head.
    # At the wrap the next sample is the source sample immediately after the
    # head of the blend, rather than silence or an audio-player restart.
    phase = np.arange(fade, dtype=np.float64) * (np.pi * 0.5 / fade)
    blend = signal[-fade:] * np.cos(phase)[:, None] + signal[:fade] * np.sin(phase)[:, None]
    loop = np.concatenate((signal[fade:-fade], blend), axis=0)
    loop -= np.mean(loop, axis=0)
    gain = 0.09 / np.sqrt(np.mean(loop * loop))
    # Gentle, offline transient limiting keeps microphone droplets from becoming
    # harsh at a higher phone volume. Stereo field placement is preserved.
    loop = 0.62 * np.tanh(loop * gain / 0.62)
    pcm = np.rint(loop * 32767).astype('<i2')
    output = io.BytesIO()
    with wave.open(output, 'wb') as wav:
        wav.setnchannels(2)
        wav.setsampwidth(2)
        wav.setframerate(RATE)
        wav.writeframes(pcm.tobytes())
    return output.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT / 'build/tsoa-rain-source.wav')
    parser.add_argument('--download-source', action='store_true')
    parser.add_argument('--output', type=Path, default=ROOT / 'app/src/main/assets/tsoa_lobby_rain.wav')
    parser.add_argument('--metadata', type=Path, default=ROOT / 'docs/TSOA_RAIN_AUDIO.json')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.check:
        data = args.output.read_bytes()
        current = inspect(data)
        expected = json.loads(args.metadata.read_text())['output']
        if current != expected:
            raise SystemExit('Audio asset differs from its prepared metadata')
        print(json.dumps({'check': True, **current}, indent=2))
        return
    if args.download_source:
        args.source.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(SOURCE_URL, timeout=60) as response:
            args.source.write_bytes(response.read())
    if not args.source.is_file():
        raise SystemExit('Provide --source ORIGINAL.wav or use --download-source')
    data = prepare(args.source)
    report = {'title': 'Light Rain Distant Thunder July 5th 2016', 'author': 'kvgarlic',
              'source_page': 'https://commons.wikimedia.org/wiki/File:Light_Rain_Distant_Thunder_July_5th_2016.wav',
              'original_url': SOURCE_URL, 'original_sha256': SOURCE_SHA,
              'freesound_page': 'https://freesound.org/people/kvgarlic/sounds/349454/',
              'license': 'CC0 1.0', 'license_url': 'https://creativecommons.org/publicdomain/zero/1.0/',
              'processing': {'excerpt_start_seconds': START, 'excerpt_seconds': EXCERPT_SECONDS,
                             'crossfade_seconds': CROSSFADE_SECONDS, 'crossfade': 'equal power',
                             'high_pass_hz': 65, 'low_pass_hz': 9000,
                             'transients': 'offline gentle tanh limiter', 'added_synthesized_audio': False},
              'output': inspect(data)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.metadata.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(data)
    args.metadata.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
