package africa.volo.kismart.agent;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class KismartDeviceAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        restartProtection(context);
        lockAppFromUninstall(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            restartProtection(context);
            lockAppFromUninstall(context);
        }
    }

    @Override
    public String onDisableRequested(Context context, Intent intent) {
        DeviceControls.protectAppFromUninstall(context);
        return "Device Service protects a financed phone. Removing this administrator can allow uninstall, force stop, and account-control bypass.";
    }

    private void restartProtection(Context context) {
        DeviceControls.enforceFinancedDeviceHardening(context);
        DeviceControls.protectAppFromUninstall(context);
        DeviceControls.hideLauncherEntry(context);
        AgentSyncService.start(context);
    }

    private void lockAppFromUninstall(Context context) {
        DeviceControls.protectAppFromUninstall(context);
    }
}
