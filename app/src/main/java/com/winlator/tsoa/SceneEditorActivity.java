package com.winlator.tsoa;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.winlator.R;
import com.winlator.renderer.SceneProfile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Hosts the bundled offline editor. The bridge accepts only a bounded scene profile. */
public final class SceneEditorActivity extends Activity {
    private static final int PICK_IMAGE = 1;
    private static final int MAX_ENCODED = 4 * ((SceneProfile.MAX_FILE_BYTES + 2) / 3);
    private WebView editor;
    private ValueCallback<Uri[]> pendingImage;
    private boolean saving;
    private AlertDialog confirmation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String html;
        try (InputStream input = getAssets().open("tsoa_scene_editor/create_tsoa_scene.html")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            html = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            Log.w("TSOA_FE_DIAG", "Scene editor asset unavailable", error);
            toast(R.string.tsoa_scene_import_failed);
            finish();
            return;
        }
        editor = new WebView(this);
        WebSettings settings = editor.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        editor.addJavascriptInterface(new EditorBridge(), "TsoaSceneEditor");
        editor.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                releaseEditor();
                finish();
                return true;
            }
        });
        editor.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                                       FileChooserParams parameters) {
                completeImageSelection(null);
                pendingImage = callback;
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(picker, PICK_IMAGE);
                } catch (android.content.ActivityNotFoundException error) {
                    completeImageSelection(null);
                }
                return true;
            }
        });
        setContentView(editor);
        String language = new TsoaOptions(this).getMenuLanguage();
        editor.loadDataWithBaseURL("https://editor.tsoa.invalid/?lang=" + language,
                html, "text/html", "UTF-8", null);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_IMAGE) return;
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        completeImageSelection(uri != null && "content".equals(uri.getScheme()) ? new Uri[]{uri} : null);
    }

    private void completeImageSelection(Uri[] selected) {
        ValueCallback<Uri[]> callback = pendingImage;
        pendingImage = null;
        if (callback != null) callback.onReceiveValue(selected);
    }

    private final class EditorBridge {
        @JavascriptInterface public void install(String encoded) {
            if (encoded == null || encoded.length() > MAX_ENCODED) return;
            runOnUiThread(() -> confirmInstall(encoded));
        }
    }

    private void confirmInstall(String encoded) {
        if (saving || confirmation != null || editor == null || isFinishing() || isDestroyed()) return;
        confirmation = new AlertDialog.Builder(this)
                .setMessage(TsoaMenuLanguage.get(this, R.string.tsoa_scene_editor_save))
                .setNegativeButton(TsoaMenuLanguage.get(this, R.string.tsoa_cancel), null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> install(encoded))
                .create();
        confirmation.setOnDismissListener(dialog -> confirmation = null);
        confirmation.show();
    }

    private void install(String encoded) {
        saving = true;
        new Thread(() -> {
            boolean installed;
            try {
                byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
                TsoaSceneStore.install(getApplicationContext(), new ByteArrayInputStream(decoded));
                installed = true;
            } catch (IOException | IllegalArgumentException | SecurityException error) {
                Log.w("TSOA_FE_DIAG", "Could not save prepared scene", error);
                installed = false;
            }
            final boolean success = installed;
            runOnUiThread(() -> {
                saving = false;
                if (isFinishing() || isDestroyed()) return;
                toast(success ? R.string.tsoa_scene_imported : R.string.tsoa_scene_import_failed);
                if (success) finish();
            });
        }, "tsoa-scene-save").start();
    }

    private void toast(int message) {
        Toast.makeText(this, TsoaMenuLanguage.get(this, message), Toast.LENGTH_LONG).show();
    }

    private void releaseEditor() {
        completeImageSelection(null);
        if (editor == null) return;
        android.view.ViewParent parent = editor.getParent();
        if (parent instanceof android.view.ViewGroup) ((android.view.ViewGroup) parent).removeView(editor);
        editor.destroy();
        editor = null;
    }

    @Override protected void onDestroy() {
        if (confirmation != null) confirmation.dismiss();
        releaseEditor();
        super.onDestroy();
    }
}
