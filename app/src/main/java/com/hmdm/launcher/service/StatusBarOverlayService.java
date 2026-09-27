/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Phoenix custom build addition.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.launcher.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.ui.custom.BatteryStateView;
import com.hmdm.launcher.ui.custom.StatusBarUpdater;

/**
 * Phoenix custom build: the clean status bar for the STOCK Lenovo home.
 *
 * The owner requires the rep tablet to look like a brand-new Lenovo with NO management disclosures,
 * while still showing the clock, battery and wifi. Android forces "Privacy reminder" and "Location can
 * be accessed" onto the real status bar and no server toggle removes them, so as device owner we disable
 * the real bar entirely ({@link com.hmdm.launcher.util.Utils#setStatusBarDisabled}) and this service
 * draws a plain replacement over the top of the screen: a dark strip with the clock on the left and the
 * wifi and battery indicators on the right. It is a foreground service so the OS keeps it alive; its own
 * notification is invisible because the real bar and its shade are disabled.
 *
 * The strip is not touchable and not focusable, so the home screen behaves normally underneath it.
 */
public class StatusBarOverlayService extends Service {

    private static final String CHANNEL_ID = "phx_status_bar";
    private static final int NOTIFICATION_ID = 0xB0;
    private static final long WIFI_UPDATE_INTERVAL_MS = 10000;

    private WindowManager windowManager;
    private View barView;
    private ImageView wifiView;
    private final StatusBarUpdater statusBarUpdater = new StatusBarUpdater();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable wifiRunnable = new Runnable() {
        @Override
        public void run() {
            updateWifi();
            handler.postDelayed(this, WIFI_UPDATE_INTERVAL_MS);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        if (barView == null) {
            try {
                addOverlay();
            } catch (Exception e) {
                // No permission to draw over other windows (should not happen for a device owner),
                // or an OEM quirk. The status bar is still disabled; we just could not draw the
                // replacement. Log and carry on rather than crash-looping the service.
                Log.w(Const.LOG_TAG, "StatusBarOverlayService: could not add the overlay bar", e);
            }
        }
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();
        try {
            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "StatusBarOverlayService: startForeground failed", e);
        }
    }

    private void addOverlay() {
        windowManager = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return;
        }

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF000000);
        int padH = dp(8);
        bar.setPadding(padH, 0, padH, 0);

        TextView clock = new TextView(this);
        clock.setTextColor(0xFFFFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams clockParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(clock, clockParams);

        wifiView = new ImageView(this);
        wifiView.setImageResource(R.drawable.ic_phx_wifi);
        int icon = dp(16);
        LinearLayout.LayoutParams wifiParams = new LinearLayout.LayoutParams(icon, icon);
        wifiParams.rightMargin = dp(8);
        bar.addView(wifiView, wifiParams);

        BatteryStateView battery = new BatteryStateView(this);
        bar.addView(battery, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                statusBarHeight(),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        windowManager.addView(bar, params);
        barView = bar;

        // Clock + battery reuse the launcher's own status-bar logic; dark background => light (white) ink.
        statusBarUpdater.startUpdating(this, clock, battery);
        statusBarUpdater.updateControlsState(true, true);

        handler.removeCallbacks(wifiRunnable);
        handler.post(wifiRunnable);
    }

    private void updateWifi() {
        if (wifiView == null) {
            return;
        }
        boolean connected = false;
        try {
            WifiManager wifiManager =
                    (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                WifiInfo info = wifiManager.getConnectionInfo();
                connected = info != null && info.getNetworkId() != -1;
            }
        } catch (Exception e) {
            // Reading wifi state can throw on some OEMs; treat as not connected rather than crash.
        }
        wifiView.setAlpha(connected ? 1f : 0.3f);
    }

    private int statusBarHeight() {
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            return getResources().getDimensionPixelSize(resId);
        }
        return dp(24);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(wifiRunnable);
        statusBarUpdater.stopUpdating();
        if (barView != null && windowManager != null) {
            try {
                windowManager.removeView(barView);
            } catch (Exception e) {
                // Already gone.
            }
            barView = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
