package com.winlator.tsoa;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.winlator.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Read-only bridge to the guest's visible output. A player classifies each saved frame. */
public final class TsoaJournal {
    private static final int[] CATEGORIES = {R.string.tsoa_pda_messages, R.string.tsoa_pda_documents,
            R.string.tsoa_pda_news, R.string.tsoa_pda_dialogue, R.string.tsoa_pda_choices, R.string.tsoa_pda_missions,
            R.string.tsoa_pda_chapters, R.string.tsoa_pda_events};
    private static final String ARCHIVE_PATTERN = "[0-7]-[0-9]{13}-(root|[0-9]{13}-[0-9a-f]{8})\\.jpg";
    private static final Executor DISK = Executors.newSingleThreadExecutor();
    private static final long MAX_STORAGE = 128L * 1024 * 1024;

    private TsoaJournal() {}

    static final class Record {
        final File file;
        final int category;
        final long time;
        final String checkpoint;

        Record(File file) {
            this.file = file;
            String name = file.getName();
            category = name.charAt(0) - '0';
            time = Long.parseLong(name.substring(2, 15));
            checkpoint = name.substring(16, name.length() - 4);
        }

        String seenKey() { return "pda_seen." + file.getName(); }
    }

    public static void open(Activity activity, SurfaceView game) {
        if (available(activity)) new Archive(activity, game).open();
    }

    static ArrayList<Record> list(Activity activity) {
        ArrayList<Record> values = new ArrayList<>();
        for (File file : records(folder(activity))) values.add(new Record(file));
        return values;
    }

    static String category(TsoaTerminal terminal, int category) { return terminal.s(CATEGORIES[category]); }

    static int accent(int category) {
        switch (category) {
            case 4: return TsoaTerminal.RED;
            case 5: return 0xffbc9e7b;
            case 6: return 0xffb1b0ca;
            case 7: return 0xff87adad;
            default: return 0xff9499a4;
        }
    }

