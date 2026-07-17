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
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
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
    private boolean activityResumed;
    private boolean windowHasFocus;
    private boolean lockTaskPinned;
    private int paymentPollAttempts;
    private int paymentBalanceBefore;

    private final Runnable monitorRunnable = new Runnable() {
        @Override
        public void run() {
            // Sync only — do NOT re-apply lock-task every second (that eats Pay button taps).
            autoSync();
            long delay = paymentLockActive() ? 2500L : 5000L;
            monitorHandler.postDelayed(this, delay);
        }
    };

    private final Runnable reassertIfLeftRunnable = () -> {
        // Only re-open if we truly left this screen (not focus glitches from dialogs/lock-task).
        if (activityResumed && windowHasFocus) return;
        if (!paymentLockActive()) return;
        if (DeviceControls.isStkPromptExempt(MainActivity.this)) return;
        DeviceControls.openPaymentScreenNow(MainActivity.this);
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
        // Pin once on open so the screen cannot be left, without thrashing touches.
        pinPaymentScreenOnce();
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
        activityResumed = true;
        monitorHandler.removeCallbacks(reassertIfLeftRunnable);
        if (openLockScreenIfFullLockActive()) return;
        DeviceControls.enforceFinancedDeviceHardening(this);
        DeviceControls.protectAppFromUninstall(this);
        Policy policy = KismartApi.lastPolicy(this);
        if (policy != null) {
            if (DeviceControls.enforceMissingProtectionGuard(this, policy)) return;
            latestPolicy = policy;
            renderPolicy(policy);
            // Apply restrictions without repeatedly restarting lock-task while user is here.
            pinPaymentScreenOnce();
        }
        ensurePayButtonClickable();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        super.onPause();
        // Unpaid: if user actually leaves (Home/Recents), pull back after a short settle delay.
        // Do not fire immediately — that restarts MainActivity and kills Pay button taps.
        scheduleReturnIfLeft(500L);
    }

    @Override
    public void onBackPressed() {
        // Unpaid: never leave payment screen (STK PIN is handled by system dialog on top).
        if (paymentLockActive()) {
            setDetail("Payment required. Tap Pay via M-Pesa and complete payment to unlock.");
            ensurePayButtonClickable();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (paymentLockActive()
                && (keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_APP_SWITCH
                || keyCode == KeyEvent.KEYCODE_HOME)) {
            setDetail("Payment required. Tap Pay via M-Pesa and complete payment to unlock.");
            ensurePayButtonClickable();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (paymentLockActive()
                && (keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_APP_SWITCH
                || keyCode == KeyEvent.KEYCODE_HOME)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean moveTaskToBack(boolean nonRoot) {
        if (paymentLockActive() && !DeviceControls.isStkPromptExempt(this)) {
            setDetail("Payment required. Tap Pay via M-Pesa and complete payment to unlock.");
            ensurePayButtonClickable();
            return true;
        }
        return super.moveTaskToBack(nonRoot);
    }

    @Override
    public void finish() {
        // Unpaid: do not close this screen until M-Pesa payment is confirmed.
        if (paymentLockActive()) {
            setDetail("Payment required. Tap Pay via M-Pesa and complete payment to unlock.");
            ensurePayButtonClickable();
            return;
        }
        super.finish();
    }

    /** Only path allowed to close MainActivity while balance remains (e.g. full lock handoff). */
    private void forceFinish() {
        super.finish();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        scheduleReturnIfLeft(350L);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        windowHasFocus = hasFocus;
        if (hasFocus) {
            monitorHandler.removeCallbacks(reassertIfLeftRunnable);
            ensurePayButtonClickable();
            return;
        }
        // Focus loss from AlertDialog / keyboard / STK must not restart this activity.
        if (DeviceControls.isStkPromptExempt(this)) return;
        if (paymentLockActive()) {
            scheduleReturnIfLeft(700L);
        }
    }

    /** True while any financed balance remains — screen cannot be left until paid. */
    private boolean paymentLockActive() {
        Policy policy = latestPolicy != null ? latestPolicy : KismartApi.lastPolicy(this);
        return DeviceControls.mustStayOnPaymentScreen(policy);
    }

    /**
     * Pin lock-task / payment restrictions once while unpaid.
     * Avoid re-calling startLockTask on every focus tick — that blocks Pay button clicks.
     */
    private void pinPaymentScreenOnce() {
        if (!paymentLockActive()) {
            lockTaskPinned = false;
            return;
        }
        if (DeviceControls.isStkPromptExempt(this)) return;
        Policy policy = latestPolicy != null ? latestPolicy : KismartApi.lastPolicy(this);
        if (policy == null) return;
        if (!lockTaskPinned) {
            DeviceControls.applyPolicy(this, policy);
            lockTaskPinned = true;
        }
    }

    private void scheduleReturnIfLeft(long delayMs) {
        if (!paymentLockActive()) return;
        if (DeviceControls.isStkPromptExempt(this)) return;
        monitorHandler.removeCallbacks(reassertIfLeftRunnable);
        monitorHandler.postDelayed(reassertIfLeftRunnable, delayMs);
    }

    /** Keep the green Pay button tappable whenever an amount is due. */
    private void ensurePayButtonClickable() {
        if (paymentButton == null) return;
        paymentButton.bringToFront();
        paymentButton.setClickable(true);
        paymentButton.setFocusable(true);
        if (!paymentPending) {
            int amount = suggestedStkAmount(latestPolicy != null ? latestPolicy : KismartApi.lastPolicy(this));
            if (amount > 0) {
                paymentButton.setEnabled(true);
                paymentButton.setTextColor(WHITE);
                paymentButton.setBackground(panelBg(GREEN, GREEN_DARK, 0, 6));
            }
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
        forceFinish();
        return true;
    }

    @Override
    protected void onDestroy() {
        monitorHandler.removeCallbacks(monitorRunnable);
        monitorHandler.removeCallbacks(paymentPollRunnable);
        monitorHandler.removeCallbacks(reassertIfLeftRunnable);
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

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setBackground(panelBg(SOFT, LINE, 1, 8));
        logo.setContentDescription("KISMART");
        // Hidden admin path only when account is paid. While unpaid, only Pay via M-Pesa is allowed.
        logo.setOnLongClickListener(view -> {
            if (paymentLockActive()) {
                setDetail("Payment required. Tap Pay via M-Pesa and complete payment to unlock.");
                ensurePayButtonClickable();
                return true;
            }
            showAdminUnlock();
            return true;
        });
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        logoParams.setMargins(0, 0, dp(12), 0);
        header.addView(logo, logoParams);

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
        // Allow children (Pay button) to receive taps without parent intercept.
        panel.setClickable(false);
        panel.setFocusable(false);

        paymentButton = actionButton("Pay Now", true, view -> showStkPrompt());
        paymentButton.setTextSize(18);
        paymentButton.setClickable(true);
        paymentButton.setFocusable(true);
        paymentButton.setEnabled(true);
        panel.addView(paymentButton, blockParams(0, 10, dp(58)));
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
                .setMessage("Correct passcode opens Admin Setup only. Payment limit stays on for normal phone use until the account is paid.")
                .setView(pin)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open", (dialog, which) -> {
                    String value = pin.getText() == null ? "" : pin.getText().toString().trim();
                    SharedPreferences prefs = KismartApi.prefs(this);
                    String deviceSecret = prefs.getString(KismartApi.KEY_SECRET, "");
                    if (ADMIN_PIN.equals(value)
                            || KismartApi.DEFAULT_DEVICE_SECRET.equals(value)
                            || (deviceSecret != null && !deviceSecret.isEmpty() && value.equals(deviceSecret))) {
                        DeviceControls.grantAdminSession(this);
                        Intent intent = new Intent(this, AdminSetupActivity.class);
                        intent.putExtra(EXTRA_ADMIN_VERIFIED, true);
                        startActivity(intent);
                        setDetail("Admin Setup open. Leave setup to resume payment limit.");
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

        // Known phone on file → send STK immediately (only action is the green Pay button).
        String knownPhone = latestPolicy.customerPhone == null ? "" : latestPolicy.customerPhone.trim();
        if (!knownPhone.isEmpty()) {
            submitStk(amount, knownPhone);
            return;
        }

        // Phone missing on account: collect it once, then STK. No cancel-to-leave path.
        EditText phoneInput = new EditText(this);
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneInput.setSingleLine(true);
        phoneInput.setHint("07XXXXXXXX");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Pay " + formatKes(amount) + " via M-Pesa")
                .setMessage("Enter the M-Pesa phone number. You must complete payment to leave this screen.")
                .setView(phoneInput)
                .setCancelable(false)
                .setPositiveButton("Send STK", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String phone = phoneInput.getText() == null ? "" : phoneInput.getText().toString().trim();
            if (phone.isEmpty()) {
                setDetail("Enter a valid M-Pesa phone number.");
                return;
            }
            dialog.dismiss();
            submitStk(amount, phone);
        }));
        dialog.show();
    }

    private void submitStk(int amount, String phoneNumber) {
        if (paymentButton != null) paymentButton.setEnabled(false);
        setDetail("Sending M-Pesa STK prompt...");
        paymentBalanceBefore = latestPolicy == null ? 0 : latestPolicy.balance;
        // ~10s STK PIN window only; then confirm payment (~3s) or restore limit.
        lockTaskPinned = false;
        DeviceControls.markStkPromptActive(this, 10_000L);
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
                    lockTaskPinned = false;
                    DeviceControls.markStkPromptActive(this, 10_000L);
                    if (policyToApply != null) {
                        latestPolicy = policyToApply;
                        renderPolicy(policyToApply);
                    }
                    paymentPending = true;
                    paymentPollAttempts = 0;
                    updatePaymentButton(latestPolicy);
                    setDetail(message + (sentTo == null || sentTo.isEmpty() ? "" : " (" + sentTo + ")"));
                    // First confirm check ~3s after STK is sent (catches fast PIN entry).
                    schedulePaymentPoll(3000);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    paymentPending = false;
                    DeviceControls.clearStkPromptExempt(this);
                    lockTaskPinned = false;
                    pinPaymentScreenOnce();
                    updatePaymentButton(latestPolicy);
                    ensurePayButtonClickable();
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
        setDetail("Checking M-Pesa confirmation... (" + paymentPollAttempts + ")");
        executor.execute(() -> {
            try {
                Policy policy = KismartApi.sync(this);
                runOnUiThread(() -> {
                    renderPolicy(policy);
                    boolean paidDown = policy.balance < paymentBalanceBefore;
                    boolean cleared = policy.balance <= 0;
                    if (paidDown || cleared) {
                        paymentPending = false;
                        monitorHandler.removeCallbacks(paymentPollRunnable);
                        DeviceControls.clearStkPromptExempt(this);
                        // Apply updated policy: only full clear (balance 0) releases the screen.
                        lockTaskPinned = false;
                        DeviceControls.applyPolicy(this, policy);
                        if (!cleared) lockTaskPinned = true;
                        updatePaymentButton(policy);
                        ensurePayButtonClickable();
                        if (cleared) {
                            setDetail("Payment confirmed. Full access restored — you may leave this screen.");
                        } else {
                            setDetail("Payment received. Remaining balance " + formatKes(policy.balance)
                                    + ". Stay on this screen and pay the rest to unlock.");
                        }
                        return;
                    }
                    if (policy.isPendingStkFailed()) {
                        paymentPending = false;
                        monitorHandler.removeCallbacks(paymentPollRunnable);
                        DeviceControls.clearStkPromptExempt(this);
                        lockTaskPinned = false;
                        pinPaymentScreenOnce();
                        updatePaymentButton(policy);
                        ensurePayButtonClickable();
                        String fail = policy.pendingStkMessage;
                        if (fail == null || fail.trim().isEmpty()) {
                            fail = "M-Pesa did not confirm the payment.";
                        }
                        setDetail("Payment not confirmed: " + fail + " Limit is on.");
                        return;
                    }
                    // STK window ended and still unpaid → force limit + stay on Pay.
                    if (!DeviceControls.isStkPromptExempt(this) && policy.balance > 0) {
                        lockTaskPinned = false;
                        DeviceControls.resumePaymentLimitAfterStk(this);
                        lockTaskPinned = true;
                    }
                    // ~10s STK + a few 3s checks (≈ attempts 1@3s, 2@6s, 3@9s, 4@12s, 5@15s).
                    if (paymentPollAttempts >= 5) {
                        paymentPending = false;
                        monitorHandler.removeCallbacks(paymentPollRunnable);
                        DeviceControls.clearStkPromptExempt(this);
                        lockTaskPinned = false;
                        pinPaymentScreenOnce();
                        updatePaymentButton(policy);
                        ensurePayButtonClickable();
                        setDetail("Payment not confirmed. Limit is on. Stay on this screen and tap Pay to try again.");
                        return;
                    }
                    schedulePaymentPoll(3000);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (DeviceControls.isStkPromptExempt(this) && paymentPollAttempts < 5) {
                        schedulePaymentPoll(3000);
                        return;
                    }
                    if (paymentPollAttempts >= 5) {
                        paymentPending = false;
                        DeviceControls.clearStkPromptExempt(this);
                        lockTaskPinned = false;
                        pinPaymentScreenOnce();
                        updatePaymentButton(latestPolicy);
                        ensurePayButtonClickable();
                        setDetail("Could not confirm payment. Limit stays on.");
                    } else {
                        schedulePaymentPoll(3000);
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
            paymentButton.setEnabled(false);
            paymentButton.setClickable(false);
            paymentButton.setTextColor(MUTED);
            paymentButton.setBackground(panelBg(SOFT, LINE, 1, 6));
        } else if (canPay) {
            paymentButton.setText("Pay " + formatKes(amount) + " via M-Pesa");
            paymentButton.setEnabled(true);
            paymentButton.setClickable(true);
            paymentButton.setFocusable(true);
            paymentButton.setTextColor(WHITE);
            paymentButton.setBackground(panelBg(GREEN, GREEN_DARK, 0, 6));
        } else {
            paymentButton.setText("No Payment Due");
            paymentButton.setEnabled(false);
            paymentButton.setClickable(false);
            paymentButton.setTextColor(MUTED);
            paymentButton.setBackground(panelBg(SOFT, LINE, 1, 6));
        }
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
