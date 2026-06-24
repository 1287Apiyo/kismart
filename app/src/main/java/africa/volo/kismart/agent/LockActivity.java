package africa.volo.kismart.agent;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LockActivity extends Activity {
    private static final int BLACK = Color.rgb(0, 0, 0);
    private static final int GREEN = Color.rgb(22, 163, 74);
    private static final long RESTORE_CHECK_MS = 3000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean locked = true;
    private boolean syncing;
    private TextView status;
    private boolean screenReceiverRegistered;

    private final Runnable restoreCheck = new Runnable() {
        @Override
        public void run() {
            checkForAdminRestore();
            if (locked) handler.postDelayed(this, RESTORE_CHECK_MS);
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? "" : intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                enterStrictVisualMode();
                showLockedMessage();
                DeviceControls.enforceFullLock(LockActivity.this);
            }
        }
    };

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        enterStrictVisualMode();
        setContentView(buildUi());
        registerScreenReceiver();
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
        if (locked) {
            handler.post(() -> DeviceControls.enforceFullLock(this));
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (locked) handler.post(() -> DeviceControls.enforceFullLock(this));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterStrictVisualMode();
        } else if (locked) {
            handler.post(() -> DeviceControls.enforceFullLock(this));
        }
    }

    @Override
    public void onBackPressed() {
        enterStrictVisualMode();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (locked) handler.post(() -> DeviceControls.enforceFullLock(this));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_POWER) {
            showLockedMessage();
            DeviceControls.enforceFullLock(this);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(restoreCheck);
        unregisterScreenReceiver();
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BLACK);
        return root;
    }

    private void render(Policy policy) {
        if (policy == null) {
            showLockedMessage();
            return;
        }
        if (!DeviceControls.isFullLockPolicy(policy)) {
            locked = false;
            DeviceControls.applyPolicy(this, policy);
            finish();
            return;
        }
        locked = true;
        DeviceControls.reinforceVisibleFullLock(this);
        showLockedMessage();
    }

    private void checkForAdminRestore() {
        if (syncing) return;
        syncing = true;
        executor.execute(() -> {
            try {
                Policy policy = KismartApi.sync(this);
                runOnUiThread(() -> render(policy));
            } catch (Exception error) {
                runOnUiThread(this::showLockedMessage);
            } finally {
                syncing = false;
            }
        });
    }

    private void enterStrictVisualMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().setStatusBarColor(BLACK);
        getWindow().setNavigationBarColor(BLACK);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_SECURE
        );
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
        setFinishOnTouchOutside(false);
    }

    private void showLockedMessage() {
        if (status != null) status.setText(DeviceControls.FULL_LOCK_MESSAGE);
    }

    private void registerScreenReceiver() {
        if (screenReceiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        screenReceiverRegistered = true;
    }

    private void unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return;
        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        screenReceiverRegistered = false;
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
