package africa.volo.kismart.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BLACK = Color.rgb(14, 18, 16);
    private static final int GREEN = Color.rgb(22, 163, 74);
    private static final int GREEN_DARK = Color.rgb(21, 128, 61);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(92, 99, 95);
    private static final int LINE = Color.rgb(224, 229, 226);
    private static final int SOFT = Color.rgb(246, 248, 247);
    private static final String ADMIN_PIN = "4321";
    private static final String EXTRA_OPEN_ADMIN_SETUP = AdminSetupReceiver.ACTION_EXTRA_OPEN_ADMIN_SETUP;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler monitorHandler = new Handler(Looper.getMainLooper());
    private EditText serverUrl;
    private EditText imei;
    private EditText secret;
    private TextView amountView;
    private TextView dueView;
    private TextView accountView;
    private TextView arrearsView;
    private TextView accountStatusView;
    private TextView statusDetail;
    private TextView adminStatus;
    private LinearLayout adminPanel;
    private Button paymentButton;
    // unlock feature removed: Request Unlock button and functionality disabled
    private Policy latestPolicy;
    private boolean syncing;
    private boolean adminVisible;

    private final Runnable monitorRunnable = new Runnable() {
        @Override
        public void run() {
            autoSync();
            monitorHandler.postDelayed(this, 5000L);
        }
    };

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        if (openLockScreenIfFullLockActive()) return;
        configureWindow();
        setContentView(buildUi());
        DeviceControls.hideLauncherEntry(this);
        DeviceControls.enforceFinancedDeviceHardening(this);
        DeviceControls.protectAppFromUninstall(this);
        loadPrefs();
        renderPolicy(KismartApi.lastPolicy(this));
        openAdminSetupIfRequested(getIntent());
        AgentSyncService.start(this);
        monitorHandler.postDelayed(monitorRunnable, 1500L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (openLockScreenIfFullLockActive()) return;
        openAdminSetupIfRequested(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (openLockScreenIfFullLockActive()) return;
        DeviceControls.enforceFinancedDeviceHardening(this);
        DeviceControls.protectAppFromUninstall(this);
        Policy policy = KismartApi.lastPolicy(this);
        if (policy != null) {
            latestPolicy = policy;
            renderPolicy(policy);
            DeviceControls.applyPolicy(this, policy);
        }
    }

    private boolean openLockScreenIfFullLockActive() {
        Policy policy = KismartApi.lastPolicy(this);
        if (!DeviceControls.isFullLockPolicy(policy)) return false;
        DeviceControls.enforceFullLock(this);
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        monitorHandler.removeCallbacks(monitorRunnable);
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(WHITE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        root.addView(header());
        root.addView(accountSummary());
        root.addView(primaryActions());
        root.addView(statusStrip());
        adminPanel = adminSetupPanel();
        adminPanel.setVisibility(View.GONE);
        root.addView(adminPanel);
        return scroll;
    }

    private View header() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(2), 0, dp(18));

        TextView mark = label("KES", 13, WHITE, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(panelBg(GREEN, GREEN, 0, 8));
        mark.setOnLongClickListener(view -> {
            showAdminUnlock();
            return true;
        });
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        markParams.setMargins(0, 0, dp(12), 0);
        header.addView(mark, markParams);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        header.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        copy.addView(label("My device account", 20, BLACK, true));
        copy.addView(label("Payments and access status", 13, MUTED, false));
        return header;
    }

    private View accountSummary() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(panelBg(SOFT, LINE, 1, 8));
        panel.setLayoutParams(blockParams(0, 14));

        panel.addView(label("Amount to pay now", 13, MUTED, false));
        amountView = label("Ksh 0", 42, BLACK, true);
        amountView.setPadding(0, dp(4), 0, dp(10));
        panel.addView(amountView);

        dueView = label("Due date: Not synced", 15, BLACK, true);
        dueView.setPadding(0, dp(2), 0, dp(8));
        panel.addView(dueView);

        accountStatusView = label("Account status: Syncing", 14, GREEN_DARK, true);
        accountStatusView.setPadding(0, 0, 0, dp(6));
        panel.addView(accountStatusView);

        arrearsView = label("Arrears: Ksh 0", 14, MUTED, false);
        arrearsView.setPadding(0, 0, 0, dp(6));
        panel.addView(arrearsView);

        accountView = label("Waiting for account details.", 13, MUTED, false);
        accountView.setLineSpacing(dp(2), 1.0f);
        panel.addView(accountView);
        return panel;
    }

    private View primaryActions() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(4), 0, dp(12));

        paymentButton = actionButton("Pay Now", true, view -> showStkPrompt());
        paymentButton.setTextSize(18);
        panel.addView(paymentButton, blockParams(0, 10, dp(58)));

        // Request Unlock feature removed — users should use payments only
        // ...existing code...
        return panel;
    }

    private View statusStrip() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(6), 0, dp(10));
        statusDetail = label("Device service is monitoring your account.", 13, MUTED, false);
        statusDetail.setLineSpacing(dp(2), 1.0f);
        panel.addView(statusDetail);
        return panel;
    }

    private LinearLayout adminSetupPanel() {
        LinearLayout panel = section("Admin setup");
        adminStatus = label("", 13, MUTED, false);
        adminStatus.setPadding(0, 0, 0, dp(10));
        panel.addView(adminStatus);

        serverUrl = input("Backend URL", KismartApi.DEFAULT_SERVER_URL);
        imei = input("Registered device IMEI", KismartApi.DEFAULT_IMEI);
        secret = input("Device sync secret", "Required");
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        panel.addView(serverUrl);
        panel.addView(imei);
        panel.addView(secret);

        panel.addView(actionRow(
                actionButton("Save", true, view -> savePrefs()),
                actionButton("Sync Now", true, view -> syncNow())
        ));
        panel.addView(actionRow(
                actionButton("Enable Admin", false, view -> DeviceControls.requestAdmin(this)),
                actionButton("Accessibility", false, view -> DeviceControls.openAccessibilitySettings(this))
        ));
        return panel;
    }

    private void showAdminUnlock() {
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pin.setSingleLine(true);
        pin.setHint("Admin passcode");
        new AlertDialog.Builder(this)
                .setTitle("Admin access")
                .setView(pin)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open", (dialog, which) -> {
                    String value = pin.getText().toString().trim();
                    String deviceSecret = secret == null ? "" : secret.getText().toString().trim();
                    if (ADMIN_PIN.equals(value) || KismartApi.DEFAULT_DEVICE_SECRET.equals(value) || value.equals(deviceSecret)) {
                        setAdminVisible(true);
                    } else {
                        setDetail("Admin access denied.");
                    }
                })
                .show();
    }

    private void setAdminVisible(boolean visible) {
        adminVisible = visible;
        if (adminPanel != null) adminPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) updateAdminStatus();
    }

    private void openAdminSetupIfRequested(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_OPEN_ADMIN_SETUP, false)) return;
        setAdminVisible(true);
        setDetail("Admin setup opened.");
    }

    private void loadPrefs() {
        SharedPreferences prefs = KismartApi.prefs(this);
        serverUrl.setText(KismartApi.serverUrl(this));
        imei.setText(valueOrDefault(prefs.getString(KismartApi.KEY_IMEI, ""), KismartApi.DEFAULT_IMEI));
        secret.setText(valueOrDefault(prefs.getString(KismartApi.KEY_SECRET, ""), KismartApi.DEFAULT_DEVICE_SECRET));
    }

    private void savePrefs() {
        fillMissingValues();
        KismartApi.prefs(this).edit()
                .putString(KismartApi.KEY_SERVER_URL, serverUrl.getText().toString().trim())
                .putString(KismartApi.KEY_IMEI, imei.getText().toString().trim())
                .putString(KismartApi.KEY_SECRET, secret.getText().toString().trim())
                .apply();
        DeviceControls.hideLauncherEntry(this);
        AgentSyncService.start(this);
        updateAdminStatus();
        setDetail("Setup saved.");
    }

    private void fillMissingValues() {
        if (serverUrl.getText().toString().trim().isEmpty()) serverUrl.setText(KismartApi.DEFAULT_SERVER_URL);
        if (imei.getText().toString().trim().isEmpty()) imei.setText(KismartApi.DEFAULT_IMEI);
        if (secret.getText().toString().trim().isEmpty()) secret.setText(KismartApi.DEFAULT_DEVICE_SECRET);
    }

    private String valueOrDefault(String value, String fallback) {
        String cleaned = value == null ? "" : value.trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private void syncNow() {
        savePrefs();
        setDetail("Syncing account...");
        executor.execute(() -> {
            try {
                Policy policy = KismartApi.sync(this);
                runOnUiThread(() -> {
                    DeviceControls.applyPolicy(this, policy);
                    renderPolicy(policy);
                    setDetail("Account synced.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    enforceCachedPolicy();
                    setDetail("Offline. Last admin policy is still active. New admin actions apply when this phone rejoins the same network.");
                });
            }
        });
    }

    private void autoSync() {
        String server = serverUrl.getText().toString().trim();
        String deviceImei = imei.getText().toString().trim();
        if (syncing || server.isEmpty() || deviceImei.isEmpty()) return;
        syncing = true;
        executor.execute(() -> {
            try {
                Policy policy = KismartApi.sync(this);
                runOnUiThread(() -> {
                    DeviceControls.applyPolicy(this, policy);
                    renderPolicy(policy);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> enforceCachedPolicy());
            } finally {
                syncing = false;
            }
        });
    }

    private void enforceCachedPolicy() {
        Policy policy = KismartApi.lastPolicy(this);
        if (policy == null) return;
        latestPolicy = policy;
        DeviceControls.applyPolicy(this, policy);
        renderPolicy(policy);
    }

    private void showStkPrompt() {
        if (latestPolicy == null) {
            setDetail("Account is still syncing. Try again shortly.");
            return;
        }
        int amount = suggestedStkAmount(latestPolicy);
        if (amount <= 0) {
            setDetail("Your account has no amount due.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Pay " + formatKes(amount))
                .setMessage("Confirm payment request for your device account.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Pay Now", (dialog, which) -> submitStk(amount))
                .show();
    }

    private void submitStk(int amount) {
        savePrefs();
        setDetail("Sending payment request...");
        executor.execute(() -> {
            try {
                Policy policy = KismartApi.simulateStkPayment(this, amount);
                runOnUiThread(() -> {
                    DeviceControls.applyPolicy(this, policy);
                    renderPolicy(policy);
                    setDetail("Payment recorded. Account updated.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> setDetail("Payment failed: " + error.getMessage()));
            }
        });
    }

    // requestUnlock removed — unlock flow disabled in favor of payments only

    private int suggestedStkAmount(Policy policy) {
        if (policy == null) return 0;
        if (policy.arrears > 0) return Math.min(policy.balance, policy.arrears);
        if (policy.balance > 0) return Math.min(policy.balance, 1000);
        return 0;
    }

    private String formatKes(int amount) {
        return "Ksh " + String.format(Locale.US, "%,d", amount);
    }

    private void renderPolicy(Policy policy) {
        latestPolicy = policy;
        if (policy == null) {
            amountView.setText("Ksh 0");
            dueView.setText("Due date: Not synced");
            if (accountStatusView != null) accountStatusView.setText("Account status: Syncing");
            if (arrearsView != null) arrearsView.setText("Arrears: Ksh 0");
            accountView.setText("Waiting for account details.");
            updatePaymentButton(null);
            updateAdminStatus();
            return;
        }
        amountView.setText(formatKes(suggestedStkAmount(policy)));
        dueView.setText("Due date: " + dueDate(policy));
        if (accountStatusView != null) accountStatusView.setText("Account status: " + accountState(policy));
        if (arrearsView != null) arrearsView.setText("Arrears: " + formatKes(policy.arrears));
        accountView.setText(accountText(policy));
        updatePaymentButton(policy);
        updateAdminStatus();
    }

    private String dueDate(Policy policy) {
        return policy.nextDue == null || policy.nextDue.trim().isEmpty() ? "Not set" : policy.nextDue.trim();
    }

    private String accountText(Policy policy) {
        return "Customer: " + valueOrDefault(policy.customer, "Customer")
                + "\nTotal balance: " + formatKes(policy.balance);
    }

    private String accountState(Policy policy) {
        if (DeviceControls.isFullLockPolicy(policy)) return "Locked";
        if (policy.paymentOnlyActive) return "Restricted";
        if (policy.balance <= 0) return "Paid";
        return "Active";
    }

    private void updatePaymentButton(Policy policy) {
        if (paymentButton == null) return;
        int amount = suggestedStkAmount(policy);
        boolean canPay = amount > 0;
        paymentButton.setText(canPay ? "Pay " + formatKes(amount) + " Now" : "No Payment Due");
        paymentButton.setEnabled(canPay);
        paymentButton.setTextColor(canPay ? WHITE : MUTED);
        paymentButton.setBackground(panelBg(canPay ? GREEN : SOFT, canPay ? GREEN_DARK : LINE, canPay ? 0 : 1, 6));
    }

    private void updateAdminStatus() {
        if (!adminVisible || adminStatus == null) return;
        boolean admin = DeviceControls.isAdminActive(this);
        boolean owner = DeviceControls.isDeviceOwner(this);
        boolean guard = DeviceControls.isAccessibilityGuardEnabled(this);
        String mode = owner ? "Device Owner" : guard ? "Accessibility Guard" : admin ? "Device Admin" : "Not enabled";
        String guardState = guard ? "Accessibility: On" : "Accessibility: Off";
        String policyState = latestPolicy != null && latestPolicy.restrictionActive
                ? "Restricted: " + latestPolicy.restrictionLevel
                : "Restricted: Off";
        adminStatus.setText("Control mode: " + mode + "\n" + guardState + "\n" + policyState);
    }

    private void setDetail(String value) {
        if (statusDetail != null) statusDetail.setText(value);
    }

    private LinearLayout section(String title) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(12), 0, dp(8));
        panel.setLayoutParams(blockParams(0, 10));

        TextView heading = label(title.toUpperCase(Locale.US), 12, GREEN_DARK, true);
        heading.setPadding(0, 0, 0, dp(10));
        panel.addView(heading);
        return panel;
    }

    private LinearLayout actionRow(Button first, Button second) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(first, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        secondParams.setMargins(dp(10), 0, 0, 0);
        row.addView(second, secondParams);
        row.setLayoutParams(blockParams(0, 10));
        return row;
    }

    private TextView label(String value, int size, int color, boolean strong) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.START);
        if (strong) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private EditText input(String label, String hint) {
        EditText input = new EditText(this);
        input.setHint(label + " - " + hint);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(BLACK);
        input.setHintTextColor(Color.rgb(132, 132, 132));
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackground(panelBg(SOFT, LINE, 1, 6));
        input.setLayoutParams(blockParams(0, 10, dp(46)));
        return input;
    }

    private Button actionButton(String label, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? WHITE : BLACK);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setBackground(panelBg(primary ? GREEN : WHITE, primary ? GREEN : LINE, 1, 6));
        button.setOnClickListener(listener);
        return button;
    }

    private void configureWindow() {
        getWindow().setStatusBarColor(WHITE);
        getWindow().setNavigationBarColor(WHITE);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private LinearLayout.LayoutParams blockParams(int topDp, int bottomDp) {
        return blockParams(topDp, bottomDp, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams blockParams(int topDp, int bottomDp, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
        );
        params.setMargins(0, dp(topDp), 0, dp(bottomDp));
        return params;
    }

    private GradientDrawable panelBg(int fill, int stroke, int strokeWidthDp, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeWidthDp > 0) drawable.setStroke(dp(strokeWidthDp), stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
