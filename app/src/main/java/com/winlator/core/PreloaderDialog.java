package com.winlator.core;

import android.app.Activity;
import android.app.Dialog;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.winlator.MainActivity;
import com.winlator.R;

public class PreloaderDialog {
    private final Activity activity;
    private Dialog dialog;

    public PreloaderDialog(Activity activity) {
        this.activity = activity;
    }

    static void applyDedicatedVisibility(Activity activity, Dialog dialog) {
        // The dedicated lobby supplies the visible status; retain dialog/task lifecycle.
        boolean dedicated = activity instanceof MainActivity
                ? ((MainActivity)activity).isDedicatedLauncherMode()
                : activity.getIntent() != null &&
                  activity.getIntent().getBooleanExtra("tsoa_fe_mode", false);
        if (!dedicated) return;

        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.alpha = 0f;
            attributes.dimAmount = 0f;
            window.setAttributes(attributes);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    private void create() {
        if (dialog != null) return;
        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(R.layout.preloader_dialog);

        Window window = dialog.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    public synchronized void show(int textResId) {
        if (isShowing()) return;
        close();
        if (dialog == null) create();
        ((TextView)dialog.findViewById(R.id.TextView)).setText(textResId);
        applyDedicatedVisibility(activity, dialog);
        dialog.show();
    }

    public void showOnUiThread(final int textResId) {
        activity.runOnUiThread(() -> show(textResId));
    }

    public synchronized void close() {
        try {
            if (dialog != null) {
                dialog.dismiss();
            }
        }
        catch (Exception e) {}
    }

    public void closeOnUiThread() {
        activity.runOnUiThread(this::close);
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
}
