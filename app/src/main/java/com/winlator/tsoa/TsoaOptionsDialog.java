package com.winlator.tsoa;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.winlator.R;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;

import java.util.ArrayList;
import java.util.Arrays;

/** Small, scrollable settings pages. Changes affect the lobby now and the next game session. */
public final class TsoaOptionsDialog {
    public interface Actions {
        void editControls();
        void resetControls();
        void importGame();
        void openChronos();
        void openPda();
        void exportSaves();
        void importSaves();
        void verifyFiles();
        void exportDiagnostics();
    }

    private static final int TEXT = Color.rgb(216, 226, 225);
    private static final int MUTED = Color.rgb(147, 167, 168);
    private static final int ACCENT = Color.rgb(141, 190, 187);
    private final Activity activity;
    private final SharedPreferences preferences;
    private final TsoaOptions options;
    private final Runnable onChanged;
    private final Actions actions;
    private Dialog dialog;
    private LinearLayout content;

    private TsoaOptionsDialog(Activity activity, Runnable onChanged, Actions actions) {
        this.activity = activity;
        this.preferences = TsoaOptions.prefs(activity);
        this.options = new TsoaOptions(activity);
        this.onChanged = onChanged;
        this.actions = actions;
    }

    public static void show(Activity activity, Runnable onChanged, Actions actions) {
        if (!activity.isFinishing()) new TsoaOptionsDialog(activity, onChanged, actions).showPage(0);
    }

    private void showPage(int page) {
        if (activity.isFinishing()) return;
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout shell = new LinearLayout(activity);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(22), dp(10), dp(22), dp(8));
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(Color.rgb(12, 23, 27));
        surface.setCornerRadius(dp(12));
        surface.setStroke(dp(1), Color.rgb(43, 68, 72));
        shell.setBackground(surface);

