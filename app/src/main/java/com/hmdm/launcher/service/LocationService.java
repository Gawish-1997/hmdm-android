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

package com.hmdm.launcher.service;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.DeviceInfo;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.util.RemoteLogger;

public class LocationService extends Service {
    private LocationManager locationManager;

    private static final int NOTIFICATION_ID = 112;
    public static String CHANNEL_ID = LocationService.class.getName();

    public static final String ACTION_UPDATE_GPS = "gps";
    public static final String ACTION_UPDATE_NETWORK = "network";
    public static final String ACTION_STOP = "stop";

    boolean updateViaGps = false;
    boolean started = false;

    // Phoenix custom build 6.44: remember the newest fix the launcher actually receives, so the
    // 15-minute device-info report (SendDeviceInfoWorker) carries a position even when the system's
    // getLastKnownLocation() returns null in that background job — seen on the Lenovo tablets, where
    // fixes arrived every minute yet getLastKnownLocation stayed null, so info.location never reached
    // the server. Kept in process (static) and in SharedPreferences so it survives a process restart.
    public static final String FIX_PREFS = "phoenix_last_fix";
    private static volatile double lastLat = 0, lastLon = 0;
    private static volatile long lastFixTime = 0;

    public static void rememberFix(Context context, Location location) {
        if (location == null) return;
        double lat = location.getLatitude(), lon = location.getLongitude();
        if (lat == 0 && lon == 0) return;
        long ts = location.getTime() > 0 ? location.getTime() : System.currentTimeMillis();
        lastLat = lat; lastLon = lon; lastFixTime = ts;
        try {
            context.getApplicationContext().getSharedPreferences(FIX_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong("lat", Double.doubleToRawLongBits(lat))
                    .putLong("lon", Double.doubleToRawLongBits(lon))
                    .putLong("ts", ts)
                    .apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // The newest remembered fix as a DeviceInfo.Location, or null when there is none within 6 hours.
    public static DeviceInfo.Location cachedLocation(Context context) {
        double lat = lastLat, lon = lastLon;
        long ts = lastFixTime;
        if (ts == 0) {
            try {
                android.content.SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(FIX_PREFS, Context.MODE_PRIVATE);
                ts = prefs.getLong("ts", 0);
                lat = Double.longBitsToDouble(prefs.getLong("lat", 0));
                lon = Double.longBitsToDouble(prefs.getLong("lon", 0));
            } catch (Exception e) {
                return null;
            }
        }
        if (ts == 0 || (lat == 0 && lon == 0)) return null;
        if (System.currentTimeMillis() - ts > 6L * 60 * 60 * 1000) return null;
        DeviceInfo.Location location = new DeviceInfo.Location();
        location.setLat(lat);
        location.setLon(lon);
        location.setTs(ts);
        return location;
    }

    private static final int LOCATION_UPDATE_INTERVAL = 60000;

    // Use different location listeners for GPS and Network
    // Not sure what happens if we share the same listener for both providers
    private LocationListener gpsLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            // Do nothing here: we use getLastKnownLocation() to determine the location!
            //Toast.makeText(LocationService.this, "Location updated from GPS", Toast.LENGTH_SHORT).show();
            RemoteLogger.log(LocationService.this, Const.LOG_VERBOSE, "GPS location update: lat="
                    + location.getLatitude() + ", lon=" + location.getLongitude());
            rememberFix(LocationService.this, location);
            ProUtils.processLocation(LocationService.this, location, LocationManager.GPS_PROVIDER);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };
    private LocationListener networkLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            // Do nothing here: we use getLastKnownLocation() to determine the location!
            //Toast.makeText(LocationService.this, "Location updated from Network", Toast.LENGTH_SHORT).show();
            RemoteLogger.log(LocationService.this, Const.LOG_VERBOSE, "Network location update: lat="
                    + location.getLatitude() + ", lon=" + location.getLongitude());
            rememberFix(LocationService.this, location);
            ProUtils.processLocation(LocationService.this, location, LocationManager.NETWORK_PROVIDER);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private Handler handler = new Handler();
    private GnssStatus.Callback gnssStatusCallback = null;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager)this.getSystemService(LOCATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssStatusCallback = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                    super.onSatelliteStatusChanged(status);
                    try {
                        Log.d(Const.LOG_TAG, "Satellite status changed, count: " + status.getSatelliteCount());
                        SettingsHelper.getInstance(LocationService.this.getApplicationContext()).setSatelliteCount(status.getSatelliteCount());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            };
        }
    }

    @SuppressLint("WrongConstant")
    private void startAsForeground() {
        NotificationCompat.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Notification Channel", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
            builder = new NotificationCompat.Builder(this, CHANNEL_ID);
        } else {
            builder = new NotificationCompat.Builder( this );
        }
        Notification notification = builder
                .setContentTitle(ProUtils.getAppName(this))
                .setTicker(ProUtils.getAppName(this))
                .setContentText( getString( R.string.location_service_text ) )
                .setSmallIcon( R.drawable.ic_location_service ).build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private boolean requestLocationUpdates() {
        if (updateViaGps && (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))) {
            updateViaGps = false;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
            // No permission, so give up!
            return false;
        }

        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        boolean passiveEnabled = locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER);
        RemoteLogger.log(this, Const.LOG_VERBOSE,
                "Request location updates. gps=" + gpsEnabled + ", network=" + networkEnabled + ", passive=" + passiveEnabled);

        locationManager.removeUpdates(networkLocationListener);
        locationManager.removeUpdates(gpsLocationListener);
        try {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, LOCATION_UPDATE_INTERVAL, 0, networkLocationListener);
            if (updateViaGps) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_UPDATE_INTERVAL, 0, gpsLocationListener);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    locationManager.registerGnssStatusCallback(gnssStatusCallback, handler);
                }
            }
        } catch (Exception e) {
            // Provider may not exist, so process it friendly
            e.printStackTrace();
            return false;
        }

        return true;
    }


    @Override
    public void onDestroy() {
        locationManager.removeUpdates(networkLocationListener);
        locationManager.removeUpdates(gpsLocationListener);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
        }
        started = false;

        super.onDestroy();
    }


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent inputIntent, int flags, int startId) {
        boolean legacyGpsFlag = updateViaGps;
        if (inputIntent != null && inputIntent.getAction() != null) {
            if (inputIntent.getAction().equals(ACTION_STOP)) {
                // Stop service
                started = false;
                stopForeground(true);
                stopSelf();
                return Service.START_NOT_STICKY;
            } else if (inputIntent.getAction().equals(ACTION_UPDATE_GPS)) {
                updateViaGps = true;
            } else {
                updateViaGps = false;
            }
        } else {
            updateViaGps = false;
        }
        // Phoenix custom build: the launcher now also starts this service with startForegroundService()
        // from the background (Initializer), which requires startForeground() within seconds even when
        // the service then stops itself — so go foreground first. The notice is hidden with the status bar.
        // 6.43: on EVERY start, not only the first — every sync now calls startForegroundService() on the
        // already-running service, and re-posting the same foreground notice is what keeps each of those
        // calls from timing out on any Android version.
        try {
            startAsForeground();
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (!started || legacyGpsFlag != updateViaGps) {
            if (!requestLocationUpdates()) {
                // No permissions!
                started = false;
                stopForeground(true);
                stopSelf();
                return Service.START_NOT_STICKY;
            }
        }
        if (!started) {
            startAsForeground();
            started = true;
        }
        return START_STICKY;
    }
}
