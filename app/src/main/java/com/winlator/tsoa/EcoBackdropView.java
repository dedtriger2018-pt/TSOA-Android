package com.winlator.tsoa;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import com.winlator.R;

import java.util.Random;

/** A quiet storm over wet ground. Artwork is still; weather and water are native Canvas layers. */
public final class EcoBackdropView extends View {
    private static final int FRAME_DELAY_MS = 33;
    private static final int MAX_DROPS = 112;
    private static final int RIPPLE_COUNT = 16;
    private static final int MAX_BITMAP_PIXELS = 3 * 1024 * 1024;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veilPaint = new Paint();
    private final Paint vignettePaint = new Paint();
    private final Paint mistPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lowMistPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reflectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Rect bitmapSource = new Rect();
    private final RectF bitmapTarget = new RectF();
    private final RectF movingSkyTarget = new RectF();
    private final RectF rippleBounds = new RectF();
    private final float[] dropX = new float[MAX_DROPS];
    private final float[] dropY = new float[MAX_DROPS];
    private final float[] dropDepth = new float[MAX_DROPS];
    private final float[] rippleX = new float[RIPPLE_COUNT];
    private final float[] rippleY = new float[RIPPLE_COUNT];
    private final float[] ripplePhase = new float[RIPPLE_COUNT];
    private final Random random = new Random(1672670L);
    private float density;
    private float textScale;
    private Bitmap artwork;
    private int dropCount = 72;
    private float brightness = 0.78f;
    private boolean reducedMotion;
    private boolean active = true;
    private boolean attached;
    private boolean initialized;
    private boolean framePending;
    private long lastFrameTime;
    private float time;
    private String reflectionAction = "JOGAR";

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            framePending = false;
            if (!shouldAnimate()) return;
            long now = SystemClock.uptimeMillis();
            float delta = lastFrameTime == 0 ? 0 : Math.min(0.066f, (now - lastFrameTime) / 1000f);
            lastFrameTime = now;
            advanceRain(delta);
            time += delta;
            invalidate();
            scheduleFrame();
        }
    };

    public EcoBackdropView(Context context) { this(context, null); }

    public EcoBackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        textScale = getResources().getDisplayMetrics().scaledDensity;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        setClickable(false);
        setBackgroundColor(Color.rgb(15, 24, 28));
        rainPaint.setStrokeCap(Paint.Cap.ROUND);
        ripplePaint.setStyle(Paint.Style.STROKE);
        reflectionPaint.setTextAlign(Paint.Align.CENTER);
        reflectionPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        for (int i = 0; i < MAX_DROPS; i++) {
            dropX[i] = random.nextFloat() * 1.18f;
            dropY[i] = random.nextFloat() * 1.08f - 0.08f;
            dropDepth[i] = 0.25f + random.nextFloat() * 0.75f;
        }
        for (int i = 0; i < RIPPLE_COUNT; i++) {
            rippleX[i] = 0.04f + random.nextFloat() * 0.92f;
            rippleY[i] = 0.79f + random.nextFloat() * 0.20f;
            ripplePhase[i] = random.nextFloat();
        }
        initialized = true;
    }

    public void applyOptions(TsoaOptions options) {
        setRainIntensity(options.getRainIntensity());
        setBackgroundBrightness(options.getBackgroundBrightness());
        setReducedMotion(options.isReducedMotion());
    }

    /** Keeps the reflected action consistent with the real, accessible launcher button. */
    public void setReflectionAction(CharSequence value) {
        String next = value == null ? "" : value.toString();
        if (!next.equals(reflectionAction)) {
            reflectionAction = next;
            invalidate();
        }
    }

    public void setActive(boolean value) {
        if (active == value) return;
        active = value;
        updateAnimation();
    }

    /** Rain is automatic. Reduced motion freezes all layers instead of running another timer. */
    public void setRainIntensity(int intensity) {
        dropCount = intensity <= 1 ? 38 : intensity >= 3 ? MAX_DROPS : 72;
        invalidate();
    }

    public void setBackgroundBrightness(float value) {
        brightness = Float.isNaN(value) ? 0.78f : Math.max(0.35f, Math.min(1f, value));
        invalidate();
    }

    public void setReducedMotion(boolean value) {
        if (reducedMotion == value) return;
        reducedMotion = value;
        updateAnimation();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        loadArtwork();
        updateAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        stopFrames();
        // A hardware display list may still reference the bitmap; let normal GC release it.
        artwork = null;
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (initialized) updateAnimation();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (initialized) updateAnimation();
    }

    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (!initialized) return;
        density = getResources().getDisplayMetrics().density;
        textScale = getResources().getDisplayMetrics().scaledDensity;
        // MainActivity handles rotation itself, so resolve the portrait/landscape drawable again.
        // Do not recycle the old bitmap while a hardware display list can still reference it.
        artwork = null;
        loadArtwork();
        updateCrop(getWidth(), getHeight());
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        loadArtwork();
        updateCrop(w, h);
        veilPaint.setShader(new LinearGradient(0, 0, 0, h,
            new int[]{0x09071014, 0x22071014, 0x08071014, 0x26071014},
            new float[]{0, 0.45f, 0.72f, 1}, Shader.TileMode.CLAMP));
        vignettePaint.setShader(new RadialGradient(w * 0.50f, h * 0.48f,
            Math.max(w, h) * 0.85f,
            new int[]{0x00040A0D, 0x0A040A0D, 0x78040A0D},
            new float[]{0, 0.54f, 1}, Shader.TileMode.CLAMP));
        mistPaint.setShader(new RadialGradient(w * 0.46f, h * 0.34f,
            Math.max(w, h) * 0.51f,
            new int[]{0x0CABB8BA, 0x0798ACB0, 0x0098ACB0},
            new float[]{0, 0.48f, 1}, Shader.TileMode.CLAMP));
        lowMistPaint.setShader(new LinearGradient(0, h * 0.64f, 0, h * 0.88f,
            new int[]{0x0097ADB0, 0x1097ADB0, 0x0097ADB0},
            new float[]{0, 0.47f, 1}, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int width = getWidth();
        final int height = getHeight();
        if (width <= 0 || height <= 0) return;
        if (artwork != null) {
            bitmapPaint.setAlpha(255);
            canvas.drawBitmap(artwork, bitmapSource, bitmapTarget, bitmapPaint);
            drawMovingSky(canvas, width, height);
        }
        drawMist(canvas, width, height);
        canvas.drawRect(0, 0, width, height, veilPaint);
        canvas.drawRect(0, 0, width, height, vignettePaint);
        int dim = Math.round((1f - brightness) * 100f);
        if (dim > 0) canvas.drawColor(Color.argb(dim, 2, 9, 12));
        drawReflections(canvas, width, height);
        drawRain(canvas, width, height);
        drawRipples(canvas, width, height);
        scheduleFrame();
    }

    private void loadArtwork() {
        if (artwork != null || !initialized) return;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        bounds.inScaled = false;
        BitmapFactory.decodeResource(getResources(), R.drawable.tsoa_eco_background, bounds);
        int sample = 1;
        while ((long)(bounds.outWidth / sample) * (bounds.outHeight / sample) > MAX_BITMAP_PIXELS) sample *= 2;
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inScaled = false;
        decode.inSampleSize = sample;
        artwork = BitmapFactory.decodeResource(getResources(), R.drawable.tsoa_eco_background, decode);
        updateCrop(getWidth(), getHeight());
    }

    private void updateCrop(int width, int height) {
        if (artwork == null || width <= 0 || height <= 0) return;
        float targetRatio = (float)width / height;
        float imageRatio = (float)artwork.getWidth() / artwork.getHeight();
        int sourceWidth = artwork.getWidth();
        int sourceHeight = artwork.getHeight();
        if (imageRatio > targetRatio) sourceWidth = Math.max(1, Math.round(sourceHeight * targetRatio));
        else sourceHeight = Math.max(1, Math.round(sourceWidth / targetRatio));
        int x = (artwork.getWidth() - sourceWidth) / 2;
        // Anchor the wet-ground horizon when a portrait artwork is cropped for landscape.
        int y = Math.round(artwork.getHeight() * 0.70f - sourceHeight * 0.76f);
        y = Math.max(0, Math.min(artwork.getHeight() - sourceHeight, y));
        bitmapSource.set(x, y, x + sourceWidth, y + sourceHeight);
        bitmapTarget.set(0, 0, width, height);
    }

    private void drawMovingSky(Canvas canvas, int width, int height) {
        float driftX = (float)Math.sin(time * 0.035f) * width * 0.012f;
        float driftY = (float)Math.sin(time * 0.028f) * height * 0.005f;
        movingSkyTarget.set(-width * 0.018f + driftX, -height * 0.012f + driftY,
            width * 1.018f + driftX, height * 1.012f + driftY);
        canvas.save();
        canvas.clipRect(0, 0, width, height * 0.68f);
        bitmapPaint.setAlpha(42);
        canvas.drawBitmap(artwork, bitmapSource, movingSkyTarget, bitmapPaint);
        bitmapPaint.setAlpha(255);
        canvas.restore();
    }

    private void drawMist(Canvas canvas, int width, int height) {
        float drift = (float)Math.sin(time * 0.09f) * width * 0.06f;
        canvas.save();
        canvas.translate(drift, 0);
        canvas.drawRect(-width, 0, width * 2, height * 0.78f, mistPaint);
        canvas.restore();
        canvas.drawRect(0, height * 0.64f, width, height * 0.88f, lowMistPaint);
    }

    private void drawRain(Canvas canvas, int width, int height) {
        float unit = Math.min(width, height);
        float wind = 0.19f + (float)Math.sin(time * 0.17f) * 0.06f;
        for (int i = 0; i < dropCount; i++) {
            float depth = dropDepth[i];
            float x = dropX[i] * width;
            float y = dropY[i] * height;
            float length = unit * (0.012f + depth * 0.026f);
            rainPaint.setColor(Color.argb((int)(12 + 27 * depth), 168, 194, 201));
            rainPaint.setStrokeWidth(Math.max(0.6f, density * (0.28f + depth * 0.30f)));
            canvas.drawLine(x, y, x - length * wind, y + length, rainPaint);
        }
    }

    private void advanceRain(float delta) {
        for (int i = 0; i < dropCount; i++) {
            float speed = 0.23f + dropDepth[i] * 0.55f;
            dropY[i] += speed * delta;
            dropX[i] -= speed * delta * 0.05f;
            if (dropY[i] > 1.06f) {
                dropY[i] = -0.08f;
                dropX[i] = random.nextFloat() * 1.18f;
            }
            if (dropX[i] < -0.05f) dropX[i] = 1.08f;
        }
    }

    private void drawRipples(Canvas canvas, int width, int height) {
        float groundScale = width > height ? 0.62f : 1f;
        for (int i = 0; i < RIPPLE_COUNT; i++) {
            float phase = (time * (0.32f + (i % 3) * 0.035f) + ripplePhase[i]) % 1f;
            float depth = (rippleY[i] - 0.76f) / 0.24f;
            float radius = density * (2f + phase * (8f + depth * 16f)) * groundScale;
            float x = rippleX[i] * width;
            float y = rippleY[i] * height;
            ripplePaint.setColor(Color.argb((int)((1f - phase) * (12f + depth * 13f)), 182, 201, 202));
            ripplePaint.setStrokeWidth(Math.max(0.55f, density * 0.35f));
            rippleBounds.set(x - radius, y - radius * 0.18f, x + radius, y + radius * 0.18f);
            canvas.drawOval(rippleBounds, ripplePaint);
        }
    }

    private void drawReflections(Canvas canvas, int width, int height) {
        boolean landscape = width > height;
        float start = height * (landscape ? 0.85f : 0.77f);
        float gap = height * (landscape ? 0.033f : 0.038f);
        float center = width * 0.5f;
        drawWaterText(canvas, "TSOA_FE", center, start, 15, 48);
        drawWaterText(canvas, "The Street of Adrift", center, start + gap * 0.48f, 8, 28);
        drawWaterText(canvas, reflectionAction, center, start + gap * 1.65f, 12, 37);
        drawWaterText(canvas, "Configurações", center, start + gap * 2.65f, 10, 26);
        drawWaterText(canvas, "Sobre", center, start + gap * 3.55f, 10, 20);
    }

    private void drawWaterText(Canvas canvas, String text, float x, float y, float sp, int alpha) {
        float shimmer = (float)Math.sin(time * 0.85f + y * 0.008f) * density * 0.9f;
        reflectionPaint.setTextSize(sp * textScale);
        reflectionPaint.setColor(Color.argb(alpha, 178, 199, 201));
        canvas.save();
        canvas.translate(x + shimmer, y);
        // A flattened, inverted echo stays decorative and never creates a duplicate touch target.
        canvas.scale(1, -0.46f);
        canvas.drawText(text, 0, 0, reflectionPaint);
        canvas.restore();
    }

    private boolean shouldAnimate() {
        return initialized && attached && active && !reducedMotion
            && getWindowVisibility() == VISIBLE && isShown();
    }

    private void scheduleFrame() {
        if (framePending || !shouldAnimate()) return;
        framePending = true;
        postDelayed(frame, FRAME_DELAY_MS);
    }

    private void stopFrames() {
        removeCallbacks(frame);
        framePending = false;
        lastFrameTime = 0;
    }

    private void updateAnimation() {
        stopFrames();
        if (shouldAnimate()) scheduleFrame();
        invalidate();
    }
}
