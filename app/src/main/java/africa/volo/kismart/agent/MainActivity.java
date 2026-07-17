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

import org.json.JSONObject;

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
    private static final String EXTRA_ADMIN_VERIFIED = AdminSetupReceiver.ACTION_EXTRA_ADMIN_VERIFIED;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler monitorHandler = new Handler(Looper.getMainLooper());
    private final Runnable paymentPollRunnable = this::pollPaymentStatus;
    private TextView amountView;
    private TextView dueView;
    private TextView accountView;
    private TextView arrearsView;
    private TextView accountStatusView;
    private TextView statusDetail;
    private Button paymentButton;
    private Policy latestPolicy;
    private boolean syncing;
    private boolean paymentPending;
    private int paymentPollAttempts;
    private int paymentBalanceBefore;

    private final Runnable monitorRunnable = new Runnable() {
        @Override
        public void run() {
            Policy policy = latestPolicy != null ? latestPolicy : KismartApi.lastPolicy(MainActivity.this);
            // While unpaid (and not admin), re-pin payment UI every cycle.
            if (DeviceControls.mustStayOnPaymentScreen(MainActivity.this)) {
                DeviceControls.applyPolicy(MainActivity.this, policy);
            }
            autoSync();
            long delay = DeviceControls.mustStayOnPaymentScreen(MainActivity.this) ? 2000L : 5000L;
            monitorHandler.postDelayed(this, delay);
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
        renderPolicy(KismartApi.lastPolicy(this));
        AgentSyncService.start(this);
        monitorHandler.postDelayed(monitorRunnable, 1500L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (openLockScreenIfFullLockActive()) return;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (openLockScreenIfFullLockActive()) return;
        DeviceControls.enforceFinancedDeviceHardening(this);
        DeviceControls.protectAppFromUninstall(this);
        Policy policy = KismartApi.lastPolicy(this);
        if (policy != null) {
            if (DeviceControls.enforceMissingProtectionGuard(this, policy)) return;
            latestPolicy = policy;
            renderPolicy(policy);
            DeviceControls.applyPolicy(this, policy);
        }
    }

    @Override
    public void onBackPressed() {
        // Unpaid debt: trap on payment screen — back must not leave KISMART (unless admin session).
        Policy policy = latestPolicy != null ? latestPolicy : KismartApi.lastPolicy(this);
        if (DeviceControls.mustStayOnPaymentScreen(this)) {
            DeviceControls.applyPolicy(this, policy);
            setDetail("Payment required. Pay via M-Pesa to unlock this phone.");
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // If the user tries Home/Recents while debt remains, pull them back to Pay.
        Policy policy = latestPolicy != null ? latestPolicy : KismartApi.lastPolicy(this);
        if (DeviceControls.mustStayOnPaymentScreen(this)) {
            monitorHandler.postDelayed(() -> DeviceControls.forcePaymentScreen(MainActivity.this), 250L);
            monitorHandler.postDelayed(() -> DeviceControls.applyPolicy(MainActivity.this, policy), 400L);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) return;
        if (DeviceControls.mustStayOnPaymentScreen(this)) {
            monitorHandler.postDelayed(() -> DeviceControls.forcePaymentScreen(MainActivity.this), 200L);
        }
    }

    private boolean openLockScreenIfFullLockActive() {
        Intent intent = getIntent();
        if (intent != null
                && intent.getBooleanExtra(EXTRA_OPEN_ADMIN_SETUP, false)
                && intent.getBooleanExtra(EXTRA_ADMIN_VERIFIED, false)) {
            return false;
        }
        Policy policy = KismartApi.lastPolicy(this);
        if (!DeviceControls.isFullLockPolicy(policy)) return false;
        DeviceControls.enforceFullLock(this);
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        monitorHandler.removeCallbacks(monitorRunnable);
        monitorHandler.removeCallbacks(paymentPollRunnable);
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

    private void showAdminUnlock() {
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pin.setSingleLine(true);
        pin.setHint("Admin passcode");
        new AlertDialog.Builder(this)
                .setTitle("Admin access")
                .setMessage("Correct passcode unlocks setup for 45 minutes so you can Sync without being sent back to Pay.")
                .setView(pin)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open", (dialog, which) -> {
                    String value = pin.getText() == null ? "" : pin.getText().toString().trim();
                    SharedPreferences prefs = KismartApi.prefs(this);
                    String deviceSecret = prefs.getString(KismartApi.KEY_SECRET, "");
                    if (ADMIN_PIN.equals(value)
                            || KismartApi.DEFAULT_DEVICE_SECRET.equals(value)
                            || (deviceSecret != null && !deviceSecret.isEmpty() && value.equals(deviceSecret))) {
                        // Grant admin session so payment lockdown does not kick you out of setup.
                        DeviceControls.grantAdminSession(this);
                        Intent intent = new Intent(this, AdminSetupActivity.class);
                        intent.putExtra(EXTRA_ADMIN_VERIFIED, true);
                        startActivity(intent);
                        setDetail("Admin unlocked. You can Sync and configure without being sent to Pay.");
                    } else {
                        setDetail("Admin access denied. Check passcode (default 4321 or device sync secret).");
                    }
                })
                .show();
    }

    private void syncNow() {
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
                    setDetail("Offline. Last admin policy is still active.");
                });
            }
        });
    }

    private void autoSync() {
        if (syncing) return;
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
        if (DeviceControls.enforceMissingProtectionGuard(this, policy)) return;
        latestPolicy = policy;
        DeviceControls.applyPolicy(this, policy);
        renderPolicy(policy);
    }

    private void showStkPrompt() {
        if (latestPolicy == null) {
            setDetail("Account is still syncing. Try again shortly.");
            return;
        }
        if (paymentPending) {
            setDetail("M-Pesa prompt already sent. Enter your PIN on the phone, then wait for confirmation.");
            return;
        }
        int amount = suggestedStkAmount(latestPolicy);
        if (amount <= 0) {
            setDetail("Your account has no amount due.");
            return;
        }

        EditText phoneInput = new EditText(this);
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneInput.setSingleLine(true);
        phoneInput.setHint("07XXXXXXXX");
        String knownPhone = latestPolicy.customerPhone == null ? "" : latestPolicy.customerPhone.trim();
        if (!knownPhone.isEmpty()) {
            phoneInput.setText(knownPhone);
        }

        new AlertDialog.Builder(this)
                .setTitle("Pay " + formatKes(amount) + " via M-Pesa")
                .setMessage("A real M-Pesa STK prompt will be sent to this phone number. Confirm the number, then enter your M-Pesa PIN when the prompt appears.")
                .setView(phoneInput)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send STK", (dialog, which) -> {
                    String phone = phoneInput.getText() == null ? "" : phoneInput.getText().toString().trim();
                    submitStk(amount, phone);
                })
                .show();
    }

    private void submitStk(int amount, String phoneNumber) {
        if (paymentButton != null) paymentButton.setEnabled(false);
        setDetail("Sending M-Pesa STK prompt...");
        paymentBalanceBefore = latestPolicy == null ? 0 : latestPolicy.balance;
        // Keep limit active while we request STK — never unlock just because Pay was tapped.
        if (latestPolicy != null && latestPolicy.balance > 0) {
            DeviceControls.applyPolicy(this, latestPolicy);
        }
        executor.execute(() -> {
            try {
                JSONObject result = KismartApi.submitPaybillStk(this, amount, phoneNumber);
                String sentTo = result.optString("phoneNumber", phoneNumber);
                String message = result.optString(
                        "message",
                        "M-Pesa STK prompt sent. Enter your PIN on the phone to complete payment."
                );
                JSONObject policyJson = result.optJSONObject("policy");
                Policy returnedPolicy = null;
                if (policyJson != null) {
                    try {
                        returnedPolicy = Policy.fromJson(policyJson);
                        KismartApi.persistPolicy(this, policyJson);
                    } catch (Exception ignored) {
                    }
                }
                final Policy policyToApply = returnedPolicy;
                runOnUiThread(() -> {
                    if (policyToApply != null) {
                        DeviceControls.applyPolicy(this, policyToApply);
                        renderPolicy(policyToApply);
                    } else if (latestPolicy != null && latestPolicy.balance > 0) {
                        DeviceControls.applyPolicy(this, latestPolicy);
                    }
                    paymentPending = true;
                    paymentPollAttempts = 0;
                    setDetail(message + (sentTo == null || sentTo.isEmpty() ? "" : " (" + sentTo + ")"));
                    schedulePaymentPoll(3000);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    paymentPending = false;
                    if (latestPolicy != null && latestPolicy.balance > 0) {
                        DeviceControls.applyPolicy(this, latestPolicy);
                    }
                    updatePaymentButton(latestPolicy);
                    setDetail("Payment request failed: " + error.getMessage());
                });
            }
        });
    }

    private void schedulePaymentPoll(long delayMs) {
        monitorHandler.removeCallbacks(paymentPollRunnable);
        monitorHandler.postDelayed(paymentPollRunnable, delayMs);
    }

    private void pollPaymentStatus() {
        if (!paymentPending) return;
        paymentPollAttempts += 1;
        setDetail("Waiting for M-Pesa PIN / confirmation... (" + paymentPollAttempts + ")");
        executor.execute(() -> {
            try {
                Policy policy = KismartApi.sync(this);
                runOnUiThread(() -> {
                    // Always re-apply limit while unpaid — STK wait must not free the phone.
                    DeviceControls.applyPolicy(this, policy);
                    renderPolicy(policy);
                    boolean paidDown = policy.balance < paymentBalanceBefore;
                    boolean cleared = policy.balance <= 0;
                    if (paidDown || cleared) {
                        paymentPending = false;
                        monitorHandler.removeCallbacks(paymentPollRunnable);
                        setDetail(cleared
                                ? "Payment confirmed. Account is fully paid."
                                : "Payment confirmed. Balance updated to " + formatKes(policy.balance) + ".");
                        return;
                    }
                    if (policy.isPendingStkFailed()) {
                        paymentPending = false;
                        monitorHandler.removeCallbacks(paymentPollRunnable);
                        updatePaymentButton(policy);
                        String fail = policy.pendingStkMessage;
                        if (fail == null || fail.trim().isEmpty()) {
                            fail = "M-Pesa did not confirm the payment prompt.";
                        }
                        setDetail("M-Pesa STK failed: " + fail + " Limit stays on until you pay. Tap Pay to try again.");
                        return;
                    }
                    if (paymentPollAttempts >= 20) {
                        paymentPending = false;
                        updatePaymentButton(policy);
                        setDetail("Still waiting for M-Pesa. If no PIN dialog appeared, tap Pay again. Limit stays on until payment is confirmed.");
                        return;
                    }
                    schedulePaymentPoll(4000);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (latestPolicy != null && latestPolicy.balance > 0) {
                        DeviceControls.applyPolicy(this, latestPolicy);
                    }
                    if (paymentPollAttempts >= 20) {
                        paymentPending = false;
                        updatePaymentButton(latestPolicy);
                        setDetail("Could not confirm payment yet. Limit stays on. Check M-Pesa, then tap Pay or Sync.");
                    } else {
                        schedulePaymentPoll(4000);
                    }
                });
            }
        });
    }

    private int suggestedStkAmount(Policy policy) {
        if (policy == null) return 0;
        if (policy.arrears > 0) return Math.min(policy.balance, policy.arrears);
        if (policy.nextAmount > 0) return Math.min(policy.balance, policy.nextAmount);
        if (policy.balance > 0) return policy.balance;
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
            return;
        }
        amountView.setText(formatKes(suggestedStkAmount(policy)));
        dueView.setText("Due date: " + dueDate(policy));
        if (accountStatusView != null) accountStatusView.setText("Account status: " + accountState(policy));
        if (arrearsView != null) arrearsView.setText("Arrears: " + formatKes(policy.arrears));
        accountView.setText(accountText(policy));
        updatePaymentButton(policy);
    }

    private String dueDate(Policy policy) {
        return policy.nextDue == null || policy.nextDue.trim().isEmpty() ? "Not set" : policy.nextDue.trim();
    }

    private String accountText(Policy policy) {
        String phone = policy.customerPhone == null || policy.customerPhone.trim().isEmpty()
                ? "On file"
                : policy.customerPhone.trim();
        return "Customer: " + (policy.customer == null ? "Customer" : policy.customer)
                + "\nM-Pesa phone: " + phone
                + "\nTotal balance: " + formatKes(policy.balance);
    }

    private String accountState(Policy policy) {
        if (DeviceControls.isFullLockPolicy(policy)) return "Locked";
        if (DeviceControls.isPaymentLimitActive(policy)) return "Restricted";
        if (policy.balance <= 0) return "Paid";
        return "Active";
    }

    private void updatePaymentButton(Policy policy) {
        if (paymentButton == null) return;
        int amount = suggestedStkAmount(policy);
        boolean canPay = amount > 0 && !paymentPending;
        if (paymentPending) {
            paymentButton.setText("Waiting for M-Pesa...");
        } else {
            paymentButton.setText(canPay ? "Pay " + formatKes(amount) + " via M-Pesa" : "No Payment Due");
        }
        paymentButton.setEnabled(canPay);
        paymentButton.setTextColor(canPay ? WHITE : MUTED);
        paymentButton.setBackground(panelBg(canPay ? GREEN : SOFT, canPay ? GREEN_DARK : LINE, canPay ? 0 : 1, 6));
    }

    private void setDetail(String value) {
        if (statusDetail != null) statusDetail.setText(value);
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
