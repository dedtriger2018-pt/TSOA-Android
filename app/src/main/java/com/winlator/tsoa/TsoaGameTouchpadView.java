package com.winlator.tsoa;

import android.content.Context;
import android.graphics.Matrix;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.winlator.widget.TouchpadView;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XServer;

/** Adds optional long-press right-click without changing Winlator's two-finger gestures. */
public final class TsoaGameTouchpadView extends TouchpadView {
    private final XServer server;
    private final TsoaOptions options;
    private final Matrix eventTransform = new Matrix();
    private final float touchSlop;
    private float downX;
    private float downY;
    private boolean longPressed;
    private boolean parentUsesFullscreen;
    private boolean singleFinger;
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (!singleFinger || !isEnabled() || !options.isLongPressRightClick()) return;
            longPressed = true;
            singleFinger = false;
            // Clear the parent's pending tap, otherwise ACTION_UP would also send a left click.
            MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            TsoaGameTouchpadView.super.onTouchEvent(cancel);
            cancel.recycle();
            server.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
            postDelayed(() -> server.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT), 30);
        }
    };

    public TsoaGameTouchpadView(Context context, XServer server, boolean capturePointer, TsoaOptions options) {
        super(context, server, capturePointer);
        this.server = server;
        this.options = options;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        parentUsesFullscreen = server.getRenderer().isFullscreen();
        super.onSizeChanged(width, height, oldWidth, oldHeight);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    longPressed = false;
                    singleFinger = true;
                    downX = event.getX();
                    downY = event.getY();
                    if (options.isLongPressRightClick()) postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    singleFinger = false;
                    removeCallbacks(longPress);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) {
                        singleFinger = false;
                        removeCallbacks(longPress);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    singleFinger = false;
                    removeCallbacks(longPress);
                    break;
            }
            if (longPressed) return true;
        }
        MotionEvent mapped = mapEvent(event);
        boolean handled = super.onTouchEvent(mapped);
        mapped.recycle();
        return handled;
    }

    @Override
    public boolean onExternalMouseEvent(MotionEvent event) {
        MotionEvent mapped = mapEvent(event);
        boolean handled = super.onExternalMouseEvent(mapped);
        mapped.recycle();
        return handled;
    }

    private MotionEvent mapEvent(MotionEvent event) {
        MotionEvent copy = MotionEvent.obtain(event);
        if (getWidth() <= 0 || getHeight() <= 0) return copy;
        float aspect = Math.min((float)getWidth() / server.screenInfo.width, (float)getHeight() / server.screenInfo.height);
        float offsetX = (getWidth() - server.screenInfo.width * aspect) * 0.5f;
        float offsetY = (getHeight() - server.screenInfo.height * aspect) * 0.5f;
        eventTransform.reset();
        if ("stretch".equals(options.getAspectMode())) {
            eventTransform.setScale(aspect * server.screenInfo.width / getWidth(), aspect * server.screenInfo.height / getHeight());
            if (!parentUsesFullscreen) eventTransform.postTranslate(offsetX, offsetY);
        }
        else if (parentUsesFullscreen) eventTransform.setTranslate(-offsetX, -offsetY);
        copy.transform(eventTransform);
        return copy;
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(longPress);
        singleFinger = false;
        super.onDetachedFromWindow();
    }
}
