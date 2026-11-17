package com.winlator.tsoa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Field-recorded stereo rain in one static PCM buffer, looped by Android without a player restart. */
public final class TsoaAmbience implements AutoCloseable {
    private static final String ASSET = "tsoa_lobby_rain.wav";
    private static final String TAG = "TSOA_FE_DIAG";
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioManager audioManager;
    private final AudioFocusRequest focusRequest;
    private final ExecutorService loader = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "TSOA-lobby-audio");
        thread.setDaemon(true);
        return thread;
    });
    private AudioTrack track;
    private boolean preparing;
    private boolean active;
    private boolean enabled = true;
    private boolean closed;
    private boolean hasFocus;
    private boolean focusRegistered;
    private boolean receiverRegistered;
    private boolean pausedForNoisy;
    private boolean focusBlocked;
    private boolean playbackFailed;
    private boolean ducked;
    private float volume = 0.18f;
    private float currentGain;
    private float fadeFrom;
    private float fadeTarget;
    private long fadeStart;
    private long fadeDuration;

    private final Runnable fade = new Runnable() {
        @Override public void run() {
            if (!canPlay() || track == null || !hasFocus) return;
            float amount = Math.min(1f, (SystemClock.uptimeMillis() - fadeStart) / (float)fadeDuration);
            // Smoothstep keeps the initial fade from sounding like an abrupt switch.
            amount = amount * amount * (3f - 2f * amount);
            setGain(fadeFrom + (fadeTarget - fadeFrom) * amount);
            if (SystemClock.uptimeMillis() - fadeStart < fadeDuration) handler.postDelayed(this, 40L);
        }
    };

    private final BroadcastReceiver becomingNoisy = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pausedForNoisy = true;
                pauseNow(true);
            }
        }
    };

    public TsoaAmbience(Context context) {
        this.context = context.getApplicationContext();
        audioManager = (AudioManager)this.context.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes = attributes();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(this::onFocusChange, handler)
                .build();
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        try {
            if (Build.VERSION.SDK_INT >= 33) this.context.registerReceiver(becomingNoisy, filter, Context.RECEIVER_NOT_EXPORTED);
            else this.context.registerReceiver(becomingNoisy, filter);
            receiverRegistered = true;
        }
        catch (RuntimeException error) {
            // Optional ambience must never prevent launching the game.
            Log.w(TAG, "Aviso de troca de saída de áudio indisponível.", error);
        }
    }

    public void applyOptions(TsoaOptions options) {
        boolean nextEnabled = options.isAmbienceEnabled();
        float nextVolume = options.getAmbienceVolume() / 100f;
        runOnMain(() -> {
            if (closed) return;
            enabled = nextEnabled;
            volume = nextVolume;
            updatePlayback();
        });
    }

    /** false silences immediately, before the game starts or the app leaves the foreground. */
    public void setActive(boolean value) {
        runOnMain(() -> {
            if (closed) return;
            if (value && !active) {
                pausedForNoisy = false;
                focusBlocked = false;
                playbackFailed = false;
            }
            active = value;
            updatePlayback();
        });
    }

    private boolean canPlay() {
        return active && enabled && volume > 0f && !closed && !pausedForNoisy && !focusBlocked && !playbackFailed;
    }

    private void updatePlayback() {
        if (!canPlay()) {
            pauseNow(true);
            return;
        }
        if (track == null) {
            if (!preparing) prepareTrack();
            return;
        }
        if (!requestFocus()) return;
        try {
            boolean starting = track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING;
            if (starting) {
                setGain(0f);
                track.play();
            }
            fadeTo(volume * (ducked ? 0.2f : 1f), starting ? 900L : 180L);
        }
        catch (IllegalStateException error) { fail("Falha ao iniciar o som do lobby.", error); }
    }

    private void prepareTrack() {
        preparing = true;
        // Reading the small stereo asset and filling native static memory never blocks the UI thread.
        loader.execute(() -> {
            AudioTrack loaded = null;
            try (InputStream input = context.getAssets().open(ASSET)) {
                TsoaPcmLoop loop = TsoaPcmLoop.read(input);
                loaded = new AudioTrack.Builder()
                        .setAudioAttributes(attributes())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(loop.sampleRate)
                                .setChannelMask(loop.channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(loop.pcm.length)
                        .build();
                int written = loaded.write(loop.pcm, 0, loop.pcm.length);
                if (written != loop.pcm.length || loaded.getState() != AudioTrack.STATE_INITIALIZED)
                    throw new IOException("Incomplete static PCM buffer: " + written);
                // AudioTrack takes frame counts, not samples or bytes. -1 loops indefinitely.
                if (loaded.setLoopPoints(0, loop.frameCount, -1) != AudioTrack.SUCCESS)
                    throw new IOException("Android rejected the PCM loop points.");
                loaded.setVolume(0f);
                final AudioTrack ready = loaded;
                handler.post(() -> {
                    preparing = false;
                    if (closed) {
                        ready.release();
                        return;
                    }
                    track = ready;
                    Log.i(TAG, "Lobby rain PCM ready: " + loop.sampleRate + " Hz, channels=" +
                            loop.channels + ", frames=" + loop.frameCount + ", loops=infinite");
                    updatePlayback();
                });
            }
            catch (Exception error) {
                if (loaded != null) loaded.release();
                handler.post(() -> {
                    preparing = false;
                    if (!closed) fail("Não foi possível preparar o loop de chuva.", error);
                });
            }
        });
    }

    private boolean requestFocus() {
        if (hasFocus) return true;
        if (audioManager == null || focusRegistered) return false;
        // Ambient decoration should not interrupt music already playing in another app.
        if (audioManager.isMusicActive()) return false;
        int result = audioManager.requestAudioFocus(focusRequest);
        focusRegistered = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        hasFocus = focusRegistered;
        return hasFocus;
    }

    private void onFocusChange(int change) {
        if (closed) return;
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                hasFocus = true;
                ducked = false;
                if (canPlay()) updatePlayback();
                else pauseNow(true);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                ducked = true;
                if (canPlay()) fadeTo(volume * 0.2f, 120L);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                hasFocus = false;
                pauseNow(false);
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                focusBlocked = true;
                pauseNow(true);
                break;
            default: break;
        }
    }

    private void fadeTo(float target, long duration) {
        handler.removeCallbacks(fade);
        fadeFrom = currentGain;
        fadeTarget = target;
        fadeStart = SystemClock.uptimeMillis();
        fadeDuration = duration;
        handler.post(fade);
    }

    private void setGain(float gain) {
        currentGain = Math.max(0f, Math.min(1f, gain));
        if (track != null) track.setVolume(currentGain);
    }

    private void pauseNow(boolean abandon) {
        handler.removeCallbacks(fade);
        if (track != null) {
            try {
                setGain(0f);
                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.pause();
            }
            catch (IllegalStateException ignored) {}
        }
        currentGain = 0f;
        if (abandon) abandonFocus();
    }

    private void abandonFocus() {
        if (audioManager != null && focusRegistered) audioManager.abandonAudioFocusRequest(focusRequest);
        focusRegistered = false;
        hasFocus = false;
        ducked = false;
    }

    private void fail(String message, Exception error) {
        playbackFailed = true;
        pauseNow(true);
        if (track != null) {
            track.release();
            track = null;
        }
        Log.w(TAG, message, error);
    }

    @Override public void close() {
        runOnMain(() -> {
            if (closed) return;
            closed = true;
            pauseNow(true);
            if (track != null) {
                track.release();
                track = null;
            }
            loader.shutdown();
            if (receiverRegistered) {
                context.unregisterReceiver(becomingNoisy);
                receiverRegistered = false;
            }
        });
    }

    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else handler.post(action);
    }
}
