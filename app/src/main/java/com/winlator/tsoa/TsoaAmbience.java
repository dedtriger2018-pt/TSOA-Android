package com.winlator.tsoa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/** A quiet, pre-rendered rain/wind loop owned only by the visible launcher. */
public final class TsoaAmbience implements AutoCloseable {
    private static final String ASSET = "tsoa_lobby_rain.wav";
    private static final String TAG = "TSOA_FE_DIAG";
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioManager audioManager;
    private final AudioFocusRequest focusRequest;
    private MediaPlayer player;
    private boolean prepared;
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
            if (!canPlay() || player == null || !prepared || !hasFocus) return;
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
        if (player == null) {
            preparePlayer();
            return;
        }
        if (!prepared || !requestFocus()) return;
        try {
            boolean starting = !player.isPlaying();
            if (starting) {
                setGain(0f);
                player.start();
            }
            fadeTo(volume * (ducked ? 0.2f : 1f), starting ? 900L : 180L);
        }
        catch (IllegalStateException error) { fail("Falha ao iniciar o som do lobby.", error); }
    }

    private void preparePlayer() {
        try (AssetFileDescriptor source = context.getAssets().openFd(ASSET)) {
            MediaPlayer next = new MediaPlayer();
            player = next;
            prepared = false;
            next.setAudioAttributes(attributes());
            next.setDataSource(source.getFileDescriptor(), source.getStartOffset(), source.getLength());
            next.setLooping(true);
            next.setVolume(0f, 0f);
            next.setOnPreparedListener(ready -> {
                if (closed || player != ready) return;
                prepared = true;
                updatePlayback();
            });
            next.setOnErrorListener((failed, what, extra) -> {
                if (player == failed) fail("Som do lobby indisponível: " + what + "/" + extra, null);
                return true;
            });
            next.prepareAsync();
        }
        catch (Exception error) { fail("Não foi possível preparar o som do lobby.", error); }
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
        if (player != null) player.setVolume(currentGain, currentGain);
    }

    private void pauseNow(boolean abandon) {
        handler.removeCallbacks(fade);
        if (player != null && prepared) {
            try {
                setGain(0f);
                if (player.isPlaying()) player.pause();
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
        prepared = false;
        pauseNow(true);
        if (player != null) {
            player.release();
            player = null;
        }
        Log.w(TAG, message, error);
    }

    @Override public void close() {
        runOnMain(() -> {
            if (closed) return;
            closed = true;
            pauseNow(true);
            if (player != null) {
                player.release();
                player = null;
            }
            prepared = false;
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
