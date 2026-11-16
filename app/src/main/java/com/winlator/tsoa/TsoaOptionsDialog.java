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
        if (!activity.isFinishing()) new TsoaOptionsDialog(activity, onChanged, actions).showPage("Opções", 0);
    }

    private void showPage(String title, int page) {
        if (activity.isFinishing()) return;
        if (dialog != null) dialog.dismiss();
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

        TextView heading = text(title, 20, TEXT);
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

        Button close = button(page == 0 ? "FECHAR" : "VOLTAR");
        close.setOnClickListener(view -> {
            if (page == 0) dialog.dismiss();
            else showPage("Opções", 0);
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
        note("Seus ajustes são salvos automaticamente. As opções do jogo valem na próxima vez que tocar em Jogar.");
        action("Vídeo", "Resolução, proporção e limite de quadros", () -> showPage("Vídeo", 1), false);
        action("Controles na tela", "Botão ESC, RTS ou seu próprio layout", () -> showPage("Controles na tela", 2), false);
        action("Mouse e toque", "Sensibilidade e gestos", () -> showPage("Mouse e toque", 3), false);
        action("Áudio", "Volume e modo silencioso", () -> showPage("Áudio", 4), false);
        action("Ambiente", "Chuva, brilho e movimento do lobby", () -> showPage("Ambiente", 5), false);
        action("Conforto", "Tela ligada, vibração e início automático", () -> showPage("Conforto", 6), false);
        action("Arquivos e diagnóstico", "Saves, arquivos do jogo e registros", () -> showPage("Arquivos e diagnóstico", 7), false);
        String version = "";
        try { version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName; }
        catch (Exception ignored) {}
        note("TSOA_FE · " + version + "\nWinlator · Wine · Box64 · Mesa\nJogo: Caramel-Mocha Studio / LoveStoryProject");
    }

    private void video() {
        String[] all = activity.getResources().getStringArray(R.array.screen_size_entries);
        String[] labels = Arrays.copyOfRange(all, 1, all.length);
        String[] values = new String[labels.length];
        for (int i = 0; i < values.length; i++) values[i] = i == 0 ? "native" : labels[i].trim().split("\\s+")[0];
        choice("Resolução do jogo", "screen_size", labels, values, options.getScreenSize());
        choice("Proporção da imagem", "aspect_mode", new String[]{"Preservar proporção", "Preencher a tela"},
                new String[]{"fit", "stretch"}, options.getAspectMode());
        toggle("Tela cheia", "Oculta as barras do Android durante o jogo.", "fullscreen", options.isFullscreen());
        intChoice("Limite de quadros", "fps_limit", new String[]{"Sem limite", "30 FPS", "60 FPS"},
                new int[]{0, 30, 60}, options.getFpsLimit());
        note("Resoluções maiores exigem mais do aparelho. 960 × 544 é o ponto de partida testado no Honor X8.");
    }

    private void controls() {
        TsoaGameControls.ensureProfiles(activity);
        choice("Controles na tela", "controls_mode", new String[]{"Ocultos", "Somente ESC / Menu", "RTS", "Personalizado"},
                new String[]{"off", "esc", "rts", "custom"}, options.getControlsMode());
        note("Os controles são opcionais. O modo mínimo mostra apenas ESC para abrir o menu do jogo.");
        ArrayList<ControlsProfile> profiles = new InputControlsManager(activity).getProfiles(true);
        if (!profiles.isEmpty()) {
            String[] names = new String[profiles.size() + 1];
            int[] ids = new int[profiles.size() + 1];
            names[0] = "Selecionar um perfil";
            ids[0] = 0;
            for (int i = 0; i < profiles.size(); i++) {
                names[i + 1] = profiles.get(i).getName();
                ids[i + 1] = profiles.get(i).id;
            }
            intChoice("Perfil personalizado", "controls_profile_id", names, ids, options.getProfileId());
        }
        slider("Visibilidade dos botões", "controls_opacity", 20, 100, Math.round(options.getControlsOpacity() * 100), true, "%");
        action("Editar teclas e posições", "Mover, redimensionar e escolher teclas no editor", actions::editControls, true);
        action("Restaurar layout padrão", "Voltar ao botão ESC; seus perfis são preservados", () -> {
            new AlertDialog.Builder(activity)
                    .setTitle("Restaurar o botão ESC?")
                    .setMessage("O layout mínimo será restaurado. Seus perfis personalizados não serão apagados.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Restaurar", (alert, which) -> {
                        preferences.edit().putString("controls_mode", "esc").putFloat("controls_opacity", 0.55f).apply();
                        actions.resetControls();
                        changed();
                        showPage("Controles na tela", 2);
                    }).show();
        }, false);
    }

    private void mouse() {
        slider("Velocidade do cursor", "cursor_speed", 25, 300, Math.round(options.getCursorSpeed() * 100), true, "%");
        toggle("Toque para clicar", "Um toque curto corresponde ao botão esquerdo.", "tap_to_click", options.isTapToClick());
        toggle("Segurar para clique direito", "Mantenha o dedo parado para o botão direito.", "long_press_right_click", options.isLongPressRightClick());
    }

    private void audio() {
        slider("Volume do jogo", "volume", 0, 100, options.getVolume(), false, "%");
        toggle("Silenciar", "Mantém o volume escolhido para quando reativar o áudio.", "mute", options.isMuted());
        note("Aplicado ao áudio do jogo na próxima abertura. A disponibilidade de som também depende da compatibilidade do jogo.");
    }

    private void ambience() {
        intChoice("Intensidade da chuva", "rain_intensity", new String[]{"Suave", "Normal", "Forte"},
                new int[]{1, 2, 3}, options.getRainIntensity());
        slider("Brilho do fundo", "background_brightness", 35, 100,
                Math.round(options.getBackgroundBrightness() * 100), true, "%");
        toggle("Reduzir movimento", "Pausa a animação do ambiente e mantém o fundo estático.", "reduced_motion", options.isReducedMotion());
        toggle("Som do ambiente", "Chuva e vento suaves somente nesta tela inicial.", "ambience_enabled", options.isAmbienceEnabled());
        slider("Volume do ambiente", "ambience_volume", 0, 100, options.getAmbienceVolume(), false, "%");
        note("A chuva faz parte do lobby. A animação é pausada quando o aplicativo fica em segundo plano ou o jogo abre.");
        note("O som do lobby também pausa ao abrir o jogo. Este volume é separado do áudio da partida e respeita o volume de mídia do aparelho.");
    }

    private void comfort() {
        toggle("Manter a tela ligada", "Enquanto o aplicativo estiver aberto.", "keep_screen_on", options.isKeepScreenOn());
        toggle("Resposta tátil", "Vibração breve nos botões do lobby.", "haptics", options.isHaptics());
        toggle("Abrir o jogo automaticamente", "Na próxima entrada no aplicativo, após preparar os arquivos.", "auto_launch", options.isAutoLaunch());
        note("Para interromper o início automático, use Voltar durante a contagem exibida no lobby.");
    }

    private void files() {
        action("Fazer backup dos saves", "Exportar um arquivo ZIP para o local escolhido", actions::exportSaves, true);
        action("Restaurar saves", "Importar um backup de progresso do jogo", actions::importSaves, true);
        action("Verificar arquivos", "Conferir os arquivos essenciais sem reinstalar", actions::verifyFiles, true);
        action("Exportar diagnóstico", "Salvar registros para investigar algum problema", actions::exportDiagnostics, true);
        action("Importar outra pasta do jogo", "Selecionar a pasta que contém TSOA.exe", actions::importGame, true);
        note("Os saves ficam nos dados privados do aplicativo. Faça um backup antes de desinstalar o TSOA_FE.");
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
        note(detail);
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

    private void changed() {
        if (onChanged != null) onChanged.run();
    }

    private int dp(int value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }
}
