package com.winlator;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.Gravity;
import android.view.View;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.contentdialog.AboutDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.Callback;
import com.winlator.core.LocaleHelper;
import com.winlator.core.GameImportManager;
import com.winlator.box64.Box64Preset;
import com.winlator.container.AudioDrivers;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;
import com.winlator.XServerDisplayActivity;
import com.winlator.core.PreloaderDialog;
import com.winlator.xenvironment.RootFSInstaller;
import com.winlator.tsoa.EcoBackdropView;
import com.winlator.tsoa.TsoaOptions;
import com.winlator.tsoa.TsoaAmbience;
import com.winlator.tsoa.TsoaOptionsDialog;
import com.winlator.tsoa.TsoaMaintenance;
import com.winlator.tsoa.TsoaGameControls;
import com.winlator.inputcontrols.ControlsProfile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    public static final boolean DEBUG_MODE = false; // FIXME change to false
    public static final @IntRange(from = 1, to = 19) byte CONTAINER_PATTERN_COMPRESSION_LEVEL = 9;
    public static final byte PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 1;
    public static final byte OPEN_FILE_REQUEST_CODE = 2;
    public static final byte EDIT_INPUT_CONTROLS_REQUEST_CODE = 3;
    public static final byte OPEN_DIRECTORY_REQUEST_CODE = 4;
    private DrawerLayout drawerLayout;
    public final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
    private boolean editInputControls = false;
    private int selectedProfileId;
    private Callback<Uri> openFileCallback;
    private SharedPreferences preferences;
    private Fragment currentFragment;

    // Dedicated TSOA_FE launcher state. The upstream Winlator screens remain in the source
    // tree for compatibility, but the app entry point uses this minimal launcher flow.
    private static final int REQUEST_GAME_FOLDER = 51;
    private static final String LAUNCHER_PREFS = "naninovel_launcher";
    private static final String PREF_CONTAINER_ID = "container_id";
    private boolean dedicatedLauncher = false;
    private boolean rootFSReady = false;
    private boolean importInProgress = false;
    private boolean gameLaunchActive = false;
    private TextView launcherStatus;
    private TextView launcherDetail;
    private ProgressBar launcherProgress;
    private Button launcherAction;
    private final java.util.concurrent.ExecutorService importExecutor = Executors.newSingleThreadExecutor();
    private FrameLayout launcherRoot;
    private LinearLayout launcherHeader;
    private LinearLayout launcherMenu;
    private EcoBackdropView ecoBackdrop;
    private TsoaMaintenance maintenance;
    private TsoaAmbience ambience;
    private int launcherInsetLeft;
    private int launcherInsetTop;
    private int launcherInsetRight;
    private int launcherInsetBottom;
    private boolean launcherBusy;
    private boolean autoLaunchConsumed;
    private boolean autoLaunchScheduled;
    private boolean launcherVisible;
    private final Runnable autoLaunch = () -> {
        autoLaunchScheduled = false;
        if (!isFinishing() && rootFSReady && !importInProgress && !gameLaunchActive) {
            ensureDedicatedContainerAndLaunch();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);

        dedicatedLauncher = !getIntent().getBooleanExtra("edit_input_controls", false);
        if (dedicatedLauncher) {
            setupDedicatedLauncher();
            return;
        }

        setContentView(R.layout.main_activity);

        drawerLayout = findViewById(R.id.DrawerLayout);
        NavigationView navigationView = findViewById(R.id.NavigationView);
        navigationView.setNavigationItemSelectedListener(this);

        setSupportActionBar(findViewById(R.id.Toolbar));
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        Intent intent = getIntent();
        editInputControls = intent.getBooleanExtra("edit_input_controls", false);
        if (editInputControls) {
            selectedProfileId = intent.getIntExtra("selected_profile_id", 0);
            actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_back);
            onNavigationItemSelected(navigationView.getMenu().findItem(R.id.menu_item_input_controls));
            navigationView.setCheckedItem(R.id.menu_item_input_controls);
        }
        else {
            boolean showShortcutsFirst = preferences.getBoolean("show_shortcuts_first", false);
            int selectedMenuItemId = intent.getIntExtra("selected_menu_item_id", 0);
            int menuItemId = selectedMenuItemId > 0 ? selectedMenuItemId : (showShortcutsFirst ? R.id.menu_item_shortcuts : R.id.menu_item_containers);

            actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_menu);
            onNavigationItemSelected(navigationView.getMenu().findItem(menuItemId));
            navigationView.setCheckedItem(menuItemId);
            if (!requestAppPermissions()) RootFSInstaller.installIfNeeded(this);

            int containerId = intent.getIntExtra("container_id", 0);
            String startPath = intent.getStringExtra("start_path");
            if (containerId > 0 && startPath != null) {
                showFragment(new ContainerFileManagerFragment(containerId, startPath));
            }
        }
    }

    private void setupDedicatedLauncher() {
        launcherPreferences = getSharedPreferences(LAUNCHER_PREFS, MODE_PRIVATE);
        maintenance = new TsoaMaintenance(this);
        buildDedicatedLauncherUI();
        setLauncherStatus("Preparando o ambiente de compatibilidade...", "A instalação inicial do runtime pode levar alguns minutos.", true);
        RootFSInstaller.installIfNeeded(this, (success) -> {
            if (isFinishing() || isDestroyed()) return;
            rootFSReady = success;
            if (!success) {
                setLauncherStatus("Não foi possível preparar o runtime.", "Verifique o espaço livre e tente novamente.", false);
                setLauncherActionText("TENTAR NOVAMENTE");
                launcherAction.setOnClickListener((view) -> {
                    setLauncherStatus("Preparando o ambiente...", "Extraindo os componentes do Winlator.", true);
                    RootFSInstaller.installIfNeeded(this, (retrySuccess) -> {
                        if (isFinishing() || isDestroyed()) return;
                        rootFSReady = retrySuccess;
                        if (retrySuccess) onLauncherRuntimeReady();
                        else setLauncherStatus("Falha na preparação.", "Confira o armazenamento disponível e tente novamente.", false);
                    });
                });
                return;
            }
            onLauncherRuntimeReady();
        });
    }

    private android.content.SharedPreferences launcherPreferences;

    private void buildDedicatedLauncherUI() {
        launcherRoot = new FrameLayout(this);
        launcherRoot.setBackgroundColor(Color.rgb(9, 18, 22));
        ecoBackdrop = new EcoBackdropView(this);
        ambience = new TsoaAmbience(this);
        launcherRoot.addView(ecoBackdrop, new FrameLayout.LayoutParams(-1, -1));
        launcherRoot.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) cancelAutoLaunch();
            return false;
        });

        launcherHeader = new LinearLayout(this);
        launcherHeader.setOrientation(LinearLayout.VERTICAL);
        launcherHeader.setGravity(Gravity.CENTER_HORIZONTAL);
        launcherHeader.setPadding(0, dp(2), 0, dp(2));
        TextView title = new TextView(this);
        title.setText("TSOA_FE");
        title.setTextColor(Color.rgb(213, 225, 224));
        title.setTextSize(16);
        title.setLetterSpacing(0.35f);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        launcherHeader.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = new TextView(this);
        subtitle.setText("The Street of Adrift");
        subtitle.setTextColor(Color.rgb(165, 182, 184));
        subtitle.setTextSize(10);
        subtitle.setLetterSpacing(0.15f);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(8);
        launcherHeader.addView(subtitle, subtitleParams);
        launcherRoot.addView(launcherHeader, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        launcherMenu = new LinearLayout(this);
        launcherMenu.setOrientation(LinearLayout.VERTICAL);
        launcherMenu.setGravity(Gravity.CENTER_HORIZONTAL);
        launcherAction = ecoButton("JOGAR", 14, Color.rgb(220, 233, 230));
        launcherAction.setLetterSpacing(0.3f);
        launcherMenu.addView(launcherAction, new LinearLayout.LayoutParams(-1, dp(48)));

        Button options = ecoButton("Configurações", 12, Color.rgb(174, 194, 194));
        options.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams optionsParams = new LinearLayout.LayoutParams(-1, dp(48));
        optionsParams.topMargin = dp(8);
        launcherMenu.addView(options, optionsParams);
        options.setOnClickListener(view -> {
            feedback(view);
            cancelAutoLaunch();
            showLauncherOptions();
        });
        Button about = ecoButton("Sobre", 12, Color.rgb(174, 194, 194));
        about.setLetterSpacing(0.12f);
        launcherMenu.addView(about, new LinearLayout.LayoutParams(-1, dp(48)));
        about.setOnClickListener(view -> {
            feedback(view);
            cancelAutoLaunch();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://store.steampowered.com/app/1672670/")));
            }
            catch (android.content.ActivityNotFoundException error) {
                AppUtils.showToast(this, "Instale um navegador para abrir a página da Steam.");
            }
        });

        launcherStatus = new TextView(this);
        launcherStatus.setTextColor(Color.rgb(192, 207, 205));
        launcherStatus.setTextSize(11);
        launcherStatus.setGravity(Gravity.CENTER);
        launcherStatus.setMaxLines(1);
        launcherStatus.setEllipsize(TextUtils.TruncateAt.END);
        launcherStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        launcherStatus.setPadding(dp(8), dp(8), dp(8), 0);
        launcherMenu.addView(launcherStatus, new LinearLayout.LayoutParams(-1, -2));
        launcherDetail = new TextView(this);
        launcherDetail.setTextColor(Color.rgb(148, 171, 173));
        launcherDetail.setTextSize(10);
        launcherDetail.setGravity(Gravity.CENTER);
        launcherDetail.setMaxLines(2);
        launcherDetail.setEllipsize(TextUtils.TruncateAt.END);
        launcherDetail.setPadding(dp(8), dp(4), dp(8), 0);
        launcherMenu.addView(launcherDetail, new LinearLayout.LayoutParams(-1, -2));
        launcherProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        launcherProgress.setProgressTintList(ColorStateList.valueOf(Color.rgb(172, 157, 124)));
        launcherProgress.setIndeterminateTintList(ColorStateList.valueOf(Color.rgb(172, 157, 124)));
        launcherProgress.setIndeterminate(true);
        launcherProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(190), dp(2));
        progressParams.topMargin = dp(10);
        launcherMenu.addView(launcherProgress, progressParams);
        launcherRoot.addView(launcherMenu, new FrameLayout.LayoutParams(dp(276), -2,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        launcherMenu.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) {
                layoutEcoContent(launcherInsetLeft, launcherInsetTop, launcherInsetRight, launcherInsetBottom);
            }
        });
        launcherRoot.setOnApplyWindowInsetsListener((view, insets) -> {
            layoutEcoContent(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        launcherRoot.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                layoutEcoContent(launcherInsetLeft, launcherInsetTop, launcherInsetRight, launcherInsetBottom);
            }
        });
        setContentView(launcherRoot);
        applyLauncherOptions();
        layoutEcoContent(0, 0, 0, 0);
    }

    private Button ecoButton(String text, float size, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(size);
        button.setTextColor(color);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(28, 183, 211, 207)),
            new ColorDrawable(Color.TRANSPARENT), null));
        return button;
    }

    private void layoutEcoContent(int left, int top, int right, int bottom) {
        if (launcherRoot == null) return;
        launcherInsetLeft = left;
        launcherInsetTop = top;
        launcherInsetRight = right;
        launcherInsetBottom = bottom;
        int width = launcherRoot.getWidth() > 0 ? launcherRoot.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int height = launcherRoot.getHeight() > 0 ? launcherRoot.getHeight() : getResources().getDisplayMetrics().heightPixels;
        int usableWidth = Math.max(dp(160), width - left - right);
        int usableHeight = Math.max(dp(200), height - top - bottom);
        boolean landscape = usableWidth > usableHeight;
        FrameLayout.LayoutParams headerParams = (FrameLayout.LayoutParams)launcherHeader.getLayoutParams();
        headerParams.width = usableWidth;
        headerParams.leftMargin = left;
        headerParams.rightMargin = right;
        headerParams.topMargin = top + Math.round(usableHeight * (landscape ? 0.16f : 0.34f));
        launcherHeader.setLayoutParams(headerParams);

        FrameLayout.LayoutParams menuParams = (FrameLayout.LayoutParams)launcherMenu.getLayoutParams();
        menuParams.width = Math.min(dp(320), usableWidth - dp(32));
        menuParams.leftMargin = left;
        menuParams.rightMargin = right;
        int preferredMenuTop = top + Math.round(usableHeight * (landscape ? 0.43f : 0.49f)) - dp(24);
        int minimumMenuTop = headerParams.topMargin + Math.max(dp(40), launcherHeader.getMeasuredHeight()) - dp(6);
        int maximumMenuTop = top + usableHeight - dp(8) - launcherMenu.getMeasuredHeight();
        menuParams.topMargin = Math.max(minimumMenuTop, Math.min(preferredMenuTop, maximumMenuTop));
        menuParams.bottomMargin = bottom + dp(8);
        launcherMenu.setLayoutParams(menuParams);
    }

    public boolean isDedicatedLauncherMode() { return dedicatedLauncher; }

    private String getLauncherScreenSize() { return new TsoaOptions(this).getScreenSize(); }

    private void feedback(View view) {
        if (new TsoaOptions(this).isHaptics()) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void setLobbyActive(boolean active) {
        if (ecoBackdrop != null) ecoBackdrop.setActive(active);
        if (ambience != null) ambience.setActive(active);
    }

    private void setLauncherActionText(CharSequence text) {
        launcherAction.setText(text);
        if (ecoBackdrop != null) ecoBackdrop.setReflectionAction(text);
    }

    private void applyLauncherOptions() {
        if (!dedicatedLauncher || ecoBackdrop == null) return;
        TsoaOptions options = new TsoaOptions(this);
        ecoBackdrop.applyOptions(options);
        if (ambience != null) ambience.applyOptions(options);
        if (options.isKeepScreenOn()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (options.isFullscreen()) AppUtils.hideSystemUI(this);
        else {
            getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(null);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(true);
                android.view.WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
                if (controller != null) controller.show(android.view.WindowInsets.Type.systemBars());
            }
        }
        launcherRoot.requestApplyInsets();
    }

    private void showLauncherOptions() {
        TsoaOptionsDialog.show(this, this::applyLauncherOptions, new TsoaOptionsDialog.Actions() {
            @Override public void editControls() {
                try {
                    ControlsProfile profile = TsoaGameControls.getEditableProfile(MainActivity.this, new TsoaOptions(MainActivity.this));
                    if (profile == null) {
                        AppUtils.showToast(MainActivity.this, "Selecione ESC, RTS ou um perfil antes de editar.");
                        return;
                    }
                    Intent editor = new Intent(MainActivity.this, ControlsEditorActivity.class);
                    editor.putExtra("profile_id", profile.id);
                    startActivityForResult(editor, EDIT_INPUT_CONTROLS_REQUEST_CODE);
                }
                catch (Exception error) {
                    AppUtils.showToast(MainActivity.this, "Não foi possível abrir os controles: " + error.getMessage());
                }
            }
            @Override public void resetControls() { TsoaGameControls.resetSelectedLayout(MainActivity.this); }
            @Override public void importGame() { if (canMaintainGame()) openGameFolderPicker(); }
            @Override public void exportSaves() { if (canMaintainGame()) maintenance.exportSaves(); }
            @Override public void importSaves() { if (canMaintainGame()) maintenance.importSaves(); }
            @Override public void verifyFiles() { if (canMaintainGame()) maintenance.verifyFiles(); }
            @Override public void exportDiagnostics() { maintenance.exportDiagnostics(); }
        });
    }

    private boolean canMaintainGame() {
        if (rootFSReady && !launcherBusy && !gameLaunchActive && !importInProgress && !maintenance.isBusy()) return true;
        AppUtils.showToast(this, "Aguarde a preparação terminar antes de alterar os arquivos do jogo.");
        return false;
    }

    private void cancelAutoLaunch() {
        if (launcherRoot != null) launcherRoot.removeCallbacks(autoLaunch);
        autoLaunchScheduled = false;
        autoLaunchConsumed = true;
    }

    private void maybeAutoLaunch() {
        if (launcherVisible && rootFSReady && !launcherBusy && !autoLaunchConsumed &&
            !maintenance.isBusy() && new TsoaOptions(this).isAutoLaunch() &&
            GameImportManager.isValidGameDirectory(GameImportManager.getGameDirectory(this))) {
            autoLaunchConsumed = true;
            autoLaunchScheduled = true;
            AppUtils.showToast(this, "Abertura automática em 2 segundos. Toque no fundo ou use Voltar para cancelar.");
            launcherRoot.postDelayed(autoLaunch, 2000L);
        }
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setLauncherStatus(String status, String detail, boolean busy) {
        if (launcherStatus == null) return;
        launcherStatus.setText(status);
        launcherDetail.setText(detail);
        launcherBusy = busy;
        launcherProgress.setVisibility(busy && importInProgress ? View.VISIBLE : View.GONE);
        launcherAction.setEnabled(!busy);
        launcherAction.setAlpha(busy ? 0.4f : 1.0f);
        // Initialization is intentionally silent; retain progress only for a user-requested import.
        launcherStatus.setVisibility(!busy || importInProgress ? View.VISIBLE : View.GONE);
        launcherDetail.setVisibility(!busy || importInProgress ? View.VISIBLE : View.GONE);
    }

    private void onLauncherRuntimeReady() {
        if (isFinishing() || isDestroyed()) return;
        applyLauncherOptions();
        File gameDir = GameImportManager.getGameDirectory(this);
        boolean gameReady = GameImportManager.isValidGameDirectory(gameDir);
        setLauncherStatus(gameReady ? "" : "SELECIONE A PASTA DO JOGO",
            gameReady ? "" : "Escolha a pasta que contém TSOA.exe e TSOA_Data.", false);
        launcherStatus.setVisibility(gameReady ? View.GONE : View.VISIBLE);
        launcherDetail.setVisibility(gameReady ? View.GONE : View.VISIBLE);
        setLauncherActionText(gameReady ? "JOGAR" : "IMPORTAR JOGO");
        launcherAction.setOnClickListener((view) -> {
            feedback(view);
            cancelAutoLaunch();
            if (GameImportManager.isValidGameDirectory(GameImportManager.getGameDirectory(this))) ensureDedicatedContainerAndLaunch();
            else openGameFolderPicker();
        });
        maybeAutoLaunch();
    }

    private void openGameFolderPicker() {
        cancelAutoLaunch();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_GAME_FOLDER);
    }

    private void importGameFolder(Uri treeUri) {
        importInProgress = true;
        launcherAction.setEnabled(false);
        setLauncherActionText("IMPORTANDO...");
        launcherProgress.setVisibility(View.VISIBLE);
        launcherProgress.setIndeterminate(true);
        setLauncherStatus("IMPORTANDO A INSTALAÇÃO", "Copiando todos os arquivos sem alterar a estrutura original.", true);

        importExecutor.execute(() -> {
            try {
                GameImportManager.importTree(this, treeUri, (currentFile, copied, total) -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    launcherProgress.setIndeterminate(total <= 0);
                    if (total > 0) {
                        launcherProgress.setMax(1000);
                        launcherProgress.setProgress((int)Math.min(1000, (copied * 1000L) / total));
                        launcherDetail.setText(String.format(java.util.Locale.ROOT, "%s · %.2f / %.2f GB",
                            currentFile, copied / 1073741824.0, total / 1073741824.0));
                    }
                    else launcherDetail.setText(String.format(java.util.Locale.ROOT, "%s · %.2f GB copiados",
                        currentFile, copied / 1073741824.0));
                }));
                runOnUiThread(() -> {
                    importInProgress = false;
                    if (isFinishing() || isDestroyed()) return;
                    onLauncherRuntimeReady();
                });
            }
            catch (Exception e) {
                runOnUiThread(() -> {
                    importInProgress = false;
                    if (isFinishing() || isDestroyed()) return;
                    launcherProgress.setVisibility(View.GONE);
                    setLauncherStatus("FALHA NA IMPORTAÇÃO", e.getMessage() != null ? e.getMessage() : "Não foi possível importar a pasta.", false);
                    setLauncherActionText("TENTAR NOVAMENTE");
                    launcherAction.setOnClickListener((view) -> openGameFolderPicker());
                });
            }
        });
    }

    private void ensureDedicatedContainerAndLaunch() {
        if (isFinishing() || isDestroyed() || !launcherVisible) return;
        if (!rootFSReady || importInProgress || gameLaunchActive || launcherBusy) return;
        if (maintenance != null && maintenance.isBusy()) {
            AppUtils.showToast(this, "Aguarde a operação dos saves terminar antes de jogar.");
            return;
        }
        File gameDir = GameImportManager.getGameDirectory(this);
        if (!GameImportManager.isValidGameDirectory(gameDir)) {
            onLauncherRuntimeReady();
            return;
        }

        setLauncherStatus("PREPARANDO O CONTAINER", "Configurando Turnip, DXVK e Box64 para o TSOA.", true);
        ContainerManager manager = new ContainerManager(this);
        int containerId = launcherPreferences.getInt(PREF_CONTAINER_ID, 0);
        Container container = manager.getContainerById(containerId);
        if (container != null) {
            configureDedicatedContainer(container, gameDir);
            manager.activateContainer(container);
            launchDedicatedGame(container);
            return;
        }

        try {
            JSONObject data = new JSONObject();
            data.put("name", "TSOA_FE");
            data.put("screenSize", getLauncherScreenSize());
            data.put("envVars", Container.DEFAULT_ENV_VARS);
            data.put("graphicsDriver", GraphicsDrivers.TURNIP + "," + GraphicsDrivers.GLADIO);
            data.put("dxwrapper", DXWrappers.DXVK);
            data.put("audioDriver", AudioDrivers.ALSA);
            data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
            data.put("drives", "F:" + gameDir.getAbsolutePath());
            data.put("box64Preset", Box64Preset.DEFAULT);
            data.put("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL);
            manager.createContainerAsync(data, (createdContainer) -> {
                if (isFinishing() || isDestroyed()) return;
                if (createdContainer == null) {
                    setLauncherStatus("NÃO FOI POSSÍVEL CRIAR O CONTAINER", "Confira o runtime e o espaço livre.", false);
                    setLauncherActionText("TENTAR NOVAMENTE");
                    launcherAction.setOnClickListener((view) -> ensureDedicatedContainerAndLaunch());
                    return;
                }
                configureDedicatedContainer(createdContainer, gameDir);
                createdContainer.saveData();
                launcherPreferences.edit().putInt(PREF_CONTAINER_ID, createdContainer.id).apply();
                if (!launcherVisible) {
                    onLauncherRuntimeReady();
                    return;
                }
                manager.activateContainer(createdContainer);
                launchDedicatedGame(createdContainer);
            });
        }
        catch (JSONException e) {
            setLauncherStatus("ERRO DE CONFIGURAÇÃO", e.getMessage(), false);
            setLauncherActionText("TENTAR NOVAMENTE");
            launcherAction.setOnClickListener((view) -> ensureDedicatedContainerAndLaunch());
        }
    }

    private void configureDedicatedContainer(Container container, File gameDir) {
        container.setName("TSOA_FE");
        container.setScreenSize(getLauncherScreenSize());
        container.setGraphicsDriver(GraphicsDrivers.TURNIP + "," + GraphicsDrivers.GLADIO);
        container.setDXWrapper(DXWrappers.DXVK);
        container.setAudioDriver(AudioDrivers.ALSA);
        container.setBox64Preset(Box64Preset.DEFAULT);
        container.setDrives("F:" + gameDir.getAbsolutePath());
        container.saveData();
    }

    private void launchDedicatedGame(Container container) {
        if (isFinishing() || isDestroyed()) return;
        if (!launcherVisible) {
            onLauncherRuntimeReady();
            return;
        }
        File executable = new File(GameImportManager.getGameDirectory(this), "TSOA.exe");
        if (!executable.isFile()) {
            setLauncherStatus("EXECUTÁVEL NÃO ENCONTRADO", "A instalação importada não contém TSOA.exe.", false);
            setLauncherActionText("IMPORTAR NOVAMENTE");
            launcherAction.setOnClickListener((view) -> openGameFolderPicker());
            return;
        }

        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("exec_path", executable.getAbsolutePath());
        intent.putExtra("tsoa_fe_mode", true);
        gameLaunchActive = true;
        setLauncherStatus("INICIANDO TSOA", "Carregando o runtime Windows e a renderização Vulkan.", true);
        try {
            setLobbyActive(false);
            startActivity(intent);
        }
        catch (Exception e) {
            setLobbyActive(true);
            gameLaunchActive = false;
            setLauncherStatus("FALHA AO INICIAR O JOGO", e.getMessage(), false);
            setLauncherActionText("TENTAR NOVAMENTE");
            launcherAction.setOnClickListener((view) -> ensureDedicatedContainerAndLaunch());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dedicatedLauncher) {
            launcherVisible = true;
            applyLauncherOptions();
            setLobbyActive(true);
            if (gameLaunchActive) {
                gameLaunchActive = false;
                onLauncherRuntimeReady();
            }
            else maybeAutoLaunch();
        }
    }

    @Override
    protected void onPause() {
        launcherVisible = false;
        autoLaunchScheduled = false;
        setLobbyActive(false);
        if (launcherRoot != null) launcherRoot.removeCallbacks(autoLaunch);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (launcherRoot != null) launcherRoot.removeCallbacks(autoLaunch);
        setLobbyActive(false);
        if (ambience != null) ambience.close();
        if (maintenance != null) maintenance.close();
        importExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                RootFSInstaller.installIfNeeded(this);
            }
            else finish();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (maintenance != null && maintenance.onActivityResult(requestCode, resultCode, data)) return;
        if (dedicatedLauncher && requestCode == REQUEST_GAME_FOLDER) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri treeUri = data.getData();
                try {
                    int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(treeUri, flags);
                }
                catch (SecurityException ignored) {}
                importGameFolder(treeUri);
            }
            return;
        }
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (openFileCallback != null) {
                openFileCallback.call(data.getData());
                openFileCallback = null;
            }
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (dedicatedLauncher && launcherRoot != null) {
            layoutEcoContent(0, 0, 0, 0);
            launcherRoot.requestApplyInsets();
        }
        if ((newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) && currentFragment instanceof BaseFileManagerFragment) {
            ((BaseFileManagerFragment)currentFragment).onOrientationChanged();
        }
    }

    @Override
    public void onBackPressed() {
        // In the dedicated edition, Back exits the launcher instead of exposing
        // the upstream Winlator containers/desktop navigation.
        if (dedicatedLauncher) {
            if (autoLaunchScheduled) {
                cancelAutoLaunch();
                AppUtils.showToast(this, "Abertura automática cancelada.");
                return;
            }
            finish();
            return;
        }
        if (currentFragment != null && currentFragment.isVisible()) {
            if (currentFragment instanceof BaseFileManagerFragment) {
                BaseFileManagerFragment fileManagerFragment = (BaseFileManagerFragment)currentFragment;
                if (fileManagerFragment.onBackPressed()) return;
            }
            else if (currentFragment instanceof ContainersFragment) {
                finish();
            }
        }

        showFragment(new ContainersFragment());
    }

    public void setOpenFileCallback(Callback<Uri> openFileCallback) {
        this.openFileCallback = openFileCallback;
    }

    private boolean requestAppPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return false;

        String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        if (itemId == R.id.menu_item_add ||
            itemId == R.id.menu_item_home ||
            itemId == R.id.menu_item_view_style ||
            itemId == R.id.menu_item_new_folder) {
            return super.onOptionsItemSelected(menuItem);
        }
        else {
            if (editInputControls) {
                setResult(RESULT_OK);
                finish();
            }
            else {
                if (currentFragment instanceof BaseFileManagerFragment) {
                    BaseFileManagerFragment fileManagerFragment = (BaseFileManagerFragment)currentFragment;
                    if (fileManagerFragment.onOptionsMenuClicked()) return true;
                }
                drawerLayout.openDrawer(GravityCompat.START);
            }
            return true;
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        switch (item.getItemId()) {
            case R.id.menu_item_shortcuts:
                preferences.edit().putBoolean("show_shortcuts_first", true).apply();
                showFragment(new ShortcutsFragment());
                break;
            case R.id.menu_item_containers:
                preferences.edit().putBoolean("show_shortcuts_first", false).apply();
                showFragment(new ContainersFragment());
                break;
            case R.id.menu_item_input_controls:
                showFragment(new InputControlsFragment(selectedProfileId));
                break;
            case R.id.menu_item_settings:
                showFragment(new SettingsFragment());
                break;
            case R.id.menu_item_about:
                (new AboutDialog(this)).show();
                break;
        }
        return true;
    }

    public void showFragment(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.beginTransaction()
            .replace(R.id.FLFragmentContainer, fragment)
            .commit();

        drawerLayout.closeDrawer(GravityCompat.START);
        currentFragment = fragment;
    }
}
