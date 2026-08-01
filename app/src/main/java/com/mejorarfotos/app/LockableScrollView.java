package com.mejorarfotos.app;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.ScrollView;

/** Scroll container that can hand every gesture to the crop editor. */
public final class LockableScrollView extends ScrollView {
    private boolean scrollingEnabled = true;

    public LockableScrollView(Context context) {
        super(context);
    }

    public void setScrollingEnabled(boolean enabled) {
        scrollingEnabled = enabled;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        return scrollingEnabled && super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!scrollingEnabled) return false;
        boolean handled = super.onTouchEvent(event);
        if (event.getAction() == MotionEvent.ACTION_UP && !canScrollVertically(1)
                && !canScrollVertically(-1)) {
            performClick();
        }
        return handled;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
