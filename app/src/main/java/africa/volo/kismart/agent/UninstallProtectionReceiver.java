package africa.volo.kismart.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

/**
 * Receiver that blocks uninstall attempts by intercepting the PACKAGE_REMOVED and PACKAGE_FULLY_REMOVED broadcasts.
 * Once KISMART agent is installed as device admin / device owner, it cannot be uninstalled without
 * first removing the device admin status (which is blocked by onDisableRequested in KismartDeviceAdminReceiver).
 */
public class UninstallProtectionReceiver extends BroadcastReceiver {
    static final String ACTION_RESTART_PROTECTION = "africa.volo.kismart.agent.RESTART_PROTECTION";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        
        String action = intent.getAction();
        if (action == null) return;

        if (ACTION_RESTART_PROTECTION.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            restartProtection(context);
            return;
        }
        
        // Monitor for uninstall attempts on this package
        if (Intent.ACTION_PACKAGE_REMOVED.equals(action) || 
            Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) {
            
            Uri uri = intent.getData();
            if (uri != null) {
                String packageName = uri.getSchemeSpecificPart();
                if (context.getPackageName().equals(packageName)) {
                    // Block removal if device admin is active
                    abortBroadcast();
                }
            }
        }
        
        // Monitor for uninstall prompts
        if (Intent.ACTION_PACKAGE_NEEDS_VERIFICATION.equals(action)) {
            Uri uri = intent.getData();
            if (uri != null) {
                String packageName = uri.getSchemeSpecificPart();
                if (context.getPackageName().equals(packageName)) {
                    // Reject package verification/uninstall
                    abortBroadcast();
                }
            }
        }
    }

    private void restartProtection(Context context) {
        DeviceControls.enforceFinancedDeviceHardening(context);
        DeviceControls.protectAppFromUninstall(context);
        DeviceControls.hideLauncherEntry(context);
        try {
            AgentSyncService.start(context);
        } catch (Exception ignored) {
        }
    }
}
