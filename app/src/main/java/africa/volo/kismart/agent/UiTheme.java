package africa.volo.kismart.agent;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared professional UI tokens for the device agent screens.
 * Visual only — does not change business logic.
 */
final class UiTheme {
    static final int INK = Color.rgb(15, 23, 20);
    static final int INK_SOFT = Color.rgb(32, 42, 38);
    static final int MUTED = Color.rgb(90, 104, 97);
    static final int MUTED_SOFT = Color.rgb(120, 132, 126);
    static final int LINE = Color.rgb(220, 228, 223);
    static final int SURFACE = Color.rgb(245, 247, 246);
    static final int SURFACE_ELEVATED = Color.WHITE;
    static final int ACCENT = Color.rgb(13, 107, 69);
    static final int ACCENT_DARK = Color.rgb(10, 90, 58);
    static final int ACCENT_SOFT = Color.rgb(232, 245, 238);
    static final int WARNING = Color.rgb(180, 83, 9);
    static final int WARNING_SOFT = Color.rgb(255, 247, 237);
    static final int DANGER = Color.rgb(153, 27, 27);
    static final int DANGER_SOFT = Color.rgb(254, 242, 242);
    static final int SUCCESS = Color.rgb(13, 107, 69);
    static final int WHITE = Color.WHITE;
    static final int BLACK = Color.rgb(8, 12, 10);

    private UiTheme() {}

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(int fill, int stroke, int strokeWidthDp, float radiusDp, Context context) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(context, Math.round(radiusDp)));
        if (strokeWidthDp > 0) d.setStroke(dp(context, strokeWidthDp), stroke);
        return d;
    }

    static GradientDrawable card(Context context) {
        return shape(SURFACE_ELEVATED, LINE, 1, 14, context);
    }

    static GradientDrawable softCard(Context context) {
        return shape(SURFACE, LINE, 1, 14, context);
    }

    static GradientDrawable primaryButton(Context context) {
        return shape(ACCENT, ACCENT_DARK, 0, 12, context);
    }

    static GradientDrawable secondaryButton(Context context) {
        return shape(SURFACE_ELEVATED, LINE, 1, 12, context);
    }

    static GradientDrawable ghostButton(Context context) {
        return shape(Color.TRANSPARENT, LINE, 1, 12, context);
    }

    static GradientDrawable pill(int fill, int stroke, Context context) {
        return shape(fill, stroke, 1, 999, context);
    }

    static TextView text(Context context, String value, float sp, int color, boolean bold) {
        TextView t = new TextView(context);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        else t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        if (Build.VERSION.SDK_INT >= 21) {
            t.setLetterSpacing(bold && sp >= 18 ? -0.01f : 0.01f);
        }
        return t;
    }

    static TextView caption(Context context, String value) {
        TextView t = text(context, value, 12, MUTED, false);
        if (Build.VERSION.SDK_INT >= 21) t.setLetterSpacing(0.04f);
        t.setAllCaps(false);
        return t;
    }

    static TextView sectionLabel(Context context, String value) {
        TextView t = text(context, value.toUpperCase(java.util.Locale.US), 11, MUTED, true);
        if (Build.VERSION.SDK_INT >= 21) t.setLetterSpacing(0.08f);
        return t;
    }

    static ImageView logo(Context context, int sizeDp) {
        ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription("KISMART");
        logo.setBackground(shape(WHITE, LINE, 1, 12, context));
        logo.setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp));
        logo.setLayoutParams(lp);
        return logo;
    }

    static Button primaryButton(Context context, String label, View.OnClickListener listener) {
        Button b = baseButton(context, label, listener);
        b.setTextColor(WHITE);
        b.setBackground(primaryButton(context));
        b.setElevation(dp(context, 1));
        return b;
    }

    static Button secondaryButton(Context context, String label, View.OnClickListener listener) {
        Button b = baseButton(context, label, listener);
        b.setTextColor(INK);
        b.setBackground(secondaryButton(context));
        return b;
    }

    static Button baseButton(Context context, String label, View.OnClickListener listener) {
        Button b = new Button(context);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        b.setOnClickListener(listener);
        return b;
    }

    static EditText field(Context context, String hint) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED_SOFT);
        input.setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14));
        input.setBackground(shape(SURFACE, LINE, 1, 12, context));
        return input;
    }

    static LinearLayout.LayoutParams matchWrap(Context context, int topDp, int bottomDp) {
        return match(context, topDp, bottomDp, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams match(Context context, int topDp, int bottomDp, int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
        );
        p.setMargins(0, dp(context, topDp), 0, dp(context, bottomDp));
        return p;
    }

    static LinearLayout cardContainer(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(card(context));
        card.setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18));
        card.setElevation(dp(context, 1));
        return card;
    }

    static LinearLayout metaRow(Context context, String label, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(context, 8), 0, dp(context, 8));

        TextView k = text(context, label, 13, MUTED, false);
        TextView v = text(context, value, 13, INK, true);
        v.setGravity(Gravity.END);

        row.addView(k, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    static View hairline(Context context) {
        View line = new View(context);
        line.setBackgroundColor(LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1))
        );
        line.setLayoutParams(p);
        return line;
    }

    static TextView statusPill(Context context, String label, boolean restricted) {
        TextView pill = text(context, label, 12, restricted ? WARNING : SUCCESS, true);
        pill.setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5));
        pill.setBackground(pill(
                restricted ? WARNING_SOFT : ACCENT_SOFT,
                restricted ? Color.rgb(253, 186, 116) : Color.rgb(167, 221, 188),
                context
        ));
        pill.setGravity(Gravity.CENTER);
        return pill;
    }

    static void stylePrimaryCta(Button button, Context context, boolean enabled) {
        if (button == null) return;
        if (enabled) {
            button.setEnabled(true);
            button.setClickable(true);
            button.setFocusable(true);
            button.setTextColor(WHITE);
            button.setBackground(primaryButton(context));
        } else {
            button.setEnabled(false);
            button.setClickable(false);
            button.setTextColor(MUTED);
            button.setBackground(shape(SURFACE, LINE, 1, 12, context));
        }
    }
}
