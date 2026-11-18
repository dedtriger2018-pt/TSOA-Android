package com.winlator.tsoa;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.winlator.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Visualizes actual parent links between save checkpoints and locally archived game screens. */
final class ChronosTerminal {
    private final TsoaTerminal terminal;
    private final List<ChronosStore.Point> points;
    private final List<TsoaJournal.Record> captures;
    private final String head;
    private final Runnable create;
    private final Consumer<ChronosStore.Point> manage;
    private ChronosStore.Point selected;
    private LinearLayout inspector;
    private Graph graph;

    ChronosTerminal(Activity activity, List<ChronosStore.Point> points, String head, Runnable create,
                    Consumer<ChronosStore.Point> manage) {
        terminal = new TsoaTerminal(activity);
        this.points = new ArrayList<>(points);
        Collections.reverse(this.points);
        this.head = head;
        this.create = create;
        this.manage = manage;
        captures = new ArrayList<>();
        if (!points.isEmpty()) selected = points.get(0);
        for (ChronosStore.Point item : points) if (item.id.equals(head)) selected = item;
    }

    void show() {
        terminal.show();
        render();
    }

    private void render() {
        captures.clear();
        captures.addAll(TsoaJournal.list(terminal.activity));
        LinearLayout theme = terminal.column();
        if (points.isEmpty()) {
            terminal.card(theme, terminal.s(R.string.tsoa_chronos_empty), "", TsoaTerminal.RED, null);
            theme.addView(terminal.button(terminal.s(R.string.tsoa_chronos_new), true, this::create));
            terminal.page("Chronos", "", null, terminal.scroll(theme), null);
            return;
        }
        graph = new Graph();
        graph.setBackground(terminal.surface(0xff101114, TsoaTerminal.LINE));
        theme.addView(graph, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout toolbar = terminal.row();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        addTool(toolbar, "−", terminal.s(R.string.tsoa_graph_zoom_out), () -> graph.zoom(0.81f));
        addTool(toolbar, "+", terminal.s(R.string.tsoa_graph_zoom_in), () -> graph.zoom(1.23f));
        addTool(toolbar, "‹", terminal.s(R.string.tsoa_graph_previous), () -> cycle(-1));
        addTool(toolbar, "›", terminal.s(R.string.tsoa_graph_next), () -> cycle(1));
        if (graph.nodes.containsKey(head)) addTool(toolbar, terminal.s(R.string.tsoa_graph_active),
                terminal.s(R.string.tsoa_graph_active), () -> {
                    choose(graph.nodes.get(head).point);
                    graph.center(true);
                });
        theme.addView(toolbar, new LinearLayout.LayoutParams(-1, terminal.dp(47)));
        inspector = terminal.column();
        updateInspector();
        LinearLayout body = terminal.panes(theme, terminal.scroll(inspector));
        terminal.page("Chronos", terminal.s(R.string.tsoa_graph_count, points.size()), null, body, graph::stop);
    }

    private void addTool(LinearLayout row, String title, String description, Runnable action) {
        View button = terminal.button(title, false, action);
        button.setContentDescription(description);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.rightMargin = terminal.dp(4);
        row.addView(button, params);
    }

    private void create() { terminal.dismiss(); create.run(); }

    private void cycle(int offset) {
        int index = (points.indexOf(selected) + offset + points.size()) % points.size();
        choose(points.get(index));
        graph.center(true);
    }

    private void choose(ChronosStore.Point point) {
        selected = point;
        updateInspector();
        graph.invalidate();
        graph.setContentDescription(terminal.s(R.string.tsoa_chronos_point, terminal.date(selected.time)));
    }

    private void updateInspector() {
        inspector.removeAllViews();
        String label = terminal.date(selected.time);
        if (selected.id.equals(head)) label = terminal.s(R.string.tsoa_chronos_current, label);
        terminal.card(inspector, label, terminal.s(R.string.tsoa_graph_checkpoint), TsoaTerminal.RED, null);
        ChronosStore.Point point = selected;
        inspector.addView(terminal.button(terminal.s(R.string.tsoa_graph_manage), false,
                () -> { terminal.dismiss(); manage.accept(point); }));
        inspector.addView(terminal.button(terminal.s(R.string.tsoa_chronos_new), true, this::create));
        TextView heading = terminal.label(terminal.s(R.string.tsoa_graph_records), 12, TsoaTerminal.MUTED, true);
        heading.setPadding(0, terminal.dp(12), 0, terminal.dp(6));
        inspector.addView(heading);
        int count = 0;
        for (TsoaJournal.Record record : captures) if (record.checkpoint.equals(point.id)) {
            count++;
            terminal.card(inspector, TsoaJournal.category(terminal, record.category), terminal.date(record.time),
                    TsoaJournal.accent(record.category), () -> TsoaJournal.view(terminal, record, this::render));
        }
        if (count == 0) inspector.addView(terminal.label(terminal.s(R.string.tsoa_graph_no_records), 13, TsoaTerminal.MUTED, false));
    }

    private static final class Node {
        final ChronosStore.Point point;
        final String date;
        final List<Node> children = new ArrayList<>();
        final Set<Integer> categories = new java.util.TreeSet<>();
        final RectF area = new RectF();

        Node(ChronosStore.Point point, String date) { this.point = point; this.date = date; }
    }

    private final class Graph extends View {
        private static final float CARD_W = 168, CARD_H = 58, X_UNIT = 214, Y_UNIT = 114;
        private final Map<String, Node> nodes = new java.util.LinkedHashMap<>();
        private final Set<String> active = new HashSet<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final GestureDetector pan;
        private final ScaleGestureDetector pinch;
        private final String activeLabel = terminal.s(R.string.tsoa_graph_active);
        private final String checkpointLabel = terminal.s(R.string.tsoa_graph_checkpoint);
        private final float density = getResources().getDisplayMetrics().density;
        private boolean positioned;
        private int rows;
        private float scale = 0.94f, panX, panY;
        private ValueAnimator animation;

        Graph() {
            super(terminal.activity);
            for (ChronosStore.Point point : points) nodes.put(point.id, new Node(point, terminal.date(point.time)));
            for (Node node : nodes.values()) {
                Node parent = nodes.get(node.point.parent);
                if (parent != null) parent.children.add(node);
            }
            Set<Node> placed = new HashSet<>();
            for (Node node : nodes.values()) if (!nodes.containsKey(node.point.parent)) place(node, 0, rows++, placed);
            for (Node node : nodes.values()) if (!placed.contains(node)) place(node, 0, rows++, placed);
            for (Node node = nodes.get(head); node != null && active.add(node.point.id); node = nodes.get(node.point.parent)) {}
            for (TsoaJournal.Record record : captures) {
                Node node = nodes.get(record.checkpoint);
                if (node != null) node.categories.add(record.category);
            }
            setFocusable(true);
            pan = new GestureDetector(terminal.activity, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) { stop(); return true; }
                @Override public boolean onScroll(MotionEvent down, MotionEvent current, float dx, float dy) {
                    if (!pinch.isInProgress() && current.getPointerCount() == 1) {
                        panX -= dx; panY -= dy; constrain(); invalidate();
                    }
                    return true;
                }
                @Override public boolean onSingleTapUp(MotionEvent event) {
                    float x = (event.getX()-panX)/(density*scale), y = (event.getY()-panY)/(density*scale);
                    for (Node node : nodes.values()) if (node.area.contains(x,y)) {
                        choose(node.point);
                        performClick();
                        break;
                    }
                    return true;
                }
            });
            pinch = new ScaleGestureDetector(terminal.activity, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector event) {
                    adjust(event.getScaleFactor(), event.getFocusX(), event.getFocusY());
                    return true;
                }
            });
            setContentDescription(terminal.s(R.string.tsoa_graph_checkpoint));
        }

        private void place(Node node, int depth, int row, Set<Node> placed) {
            if (!placed.add(node)) return;
            float x = 18 + depth*X_UNIT, y = 18 + row*Y_UNIT;
            node.area.set(x,y,x+CARD_W,y+CARD_H);
            for (int i = 0; i < node.children.size(); i++)
                place(node.children.get(i), depth+1, i == 0 ? row : rows++, placed);
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            if (!positioned && w > 0 && h > 0) { positioned = true; center(false); }
            else constrain();
        }

        private void constrain() {
            float right=0, bottom=0, factor=density*scale;
            for (Node node : nodes.values()) {
                right=Math.max(right,node.area.right);
                bottom=Math.max(bottom,node.area.bottom+25);
            }
            float margin=terminal.dp(46);
            panX=Math.max(Math.min(margin,getWidth()-right*factor-margin),Math.min(margin,panX));
            panY=Math.max(Math.min(margin,getHeight()-bottom*factor-margin),Math.min(margin,panY));
        }

        void center(boolean animate) {
            Node node = nodes.get(selected.id);
            if (node == null) return;
            stop();
            float oldX=panX, oldY=panY;
            float x=getWidth()*0.51f-node.area.centerX()*density*scale;
            float y=getHeight()*0.44f-node.area.centerY()*density*scale;
            if (!animate || new TsoaOptions(terminal.activity).isReducedMotion()) {
                panX=x; panY=y; constrain(); invalidate(); return;
            }
            animation=ValueAnimator.ofFloat(0,1);
            animation.setDuration(180);
            animation.addUpdateListener(frame -> {
                float t=(float)frame.getAnimatedValue();
                panX=oldX+t*(x-oldX); panY=oldY+t*(y-oldY);
                constrain(); invalidate();
            });
            animation.start();
        }

        void zoom(float change) { adjust(change,getWidth()*0.5f,getHeight()*0.5f); }

        private void adjust(float change, float x, float y) {
            float next=Math.max(0.38f,Math.min(1.9f,scale*change));
            panX=x-(x-panX)*next/scale; panY=y-(y-panY)*next/scale;
            scale=next; constrain(); invalidate();
        }

        void stop() { if (animation != null) { animation.cancel(); animation=null; } }
        @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
        @Override public boolean onTouchEvent(MotionEvent event) {
            pinch.onTouchEvent(event);
            pan.onTouchEvent(event);
            return true;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.translate(panX,panY);
            canvas.scale(density*scale,density*scale);
            paint.setStrokeWidth(1.15f);
            paint.setStyle(Paint.Style.STROKE);
            for (Node node : nodes.values()) {
                Node parent=nodes.get(node.point.parent);
                if (parent == null) continue;
                float x=parent.area.right,y=parent.area.centerY(), nx=node.area.left,ny=node.area.centerY();
                float middle=(x+nx)*0.5f;
                path.reset(); path.moveTo(x,y);
                path.cubicTo(middle,y,middle,ny,nx,ny);
                paint.setColor(active.contains(node.point.id) ? TsoaTerminal.RED : 0xff515159);
                canvas.drawPath(path,paint);
            }
            for (Node node : nodes.values()) drawNode(canvas,node);
            canvas.restore();
        }

        private void drawNode(Canvas canvas, Node node) {
            boolean focus=node.point==selected, current=node.point.id.equals(head);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(focus ? 0xff251d21 : TsoaTerminal.PANEL);
            canvas.drawRoundRect(node.area,3,3,paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(focus || current ? TsoaTerminal.RED : TsoaTerminal.LINE);
            canvas.drawRoundRect(node.area,3,3,paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(12);
            paint.setColor(current ? TsoaTerminal.RED : TsoaTerminal.MUTED);
            canvas.drawText(current ? activeLabel : checkpointLabel,
                    node.area.left+10,node.area.top+21,paint);
            paint.setTypeface(Typeface.MONOSPACE);
            paint.setColor(TsoaTerminal.TEXT);
            paint.setTextSize(11);
            canvas.drawText(node.date,node.area.left+10,node.area.top+41,paint);
            if (!node.categories.isEmpty()) {
                float x=node.area.left+10, y=node.area.bottom+10;
                paint.setColor(TsoaTerminal.LINE);
                canvas.drawLine(x,node.area.bottom,x,y,paint);
                for (int category : node.categories) {
                    paint.setColor(TsoaJournal.accent(category));
                    canvas.drawCircle(x,y,2.5f,paint);
                    x+=11;
                }
            }
        }
    }
}