    private static void capture(Activity activity, SurfaceView game) {
        int width = game.getWidth(), height = game.getHeight();
        if (width < 2 || height < 2 || !game.getHolder().getSurface().isValid()) { failed(activity); return; }
        float scale = Math.min(1f, 1920f / Math.max(width, height));
        Bitmap frame = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(game, frame, result -> {
                if (result != PixelCopy.SUCCESS || !available(activity)) {
                    frame.recycle();
                    if (available(activity)) failed(activity);
                    return;
                }
                TsoaTerminal terminal = new TsoaTerminal(activity);
                LinearLayout categories = terminal.column();
                for (int i = 0; i < CATEGORIES.length; i++) {
                    final int category = i;
                    terminal.card(categories, category(terminal, i), "", accent(i), () -> {
                        save(activity, frame.copy(Bitmap.Config.ARGB_8888, false), category);
                        terminal.dismiss();
                    });
                }
                terminal.page(s(activity, R.string.tsoa_pda_category), "", null, terminal.scroll(categories), frame::recycle);
                terminal.show();
            }, new Handler(Looper.getMainLooper()));
        } catch (IllegalArgumentException failure) {
            frame.recycle();
            failed(activity);
        }
    }

    private static void save(Activity activity, Bitmap frame, int category) {
        if (frame == null) { failed(activity); return; }
        File directory = folder(activity);
        String branch = TsoaOptions.prefs(activity).getString("chronos_head", "root");
        String parent = branch != null && branch.matches("[0-9]{13}-[0-9a-f]{8}") ? branch : "root";
        DISK.execute(() -> {
            File temp = null;
            boolean saved = false;
            try {
                if ((!directory.isDirectory() && !directory.mkdirs()) || Files.isSymbolicLink(directory.toPath())) throw new IOException();
                File[] records = records(directory);
                if (records.length >= 96) throw new IOException();
                long free = MAX_STORAGE;
                for (File record : records) free -= record.length();
                if (free <= 0) throw new IOException();
                temp = File.createTempFile(".capture-", ".tmp", directory);
                try (FileOutputStream output = new FileOutputStream(temp)) {
                    if (!frame.compress(Bitmap.CompressFormat.JPEG, 94, output)) throw new IOException();
                    output.getFD().sync();
                }
                if (temp.length() > free) throw new IOException();
                String name = String.format(Locale.ROOT, "%d-%013d-%s.jpg", category, System.currentTimeMillis(), parent);
                Files.move(temp.toPath(), new File(directory, name).toPath(), StandardCopyOption.ATOMIC_MOVE);
                saved = true;
            } catch (IOException problem) {
                android.util.Log.w("TSOA_FE_DIAG", "PDA capture unavailable", problem);
            } finally {
                if (temp != null) temp.delete();
                frame.recycle();
            }
            final boolean stored = saved;
            activity.runOnUiThread(() -> {
                if (available(activity)) Toast.makeText(activity, s(activity,
                        stored ? R.string.tsoa_pda_saved : R.string.tsoa_pda_failed), Toast.LENGTH_SHORT).show();
            });
        });
    }

    private static final class Archive {
        private final TsoaTerminal terminal;
        private final SurfaceView game;
        private final SharedPreferences prefs;
        private final int mode;
        private java.util.List<ChronosStore.Point> automatic;
        private ArrayList<Record> archive;
        private LinearLayout cards;
        private TextView count;
        private int category = -1;
        private String query = "";

        Archive(Activity context, SurfaceView game) {
            terminal = new TsoaTerminal(context);
            this.game = game;
            prefs = TsoaOptions.prefs(context);
            mode = new TsoaOptions(context).getSyncMode();
            if (mode == 1) category = -2;
        }

        void open() { terminal.show(); show(); }

        private void show() {
            archive = list(terminal.activity);
            automatic = mode == 0 ? java.util.Collections.emptyList() : TsoaHybridSync.store(terminal.activity).list();
            LinearLayout screen = terminal.column();
            LinearLayout top = terminal.row();
            EditText search = new EditText(terminal.locale);
            search.setSingleLine(true);
            search.setTextSize(15);
            search.setTextColor(TsoaTerminal.TEXT);
            search.setHintTextColor(TsoaTerminal.MUTED);
            search.setHint(R.string.tsoa_pda_search);
            search.setText(query);
            search.setPadding(terminal.dp(12), 0, terminal.dp(8), 0);
            search.setBackground(terminal.surface(TsoaTerminal.PANEL, TsoaTerminal.LINE));
            top.addView(search, new LinearLayout.LayoutParams(0, terminal.dp(46), 1f));
            if (game != null) {
                LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-2, terminal.dp(46));
                bounds.leftMargin = terminal.dp(8);
                top.addView(terminal.button(terminal.s(R.string.tsoa_pda_capture_short), true, () -> {
                    terminal.dismiss();
                    capture(terminal.activity, game);
                }), bounds);
            }
            screen.addView(top);
            HorizontalScrollView filters = new HorizontalScrollView(terminal.activity);
            filters.setHorizontalScrollBarEnabled(false);
            LinearLayout tokens = terminal.row();
            tokens.setPadding(0, terminal.dp(7), 0, terminal.dp(7));
            int first = mode == 0 ? -1 : -2;
            for (int id = first; id < CATEGORIES.length; id++) {
                final int filter = id;
                String name = id == -2 ? terminal.s(R.string.tsoa_sync_auto) :
                        id == -1 ? terminal.s(R.string.tsoa_pda_all) : category(terminal, id);
                android.widget.Button button = terminal.button(name, id == category, () -> {
                    category = filter;
                    for (int i = 0; i < tokens.getChildCount(); i++) {
                        boolean active = i == filter - first;
                        android.widget.Button token = (android.widget.Button)tokens.getChildAt(i);
                        token.setTextColor(active ? TsoaTerminal.RED : TsoaTerminal.TEXT);
                        token.setBackground(terminal.surface(active ? 0xff262022 : TsoaTerminal.PANEL,
                                active ? 0xff714441 : TsoaTerminal.LINE));
                        token.setSelected(active);
                    }
                    populate();
                });
                button.setSelected(id == category);
                LinearLayout.LayoutParams tokenBounds = new LinearLayout.LayoutParams(-2, terminal.dp(43));
                tokenBounds.rightMargin = terminal.dp(6);
                tokens.addView(button, tokenBounds);
            }
            filters.addView(tokens);
            screen.addView(filters);
            count = terminal.label("", 12, TsoaTerminal.MUTED, true);
            count.setPadding(0, terminal.dp(3), 0, terminal.dp(8));
            screen.addView(count);
            cards = terminal.column();
            screen.addView(terminal.scroll(cards), new LinearLayout.LayoutParams(-1, 0, 1));
            screen.setFocusableInTouchMode(true);
            screen.requestFocus();
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                    query = text.toString(); populate();
                }
                @Override public void afterTextChanged(Editable text) {}
            });
            populate();
            terminal.page(terminal.s(R.string.tsoa_pda), terminal.s(R.string.tsoa_pda_local), null, screen, null);
        }

        private void populate() {
            cards.removeAllViews();
            int matches = 0, unread = 0;
            String term = searchable(query.trim(), terminal.language());
            if (category < 0) for (ChronosStore.Point point : automatic) {
                if (point.automaticDigest == null) continue;
                String title = terminal.s(R.string.tsoa_sync_checkpoint);
                String date = terminal.date(point.time);
                if (!term.isEmpty() && !searchable(title + " " + date, terminal.language()).contains(term)) continue;
                String seenKey = "pda_sync_seen." + point.id;
                boolean unseen = !prefs.getBoolean(seenKey, false);
                if (unseen) unread++;
                matches++;
                terminal.card(cards, title, unseen ? terminal.s(R.string.tsoa_pda_unseen, date) : date,
                        unseen ? TsoaTerminal.RED : TsoaTerminal.MUTED, () -> {
                            prefs.edit().putBoolean(seenKey, true).apply();
                            LinearLayout page = terminal.column();
                            terminal.card(page, title, terminal.s(R.string.tsoa_sync_record_detail), TsoaTerminal.MUTED, null);
                            terminal.page(title, date, this::show, terminal.scroll(page), null);
                        });
            }
            if (category != -2) for (Record record : archive) {
                if (category >= 0 && category != record.category) continue;
                String title = category(terminal, record.category);
                String metadata = describe(terminal, record);
                if (!term.isEmpty() && !searchable(title + " " + metadata, terminal.language()).contains(term)) continue;
                boolean unseen = !prefs.getBoolean(record.seenKey(), false);
                if (unseen) unread++;
                matches++;
                terminal.card(cards, title, unseen ? terminal.s(R.string.tsoa_pda_unseen, metadata) : metadata,
                        unseen ? TsoaTerminal.RED : accent(record.category),
                        () -> {
                            android.view.inputmethod.InputMethodManager ime = terminal.activity
                                    .getSystemService(android.view.inputmethod.InputMethodManager.class);
                            if (ime != null) ime.hideSoftInputFromWindow(cards.getWindowToken(), 0);
                            view(terminal, record, this::show);
                        });
            }
            count.setText(terminal.s(R.string.tsoa_pda_count, matches, unread));
            if (matches == 0) terminal.card(cards, terminal.s(R.string.tsoa_pda_empty), "", TsoaTerminal.LINE, null);
        }
    }

    private static String searchable(String text, Locale locale) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(locale);
    }

    private static String describe(TsoaTerminal terminal, Record record) {
        String date = terminal.date(record.time);
        if (record.checkpoint.equals("root")) return date;
        return date + " · " + terminal.s(R.string.tsoa_chronos_point, terminal.date(
                Long.parseLong(record.checkpoint.substring(0, 13))));
    }

    static void view(TsoaTerminal terminal, Record record, Runnable back) {
        if (!terminal.isOpen()) return;
        Activity activity = terminal.activity;
        LinearLayout loading = terminal.column();
        loading.addView(terminal.label(terminal.s(R.string.tsoa_pda_opening), 14, TsoaTerminal.MUTED, false));
        terminal.page(category(terminal, record.category), describe(terminal, record), back, loading, null);
        DISK.execute(() -> {
            Bitmap bitmap = BitmapFactory.decodeFile(record.file.getAbsolutePath());
            activity.runOnUiThread(() -> {
                if (!terminal.isOpen() || !loading.isAttachedToWindow() || !available(activity)) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) { failed(activity); back.run(); return; }
                SharedPreferences prefs = TsoaOptions.prefs(activity);
                prefs.edit().putBoolean(record.seenKey(), true).apply();
                FrameViewer viewer = new FrameViewer(terminal, bitmap);
                LinearLayout page = terminal.column();
                page.addView(viewer, new LinearLayout.LayoutParams(-1, 0, 1));
                LinearLayout actions = terminal.row();
                actions.addView(terminal.button(terminal.s(R.string.tsoa_graph_zoom_in), false,
                        () -> viewer.zoom(1.35f, viewer.getWidth()*0.5f, viewer.getHeight()*0.5f)),
                        new LinearLayout.LayoutParams(0, -2, 1));
                actions.addView(terminal.button(terminal.s(R.string.tsoa_graph_zoom_out), false,
                        () -> viewer.zoom(0.74f, viewer.getWidth()*0.5f, viewer.getHeight()*0.5f)),
                        new LinearLayout.LayoutParams(0, -2, 1));
                actions.addView(terminal.button(terminal.s(R.string.tsoa_chronos_delete), false, () ->
                        new AlertDialog.Builder(activity).setMessage(terminal.s(R.string.tsoa_pda_delete_confirm))
                                .setPositiveButton(terminal.s(R.string.tsoa_chronos_delete), (confirm, button) -> {
                                    if (!record.file.delete()) failed(activity);
                                    else { prefs.edit().remove(record.seenKey()).apply(); back.run(); }
                                }).setNegativeButton(terminal.s(R.string.tsoa_cancel), null).show()),
                        new LinearLayout.LayoutParams(0, -2, 1));
                page.addView(actions);
                terminal.page(category(terminal, record.category), describe(terminal, record), back, page, () -> {
                    viewer.setImageDrawable(null);
                    bitmap.recycle();
                });
            });
        });
    }

    private static final class FrameViewer extends ImageView {
        private final Matrix transform = new Matrix();
        private final int imageWidth, imageHeight;
        private final ScaleGestureDetector pinch;
        private final GestureDetector pan;
        private float scale, minimum, x, y;

        FrameViewer(TsoaTerminal terminal, Bitmap bitmap) {
            super(terminal.activity);
            imageWidth = bitmap.getWidth(); imageHeight = bitmap.getHeight();
            setContentDescription(terminal.s(R.string.tsoa_pda_capture));
            setBackgroundColor(TsoaTerminal.VOID);
            setScaleType(ScaleType.MATRIX);
            setImageBitmap(bitmap);
            pinch = new ScaleGestureDetector(terminal.activity, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    zoom(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                    return true;
                }
            });
            pan = new GestureDetector(terminal.activity, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) { return true; }
                @Override public boolean onScroll(MotionEvent first, MotionEvent current, float dx, float dy) {
                    if (!pinch.isInProgress()) { x -= dx; y -= dy; update(); }
                    return true;
                }
                @Override public boolean onDoubleTap(MotionEvent event) {
                    zoom(scale > minimum * 1.2f ? minimum/scale : 2f, event.getX(), event.getY());
                    return true;
                }
            });
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            minimum = Math.min(width/(float)imageWidth,height/(float)imageHeight);
            scale = minimum; x = y = 0;
            update();
        }

        void zoom(float factor, float cx, float cy) {
            if (scale <= 0) return;
            float next = Math.max(minimum, Math.min(minimum*5, scale*factor));
            x = cx-(cx-x)*next/scale; y = cy-(cy-y)*next/scale;
            scale = next;
            update();
        }

        private void update() {
            float w = imageWidth*scale, h = imageHeight*scale;
            x = w < getWidth() ? (getWidth()-w)*0.5f : Math.max(getWidth()-w, Math.min(0,x));
            y = h < getHeight() ? (getHeight()-h)*0.5f : Math.max(getHeight()-h, Math.min(0,y));
            transform.setScale(scale,scale);
            transform.postTranslate(x,y);
            setImageMatrix(transform);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            pinch.onTouchEvent(event);
            pan.onTouchEvent(event);
            return true;
        }
    }

    private static File folder(Activity activity) { return new File(activity.getNoBackupFilesDir(), "pda"); }
    private static File[] records(File directory) {
        File[] entries = directory.listFiles(file -> file.getName().matches(ARCHIVE_PATTERN)
                && file.isFile() && !Files.isSymbolicLink(file.toPath()));
        if (entries == null) return new File[0];
        Arrays.sort(entries, (a, b) -> b.getName().substring(2).compareTo(a.getName().substring(2)));
        return entries;
    }
    private static String s(Activity activity, int res, Object... args) { return TsoaMenuLanguage.get(activity, res, args); }
    private static boolean available(Activity activity) {
        return !activity.isFinishing() && !activity.isDestroyed() && !new TsoaOptions(activity).isOriginalMode();
    }
    private static void failed(Activity activity) {
        Toast.makeText(activity, s(activity, R.string.tsoa_pda_failed), Toast.LENGTH_SHORT).show();
    }
}
