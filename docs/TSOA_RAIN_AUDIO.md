# Recorded lobby rain

The lobby uses a real stereo field recording rather than the earlier synthesized
mono noise bed. The source is **Light Rain Distant Thunder July 5th 2016**, by
**kvgarlic**, recorded in an Illinois forest with a Marantz PMD661 and two
Sennheiser ME66 microphones.

- Source: https://commons.wikimedia.org/wiki/File:Light_Rain_Distant_Thunder_July_5th_2016.wav
- Original Freesound page: https://freesound.org/people/kvgarlic/sounds/349454/
- License: **CC0 1.0**, https://creativecommons.org/publicdomain/zero/1.0/
- Original SHA-256: `aa85a4aa26b261b8d8d9da52df3cb1c7f09aa86f5253e835ff5e15513e2451cb`

The prepared asset takes seconds 38–72, filters very low rumble and excessive
high frequencies, blends four seconds of the tail into the head, and gently
limits microphone droplets offline. The resulting thirty-second PCM16 stereo
loop is 24 kHz and uses 2,880,000 bytes of decoded audio. No music, synthesized
rain or artificial moving panner is added. Stereo space is most apparent with
headphones.

`TsoaPcmLoop` reads a bounded PCM payload and counts **audio frames**, not bytes
or individual samples. `TsoaAmbience` uploads it once to an Android
`AudioTrack.MODE_STATIC` buffer and configures `setLoopPoints(0, frameCount, -1)`.
Android repeats the buffer without restarting a decoder or player. All file
loading and buffer upload happen off the UI thread. Fade-in, independent volume,
audio focus, headphone-disconnect handling and immediate silence on leaving the
lobby remain in place. The source asset is read through `AssetManager.open`, so
its ZIP compression mode does not affect playback.

Generate the committed asset using host FFmpeg and NumPy:

```sh
python3 tools/generate_tsoa_ambience.py --download-source
```

Or provide the already downloaded original with `--source ORIGINAL.wav`. The
source hash must match before processing. Verify the prepared asset without
FFmpeg or NumPy:

```sh
python3 tools/generate_tsoa_ambience.py --check
python3 tools/tests/test_tsoa_pcm_loop.py
```

`TSOA_RAIN_AUDIO.json` records source provenance, processing and asset metrics.
A normal adjacent-sample step at the wrap is not required to equal zero: the
crossfade rejoins consecutive samples of the recording. Hardware playback,
subjective sound quality and focus changes still require a check on the Honor.
