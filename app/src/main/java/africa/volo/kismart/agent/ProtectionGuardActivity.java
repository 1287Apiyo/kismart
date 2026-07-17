package africa.volo.kismart.agent;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
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
        window.setStatusBarColor(UiTheme.BLACK);
        window.setNavigationBarColor(UiTheme.BLACK);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(UiTheme.BLACK);
        root.setClickable(true);
        root.setFocusable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(UiTheme.shape(Color.rgb(18, 24, 21), Color.rgb(40, 52, 46), 1, 16, this));
        card.setPadding(dp(22), dp(26), dp(22), dp(22));

        ImageView logo = UiTheme.logo(this, 64);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        logoParams.bottomMargin = dp(18);
        card.addView(logo, logoParams);

        TextView kicker = UiTheme.sectionLabel(this, "Setup required");
        kicker.setTextColor(Color.rgb(159, 212, 184));
        kicker.setGravity(Gravity.CENTER);
        card.addView(kicker);

        TextView title = UiTheme.text(this, "Enable Device Service", 22, UiTheme.WHITE, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(10), 0, dp(8));
        card.addView(title);

        TextView message = UiTheme.text(
                this,
                "Turn on Device Service under Accessibility to protect this financed phone and continue.",
                14,
                Color.rgb(176, 190, 182),
                false
        );
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(dp(3), 1.15f);
        message.setPadding(0, 0, 0, dp(20));
        card.addView(message);

        Button enable = UiTheme.primaryButton(this, "Open Accessibility settings", view -> openAccessibilitySettings());
        card.addView(enable, UiTheme.match(this, 0, 0, dp(52)));

        root.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
