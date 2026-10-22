package com.hmdm.launcher.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.Initializer;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.util.RemoteLogger;

/**
 * Phoenix custom build: bring the agent back after it updates ITSELF.
 *
 * Upstream counts on Android relaunching the HOME app once its package is replaced (and, on EMUI, on
 * the restarter helper app). Under the stock Lenovo home the launcher is not the home, so a self-update
 * killed its process and nothing started it again until the next reboot: no push long-polling, no
 * hourly sync, no commands. PHX-TAB-0008 went silent that way at 6.39 -> 6.41 (22 Oct 2026).
 *
 * MY_PACKAGE_REPLACED is delivered only to this package, to its NEW version, right after the update,
 * and it is exempt from the Android 8 implicit-broadcast limits, so a manifest receiver gets it. It
 * re-runs the boot initialisation (push service, location service, config load). The remote-access app
 * is NOT launched on this path: the launcher's update never stopped it, and opening it in the middle
 * of the day would flash it on the rep's screen.
 */
public class PackageReplacedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            return;
        }
        Log.i(Const.LOG_TAG, "Got the MY_PACKAGE_REPLACED broadcast");

        SettingsHelper settingsHelper = SettingsHelper.getInstance(context.getApplicationContext());
        if (!settingsHelper.isBaseUrlSet()) {
            // Not enrolled yet (replaced before the first setup finished): the setup flow starts us.
            return;
        }
        RemoteLogger.log(context, Const.LOG_INFO, "phoenix: launcher updated to " + BuildConfig.VERSION_NAME + ", restarting its services");

        Initializer.init(context, () -> {
            Initializer.startServicesAndLoadConfig(context, false);

            SettingsHelper.getInstance(context).setMainActivityRunning(false);
            if (ProUtils.kioskModeRequired(context)) {
                Log.i(Const.LOG_TAG, "Kiosk mode required, forcing Headwind MDM to run in the foreground");
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(homeIntent);
            }
        });
    }
}
