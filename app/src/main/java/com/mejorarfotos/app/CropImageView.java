package com.mejorarfotos.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Simple, dependency-free cropper. The selection can be moved or resized from its corners. */
public class CropImageView extends View {
    private Bitmap bitmap;
    private Bitmap comparisonBitmap;
    private boolean comparing;
    private float divider = .5f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF selection = new RectF();
    private float scale, offsetX, offsetY;
    private float downX, downY;
    private int action = 0; // 1 move, 2 left/top, 3 right/bottom

    public CropImageView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(19, 24, 21));
        overlay.setColor(Color.argb(170, 0, 0, 0));
        line.setColor(Color.rgb(214, 243, 106));
        line.setStrokeWidth(dp(2));
        line.setStyle(Paint.Style.STROKE);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    public void setBitmap(Bitmap value) {
        bitmap = value;
        comparisonBitmap = null;
        comparing = false;
        selection.set(.07f, .07f, .93f, .93f);
        requestLayout();
        invalidate();
    }

    public Bitmap getBitmap() { return bitmap; }

    public void setComparison(Bitmap before, Bitmap after) {
        bitmap = after;
        comparisonBitmap = before;
        comparing = before != null && after != null;
        selection.set(0, 0, 1, 1);
        invalidate();
    }

    public void toggleComparison() { comparing = !comparing; invalidate(); }
    public boolean isComparing() { return comparing && comparisonBitmap != null; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) {
            paint.setColor(Color.rgb(161, 174, 164));
            paint.setTextSize(dp(16));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Elige una foto o un video para empezar", getWidth() / 2f, getHeight() / 2f, paint);
            return;
        }
        scale = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
        offsetX = (getWidth() - bitmap.getWidth() * scale) / 2f;
        offsetY = (getHeight() - bitmap.getHeight() * scale) / 2f;
        RectF imageRect = new RectF(offsetX, offsetY, offsetX + bitmap.getWidth() * scale, offsetY + bitmap.getHeight() * scale);
        if (isComparing()) {
            canvas.drawBitmap(bitmap, null, imageRect, paint);
            canvas.save();
            canvas.clipRect(imageRect.left, imageRect.top, imageRect.left + imageRect.width() * divider, imageRect.bottom);
            float beforeScale = Math.min(getWidth() / (float) comparisonBitmap.getWidth(), getHeight() / (float) comparisonBitmap.getHeight());
            float beforeX = (getWidth() - comparisonBitmap.getWidth() * beforeScale) / 2f;
            float beforeY = (getHeight() - comparisonBitmap.getHeight() * beforeScale) / 2f;
            RectF beforeRect = new RectF(beforeX, beforeY, beforeX + comparisonBitmap.getWidth() * beforeScale, beforeY + comparisonBitmap.getHeight() * beforeScale);
            canvas.drawBitmap(comparisonBitmap, null, beforeRect, paint);
            canvas.restore();
            paint.setColor(Color.rgb(214, 243, 106)); paint.setStrokeWidth(dp(2));
            float x = imageRect.left + imageRect.width() * divider;
            canvas.drawLine(x, imageRect.top, x, imageRect.bottom, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, (imageRect.top + imageRect.bottom) / 2f, dp(13), paint);
            paint.setColor(Color.rgb(20, 25, 18)); paint.setTextSize(dp(11)); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("◀▶", x, (imageRect.top + imageRect.bottom) / 2f + dp(4), paint);
            return;
        }
        canvas.drawBitmap(bitmap, null, imageRect, paint);
        if (selection.width() > 0) {
            RectF r = selectionPixels();
            canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, r.top, overlay);
            canvas.drawRect(imageRect.left, r.bottom, imageRect.right, imageRect.bottom, overlay);
            canvas.drawRect(imageRect.left, r.top, r.left, r.bottom, overlay);
            canvas.drawRect(r.right, r.top, imageRect.right, r.bottom, overlay);
            canvas.drawRect(r, line);
            float h = dp(12);
            paint.setColor(Color.rgb(214, 243, 106));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(r.left, r.top, h / 2, paint);
            canvas.drawCircle(r.right, r.bottom, h / 2, paint);
        }
    }

    private RectF selectionPixels() {
        return new RectF(offsetX + selection.left * bitmap.getWidth() * scale,
                offsetY + selection.top * bitmap.getHeight() * scale,
                offsetX + selection.right * bitmap.getWidth() * scale,
                offsetY + selection.bottom * bitmap.getHeight() * scale);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null || event.getAction() == MotionEvent.ACTION_CANCEL) return true;
        if (isComparing()) {
            if (event.getAction() == MotionEvent.ACTION_MOVE || event.getAction() == MotionEvent.ACTION_DOWN) {
                float left = offsetX, width = bitmap.getWidth() * scale;
                divider = Math.max(0.05f, Math.min(.95f, (event.getX() - left) / width)); invalidate();
            }
            return true;
        }
        RectF r = selectionPixels();
        float margin = dp(28);
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY();
            if (Math.abs(downX - r.left) < margin && Math.abs(downY - r.top) < margin) action = 2;
            else if (Math.abs(downX - r.right) < margin && Math.abs(downY - r.bottom) < margin) action = 3;
            else if (r.contains(downX, downY)) action = 1;
            else action = 0;
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE && action != 0) {
            float dx = (event.getX() - downX) / (bitmap.getWidth() * scale);
            float dy = (event.getY() - downY) / (bitmap.getHeight() * scale);
            if (action == 1) {
                float w = selection.width(), h = selection.height();
                float nx = Math.max(0, Math.min(1 - w, selection.left + dx));
                float ny = Math.max(0, Math.min(1 - h, selection.top + dy));
                selection.offset(nx - selection.left, ny - selection.top);
            } else if (action == 2) {
                selection.left = Math.max(0, Math.min(selection.right - .08f, selection.left + dx));
                selection.top = Math.max(0, Math.min(selection.bottom - .08f, selection.top + dy));
            } else {
                selection.right = Math.min(1, Math.max(selection.left + .08f, selection.right + dx));
                selection.bottom = Math.min(1, Math.max(selection.top + .08f, selection.bottom + dy));
            }
            downX = event.getX(); downY = event.getY(); invalidate();
        }
        return true;
    }

    public Bitmap crop() {
        if (bitmap == null) return null;
        int left = Math.max(0, Math.round(selection.left * bitmap.getWidth()));
        int top = Math.max(0, Math.round(selection.top * bitmap.getHeight()));
        int right = Math.min(bitmap.getWidth(), Math.round(selection.right * bitmap.getWidth()));
        int bottom = Math.min(bitmap.getHeight(), Math.round(selection.bottom * bitmap.getHeight()));
        return Bitmap.createBitmap(bitmap, left, top, Math.max(1, right - left), Math.max(1, bottom - top));
    }

    public void showFullImage() { selection.set(0, 0, 1, 1); invalidate(); }
}
