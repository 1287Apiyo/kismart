package africa.volo.kismart.agent;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LockActivity extends Activity {
    private static final int BLACK = Color.rgb(0, 0, 0);
    private static final int GREEN = Color.rgb(22, 163, 74);
    private static final int MUTED = Color.rgb(66, 66, 66);
    private static final long RESTORE_CHECK_MS = 3000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean locked = true;
    private boolean syncing;
    private long allowLeaveUntil;
    private TextView status;

    private final Runnable restoreCheck = new Runnable() {
        @Override
        public void run() {
            checkForAdminRestore();
            if (locked) handler.postDelayed(this, RESTORE_CHECK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        enterStrictVisualMode();
        setContentView(buildUi());
        DeviceControls.reinforceVisibleFullLock(this);
        DeviceControls.protectAppFromUninstall(this);
        render(KismartApi.lastPolicy(this));
        handler.post(restoreCheck);
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterStrictVisualMode();
        DeviceControls.reinforceVisibleFullLock(this);
        render(KismartApi.lastPolicy(this));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (locked && System.currentTimeMillis() >= allowLeaveUntil) {
            handler.postDelayed(() -> DeviceControls.enforceFullLock(this), 150L);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterStrictVisualMode();
    }

    @Override
    public void onBackPressed() {
        enterStrictVisualMode();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (System.currentTimeMillis() < allowLeaveUntil) return;
        if (locked) handler.postDelayed(() -> DeviceControls.enforceFullLock(this), 250L);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(restoreCheck);
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.setBackgroundColor(BLACK);

        Space top = new Space(this);
        root.addView(top, new LinearLayout.LayoutParams(1, 0, 1));

        TextView mark = text("DEVICE SERVICE", 12, MUTED, true);
        mark.setGravity(Gravity.CENTER);
        root.addView(mark);

        TextView title = text("PAYMENT REQUIRED", 18, GREEN, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(8), 0, dp(4));
        root.addView(title);

        status = text("Open the payment prompt to continue", 12, MUTED, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        Space bottom = new Space(this);
        root.addView(bottom, new LinearLayout.LayoutParams(1, 0, 1));

        Button emergency = new Button(this);
        emergency.setText("Emergency Call");
        emergency.setAllCaps(false);
        emergency.setTextSize(12);
        emergency.setTextColor(MUTED);
        emergency.setBackgroundColor(BLACK);
        emergency.setOnClickListener(view -> {
            allowLeaveUntil = System.currentTimeMillis() + 60000L;
            DeviceControls.callEmergency(this);
        });
        root.addView(emergency, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
        ));

        return root;
    }

    private void render(Policy policy) {
        if (policy == null) {
            if (status != null) status.setText("Waiting for backend");
            return;
        }
        if (!DeviceControls.isFullLockPolicy(policy)) {
            locked = false;
            DeviceControls.applyPolicy(this, policy);
            finish();
            return;
        }
        DeviceControls.reinforceVisibleFullLock(this);
        if (status != null) status.setText("Payment required");
    }

    private void checkForAdminRestore() {
        if (syncing) return;
        syncing = true;
        executor.execute(() -> {
            try {
                Policy policy = KismartApi.sync(this);
                runOnUiThread(() -> render(policy));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (status != null) status.setText("Waiting for admin unlock");
                });
            } finally {
                syncing = false;
            }
        });
    }

    private void enterStrictVisualMode() {
        getWindow().setStatusBarColor(BLACK);
        getWindow().setNavigationBarColor(BLACK);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private TextView text(String value, int size, int color, boolean strong) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (strong) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
