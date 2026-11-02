package com.winlator;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.ImageView;
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
    private static final String PREF_SCREEN_SIZE = "screen_size";
    private boolean dedicatedLauncher = false;
    private boolean rootFSReady = false;
    private boolean importInProgress = false;
    private boolean gameLaunchActive = false;
    private TextView launcherStatus;
    private TextView launcherDetail;
    private ProgressBar launcherProgress;
    private Button launcherAction;
    private Button launcherImportAgain;
    private Spinner launcherResolution;
    private String[] launcherResolutionValues;
    private final java.util.concurrent.ExecutorService importExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);

        dedicatedLauncher = true;
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
        buildDedicatedLauncherUI();
        setLauncherStatus("Preparando o ambiente de compatibilidade...", "A instalação inicial do runtime pode levar alguns minutos.", true);
        RootFSInstaller.installIfNeeded(this, (success) -> {
            rootFSReady = success;
            if (!success) {
                setLauncherStatus("Não foi possível preparar o runtime.", "Verifique o espaço livre e tente novamente.", false);
                launcherAction.setText("TENTAR NOVAMENTE");
                launcherAction.setOnClickListener((view) -> {
                    setLauncherStatus("Preparando o ambiente...", "Extraindo os componentes do Winlator.", true);
                    RootFSInstaller.installIfNeeded(this, (retrySuccess) -> {
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
        int bg = Color.rgb(18, 15, 18);
        int accent = Color.rgb(190, 62, 55);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(28), dp(28), dp(24));
        root.setBackgroundColor(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(112), dp(112));
        iconParams.bottomMargin = dp(18);
        root.addView(icon, iconParams);

        TextView title = new TextView(this);
        title.setText("TSOA_FE");
        title.setTextColor(Color.WHITE);
        title.setTextSize(25);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("MOBILE LAUNCHER");
        subtitle.setTextColor(Color.rgb(184, 169, 165));
        subtitle.setTextSize(11);
        subtitle.setLetterSpacing(0.18f);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(5);
        subtitleParams.bottomMargin = dp(18);
        root.addView(subtitle, subtitleParams);

        String[] allScreenSizes = getResources().getStringArray(R.array.screen_size_entries);
        String[] resolutionLabels = java.util.Arrays.copyOfRange(allScreenSizes, 1, allScreenSizes.length);
        launcherResolutionValues = new String[resolutionLabels.length];

        for (int i = 0; i < resolutionLabels.length; i++) {
            String label = resolutionLabels[i].trim();
            launcherResolutionValues[i] = i == 0 ? "native" : label.split("\\s+")[0];
        }

        TextView resolutionLabel = new TextView(this);
        resolutionLabel.setText("RESOLUÇÃO DO JOGO");
        resolutionLabel.setTextColor(Color.rgb(210, 190, 185));
        resolutionLabel.setTextSize(12);
        resolutionLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams resolutionLabelParams = new LinearLayout.LayoutParams(-1, -2);
        resolutionLabelParams.bottomMargin = dp(6);
        root.addView(resolutionLabel, resolutionLabelParams);

        launcherResolution = new Spinner(this);
        ArrayAdapter<String> resolutionAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, resolutionLabels);
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        launcherResolution.setAdapter(resolutionAdapter);
        root.addView(launcherResolution, new LinearLayout.LayoutParams(-1, dp(48)));

        String savedResolution = launcherPreferences.getString(PREF_SCREEN_SIZE, "960x544");
        int selectedResolutionIndex = Math.min(5, resolutionLabels.length - 1);
        for (int i = 0; i < launcherResolutionValues.length; i++) {
            if (launcherResolutionValues[i].equals(savedResolution)) {
                selectedResolutionIndex = i;
                break;
            }
        }
        launcherResolution.setSelection(selectedResolutionIndex, false);
        launcherResolution.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                launcherPreferences.edit()
                    .putString(PREF_SCREEN_SIZE, launcherResolutionValues[position]).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        launcherStatus = new TextView(this);
        launcherStatus.setTextColor(Color.WHITE);
        launcherStatus.setTextSize(16);
        launcherStatus.setGravity(Gravity.CENTER);
        root.addView(launcherStatus, new LinearLayout.LayoutParams(-1, -2));

        launcherDetail = new TextView(this);
        launcherDetail.setTextColor(Color.rgb(174, 165, 165));
        launcherDetail.setTextSize(12);
        launcherDetail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(10);
        root.addView(launcherDetail, detailParams);

        launcherProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        launcherProgress.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(4));
        progressParams.topMargin = dp(24);
        progressParams.bottomMargin = dp(24);
        root.addView(launcherProgress, progressParams);

        launcherAction = new Button(this);
        launcherAction.setAllCaps(false);
        launcherAction.setText("PREPARANDO...");
        launcherAction.setTextColor(Color.WHITE);
        launcherAction.setTextSize(15);
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setColor(accent);
        buttonBackground.setCornerRadius(dp(12));
        launcherAction.setBackground(buttonBackground);
        root.addView(launcherAction, new LinearLayout.LayoutParams(-1, dp(54)));

        launcherImportAgain = new Button(this);
        launcherImportAgain.setAllCaps(false);
        launcherImportAgain.setText("IMPORTAR OUTRA PASTA");
        launcherImportAgain.setTextColor(Color.rgb(210, 190, 185));
        launcherImportAgain.setTextSize(12);
        launcherImportAgain.setVisibility(View.GONE);
        launcherImportAgain.setOnClickListener((view) -> openGameFolderPicker());
        root.addView(launcherImportAgain, new LinearLayout.LayoutParams(-1, dp(48)));

        TextView footer = new TextView(this);
        footer.setText("ADRENO  •  DIRECT3D 11  •  DXVK");
        footer.setTextColor(Color.rgb(105, 94, 94));
        footer.setTextSize(10);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, -2);
        footerParams.topMargin = dp(24);
        root.addView(footer, footerParams);

        setContentView(root);
    }

    private String getLauncherScreenSize() {
        if (launcherResolution != null && launcherResolutionValues != null) {
            int position = launcherResolution.getSelectedItemPosition();
            if (position >= 0 && position < launcherResolutionValues.length) {
                String value = launcherResolutionValues[position];
                launcherPreferences.edit().putString(PREF_SCREEN_SIZE, value).apply();
                return value;
            }
        }
        return launcherPreferences.getString(PREF_SCREEN_SIZE, "960x544");
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setLauncherStatus(String status, String detail, boolean busy) {
        if (launcherStatus == null) return;
        launcherStatus.setText(status);
        launcherDetail.setText(detail);
        launcherProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        launcherAction.setEnabled(!busy);
        if (busy) launcherAction.setText("AGUARDE...");
    }

    private void onLauncherRuntimeReady() {
        File gameDir = GameImportManager.getGameDirectory(this);
boolean gameReady = GameImportManager.isValidGameDirectory(gameDir);
        setLauncherStatus(gameReady ? "TSOA ESTÁ PRONTO" : "SELECIONE A PASTA DO JOGO",
            gameReady ? "O ambiente está configurado para inicialização direta." : "Escolha a pasta game que contém TSOA.exe e TSOA_Data.", false);
        launcherAction.setText(gameReady ? "JOGAR" : "IMPORTAR JOGO");
        launcherAction.setOnClickListener((view) -> {
            if (GameImportManager.isValidGameDirectory(GameImportManager.getGameDirectory(this))) ensureDedicatedContainerAndLaunch();
            else openGameFolderPicker();
        });
        launcherImportAgain.setVisibility(gameReady ? View.VISIBLE : View.GONE);
    }

    private void openGameFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_GAME_FOLDER);
    }

    private void importGameFolder(Uri treeUri) {
        importInProgress = true;
        launcherImportAgain.setVisibility(View.GONE);
        launcherAction.setEnabled(false);
        launcherAction.setText("IMPORTANDO...");
        launcherProgress.setVisibility(View.VISIBLE);
        launcherProgress.setIndeterminate(true);
        setLauncherStatus("IMPORTANDO A INSTALAÇÃO", "Copiando todos os arquivos sem alterar a estrutura original.", true);

        importExecutor.execute(() -> {
            try {
                GameImportManager.importTree(this, treeUri, (currentFile, copied, total) -> runOnUiThread(() -> {
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
                    onLauncherRuntimeReady();
                });
            }
            catch (Exception e) {
                runOnUiThread(() -> {
                    importInProgress = false;
                    launcherProgress.setVisibility(View.GONE);
                    setLauncherStatus("FALHA NA IMPORTAÇÃO", e.getMessage() != null ? e.getMessage() : "Não foi possível importar a pasta.", false);
                    launcherAction.setText("TENTAR NOVAMENTE");
                    launcherAction.setOnClickListener((view) -> openGameFolderPicker());
                });
            }
        });
    }

    private void ensureDedicatedContainerAndLaunch() {
        if (!rootFSReady || importInProgress) return;
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
                if (createdContainer == null) {
                    setLauncherStatus("NÃO FOI POSSÍVEL CRIAR O CONTAINER", "Confira o runtime e o espaço livre.", false);
                    launcherAction.setText("TENTAR NOVAMENTE");
                    launcherAction.setOnClickListener((view) -> ensureDedicatedContainerAndLaunch());
                    return;
                }
                configureDedicatedContainer(createdContainer, gameDir);
                createdContainer.saveData();
                launcherPreferences.edit().putInt(PREF_CONTAINER_ID, createdContainer.id).apply();
                manager.activateContainer(createdContainer);
                launchDedicatedGame(createdContainer);
            });
        }
        catch (JSONException e) {
            setLauncherStatus("ERRO DE CONFIGURAÇÃO", e.getMessage(), false);
            launcherAction.setText("TENTAR NOVAMENTE");
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
        File executable = new File(GameImportManager.getGameDirectory(this), "TSOA.exe");
        if (!executable.isFile()) {
            setLauncherStatus("EXECUTÁVEL NÃO ENCONTRADO", "A instalação importada não contém TSOA.exe.", false);
            launcherAction.setText("IMPORTAR NOVAMENTE");
            launcherAction.setOnClickListener((view) -> openGameFolderPicker());
            return;
        }

        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("exec_path", executable.getAbsolutePath());
        intent.putExtra("tsoa_fe_mode", true);
        gameLaunchActive = true;
        launcherImportAgain.setVisibility(View.GONE);
        setLauncherStatus("INICIANDO TSOA", "Carregando o runtime Windows e a renderização Vulkan.", true);
        try {
            startActivity(intent);
        }
        catch (Exception e) {
            gameLaunchActive = false;
            setLauncherStatus("FALHA AO INICIAR O JOGO", e.getMessage(), false);
            launcherAction.setText("TENTAR NOVAMENTE");
            launcherAction.setOnClickListener((view) -> ensureDedicatedContainerAndLaunch());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dedicatedLauncher && gameLaunchActive) {
            gameLaunchActive = false;
            onLauncherRuntimeReady();
        }
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