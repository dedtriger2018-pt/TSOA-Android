package com.winlator.tsoa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;

import com.winlator.inputcontrols.ControlElement;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.widget.InputControlsView;

/** Keep the single menu control touchable on compact screens and after a size change. */
public final class TsoaGameControlsView extends InputControlsView {
    private final TsoaOptions options;
    public TsoaGameControlsView(Context context) {
        super(context);
        options = new TsoaOptions(context);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (options.isHaptics() && (event.getActionMasked() == MotionEvent.ACTION_DOWN ||
                event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN)) {
            ControlsProfile profile = getProfile();
            int index = event.getActionIndex();
            if (profile != null) for (ControlElement element : profile.getElements()) {
                if (element.containsPoint(event.getX(index), event.getY(index))) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    break;
                }
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ControlsProfile profile = getProfile();
        if (!TsoaGameControls.isMinimalProfile(getContext(), profile)) return;
        float minimum = (float)Math.ceil(44 * getResources().getDisplayMetrics().density);
        boolean changed = false;
        for (ControlElement element : profile.getElements()) {
            Rect bounds = element.getBoundingBox();
            if (bounds.width() > 0 && bounds.height() > 0 && (bounds.width() < minimum || bounds.height() < minimum)) {
                element.setScale(element.getScale() * Math.max((minimum + 2) / bounds.width(), (minimum + 2) / bounds.height()));
                changed = true;
            }
        }
        if (changed) postInvalidateOnAnimation();
    }
}
