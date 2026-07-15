package africa.volo.kismart.agent;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AdminSetupActivity extends Activity {
    public static final String SETUP_URI = "device-service://setup";
    private static final String ADMIN_PIN = "4321";
    private static final int BLACK = Color.rgb(14, 18, 16);
    private static final int GREEN = Color.rgb(22, 163, 74);
    private static final int GREEN_DARK = Color.rgb(21, 128, 61);
    private static final int LINE = Color.rgb(224, 229, 226);
    private static final int SOFT = Color.rgb(246, 248, 247);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(92, 99, 95);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText passcode;
    private TextView status;
    private EditText serverUrl;
    private EditText imei;
    private EditText secret;
    private TextView adminStatus;
    private boolean verified;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        DeviceControls.hideLauncherEntry(this);
        DeviceControls.protectAppFromUninstall(this);
        verified = getIntent().getBooleanExtra(AdminSetupReceiver.ACTION_EXTRA_ADMIN_VERIFIED, false);
        render();
    }

    private void render() {
        if (verified) {
            setContentView(buildSetupUi());
            loadPrefs();
            updateAdminStatus();
        } else {
            setContentView(buildPasscodeUi());
        }
    }

    private View buildPasscodeUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(WHITE);

        TextView title = text("Device Service", 24, BLACK, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(0, 8));

        TextView subtitle = text("Admin setup", 14, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, matchWrap(0, 18));

        passcode = new EditText(this);
        passcode.setHint("Admin passcode");
        passcode.setSingleLine(true);
        passcode.setTextSize(16);
        passcode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(passcode, matchHeight(0, 12, dp(52)));

        Button open = new Button(this);
        open.setText("Open Setup");
        open.setAllCaps(false);
        open.setTextColor(WHITE);
        open.setTextSize(15);
        open.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        open.setBackgroundColor(GREEN);
        open.setOnClickListener(view -> verifyPasscode());
        root.addView(open, matchHeight(0, 12, dp(52)));

        status = text("", 13, MUTED, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status, matchWrap(0, 0));

        return root;
    }

    private View buildSetupUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(WHITE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));
        scroll.addView(root);

        TextView title = text("Admin Setup", 22, BLACK, true);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        adminStatus = text("", 13, MUTED, false);
        adminStatus.setPadding(0, 0, 0, dp(14));
        root.addView(adminStatus);

        serverUrl = input("Backend URL", KismartApi.DEFAULT_SERVER_URL);
        imei = input("Registered device IMEI", KismartApi.DEFAULT_IMEI);
        secret = input("Device sync secret", "Required");
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        
        root.addView(serverUrl);
        root.addView(imei);
        root.addView(secret);

        root.addView(actionRow(
                actionButton("Save", true, view -> savePrefs()),
                actionButton("Sync Now", true, view -> syncNow())
        ));

        root.addView(actionRow(
                actionButton("Enable Admin", false, view -> DeviceControls.requestAdmin(this)),
                actionButton("Accessibility", false, view -> DeviceControls.openAccessibilitySettings(this))
        ));

        status = text("Ready.", 13, MUTED, false);
        status.setPadding(0, dp(10), 0, 0);
        root.addView(status);

        return scroll;
    }

    private void verifyPasscode() {
        String value = passcode.getText().toString().trim();
        SharedPreferences prefs = KismartApi.prefs(this);
        String storedSecret = prefs.getString(KismartApi.KEY_SECRET, "");
        if (ADMIN_PIN.equals(value) || KismartApi.DEFAULT_DEVICE_SECRET.equals(value) || (storedSecret != null && storedSecret.equals(value))) {
            verified = true;
            render();
        } else {
            status.setText("Admin access denied.");
        }
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
        status.setText("Setup saved.");
    }

    private void syncNow() {
        savePrefs();
        status.setText("Syncing account...");
        executor.execute(() -> {
            try {
                Policy policy = KismartApi.sync(this);
                runOnUiThread(() -> {
                    DeviceControls.applyPolicy(this, policy);
                    updateAdminStatus();
                    status.setText("Account synced. " + policy.customer);
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Sync failed: " + error.getMessage()));
            }
        });
    }

    private void updateAdminStatus() {
        if (adminStatus == null) return;
        boolean admin = DeviceControls.isAdminActive(this);
        boolean owner = DeviceControls.isDeviceOwner(this);
        boolean guard = DeviceControls.isAccessibilityGuardEnabled(this);
        String mode = owner ? "Device Owner" : guard ? "Accessibility Guard" : admin ? "Device Admin" : "Not enabled";
        Policy policy = KismartApi.lastPolicy(this);
        String policyState = policy != null && policy.restrictionActive
                ? "Restricted: " + policy.restrictionLevel
                : "Restricted: Off";
        adminStatus.setText("Control mode: " + mode + "\n" + (guard ? "Accessibility: On" : "Accessibility: Off") + "\n" + policyState);
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

    private EditText input(String label, String hint) {
        EditText input = new EditText(this);
        input.setHint(label + " - " + hint);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(BLACK);
        input.setHintTextColor(Color.rgb(132, 132, 132));
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackground(panelBg(SOFT, LINE, 1, 6));
        input.setLayoutParams(matchHeight(0, 10, dp(46)));
        return input;
    }

    private Button actionButton(String label, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? WHITE : BLACK);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setBackground(panelBg(primary ? GREEN : WHITE, primary ? GREEN : LINE, 1, 6));
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout actionRow(Button first, Button second) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(first, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        secondParams.setMargins(dp(10), 0, 0, 0);
        row.addView(second, secondParams);
        row.setLayoutParams(matchHeight(0, 10, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private GradientDrawable panelBg(int fill, int stroke, int strokeWidthDp, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeWidthDp > 0) drawable.setStroke(dp(strokeWidthDp), stroke);
        return drawable;
    }

    private TextView text(String value, int size, int color, boolean strong) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (strong) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
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
