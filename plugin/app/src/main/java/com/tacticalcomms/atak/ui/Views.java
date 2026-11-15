package com.tacticalcomms.atak.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Programmatic view factory (keeps the plugin free of layout-inflation quirks). */
final class Views {
    static final int BG = Color.parseColor("#1E1F22");
    static final int CARD = Color.parseColor("#2B2D31");
    static final int TEXT = Color.parseColor("#F2F3F5");
    static final int MUTED = Color.parseColor("#A0A4AB");
    static final int ACCENT = Color.parseColor("#2E7D32");
    static final int ACCENT_DIM = Color.parseColor("#3A4A3B");
    static final int DANGER = Color.parseColor("#C62828");
    static final int ONLINE = Color.parseColor("#4CAF50");
    static final int OUT_BUBBLE = Color.parseColor("#1F4D2B");

    private Views() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static Button button(Context c, String label, int color, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setBackground(rounded(color, dp(c, 6)));
        b.setMinHeight(dp(c, 36));
        b.setMinimumHeight(dp(c, 36));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        int p = dp(c, 8);
        b.setPadding(p, 0, p, 0);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    }

    static LinearLayout.LayoutParams margins(Context c, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(rounded(CARD, dp(c, 8)));
        int p = dp(c, 10);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(c, 8));
        l.setLayoutParams(lp);
        return l;
    }
}
