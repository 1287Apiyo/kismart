# KISMART Device Agent

Native Android pilot agent for syncing financed-device policy from the KISMART backend.
iOS devices do not use this APK; supervised iPhones use the backend Apple MDM bridge instead.

This pilot APK allows local `http://` traffic so phones can reach a laptop backend during testing. Production deployments should use HTTPS and remove broad cleartext access.

## What It Can Test Now

- Register a device by entering the backend URL and the financed device IMEI.
- Enable Android Device Admin for screen-lock testing.
- Pull policy from `/api/devices/:imei/policy`.
- Acknowledge pending lock/restore commands through `/api/devices/:imei/sync`.
- Keep enforcing the last synced admin policy while offline, then apply queued admin commands when the phone regains any internet path to the public control URL.
- Use a fast sync path so the phone does not wait on a full Firestore collection save.
- Send a phone identity bundle on every device request: app install ID, Android ID, build fingerprint, server-issued binding token, manufacturer, brand, model, and SDK level.
- Report tamper/removal attempts through `/api/devices/:imei/tamper`.
- Apply `Limited access` as KISMART-only mode in Device Owner mode: KISMART stays open and other launchable user apps are suspended until restore.
- Show an in-app **Pay** action that starts a **real M-Pesa STK Push** through the backend (customer enters PIN on the Safaricom prompt).
- Apply a strict black full-screen lock surface when the backend policy is `Full lock`.
- Keep checking the backend for admin restore while locked.
- In Device Owner mode, restrict app uninstall/control, credential settings, safe boot, app installs, and user/account changes during full lock.
- Keep payment figures backend-authoritative; the phone app does not accept customer-entered amounts or trusted balances.
- Protect against IMEI spoofing by binding the first trusted sync to the handset Android ID + binding token. Reinstalls on the **same** phone recover automatically. A **different** physical phone using the same IMEI is blocked until admin Reset ID.

## Platform Reality

Normal Android Device Admin can test screen locking and show the full-screen lock surface, but it cannot fully prevent app removal, Settings control, recovery-mode wipes, or all phone functions. The strict "only admin can unlock" flow requires Android Device Owner provisioning. A physical factory reset removes ordinary user-installed APKs; production devices that must recover protection after a reset need managed enrollment such as Android zero-touch/QR provisioning, an EMM/MDM enrollment flow, or an OEM/system-app build. Supervised iPhones require Apple MDM enrollment.

## Test Flow

The built debug APK is:

```text
device-agent/dist/KismartDeviceAgent-debug.apk
```

1. Run the backend on the laptop:

   ```powershell
   cd "C:\Users\Volo\Documents\New project 2\system"
   node --env-file-if-exists=.env server.ts
   ```

2. Build and install this Android project from Android Studio.

3. On a real phone, use the **public HTTPS control URL** so lock/restore works from mobile data and any Wi-Fi (not only the shop LAN):

   ```text
   https://kismartsystem.vercel.app
   ```

   Temporary laptop-only testing can still use a LAN IP such as `http://192.168.x.x:8787`. After the first successful sync the agent adopts the server `controlEndpoint` public URL automatically.

   On the Android emulator against a laptop backend, use:

   ```text
   http://10.0.2.2:8787
   ```

4. In the app, enter an IMEI that exists in the dashboard, for example:

   ```text
   357527486213862
   ```

   Device sync secret (must match `KISMART_DEVICE_SYNC_SECRET` on the backend):

   ```text
   4321
   ```

5. Tap **Use Demo Values** if you want the app to fill these values automatically.

6. Tap **Enable Device Admin**.

7. Tap **Start Monitor**. The agent will sync with the backend every 5 seconds.

8. After the first successful sync, the backend marks that contract as **Identity locked** and gives the app a private binding token. In the app, **Latest Policy** should show `Identity Bound`.

9. In the dashboard, open **Device Operations** and apply **KISMART only** to test app-only mode, or **Full lock** to test the black lock screen.

10. Keep the phone app open for the first test. `Limit` should keep the phone inside KISMART and block other apps. The in-app **Pay** button sends a real M-Pesa STK prompt to the customer's phone number; payment is applied only after Safaricom confirms via the production callback. `Full lock` opens a black lock screen. Admin commands stay queued if the phone is offline and are confirmed after the agent receives them and reports the applied command IDs on the next successful sync. In Device Owner mode, Home, Recents, credential/settings paths, app control, and uninstall paths are restricted more aggressively.

11. Record a payment or press **Restore** in the dashboard, then tap **Sync Now** again to clear the restriction.

If a legitimate replacement phone, factory reset, app data wipe, or repair changes the identity, use **Device Operations** -> **Reset ID** only after verifying the physical handset.

## Production Provisioning Note

For deeper Android control, provision the app as Device Owner on a wiped test phone before normal setup:

```powershell
adb shell dpm set-device-owner africa.volo.kismart.agent/.KismartDeviceAdminReceiver
```

This requires Android platform tools, USB debugging, no configured accounts on the phone, and a supported provisioning state.

The `KISMART only`, uninstall-block, and factory-reset restriction paths require Android Device Owner for strict enforcement. Normal Device Admin can test lock commands, but Android will still allow the user to leave the app or remove protection after deactivating admin.
