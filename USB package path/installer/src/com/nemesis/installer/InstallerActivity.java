package com.nemesis.installer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;

/** The NEMESIS Installation Interface: a warning the user confirms or declines.
 *  The choice is written to /storage/upgrade/nemesis/NEMESISAGREEMENT (1 or 0)
 *  and the head unit's misc service is poked so the root-side nemesis script
 *  at /data/nemesis/nemesis runs. */
public class InstallerActivity extends Activity {
    private static final String AGREEMENT = "/storage/upgrade/NEMESISAGREEMENT";
    private static final int TITLE_FG = 0xFFB6C1C8;
    private static final int MUTED = 0xFF62797F;
    private static final int BG = 0xFF1A1D1E;

    private Button proceed;
    private Button decline;
    private TextView warning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(titleBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(warningPane(), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 2));
        body.addView(qrPane(), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        root.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    /** Same bar as the Manager, with the "sin" credit added on the right. No close button. */
    private View titleBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.co_bg_title_bar);
        bar.setPadding(dp(22), 0, dp(22), 0);

        TextView title = new TextView(this);
        title.setText(R.string.title);
        title.setTextColor(TITLE_FG);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        bar.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView credit = new TextView(this);
        credit.setText(R.string.credit);
        credit.setTextColor(MUTED);
        credit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        credit.setSingleLine(true);
        credit.setPadding(dp(16), 0, 0, 0);
        bar.addView(credit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ImageView amFlag = new ImageView(this);
        amFlag.setImageResource(R.drawable.am_flag);
        amFlag.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(33), dp(22));
        ap.leftMargin = dp(8);
        bar.addView(amFlag, ap);

        // Weight-1 spacer pushes the sin credit to the right edge.
        bar.addView(new View(this), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1));

        TextView sin = new TextView(this);
        sin.setText(R.string.sin_credit);
        sin.setTextColor(MUTED);
        sin.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        sin.setSingleLine(true);
        bar.addView(sin, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ImageView flag = new ImageView(this);
        flag.setImageResource(R.drawable.ua_flag);
        flag.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(dp(33), dp(22));
        fp.leftMargin = dp(8);
        bar.addView(flag, fp);
        return bar;
    }

    private View warningPane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(dp(28), dp(24), dp(20), dp(16));

        ScrollView sv = new ScrollView(this);
        warning = new TextView(this);
        warning.setText(R.string.warning);
        warning.setTextColor(Color.WHITE);
        warning.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
        warning.setLineSpacing(0, 1.1f);
        sv.addView(warning);
        pane.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // Same bottom-bar style as the Manager's package-detail page.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundResource(R.drawable.co_bg_bottom);
        proceed = bottomButton(R.string.proceed, true);
        decline = bottomButton(R.string.decline, false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(60));
        bp.rightMargin = dp(8);
        bar.addView(proceed, bp);
        bar.addView(decline, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(60)));
        pane.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));
        return pane;
    }

    private Button bottomButton(int label, final boolean agree) {
        Button b = new Button(this);
        b.setText(label);
        b.setBackgroundResource(R.drawable.co_bt_title);
        b.setTextColor(getResources().getColorStateList(R.color.title_btn_text));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        b.setMinWidth(dp(120));
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                submit(agree);
            }
        });
        return b;
    }

    private View qrPane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setGravity(Gravity.CENTER);
        pane.setPadding(dp(16), dp(16), dp(24), dp(16));

        ImageView qr = new ImageView(this);
        qr.setImageResource(R.drawable.qr_nemesis);
        qr.setAdjustViewBounds(true);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(dp(210), dp(210));
        pane.addView(qr, qp);

        TextView url = new TextView(this);
        url.setText(R.string.qr_url);
        url.setTextColor(TITLE_FG);
        url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        url.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        up.topMargin = dp(12);
        pane.addView(url, up);
        return pane;
    }

    /** Write the choice and poke the misc service that starts the root-side script. */
    private void submit(final boolean agree) {
        proceed.setEnabled(false);
        decline.setEnabled(false);
        warning.setText(R.string.working);
        final String v = agree ? "1" : "0";
        new Thread(new Runnable() {
            public void run() {
                // Which step failed and why, so a stuck screen can be diagnosed
                // from a photo instead of needing a logcat nobody can pull here.
                String detail = null;
                try {
                    // nemesis root on this unit runs with CapBnd=0xc0 (SETUID/SETGID only);
                    // no DAC_OVERRIDE, so it cannot read our default 0600. World-readable it.
                    File f = new File(AGREEMENT);
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write((v + "\n").getBytes("UTF-8"));
                    fos.close();
                    f.setReadable(true, false);
                    f.setWritable(true, false);
                } catch (Throwable t) {
                    detail = "agreement write: " + t;
                }
                if (detail == null) {
                    try {
                        int rc = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                                "service call com.hkmc.misc 6 s16 NEMESISSVC"}).waitFor();
                        if (rc != 0) {
                            detail = "service call exit " + rc;
                        }
                    } catch (Throwable t) {
                        detail = "service call: " + t;
                    }
                }
                // A failure here (storage not yet writable right after boot, the
                // misc service rejecting the transaction) must not leave the user
                // stuck on "Please wait" forever with no way to retry.
                if (detail != null) {
                    final String d = detail;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            warning.setText(getString(R.string.submit_error, d));
                            proceed.setEnabled(true);
                            decline.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
