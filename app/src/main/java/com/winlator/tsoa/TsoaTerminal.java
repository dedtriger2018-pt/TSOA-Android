package com.winlator.tsoa;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.winlator.R;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Shared view styling and transitions for the two local game archives. */
final class TsoaTerminal {
    static final int VOID = 0xff0c0d10, PANEL = 0xff17191d, LINE = 0xff37373d;
    static final int TEXT = 0xffeeebea, MUTED = 0xffaaa9ae, RED = 0xffe47f79;

    final Activity activity;
    final Context locale;
    private final Dialog dialog;
    private final LinearLayout root;
    private final FrameLayout body;
    private final TextView title, detail;
    private final Button back;
    private Runnable goBack, cleanup;
    private boolean wide;

    TsoaTerminal(Activity activity) {
        this.activity = activity;
        locale = TsoaMenuLanguage.context(activity);
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        root = column();
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setBackground(surface(VOID, LINE));
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        back = button("‹", false, () -> { if (goBack != null) goBack.run(); });
        back.setContentDescription(s(R.string.tsoa_back));
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        LinearLayout brand = column();
        brand.setPadding(dp(10), 0, dp(8), 0);
        title = label("", 22, TEXT, false);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        detail = label("", 12, MUTED, true);
        brand.addView(title);
        brand.addView(detail);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        Button close = button("×", false, this::dismiss);
        close.setContentDescription(s(R.string.tsoa_close));
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(48)));
        root.addView(header);
        View rule = new View(activity);
        rule.setBackgroundColor(LINE);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(1));
        line.setMargins(0, dp(10), 0, dp(10));
        root.addView(rule, line);
        body = new FrameLayout(activity);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog.setContentView(root);
        dialog.setOnKeyListener((window, key, event) -> {
            if (key != KeyEvent.KEYCODE_BACK || goBack == null) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) goBack.run();
            return true;
        });
        dialog.setOnDismissListener(ignored -> clearBody());
        root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            boolean landscape = (r - l) > (b - t) && (r - l) > dp(560);
            if (landscape != wide) {
                wide = landscape;
                if (body.getChildCount() != 0 && body.getChildAt(0).getTag() instanceof LinearLayout) {
                    LinearLayout panes = (LinearLayout)body.getChildAt(0).getTag();
                    resizePanes(panes);
                }
            }
        });
    }

    void show() {
        if (isOpen() || activity.isFinishing() || activity.isDestroyed()) return;
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            int width = activity.getResources().getDisplayMetrics().widthPixels;
            int height = activity.getResources().getDisplayMetrics().heightPixels;
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            window.setLayout(Math.min(dp(1050), width - dp(20)), Math.max(dp(196), height - dp(36)));
            window.setDimAmount(0.78f);
        }
    }

    void page(String heading, String metadata, Runnable previous, View content, Runnable onLeave) {
        clearBody();
        title.setText(heading);
        detail.setText(metadata);
        detail.setVisibility(metadata.isEmpty() ? View.GONE : View.VISIBLE);
        goBack = previous;
        back.setVisibility(previous == null ? View.GONE : View.VISIBLE);
        cleanup = onLeave;
        body.addView(content, new FrameLayout.LayoutParams(-1, -1));
        if (content.getTag() instanceof LinearLayout) resizePanes((LinearLayout)content.getTag());
        if (!new TsoaOptions(activity).isReducedMotion()) {
            content.setAlpha(0.72f);
            content.setTranslationY(dp(4));
            content.animate().alpha(1f).translationY(0f).setDuration(145).start();
        }
    }

    LinearLayout panes(View main, View inspector) {
        LinearLayout view = column();
        view.setTag(view);
        view.addView(main);
        view.addView(inspector);
        resizePanes(view);
        return view;
    }

    private void resizePanes(LinearLayout view) {
        if (view.getChildCount() != 2) return;
        view.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (int i = 0; i < 2; i++) {
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(wide ? 0 : -1,
                    wide ? -1 : 0, i == 0 ? 1.6f : 1f);
            if (i == 1) {
                layout.leftMargin = wide ? dp(12) : 0;
                layout.topMargin = wide ? 0 : dp(12);
            }
            view.getChildAt(i).setLayoutParams(layout);
        }
    }

    private void clearBody() {
        if (body.getChildCount() > 0) body.getChildAt(0).animate().cancel();
        if (cleanup != null) cleanup.run();
        cleanup = null;
        body.removeAllViews();
    }

    LinearLayout column() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    LinearLayout row() { return new LinearLayout(activity); }

    ScrollView scroll(LinearLayout content) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    TextView label(String value, float size, int color, boolean mono) {
        TextView view = new TextView(locale);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (mono) view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    Button button(String value, boolean accent, Runnable action) {
        Button view = new Button(locale);
        view.setAllCaps(false);
        view.setText(value);
        view.setTextSize(13);
        view.setTextColor(accent ? RED : TEXT);
        view.setPadding(dp(12), dp(4), dp(12), dp(4));
        view.setMinHeight(dp(46));
        view.setGravity(Gravity.CENTER);
        view.setBackground(surface(accent ? 0xff262022 : PANEL, accent ? 0xff714441 : LINE));
        view.setOnClickListener(clicked -> action.run());
        return view;
    }

    void card(LinearLayout list, String caption, String metadata, int accent, Runnable onClick) {
        LinearLayout item = row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setBackground(surface(PANEL, LINE));
        item.setMinimumHeight(dp(58));
        View mark = new View(activity);
        mark.setBackgroundColor(accent);
        LinearLayout.LayoutParams markBounds = new LinearLayout.LayoutParams(dp(2), dp(26));
        markBounds.setMargins(dp(13), 0, dp(12), 0);
        item.addView(mark, markBounds);
        LinearLayout copy = column();
        copy.setPadding(0, dp(9), dp(12), dp(9));
        copy.addView(label(caption, 15, TEXT, false));
        if (!metadata.isEmpty()) copy.addView(label(metadata, 12, MUTED, false));
        item.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        if (onClick != null) {
            item.setFocusable(true);
            item.setOnClickListener(clicked -> onClick.run());
        }
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, -2);
        bounds.bottomMargin = dp(6);
        list.addView(item, bounds);
    }

    GradientDrawable surface(int fill, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(3));
        bg.setStroke(Math.max(1, dp(1)), stroke);
        return bg;
    }

    boolean isOpen() { return dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed(); }
    void dismiss() { dialog.dismiss(); }
    int dp(float size) { return Math.round(size * activity.getResources().getDisplayMetrics().density); }
    String s(int resource, Object... args) { return locale.getString(resource, args); }
    Locale language() { return locale.getResources().getConfiguration().getLocales().get(0); }

    String date(long timestamp) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, language())
                .format(new Date(timestamp));
    }
}
