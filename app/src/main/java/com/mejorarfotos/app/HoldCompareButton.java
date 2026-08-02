package com.mejorarfotos.app;

import android.content.Context;
import android.widget.Button;

/** Accessible button used for tap-toggle and press-and-hold comparison. */
public final class HoldCompareButton extends Button {
    public HoldCompareButton(Context context) {
        super(context);
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