        int[] titles = {R.string.tsoa_options, R.string.tsoa_video, R.string.tsoa_controls,
                R.string.tsoa_mouse, R.string.tsoa_audio, R.string.tsoa_ambience, R.string.tsoa_comfort, R.string.tsoa_files};
        TextView heading = text(s(titles[page]), 20, TEXT);
        heading.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        heading.setPadding(0, dp(12), 0, dp(12));
        shell.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(12));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        switch (page) {
            case 1: video(); break;
            case 2: controls(); break;
            case 3: mouse(); break;
            case 4: audio(); break;
            case 5: ambience(); break;
            case 6: comfort(); break;
            case 7: files(); break;
            default: overview(); break;
        }

        Button close = button(page == 0 ? s(R.string.tsoa_close) : s(R.string.tsoa_back));
        close.setOnClickListener(view -> {
            if (page == 0) dialog.dismiss();
            else showPage(0);
        });
        shell.addView(close, new LinearLayout.LayoutParams(-1, dp(48)));
        dialog.setContentView(shell);
        dialog.setOnDismissListener(ignored -> changed());
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            int width = activity.getResources().getDisplayMetrics().widthPixels;
            int height = activity.getResources().getDisplayMetrics().heightPixels;
            window.setLayout(Math.min(width - dp(28), dp(520)), Math.max(dp(210), (int)(height * 0.86f)));
            window.setDimAmount(0.7f);
        }
    }

    private void overview() {
        String[] languages = {"en", "zh-CN", "pt"};
        int selectedLanguage = 2;
        for (int i = 0; i < languages.length; i++) {
            if (languages[i].equals(options.getMenuLanguage())) selectedLanguage = i;
        }
        spinner(s(R.string.tsoa_menu_language), new String[]{"English", "简体中文", "Português"}, selectedLanguage, position -> {
            preferences.edit().putString("menu_language", languages[position]).apply();
            changed();
            showPage(0);
        });
        note(s(R.string.tsoa_menu_language_detail));
        note(s(R.string.tsoa_options_autosave));
        action(s(R.string.tsoa_video), s(R.string.tsoa_video_summary), () -> showPage(1), false);
        action(s(R.string.tsoa_controls), s(R.string.tsoa_controls_summary), () -> showPage(2), false);
        action(s(R.string.tsoa_mouse), s(R.string.tsoa_mouse_summary), () -> showPage(3), false);
        action(s(R.string.tsoa_audio), s(R.string.tsoa_audio_summary), () -> showPage(4), false);
        action(s(R.string.tsoa_ambience), s(R.string.tsoa_ambience_summary), () -> showPage(5), false);
        action(s(R.string.tsoa_comfort), s(R.string.tsoa_comfort_summary), () -> showPage(6), false);
        action(s(R.string.tsoa_files), s(R.string.tsoa_files_summary), () -> showPage(7), false);
        String version = "";
        try { version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName; }
        catch (Exception ignored) {}
        note(s(R.string.tsoa_credits, version));
    }

    private void video() {
        toggle(s(R.string.tsoa_original), s(R.string.tsoa_original_detail), "game_original", options.isOriginalMode());
        String[] all = activity.getResources().getStringArray(R.array.screen_size_entries);
        String[] labels = Arrays.copyOfRange(all, 1, all.length);
        labels[0] = s(R.string.tsoa_native_resolution);
        String[] values = new String[labels.length];
        for (int i = 0; i < values.length; i++) values[i] = i == 0 ? "native" : labels[i].trim().split("\\s+")[0];
        choice(s(R.string.tsoa_game_resolution), "screen_size", labels, values, options.getScreenSize());
        choice(s(R.string.tsoa_aspect_ratio), "aspect_mode", new String[]{s(R.string.tsoa_aspect_fit), s(R.string.tsoa_aspect_stretch)},
                new String[]{"fit", "stretch"}, options.getAspectMode());
        toggle(s(R.string.tsoa_fullscreen), s(R.string.tsoa_fullscreen_detail), "fullscreen", options.isFullscreen());
        intChoice(s(R.string.tsoa_fps_limit), "fps_limit", new String[]{s(R.string.tsoa_unlimited), s(R.string.tsoa_fps_30), s(R.string.tsoa_fps_60)},
                new int[]{0, 30, 60}, options.getFpsLimit());
        intChoice(s(R.string.tsoa_simulated_hdr), "game_hdr_mode", new String[]{s(R.string.tsoa_off), s(R.string.tsoa_soft), s(R.string.tsoa_vivid)},
                new int[]{0, 1, 2}, options.getGameHdrMode());
        note(s(R.string.tsoa_hdr_detail));
        toggle(s(R.string.tsoa_soft_bloom), s(R.string.tsoa_soft_bloom_detail),
                "game_bloom_enabled", options.isGameBloomEnabled());
        toggle(s(R.string.tsoa_fxaa), s(R.string.tsoa_fxaa_detail),
                "game_fxaa_enabled", options.isGameFxaaEnabled());
        slider(s(R.string.tsoa_game_sharpening), "game_sharpening_strength", 0, 100,
                options.getGameSharpeningStrength(), false, "%");
        note(s(R.string.tsoa_game_sharpening_detail));
        toggle(s(R.string.tsoa_fsr), s(R.string.tsoa_fsr_detail),
                "game_fsr_enabled", options.isGameFsrEnabled());
        toggle(s(R.string.tsoa_frame_generation), s(R.string.tsoa_frame_generation_detail),
                "game_frame_generation_enabled", options.isGameFrameGenerationEnabled());
        String[] levels = {s(R.string.tsoa_off), s(R.string.tsoa_scene_low), s(R.string.tsoa_scene_medium), s(R.string.tsoa_scene_ultra)};
        int[] valuesByLevel = {0, 1, 2, 3};
        intChoice(s(R.string.tsoa_genesis), TsoaOptions.GENESIS, levels, valuesByLevel, options.getGenesisLevel());
        intChoice(s(R.string.tsoa_lumen), TsoaOptions.LUMEN, levels, valuesByLevel, options.getLumenLevel());
        note(s(R.string.tsoa_scene_detail));
        intChoice(s(R.string.tsoa_cinematic), "game_cinematic", levels, valuesByLevel, options.getCinematicLevel());
        slider(s(R.string.tsoa_cinematic_amount), "game_cinematic_amount", 0, 100, options.getCinematicAmount(), false, "%");
        toggle(s(R.string.tsoa_cinematic_vignette), "", "game_cinematic_vignette", options.isCinematicVignette());
        toggle(s(R.string.tsoa_cinematic_grain), "", "game_cinematic_grain", options.isCinematicGrain());
        intChoice(s(R.string.tsoa_beyond_title), "game_beyond_mode", new String[]{
                s(R.string.tsoa_off), s(R.string.tsoa_beyond_black), s(R.string.tsoa_beyond_ambient)},
                new int[]{0, 1, 2}, options.getBeyondMode());
        slider(s(R.string.tsoa_beyond), "game_beyond", 0, 100, options.getBeyondAmount(), false, "%");
        note(s(R.string.tsoa_beyond_detail));
        slider(s(R.string.tsoa_beyond_blur), "game_beyond_blur", 0, 100, options.getBeyondBlur(), false, "%");
        slider(s(R.string.tsoa_beyond_brightness), "game_beyond_brightness", 0, 100, options.getBeyondBrightness(), false, "%");
        slider(s(R.string.tsoa_beyond_saturation), "game_beyond_saturation", 0, 100, options.getBeyondSaturation(), false, "%");
        toggle(s(R.string.tsoa_adaptive), s(R.string.tsoa_adaptive_detail), "game_adaptive", options.isAdaptiveQuality());
        toggle(s(R.string.tsoa_perf), s(R.string.tsoa_perf_detail), "game_performance", options.isPerformanceVisible());
        note(s(R.string.tsoa_game_filters_detail));
        note(s(R.string.tsoa_resolution_detail));
    }

    private void controls() {
        TsoaGameControls.ensureProfiles(activity);
        choice(s(R.string.tsoa_controls), "controls_mode", new String[]{s(R.string.tsoa_controls_hidden), s(R.string.tsoa_controls_esc), s(R.string.tsoa_controls_rts), s(R.string.tsoa_controls_custom)},
                new String[]{"off", "esc", "rts", "custom"}, options.getControlsMode());
        note(s(R.string.tsoa_controls_detail));
        ArrayList<ControlsProfile> profiles = new InputControlsManager(activity).getProfiles(true);
        if (!profiles.isEmpty()) {
            String[] names = new String[profiles.size() + 1];
            int[] ids = new int[profiles.size() + 1];
            names[0] = s(R.string.tsoa_select_profile);
            ids[0] = 0;
            for (int i = 0; i < profiles.size(); i++) {
                names[i + 1] = profiles.get(i).getName();
                ids[i + 1] = profiles.get(i).id;
            }
            intChoice(s(R.string.tsoa_custom_profile), "controls_profile_id", names, ids, options.getProfileId());
        }
        slider(s(R.string.tsoa_controls_opacity), "controls_opacity", 20, 100, Math.round(options.getControlsOpacity() * 100), true, "%");
        action(s(R.string.tsoa_edit_controls), s(R.string.tsoa_edit_controls_detail), actions::editControls, true);
        action(s(R.string.tsoa_reset_controls), s(R.string.tsoa_reset_controls_detail), () -> {
            new AlertDialog.Builder(activity)
                    .setTitle(s(R.string.tsoa_reset_controls_title))
                    .setMessage(s(R.string.tsoa_reset_controls_message))
                    .setNegativeButton(s(R.string.tsoa_cancel), null)
                    .setPositiveButton(s(R.string.tsoa_restore), (alert, which) -> {
                        preferences.edit().putString("controls_mode", "esc").putFloat("controls_opacity", 0.55f).apply();
                        actions.resetControls();
                        changed();
                        showPage(2);
                    }).show();
        }, false);
    }

    private void mouse() {
        slider(s(R.string.tsoa_cursor_speed), "cursor_speed", 25, 300, Math.round(options.getCursorSpeed() * 100), true, "%");
        toggle(s(R.string.tsoa_mouse_tap), s(R.string.tsoa_mouse_tap_detail), "tap_to_click", options.isTapToClick());
        toggle(s(R.string.tsoa_mouse_hold), s(R.string.tsoa_mouse_hold_detail), "long_press_right_click", options.isLongPressRightClick());
    }

    private void audio() {
        slider(s(R.string.tsoa_game_volume), "volume", 0, 100, options.getVolume(), false, "%");
        toggle(s(R.string.tsoa_mute), s(R.string.tsoa_mute_detail), "mute", options.isMuted());
        note(s(R.string.tsoa_audio_detail));
    }

    private void ambience() {
        intChoice(s(R.string.tsoa_rain_intensity), "rain_intensity", new String[]{s(R.string.tsoa_soft), s(R.string.tsoa_normal), s(R.string.tsoa_strong)},
                new int[]{1, 2, 3}, options.getRainIntensity());
        slider(s(R.string.tsoa_background_brightness), "background_brightness", 35, 100,
                Math.round(options.getBackgroundBrightness() * 100), true, "%");
        toggle(s(R.string.tsoa_reduced_motion), s(R.string.tsoa_reduced_motion_detail), "reduced_motion", options.isReducedMotion());
        toggle(s(R.string.tsoa_ambience_sound), s(R.string.tsoa_ambience_sound_detail), "ambience_enabled", options.isAmbienceEnabled());
        slider(s(R.string.tsoa_ambience_volume), "ambience_volume", 0, 100, options.getAmbienceVolume(), false, "%");
        note(s(R.string.tsoa_rain_detail));
        note(s(R.string.tsoa_ambience_audio_detail));
    }

    private void comfort() {
        toggle(s(R.string.tsoa_keep_screen_on), s(R.string.tsoa_keep_screen_on_detail), "keep_screen_on", options.isKeepScreenOn());
        toggle(s(R.string.tsoa_haptics), s(R.string.tsoa_haptics_detail), "haptics", options.isHaptics());
        toggle(s(R.string.tsoa_auto_launch), s(R.string.tsoa_auto_launch_detail), "auto_launch", options.isAutoLaunch());
        note(s(R.string.tsoa_auto_launch_cancel_note));
    }

    private void files() {
        toggle(s(R.string.tsoa_pda), s(R.string.tsoa_pda_detail), "game_pda", options.isPdaEnabled());
        if (!options.isOriginalMode()) {
            action(s(R.string.tsoa_pda_browse), null, actions::openPda, true);
            action(s(R.string.tsoa_chronos), s(R.string.tsoa_chronos_detail), actions::openChronos, true);
        }
        action(s(R.string.tsoa_export_saves), s(R.string.tsoa_export_saves_detail), actions::exportSaves, true);
        action(s(R.string.tsoa_import_saves), s(R.string.tsoa_import_saves_detail), actions::importSaves, true);
        action(s(R.string.tsoa_verify_files), s(R.string.tsoa_verify_files_detail), actions::verifyFiles, true);
        action(s(R.string.tsoa_export_diagnostics), s(R.string.tsoa_export_diagnostics_detail), actions::exportDiagnostics, true);
        action(s(R.string.tsoa_import_game_folder), s(R.string.tsoa_import_game_folder_detail), actions::importGame, true);
        note(s(R.string.tsoa_saves_note));
    }

    private void choice(String label, String key, String[] labels, String[] values, String selected) {
        int index = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(selected)) index = i;
        spinner(label, labels, index, position -> {
            preferences.edit().putString(key, values[position]).apply();
            changed();
        });
    }

    private void intChoice(String label, String key, String[] labels, int[] values, int selected) {
        int index = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == selected) index = i;
        spinner(label, labels, index, position -> {
            preferences.edit().putInt(key, values[position]).apply();
            changed();
        });
    }

    private interface Selection { void select(int position); }

    private void spinner(String label, String[] labels, int selected, Selection selection) {
        sectionLabel(label);
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View reusable, ViewGroup parent) {
                TextView view = (TextView)super.getView(position, reusable, parent);
                view.setTextColor(TEXT);
                view.setTextSize(15);
                view.setPadding(dp(8), 0, dp(8), 0);
                return view;
            }
            @Override public View getDropDownView(int position, View reusable, ViewGroup parent) {
                TextView view = (TextView)super.getDropDownView(position, reusable, parent);
                view.setTextColor(TEXT);
                view.setTextSize(15);
                view.setBackgroundColor(Color.rgb(19, 35, 40));
                view.setPadding(dp(12), dp(12), dp(12), dp(12));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected, false);
        spinner.setContentDescription(label);
        content.addView(spinner, new LinearLayout.LayoutParams(-1, dp(48)));
        // Android dispatches an initial onItemSelected even for a programmatic selection.
        // Preserve existing preferences until the user actually chooses another item.
        final int[] previous = {selected};
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != previous[0]) {
                    previous[0] = position;
                    selection.select(position);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void toggle(String label, String detail, String key, boolean enabled) {
        Switch control = new Switch(activity);
        control.setText(label);
        control.setTextSize(15);
        control.setTextColor(TEXT);
        control.setPadding(0, dp(8), 0, dp(8));
        control.setMinHeight(dp(48));
        control.setChecked(enabled);
        control.setOnCheckedChangeListener((view, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            changed();
        });
        content.addView(control, new LinearLayout.LayoutParams(-1, -2));
        if (!detail.isEmpty()) note(detail);
    }

    private void slider(String label, String key, int min, int max, int value, boolean fraction, String suffix) {
        TextView caption = sectionLabel(label + " · " + value + suffix);
        SeekBar seek = new SeekBar(activity);
        seek.setMax(max - min);
        seek.setProgress(value - min);
        seek.setContentDescription(label);
        content.addView(seek, new LinearLayout.LayoutParams(-1, dp(48)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int current = min + progress;
                caption.setText(label + " · " + current + suffix);
                if (fraction) preferences.edit().putFloat(key, current / 100.0f).apply();
                else preferences.edit().putInt(key, current).apply();
                changed();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
    }

    private void action(String label, String detail, Runnable callback, boolean dismiss) {
        Button button = button(label);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setOnClickListener(view -> {
            if (dismiss) dialog.dismiss();
            callback.run();
        });
        content.addView(button, new LinearLayout.LayoutParams(-1, dp(48)));
        if (detail != null && !detail.isEmpty()) note(detail);
    }

    private TextView sectionLabel(String value) {
        TextView label = text(value, 14, TEXT);
        label.setPadding(0, dp(14), 0, dp(4));
        content.addView(label, new LinearLayout.LayoutParams(-1, -2));
        return label;
    }

    private void note(String value) {
        TextView note = text(value, 12, MUTED);
        note.setLineSpacing(dp(2), 1.0f);
        note.setPadding(0, 0, 0, dp(12));
        content.addView(note, new LinearLayout.LayoutParams(-1, -2));
    }

    private Button button(String label) {
        Button button = new Button(activity);
        button.setText(label);
        button.setTextColor(ACCENT);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackgroundResource(android.R.color.transparent);
        button.setMinHeight(dp(48));
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private String s(int resource, Object... arguments) {
        return TsoaMenuLanguage.get(activity, resource, arguments);
    }

    private void changed() {
        if (onChanged != null) onChanged.run();
    }

    private int dp(int value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }
}
