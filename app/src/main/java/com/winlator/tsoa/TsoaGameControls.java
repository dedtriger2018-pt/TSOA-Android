package com.winlator.tsoa;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/** Dedicated profiles remain normal Winlator profiles, so its existing editor can edit every key. */
public final class TsoaGameControls {
    private static final String MINIMAL_NAME = "TSOA • ESC";
    private static final String MINIMAL_ID_PREF = "eco_esc_profile_id";

    private TsoaGameControls() {}

    public static void ensureProfiles(Context context) {
        InputControlsManager manager = new InputControlsManager(context);
        manager.getProfiles(); // Installs the upstream RTS profile on a fresh installation.
        ensureMinimalProfile(context, manager);
    }

    private static ControlsProfile ensureMinimalProfile(Context context, InputControlsManager manager) {
        int savedId = TsoaOptions.prefs(context).getInt(MINIMAL_ID_PREF, 0);
        ControlsProfile existing = savedId > 0 ? manager.getProfile(savedId) : null;
        if (existing != null) return existing;
        for (ControlsProfile profile : manager.getProfiles()) {
            if (MINIMAL_NAME.equals(profile.getName())) {
                TsoaOptions.prefs(context).edit().putInt(MINIMAL_ID_PREF, profile.id).apply();
                return profile;
            }
        }
        ControlsProfile profile = manager.createProfile(MINIMAL_NAME);
        writeMinimalProfile(context, profile.id);
        TsoaOptions.prefs(context).edit().putInt(MINIMAL_ID_PREF, profile.id).apply();
        return manager.getProfile(profile.id);
    }

    private static void writeMinimalProfile(Context context, int id) {
        try {
            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("name", MINIMAL_NAME);
            data.put("cursorSpeed", 1.0);
            JSONObject escape = new JSONObject();
            escape.put("type", "BUTTON");
            escape.put("shape", "ROUND_RECT");
            escape.put("bindings", new JSONArray().put("KEY_ESC").put("NONE").put("NONE").put("NONE"));
            escape.put("scale", 1.5);
            escape.put("x", 0.075);
            escape.put("y", 0.13);
            escape.put("toggleSwitch", false);
            escape.put("text", "ESC");
            escape.put("iconId", 0);
            data.put("elements", new JSONArray().put(escape));
            FileUtils.writeString(ControlsProfile.getProfileFile(context, id), data.toString());
        }
        catch (JSONException e) {
            throw new IllegalStateException("Não foi possível preparar o controle ESC.", e);
        }
    }

    public static ControlsProfile getSelectedProfile(Context context, TsoaOptions options) {
        InputControlsManager manager = new InputControlsManager(context);
        manager.getProfiles();
        ControlsProfile minimal = ensureMinimalProfile(context, manager);
        switch (options.getControlsMode()) {
            case "off": return null;
            case "rts":
                for (ControlsProfile profile : manager.getProfiles()) {
                    if ("RTS".equals(profile.getName())) return profile;
                }
                return minimal;
            case "custom":
                ControlsProfile custom = manager.getProfile(options.getProfileId());
                return custom != null ? custom : minimal;
            default: return minimal;
        }
    }

    public static ControlsProfile getEditableProfile(Context context, TsoaOptions options) {
        if ("custom".equals(options.getControlsMode())) {
            InputControlsManager manager = new InputControlsManager(context);
            manager.getProfiles();
            ControlsProfile minimal = ensureMinimalProfile(context, manager);
            ControlsProfile custom = manager.getProfile(options.getProfileId());
            // Editing a new custom layout must not mutate the minimal ESC preset.
            if (custom == null || custom.id == minimal.id) {
                custom = manager.duplicateProfile(minimal);
                if (custom == null) throw new IllegalStateException("Verifique o espaço livre para criar o perfil.");
                custom.setName("TSOA • Personalizado");
                custom.save();
                TsoaOptions.prefs(context).edit().putInt("controls_profile_id", custom.id).apply();
            }
            return custom;
        }
        ControlsProfile selected = getSelectedProfile(context, options);
        if (selected != null) return selected;
        InputControlsManager manager = new InputControlsManager(context);
        manager.getProfiles();
        return ensureMinimalProfile(context, manager);
    }

    /** Restores just the minimal layout; custom and RTS layouts are kept. */
    public static void resetSelectedLayout(Context context) {
        InputControlsManager manager = new InputControlsManager(context);
        manager.getProfiles();
        ControlsProfile profile = ensureMinimalProfile(context, manager);
        File target = ControlsProfile.getProfileFile(context, profile.id);
        if (target.isFile()) FileUtils.copy(target, new File(context.getFilesDir(), "tsoa_backups/eco-esc-before-reset.icp"));
        writeMinimalProfile(context, profile.id);
        TsoaOptions.prefs(context).edit().putString("controls_mode", "esc")
                .putFloat("controls_opacity", 0.55f).apply();
    }

    public static boolean isMinimalProfile(Context context, ControlsProfile profile) {
        return profile != null && profile.id == TsoaOptions.prefs(context).getInt(MINIMAL_ID_PREF, 0);
    }

    public static void applyWindowOptions(Activity activity, TsoaOptions options) {
        if (options.isKeepScreenOn()) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (options.isFullscreen()) {
            AppUtils.hideSystemUI(activity);
            return;
        }
        View decor = activity.getWindow().getDecorView();
        decor.setOnSystemUiVisibilityChangeListener(null);
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        // The game activity's theme sets this flag before the preference is applied.
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.getWindow().setDecorFitsSystemWindows(true);
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        }
    }
}
