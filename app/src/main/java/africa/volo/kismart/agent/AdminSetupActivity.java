package africa.volo.kismart.agent;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AdminSetupActivity extends Activity {
    public static final String SETUP_URI = "device-service://setup";
    private static final String ADMIN_PIN = "4321";

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
        configureWindow();
        DeviceControls.hideLauncherEntry(this);
        DeviceControls.protectAppFromUninstall(this);
        verified = getIntent().getBooleanExtra(AdminSetupReceiver.ACTION_EXTRA_ADMIN_VERIFIED, false)
                || DeviceControls.isAdminSessionActive(this);
        if (verified) {
            DeviceControls.grantAdminSession(this);
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (verified) {
            DeviceControls.grantAdminSession(this);
        }
    }

    @Override
    protected void onDestroy() {
        DeviceControls.clearAdminSession(this);
        super.onDestroy();
    }

    private void configureWindow() {
        getWindow().setStatusBarColor(UiTheme.SURFACE);
        getWindow().setNavigationBarColor(UiTheme.SURFACE);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
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
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(UiTheme.SURFACE);

        LinearLayout card = UiTheme.cardContainer(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView logo = UiTheme.logo(this, 64);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        logoParams.bottomMargin = dp(16);
        card.addView(logo, logoParams);

        TextView brand = UiTheme.sectionLabel(this, "KISMART");
        brand.setGravity(Gravity.CENTER);
        card.addView(brand);

        TextView title = UiTheme.text(this, "Admin access", 22, UiTheme.INK, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(8), 0, dp(4));
        card.addView(title);

        TextView subtitle = UiTheme.text(this, "Enter passcode to open device setup", 13, UiTheme.MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(18));
        card.addView(subtitle);

        passcode = UiTheme.field(this, "Admin passcode");
        passcode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(passcode, UiTheme.match(this, 0, 12, dp(50)));

        Button open = UiTheme.primaryButton(this, "Continue", view -> verifyPasscode());
        card.addView(open, UiTheme.match(this, 0, 10, dp(50)));

        status = UiTheme.text(this, "", 12, UiTheme.MUTED, false);
        status.setGravity(Gravity.CENTER);
        card.addView(status);

        root.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        return root;
    }

    private View buildSetupUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiTheme.SURFACE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(root);

        root.addView(setupHeader());
        root.addView(statusCard());
        root.addView(connectionCard());
        root.addView(actionsCard());
        root.addView(footerStatus());
        return scroll;
    }

    private View setupHeader() {
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(0, 0, 0, dp(16));

        ImageView logo = UiTheme.logo(this, 44);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        logoParams.setMargins(0, 0, dp(12), 0);
        brand.addView(logo, logoParams);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        brand.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        copy.addView(UiTheme.sectionLabel(this, "KISMART"));
        TextView title = UiTheme.text(this, "Admin setup", 20, UiTheme.INK, true);
        title.setPadding(0, dp(4), 0, 0);
        copy.addView(title);
        copy.addView(UiTheme.text(this, "Device connection and control", 13, UiTheme.MUTED, false));
        return brand;
    }

    private View statusCard() {
        LinearLayout card = UiTheme.cardContainer(this);
        card.setLayoutParams(UiTheme.matchWrap(this, 0, 12));
        card.addView(UiTheme.sectionLabel(this, "Control status"));
        adminStatus = UiTheme.text(this, "", 13, UiTheme.INK_SOFT, false);
        adminStatus.setPadding(0, dp(10), 0, 0);
        adminStatus.setLineSpacing(dp(3), 1.15f);
        card.addView(adminStatus);
        return card;
    }

    private View connectionCard() {
        LinearLayout card = UiTheme.cardContainer(this);
        card.setLayoutParams(UiTheme.matchWrap(this, 0, 12));
        card.addView(UiTheme.sectionLabel(this, "Connection"));
        card.addView(fieldBlock("Public control URL", serverUrl = UiTheme.field(this, KismartApi.DEFAULT_SERVER_URL)));
        card.addView(fieldBlock("Registered device IMEI", imei = UiTheme.field(this, KismartApi.DEFAULT_IMEI)));
        secret = UiTheme.field(this, "Device sync secret");
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(fieldBlock("Device sync secret", secret));

        TextView remoteHint = UiTheme.text(
                this,
                "Use the public HTTPS URL so lock and restore work on mobile data and any Wi‑Fi. Same-phone reinstalls recover identity automatically.",
                12,
                UiTheme.MUTED,
                false
        );
        remoteHint.setLineSpacing(dp(2), 1.15f);
        remoteHint.setPadding(0, dp(4), 0, 0);
        card.addView(remoteHint);
        return card;
    }

    private View fieldBlock(String label, EditText field) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(12), 0, 0);
        TextView caption = UiTheme.text(this, label, 12, UiTheme.MUTED, true);
        caption.setPadding(0, 0, 0, dp(6));
        block.addView(caption);
        block.addView(field, UiTheme.match(this, 0, 0, dp(50)));
        return block;
    }

    private View actionsCard() {
        LinearLayout card = UiTheme.cardContainer(this);
        card.setLayoutParams(UiTheme.matchWrap(this, 0, 12));
        card.addView(UiTheme.sectionLabel(this, "Actions"));
        card.addView(spacer(10));

        card.addView(actionRow(
                UiTheme.primaryButton(this, "Save", view -> savePrefs()),
                UiTheme.primaryButton(this, "Sync now", view -> syncNow())
        ));
        card.addView(actionRow(
                UiTheme.secondaryButton(this, "Enable admin", view -> DeviceControls.requestAdmin(this)),
                UiTheme.secondaryButton(this, "Accessibility", view -> DeviceControls.openAccessibilitySettings(this))
        ));

        Button exit = UiTheme.secondaryButton(this, "Exit admin · resume pay lock", view -> {
            DeviceControls.clearAdminSession(this);
            Policy policy = KismartApi.lastPolicy(this);
            if (policy != null) DeviceControls.applyPolicy(this, policy);
            finish();
        });
        card.addView(exit, UiTheme.match(this, 0, 0, dp(50)));
        return card;
    }

    private View footerStatus() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(UiTheme.softCard(this));
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        panel.setLayoutParams(UiTheme.matchWrap(this, 0, 0));
        panel.addView(UiTheme.sectionLabel(this, "Session"));
        status = UiTheme.text(this, "Admin session active. Payment lock pauses only while this screen is open.", 13, UiTheme.INK_SOFT, false);
        status.setPadding(0, dp(6), 0, 0);
        status.setLineSpacing(dp(2), 1.1f);
        panel.addView(status);
        return panel;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h)));
        return v;
    }

    private void verifyPasscode() {
        String value = passcode.getText() == null ? "" : passcode.getText().toString().trim();
        SharedPreferences prefs = KismartApi.prefs(this);
        String storedSecret = prefs.getString(KismartApi.KEY_SECRET, "");
        if (ADMIN_PIN.equals(value)
                || KismartApi.DEFAULT_DEVICE_SECRET.equals(value)
                || (storedSecret != null && !storedSecret.isEmpty() && storedSecret.equals(value))) {
            verified = true;
            DeviceControls.grantAdminSession(this);
            render();
        } else {
            status.setText("Access denied. Use the admin passcode or device sync secret.");
            status.setTextColor(UiTheme.DANGER);
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
        String url = serverUrl.getText().toString().trim();
        if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        KismartApi.prefs(this).edit()
                .putString(KismartApi.KEY_SERVER_URL, url)
                .putString(KismartApi.KEY_IMEI, imei.getText().toString().trim())
                .putString(KismartApi.KEY_SECRET, secret.getText().toString().trim())
                .apply();
        DeviceControls.hideLauncherEntry(this);
        AgentSyncService.start(this);
        updateAdminStatus();
        status.setTextColor(UiTheme.INK_SOFT);
        status.setText("Setup saved. Phone will poll for lock and restore from any network.");
    }

    private void syncNow() {
        savePrefs();
        status.setTextColor(UiTheme.INK_SOFT);
        status.setText("Syncing account…");
        executor.execute(() -> {
            try {
                Policy policy = KismartApi.sync(this);
                runOnUiThread(() -> {
                    DeviceControls.applyPolicy(this, policy);
                    updateAdminStatus();
                    status.setTextColor(UiTheme.INK_SOFT);
                    status.setText("Account synced · " + policy.customer);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    status.setTextColor(UiTheme.DANGER);
                    status.setText("Sync failed: " + error.getMessage());
                });
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
                ? "Restricted · " + policy.restrictionLevel
                : "Restricted · Off";
        adminStatus.setText(
                "Control mode · " + mode
                        + "\nAccessibility · " + (guard ? "On" : "Off")
                        + "\n" + policyState
        );
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

    private LinearLayout actionRow(Button first, Button second) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(first, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        secondParams.setMargins(dp(10), 0, 0, 0);
        row.addView(second, secondParams);
        row.setLayoutParams(UiTheme.match(this, 0, 10, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
