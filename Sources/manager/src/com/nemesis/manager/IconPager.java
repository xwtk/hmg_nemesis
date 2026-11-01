package com.nemesis.manager;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** Horizontal swipe between equal-width pages. KitKat has no ViewPager without
 *  the support library, which this build does not use. */
public class IconPager extends HorizontalScrollView {
    interface PageListener {
        void onPage(int page);
    }

    private LinearLayout strip;
    private PageListener listener;
    private int page;
    private float downX;

    public IconPager(Context c) {
        super(c);
        init(c);
    }

    public IconPager(Context c, AttributeSet a) {
        super(c, a);
        init(c);
    }

    private void init(Context c) {
        setHorizontalScrollBarEnabled(false);
        setFillViewport(true);
        strip = new LinearLayout(c);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        addView(strip, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }

    void setPageListener(PageListener l) {
        listener = l;
    }

    void clear() {
        strip.removeAllViews();
        page = 0;
        scrollTo(0, 0);
    }

    void addPage(LinearLayout p) {
        strip.addView(p, new LinearLayout.LayoutParams(pageWidth(), LayoutParams.MATCH_PARENT));
    }

    private int pageWidth() {
        return getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels * 2 / 3;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == oldw) return;
        for (int i = 0; i < strip.getChildCount(); i++) {
            strip.getChildAt(i).getLayoutParams().width = w;
        }
        post(new Runnable() {
            public void run() {
                strip.requestLayout();
                scrollTo(page * getWidth(), 0);
            }
        });
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) downX = e.getX();
        return super.onInterceptTouchEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) downX = e.getX();
        boolean r = super.onTouchEvent(e);
        int a = e.getAction();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) settle(e.getX() - downX);
        return r;
    }

    /** A quarter-page drag is enough to turn; otherwise spring back. */
    private void settle(float dx) {
        int w = getWidth();
        if (w <= 0) return;
        int next = page;
        if (Math.abs(dx) > w / 4f) next += dx < 0 ? 1 : -1;
        int last = strip.getChildCount() - 1;
        if (next < 0) next = 0;
        if (next > last) next = last;
        page = next;
        final int x = page * w;
        post(new Runnable() {
            public void run() {
                smoothScrollTo(x, 0);
            }
        });
        if (listener != null) listener.onPage(page);
    }
}
