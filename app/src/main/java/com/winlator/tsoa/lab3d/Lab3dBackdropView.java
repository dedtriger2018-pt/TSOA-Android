package com.winlator.tsoa.lab3d;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.winlator.tsoa.EcoBackdropView;
import com.winlator.tsoa.TsoaOptions;

/** Disposable experimental 3D scenery behind the launcher's existing accessible controls. */
public final class Lab3dBackdropView extends FrameLayout implements Choreographer.FrameCallback {
    private static final String TAG = "TsoaLab3D";
    private static final long STARTUP_TIMEOUT_MS = 8000;
    private final SharedPreferences preferences;
    private final Choreographer choreographer;
    private final FramePacer pacer = new FramePacer();
    private final Button labButton;
    private final TextView badge;
    private GLSurfaceView surface;
    private Lab3dRenderer renderer;
    private EcoBackdropView fallback;
    private TsoaOptions options;
    private AlertDialog settingsDialog;
    private boolean initialized, attached, active = true, resumed, closed, failed;
    private boolean pbr, lights, dof, bloom, rain, reducedMotion;
    private int quality, fps, staticFrames;
    private float focusDistance, exposure;
    private long startupAt;
    private String settingsLabel = "", aboutLabel = "", actionLabel = "";
    private String failureReason = "";

    public Lab3dBackdropView(Context context) {
        super(context);
        preferences = TsoaOptions.prefs(context);
        choreographer = Choreographer.getInstance();
        options = new TsoaOptions(context);
        pbr = preferences.getBoolean("lab3d_pbr", true);
        lights = preferences.getBoolean("lab3d_lights", true);
        dof = preferences.getBoolean("lab3d_dof", true);
        bloom = preferences.getBoolean("lab3d_bloom", true);
        rain = preferences.getBoolean("lab3d_rain", true);
        quality = Math.max(0, Math.min(2, preferences.getInt("lab3d_quality", 1)));
        fps = preferences.getInt("lab3d_fps", 30) == 60 ? 60 : 30;
        focusDistance = clamp(preferences.getFloat("lab3d_focus", 18), 4, 30);
        exposure = clamp(preferences.getFloat("lab3d_exposure", 1), .5f, 1.6f);
        reducedMotion = options.isReducedMotion();
        setBackgroundColor(Color.rgb(9, 18, 22));
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        badge = new TextView(context);
        badge.setText("3D LAB");
        badge.setTextSize(10);
        badge.setTextColor(Color.rgb(158, 196, 196));
        badge.setLetterSpacing(.16f);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams badgeParams = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        badgeParams.setMargins(dp(18), dp(22), dp(8), 0);
        addView(badge, badgeParams);

        labButton = new Button(context);
        labButton.setText("3D");
        labButton.setTextColor(Color.rgb(210, 231, 228));
        labButton.setTextSize(12);
        labButton.setAllCaps(false);
        labButton.setPadding(0, 0, 0, 0);
        labButton.setMinimumHeight(dp(48));
        labButton.setMinimumWidth(dp(48));
        labButton.setMinHeight(dp(48));
        labButton.setMinWidth(dp(48));
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setColor(Color.argb(155, 12, 29, 34));
        buttonBackground.setCornerRadius(dp(9));
        buttonBackground.setStroke(dp(1), Color.argb(95, 139, 189, 188));
        labButton.setBackground(buttonBackground);
        labButton.setOnClickListener(view -> showSettings());
        LayoutParams buttonParams = new LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
        buttonParams.setMargins(dp(10), dp(10), dp(12), 0);
        addView(labButton, buttonParams);
        initialized = true;
        updateLanguage();
    }

    public void setMenuLabels(String settings, String about) {
        settingsLabel = settings == null ? "" : settings;
        aboutLabel = about == null ? "" : about;
        updateLanguage();
        if (fallback != null) fallback.setMenuLabels(settingsLabel, aboutLabel);
    }

    public void setReflectionAction(CharSequence action) {
        actionLabel = action == null ? "" : action.toString();
        if (fallback != null) fallback.setReflectionAction(actionLabel);
    }

    public void applyOptions(TsoaOptions value) {
        options = value;
        reducedMotion = value.isReducedMotion();
        updateLanguage();
        if (fallback != null) fallback.applyOptions(value);
        applyRendererOptions();
        invalidateScene();
        updateLifecycle();
    }

    public void setActive(boolean value) {
        active = value;
        if (!value && renderer != null) renderer.setTouch(.5f, .5f, false);
        updateLifecycle();
    }

