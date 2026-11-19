package com.winlator.tsoa;

import android.app.Activity;
import android.os.Build;
import android.os.Debug;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.winlator.R;
import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.GraphicsBudget;

/** Optional local HUD. Android services and memory sampling stay off the GL thread. */
public final class TsoaPerformanceMonitor implements Runnable {
    private final Activity activity;
    private final GLRenderer renderer;
    private final FrameLayout root;
    private TextView hud;
    private boolean adaptive;
    private int thermal = -1;

    public TsoaPerformanceMonitor(Activity activity, GLRenderer renderer, FrameLayout root) {
        this.activity = activity; this.renderer = renderer; this.root = root;
    }

    public void start(TsoaOptions options) {
        boolean original = options.isOriginalMode();
        adaptive = !original && options.isAdaptiveQuality();
        boolean show = !original && options.isPerformanceVisible();
        renderer.configureBudget(adaptive, show);
        root.removeCallbacks(this);
        if (show && hud == null) {
            hud = new TextView(TsoaMenuLanguage.context(activity));
            hud.setTextSize(11);
            hud.setTextColor(0xffe1f0ee);
            hud.setBackgroundColor(0xbb081519);
            hud.setPadding(12, 6, 12, 6);
            FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            bounds.topMargin = 12;
            root.addView(hud, bounds);
        }
        if (hud != null) hud.setVisibility(show ? View.VISIBLE : View.GONE);
        if (adaptive || show) run();
    }

    public void stop() {
        root.removeCallbacks(this);
        renderer.configureBudget(false, false);
        if (hud != null) hud.setVisibility(View.GONE);
    }

    @Override public void run() {
        GraphicsBudget budget = renderer.getGraphicsBudget();
        if (budget == null) return;
        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager power = activity.getSystemService(PowerManager.class);
            if (power != null) thermal = power.getCurrentThermalStatus();
        }
        budget.setThermal(Math.max(0, thermal));
        if (hud != null && hud.getVisibility() == View.VISIBLE) {
            GraphicsBudget.Sample sample = budget.sample();
            if (sample == null) hud.setText(TsoaMenuLanguage.get(activity, R.string.tsoa_perf_warming));
            else {
                Debug.MemoryInfo memory = new Debug.MemoryInfo();
                Debug.getMemoryInfo(memory);
                boolean idle = System.nanoTime() - sample.atNanos > 4_000_000_000L;
                hud.setText(TsoaMenuLanguage.get(activity, R.string.tsoa_perf_status,
                        idle ? 0f : sample.sourceRate, idle ? 0f : sample.generatedRate,
                        idle ? 0f : sample.submissionMs,
                        memory.getTotalPss() / 1024, TsoaMenuLanguage.get(activity, thermalLabel(thermal)), sample.quality));
            }
        }
        root.postDelayed(this, 2000);
    }
    private static int thermalLabel(int status) {
        switch (status) {
            case 0: return R.string.tsoa_thermal_normal;
            case 1: return R.string.tsoa_thermal_light;
            case 2: return R.string.tsoa_thermal_moderate;
            case 3: return R.string.tsoa_thermal_severe;
            case 4: return R.string.tsoa_thermal_critical;
            case 5: return R.string.tsoa_thermal_emergency;
            case 6: return R.string.tsoa_thermal_shutdown;
            default: return R.string.tsoa_thermal_unknown;
        }
    }

}
