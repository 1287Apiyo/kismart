package africa.volo.kismart.agent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        Policy policy = KismartApi.lastPolicy(this);
        // Unpaid debt or full lock → never leave KISMART control surfaces.
        Class<?> target;
        if (DeviceControls.isFullLockPolicy(policy)) {
            target = LockActivity.class;
        } else {
            // Payment screen is the only customer-facing UI while balance remains.
            target = MainActivity.class;
        }
        Intent intent = new Intent(this, target);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("kismart_payment_lock", DeviceControls.mustStayOnPaymentScreen(policy));
        startActivity(intent);
        finish();
    }
}