    /** Observe the same local coordinates as EcoBackdropView without consuming menu input. */
    public void onInteraction(float x, float y, int action) {
        if (fallback != null) {
            fallback.onInteraction(x, y, action);
            return;
        }
        if (renderer == null) return;
        boolean release = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        if (release) {
            renderer.setTouch(.5f, .5f, false);
            return;
        }
        if (!canRun() || reducedMotion || getWidth() <= 0 || getHeight() <= 0) return;
        if (!finite(x) || !finite(y) || x < 0 || y < 0 || x > getWidth() || y > getHeight()) return;
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)
            renderer.setTouch(x / getWidth(), y / getHeight(), true);
    }

    private boolean canRun() {
        return !closed && active && attached && isShown() && getWindowVisibility() == VISIBLE
            && hasWindowFocus() && settingsDialog == null;
    }

    private void updateLifecycle() {
        if (!initialized) return;
        boolean run = canRun();
        if (fallback != null) {
            fallback.setActive(run);
            return;
        }
        if (run && !failed && surface == null) createSurface();
        if (surface == null || renderer == null) return;
        if (run && !resumed) {
            resumed = true;
            startupAt = SystemClock.uptimeMillis();
            staticFrames = 3;
            renderer.setPaused(reducedMotion);
            surface.onResume();
            scheduleFrame();
        }
        else if (!run && resumed) pauseSurface();
        else if (run) scheduleFrame();
    }

    private void createSurface() {
        try {
            ActivityManager manager = (ActivityManager)getContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null && manager.getDeviceConfigurationInfo().reqGlEsVersion < 0x30000) {
                useFallback("OpenGL ES 3.0 unavailable");
                return;
            }
            Lab3dRenderer nextRenderer = new Lab3dRenderer(getContext());
            GLSurfaceView nextSurface = new GLSurfaceView(getContext());
            nextSurface.setEGLContextClientVersion(3);
            nextSurface.setEGLConfigChooser(8, 8, 8, 0, 16, 0);
            nextSurface.setPreserveEGLContextOnPause(false);
            nextSurface.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            nextSurface.setFocusable(false);
            nextSurface.setClickable(false);
            nextSurface.setRenderer(nextRenderer);
            nextSurface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            renderer = nextRenderer;
            surface = nextSurface;
            applyRendererOptions();
            addView(nextSurface, 0, new LayoutParams(-1, -1));
        }
        catch (RuntimeException error) {
            useFallback(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private void pauseSurface() {
        stopFrames();
        if (surface == null || !resumed) return;
        resumed = false;
        final Lab3dRenderer retiring = renderer;
        retiring.setPaused(true);
        retiring.setTouch(.5f, .5f, false);
        // preserveEGLContextOnPause is false: EGL reclaims every scene object here.
        // A queued delete can run after context loss/resume and destroy fresh names.
        surface.onPause();
    }

    private void applyRendererOptions() {
        if (renderer == null) return;
        renderer.setPbrEnabled(pbr);
        renderer.setDynamicLightsEnabled(lights);
        renderer.setDofEnabled(dof);
        renderer.setBloomEnabled(bloom);
        renderer.setRainEnabled(rain);
        renderer.setQuality(quality);
        renderer.setRenderScale(1);
        renderer.setFocusDistance(focusDistance);
        renderer.setExposure(exposure);
        renderer.setBackgroundBrightness(options.getBackgroundBrightness());
        renderer.setRainIntensity(options.getRainIntensity() == 1 ? .5f : options.getRainIntensity() == 3 ? 1.2f : .8f);
        renderer.setPaused(!resumed || reducedMotion);
    }

    private void invalidateScene() {
        staticFrames = 3;
        pacer.resetTiming();
        if (resumed && canRun()) scheduleFrame();
    }

    private void scheduleFrame() {
        if (!resumed || !canRun() || renderer == null || failed) return;
        if (reducedMotion && staticFrames <= 0 && renderer.isReady()) return;
        if (pacer.arm()) choreographer.postFrameCallback(this);
    }

    private void stopFrames() {
        choreographer.removeFrameCallback(this);
        pacer.cancel();
    }

    @Override public void doFrame(long frameTimeNanos) {
        pacer.delivered();
        if (!resumed || !canRun() || renderer == null || surface == null || failed) return;
        String error = renderer.getLastError();
        if (error != null && !error.isEmpty()) {
            useFallback(error);
            return;
        }
        if (!renderer.isReady() && SystemClock.uptimeMillis() - startupAt >= STARTUP_TIMEOUT_MS) {
            useFallback("GLES initialization timed out");
            return;
        }
        if (pacer.takeFrame(frameTimeNanos, fps)) {
            surface.requestRender();
            if (renderer.isReady() && staticFrames > 0) staticFrames--;
        }
        scheduleFrame();
    }

    private void useFallback(String reason) {
        failed = true;
        failureReason = reason;
        Log.e(TAG, "3D backdrop disabled; returning to launcher scenery: " + reason);
        pauseSurface();
        stopFrames();
        if (surface != null) removeView(surface);
        surface = null;
        renderer = null;
        if (closed || fallback != null) return;
        fallback = new EcoBackdropView(getContext());
        fallback.setMenuLabels(settingsLabel, aboutLabel);
        fallback.setReflectionAction(actionLabel);
        fallback.applyOptions(options);
        fallback.setActive(canRun());
        addView(fallback, 0, new LayoutParams(-1, -1));
        badge.setText("3D LAB · 2D");
    }

    private void retry() {
        if (closed) return;
        if (fallback != null) {
            fallback.setActive(false);
            removeView(fallback);
            fallback = null;
        }
        failed = false;
        failureReason = "";
        badge.setText("3D LAB");
        updateLifecycle();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        updateLifecycle();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        pauseSurface();
        if (fallback != null) fallback.setActive(false);
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateLifecycle();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateLifecycle();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        updateLifecycle();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (initialized && (width != oldWidth || height != oldHeight)) invalidateScene();
    }

    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        updateLanguage();
    }

    public void close() {
        if (closed) return;
        closed = true;
        if (settingsDialog != null) settingsDialog.dismiss();
        pauseSurface();
        stopFrames();
        if (surface != null) removeView(surface);
        surface = null;
        renderer = null;
        if (fallback != null) {
            fallback.setActive(false);
            removeView(fallback);
            fallback = null;
        }
    }

    private void updateLanguage() {
        if (labButton != null) labButton.setContentDescription(tr("Configurações do laboratório 3D", "3D lab settings", "3D 实验室设置"));
    }

    private void showSettings() {
        if (closed || settingsDialog != null) return;
        Context dialogContext = new ContextThemeWrapper(getContext(), android.R.style.Theme_Material_Dialog_Alert);
        LinearLayout content = new LinearLayout(dialogContext);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(16));
        content.setBackgroundColor(Color.rgb(15, 27, 32));
        TextView description = label(dialogContext, tr("Cenário 3D experimental. Somente o menu.",
            "Experimental 3D scenery. Launcher menu only.", "实验性 3D 场景。仅作用于启动器菜单。"), 13);
        content.addView(description);
        if (failed) {
            TextView status = label(dialogContext, tr("Modo 2D de segurança ativo", "2D fallback active", "已启用 2D 后备模式")
                + "\n" + failureReason, 12);
            status.setTextColor(Color.rgb(226, 181, 144));
            content.addView(status);
        }
        addToggle(content, dialogContext, tr("Materiais PBR", "PBR materials", "PBR 材质"), pbr, "lab3d_pbr", enabled -> pbr = enabled);
        addToggle(content, dialogContext, tr("Luzes dinâmicas", "Dynamic lights", "动态光照"), lights, "lab3d_lights", enabled -> lights = enabled);
        addToggle(content, dialogContext, tr("Profundidade de campo", "Depth of field", "景深"), dof, "lab3d_dof", enabled -> dof = enabled);
        addToggle(content, dialogContext, "Bloom", bloom, "lab3d_bloom", enabled -> bloom = enabled);
        addToggle(content, dialogContext, tr("Chuva", "Rain", "雨"), rain, "lab3d_rain", enabled -> rain = enabled);
        content.addView(label(dialogContext, tr("Resolução interna máxima", "Maximum internal resolution", "最大内部渲染分辨率"), 13));
        addChoices(content, dialogContext, new String[]{"640", "960", "1280"}, quality, selected -> {
            quality = selected;
            preferences.edit().putInt("lab3d_quality", quality).apply();
            applyRendererOptions();
            invalidateScene();
        });
        content.addView(label(dialogContext, tr("Limite do menu", "Menu frame rate", "菜单帧率"), 13));
        addChoices(content, dialogContext, new String[]{"30 FPS", "60 FPS"}, fps == 60 ? 1 : 0, selected -> {
            fps = selected == 1 ? 60 : 30;
            preferences.edit().putInt("lab3d_fps", fps).apply();
            invalidateScene();
        });
        TextView focus = label(dialogContext, focusText(), 13);
        content.addView(focus);
        addSlider(content, dialogContext, 26, Math.round(focusDistance) - 4, progress -> {
            focusDistance = progress + 4;
            focus.setText(focusText());
            preferences.edit().putFloat("lab3d_focus", focusDistance).apply();
            applyRendererOptions();
            invalidateScene();
        });
        TextView exposureLabel = label(dialogContext, exposureText(), 13);
        content.addView(exposureLabel);
        addSlider(content, dialogContext, 110, Math.round(exposure * 100) - 50, progress -> {
            exposure = (progress + 50) / 100f;
            exposureLabel.setText(exposureText());
            preferences.edit().putFloat("lab3d_exposure", exposure).apply();
            applyRendererOptions();
            invalidateScene();
        });
        if (reducedMotion) content.addView(label(dialogContext,
            tr("Movimento reduzido: animação pausada.", "Reduced motion: animation paused.", "已减少动态效果：动画暂停。"), 12));
        ScrollView scroll = new ScrollView(dialogContext);
        scroll.setFillViewport(true);
        scroll.addView(content);
        AlertDialog.Builder builder = new AlertDialog.Builder(dialogContext)
            .setTitle(tr("Laboratório 3D", "3D Lab", "3D 实验室"))
            .setView(scroll)
            .setPositiveButton(tr("Fechar", "Close", "关闭"), (dialog, which) -> {});
        if (failed) builder.setNeutralButton(tr("Tentar 3D", "Retry 3D", "重试 3D"), (dialog, which) -> retry());
        settingsDialog = builder.create();
        settingsDialog.setOnDismissListener(dialog -> {
            settingsDialog = null;
            updateLifecycle();
        });
        updateLifecycle();
        settingsDialog.show();
    }

    private interface ToggleAction { void changed(boolean value); }
    private interface SelectionAction { void changed(int value); }

    private void addToggle(LinearLayout parent, Context context, String text, boolean checked, String key, ToggleAction action) {
        Switch control = new Switch(context);
        control.setText(text);
        control.setTextSize(14);
        control.setTextColor(Color.rgb(212, 227, 224));
        control.setMinHeight(dp(48));
        control.setChecked(checked);
        control.setOnCheckedChangeListener((button, enabled) -> {
            action.changed(enabled);
            preferences.edit().putBoolean(key, enabled).apply();
            applyRendererOptions();
            invalidateScene();
        });
        parent.addView(control, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addChoices(LinearLayout parent, Context context, String[] choices, int selected, SelectionAction action) {
        RadioGroup group = new RadioGroup(context);
        group.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < choices.length; i++) {
            RadioButton radio = new RadioButton(context);
            radio.setId(View.generateViewId());
            radio.setText(choices[i]);
            radio.setTextSize(12);
            radio.setTextColor(Color.rgb(212, 227, 224));
            radio.setMinHeight(dp(48));
            radio.setTag(i);
            group.addView(radio, new RadioGroup.LayoutParams(0, -2, 1));
            if (i == selected) group.check(radio.getId());
        }
        group.setOnCheckedChangeListener((radios, id) -> {
            View choice = radios.findViewById(id);
            if (choice != null) action.changed((Integer)choice.getTag());
        });
        parent.addView(group, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addSlider(LinearLayout parent, Context context, int maximum, int current, SelectionAction action) {
        SeekBar slider = new SeekBar(context);
        slider.setMax(maximum);
        slider.setProgress(current);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) action.changed(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        parent.addView(slider, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private TextView label(Context context, String text, int size) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(Color.rgb(182, 205, 204));
        label.setPadding(0, dp(10), 0, dp(4));
        return label;
    }

    private String focusText() { return tr("Foco", "Focus", "焦距") + ": " + Math.round(focusDistance) + " m"; }
    private String exposureText() { return tr("Exposição", "Exposure", "曝光") + ": " + Math.round(exposure * 100) + "%"; }
    private String tr(String portuguese, String english, String chinese) {
        String language = options == null ? "pt" : options.getMenuLanguage();
        return "zh-CN".equals(language) ? chinese : "en".equals(language) ? english : portuguese;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
    private static float clamp(float value, float minimum, float maximum) {
        return finite(value) ? Math.max(minimum, Math.min(maximum, value)) : minimum;
    }

    /** Pure scheduling state, also exercised on the JVM with deterministic display timelines. */
    public static final class FramePacer {
        private boolean pending;
        private long nextFrameNanos;
        public boolean arm() {
            if (pending) return false;
            pending = true;
            return true;
        }
        public void delivered() { pending = false; }
        public void resetTiming() { nextFrameNanos = 0; }
        public void cancel() { pending = false; resetTiming(); }
        public boolean takeFrame(long now, int fps) {
            long interval = 1_000_000_000L / (fps == 60 ? 60 : 30);
            if (nextFrameNanos == 0) {
                nextFrameNanos = now + interval;
                return true;
            }
            // Display timestamps vary slightly; retain a deadline instead of losing every other vsync.
            if (now + 100_000L < nextFrameNanos) return false;
            nextFrameNanos += interval;
            if (nextFrameNanos <= now) nextFrameNanos = now + interval;
            return true;
        }
    }
}
