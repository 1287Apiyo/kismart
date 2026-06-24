package africa.volo.kismart.agent;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

public class AdminSetupActivity extends Activity {
    public static final String SETUP_URI = "device-service://setup";

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        Intent intent = getIntent();
        Uri data = intent == null ? null : intent.getData();
        if (data != null && SETUP_URI.equalsIgnoreCase(data.toString())) {
            Intent launch = new Intent(this, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launch.putExtra(AdminSetupReceiver.ACTION_EXTRA_OPEN_ADMIN_SETUP, true);
            startActivity(launch);
        }
        finish();
    }
}
