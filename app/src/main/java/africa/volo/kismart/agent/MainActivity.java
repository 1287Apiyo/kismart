package africa.volo.kismart.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
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
    private static final String ADMIN_PIN = "4321";
    private static final String EXTRA_OPEN_ADMIN_SETUP = AdminSetupReceiver.ACTION_EXTRA_OPEN_ADMIN_SETUP;
    private static final String EXTRA_ADMIN_VERIFIED = AdminSetupReceiver.ACTION_EXTRA_ADMIN_VERIFIED;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler monitorHandler = new Handler(Looper.getMainLooper());
    private final Runnable paymentPollRunnable = this::pollPaymentStatus;
    private TextView amountView;
    private TextView dueView;
    private LinearLayout customerValue;
    private LinearLayout phoneValue;
    private LinearLayout balanceValue;
    private TextView arrearsValue;
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
                UiTheme.stylePrimaryCta(paymentButton, this, true);
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
        scroll.setBackgroundColor(UiTheme.SURFACE);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // Extra top inset so content sits below the status bar and does not feel cramped.
        root.setPadding(dp(20), dp(32), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        root.addView(header());
        root.addView(amountCard());
        root.addView(accountCard());
        root.addView(primaryActions());
        root.addView(statusStrip());
        return scroll;
    }

    private View header() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        // More breathing room under the brand row before the first card.
        header.setPadding(0, dp(4), 0, dp(28));

        ImageView logo = UiTheme.logo(this, 44);
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
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        logoParams.setMargins(0, 0, dp(12), 0);
        header.addView(logo, logoParams);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        header.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        copy.addView(UiTheme.sectionLabel(this, "KISMART"));
        TextView title = UiTheme.text(this, "Device account", 20, UiTheme.INK, true);
        title.setPadding(0, dp(6), 0, dp(4));
        copy.addView(title);
        copy.addView(UiTheme.text(this, "Complete payment to restore full access", 13, UiTheme.MUTED, false));
        return header;
    }

    private View amountCard() {
        LinearLayout panel = UiTheme.cardContainer(this);
        panel.setLayoutParams(blockParams(0, 16));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(UiTheme.sectionLabel(this, "Amount due"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        accountStatusView = UiTheme.statusPill(this, "Syncing", true);
        top.addView(accountStatusView);
        panel.addView(top);

        amountView = UiTheme.text(this, "Ksh 0", 36, UiTheme.INK, true);
        amountView.setPadding(0, dp(14), 0, dp(8));
        if (Build.VERSION.SDK_INT >= 21) amountView.setLetterSpacing(-0.02f);
        panel.addView(amountView);

        dueView = UiTheme.text(this, "Due date · Not synced", 14, UiTheme.MUTED, false);
        dueView.setPadding(0, 0, 0, dp(6));
        panel.addView(dueView);

        arrearsValue = UiTheme.text(this, "Arrears · Ksh 0", 13, UiTheme.MUTED, false);
        panel.addView(arrearsValue);
        return panel;
    }

    private View accountCard() {
        LinearLayout panel = UiTheme.cardContainer(this);
        // Space below account details before the pay button block.
        panel.setLayoutParams(blockParams(0, 8));

        panel.addView(UiTheme.sectionLabel(this, "Account details"));
        panel.addView(spacer(10));

        customerValue = metaLine("Customer", "—");
        phoneValue = metaLine("M-Pesa phone", "—");
        balanceValue = metaLine("Total balance", "Ksh 0");
        panel.addView(customerValue);
        panel.addView(UiTheme.hairline(this));
        panel.addView(phoneValue);
        panel.addView(UiTheme.hairline(this));
        panel.addView(balanceValue);
        return panel;
    }

    private LinearLayout metaLine(String label, String value) {
        return UiTheme.metaRow(this, label, value);
    }

    private View primaryActions() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        // Clear vertical space around the primary CTA.
        panel.setPadding(0, dp(20), 0, dp(20));
        panel.setClickable(false);
        panel.setFocusable(false);

        paymentButton = UiTheme.primaryButton(this, "Pay via M-Pesa", view -> showStkPrompt());
        paymentButton.setTextSize(16);
        paymentButton.setClickable(true);
        paymentButton.setFocusable(true);
        paymentButton.setEnabled(true);
        // Extra margin above and below the green pay button itself.
        panel.addView(paymentButton, blockParams(4, 14, dp(56)));

        TextView helper = UiTheme.text(this, "You will receive an M-Pesa STK prompt. Enter your PIN to confirm.", 12, UiTheme.MUTED, false);
        helper.setPadding(dp(2), dp(4), dp(2), dp(4));
        helper.setLineSpacing(dp(2), 1.05f);
        panel.addView(helper);
        return panel;
    }

    private View statusStrip() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(UiTheme.softCard(this));
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        // Clear gap from the pay section above.
        panel.setLayoutParams(blockParams(8, 0));

        panel.addView(UiTheme.sectionLabel(this, "Status"));
        statusDetail = UiTheme.text(this, "Monitoring account for payment confirmation.", 13, UiTheme.INK_SOFT, false);
        statusDetail.setPadding(0, dp(8), 0, 0);
        statusDetail.setLineSpacing(dp(2), 1.1f);
        panel.addView(statusDetail);
        return panel;
    }

    private View spacer(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return v;
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
            if (amountView != null) amountView.setText("Ksh 0");
            if (dueView != null) dueView.setText("Due date · Not synced");
            setStatusPill("Syncing", true);
            if (arrearsValue != null) arrearsValue.setText("Arrears · Ksh 0");
            setMeta(customerValue, "Customer", "—");
            setMeta(phoneValue, "M-Pesa phone", "—");
            setMeta(balanceValue, "Total balance", "Ksh 0");
            updatePaymentButton(null);
            return;
        }
        if (amountView != null) amountView.setText(formatKes(suggestedStkAmount(policy)));
        if (dueView != null) dueView.setText("Due date · " + dueDate(policy));
        setStatusPill(accountState(policy), isRestrictedState(policy));
        if (arrearsValue != null) arrearsValue.setText("Arrears · " + formatKes(policy.arrears));
        setMeta(customerValue, "Customer", policy.customer == null || policy.customer.trim().isEmpty() ? "Customer" : policy.customer.trim());
        String phone = policy.customerPhone == null || policy.customerPhone.trim().isEmpty()
                ? "On file"
                : policy.customerPhone.trim();
        setMeta(phoneValue, "M-Pesa phone", phone);
        setMeta(balanceValue, "Total balance", formatKes(policy.balance));
        updatePaymentButton(policy);
    }

    private void setMeta(LinearLayout row, String label, String value) {
        if (row == null || row.getChildCount() < 2) return;
        if (row.getChildAt(0) instanceof TextView) ((TextView) row.getChildAt(0)).setText(label);
        if (row.getChildAt(1) instanceof TextView) ((TextView) row.getChildAt(1)).setText(value);
    }

    private void setStatusPill(String label, boolean restricted) {
        if (accountStatusView == null) return;
        accountStatusView.setText(label);
        accountStatusView.setTextColor(restricted ? UiTheme.WARNING : UiTheme.SUCCESS);
        accountStatusView.setBackground(UiTheme.pill(
                restricted ? UiTheme.WARNING_SOFT : UiTheme.ACCENT_SOFT,
                restricted ? Color.rgb(253, 186, 116) : Color.rgb(167, 221, 188),
                this
        ));
    }

    private boolean isRestrictedState(Policy policy) {
        if (policy == null) return true;
        if (DeviceControls.isFullLockPolicy(policy)) return true;
        if (DeviceControls.isPaymentLimitActive(policy)) return true;
        return policy.balance > 0;
    }

    private String dueDate(Policy policy) {
        return policy.nextDue == null || policy.nextDue.trim().isEmpty() ? "Not set" : policy.nextDue.trim();
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
            paymentButton.setText("Waiting for M-Pesa…");
            UiTheme.stylePrimaryCta(paymentButton, this, false);
        } else if (canPay) {
            paymentButton.setText("Pay " + formatKes(amount) + " via M-Pesa");
            UiTheme.stylePrimaryCta(paymentButton, this, true);
        } else {
            paymentButton.setText("No payment due");
            UiTheme.stylePrimaryCta(paymentButton, this, false);
        }
    }

    private void setDetail(String value) {
        if (statusDetail != null) statusDetail.setText(value);
    }

    private void configureWindow() {
        getWindow().setStatusBarColor(UiTheme.SURFACE);
        getWindow().setNavigationBarColor(UiTheme.SURFACE);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
