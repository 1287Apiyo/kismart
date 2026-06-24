package africa.volo.kismart.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AdminSetupReceiver extends BroadcastReceiver {
    static final String ACTION_EXTRA_OPEN_ADMIN_SETUP = "open_admin_setup";

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        launch.putExtra(ACTION_EXTRA_OPEN_ADMIN_SETUP, true);
        try {
            context.startActivity(launch);
        } catch (Exception ignored) {
        }
    }
}
