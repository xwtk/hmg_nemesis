package com.nemesis.manager;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** A thin bar: filled to pct when known, otherwise a light sweeping across the line. */
public class SweepBar extends View {
    private static final long SWEEP_MS = 1400;
    private final Paint track = new Paint();
    private final Paint fill = new Paint();
    private final Paint glow = new Paint();
    /** 0..100, or -1 for unknown. */
    private int pct = -1;

    public SweepBar(Context c, AttributeSet a) {
        super(c, a);
        track.setColor(0xFF3A4347);
        fill.setColor(0xFF2A9FD8);
    }

    void setPct(int p) {
        if (p == pct) return;
        pct = p;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        cv.drawRect(0, 0, w, h, track);
        if (pct >= 0) {
            cv.drawRect(0, 0, w * Math.min(pct, 100) / 100f, h, fill);
            return;
        }
        float band = w / 3f;
        float x = (SystemClock.uptimeMillis() % SWEEP_MS) / (float) SWEEP_MS * (w + band) - band;
        glow.setShader(new LinearGradient(x, 0, x + band, 0,
                new int[] {0x002A9FD8, 0xFF7FD0F5, 0x002A9FD8}, null, Shader.TileMode.CLAMP));
        cv.drawRect(Math.max(0, x), 0, Math.min(w, x + band), h, glow);
        postInvalidateOnAnimation();
    }
}
