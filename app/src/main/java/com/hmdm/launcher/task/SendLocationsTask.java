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

package com.hmdm.launcher.task;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.db.DatabaseHelper;
import com.hmdm.launcher.db.LocationTable;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.server.ServerService;
import com.hmdm.launcher.server.ServerServiceKeeper;

import java.util.List;

import okhttp3.ResponseBody;
import retrofit2.Response;

/**
 * Phoenix custom build: upload collected location fixes to the devicelocations plugin so Phoenix's
 * "Where is it" shows a position and a track.
 *
 * Upstream already declared {@link ServerService#sendLocations} and the on-device {@code locations}
 * table, but in the open source variant {@code ProUtils.processLocation} was an empty stub, so every
 * fix {@code LocationService} collected was thrown away and the server held zero location rows. This
 * task is what {@code processLocation} now calls: it stores the new fix, prunes rows older than a day,
 * and flushes everything still pending in one PUT, deleting the uploaded rows only after the server
 * accepts them (so a failed upload is retried on the next fix rather than lost).
 */
public class SendLocationsTask extends AsyncTask<LocationTable.Location, Integer, Integer> {

    private static final int MAX_BATCH = 100;

    private final Context context;
    private final SettingsHelper settingsHelper;

    public SendLocationsTask(Context context) {
        this.context = context.getApplicationContext();
        this.settingsHelper = SettingsHelper.getInstance(this.context);
    }

    @Override
    protected Integer doInBackground(LocationTable.Location... fixes) {
        SQLiteDatabase db;
        try {
            db = DatabaseHelper.instance(context).getWritableDatabase();
        } catch (Exception e) {
            e.printStackTrace();
            return Const.TASK_ERROR;
        }

        if (fixes != null) {
            for (LocationTable.Location fix : fixes) {
                if (fix != null) {
                    LocationTable.insert(db, fix);
                }
            }
        }
        try {
            LocationTable.deleteOldItems(db);
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<LocationTable.Location> pending = LocationTable.select(db, MAX_BATCH);
        if (pending == null || pending.isEmpty()) {
            return Const.TASK_SUCCESS;
        }

        String project = settingsHelper.getServerProject();
        String number = settingsHelper.getDeviceId();
        if (number == null || number.isEmpty()) {
            return Const.TASK_ERROR;
        }

        Response<ResponseBody> response = null;
        try {
            ServerService serverService = ServerServiceKeeper.getServerServiceInstance(context);
            response = serverService.sendLocations(project, number, pending).execute();
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            if (response == null) {
                ServerService secondaryServerService = ServerServiceKeeper.getSecondaryServerServiceInstance(context);
                response = secondaryServerService.sendLocations(project, number, pending).execute();
            }
            if (response != null && response.isSuccessful()) {
                LocationTable.delete(db, pending);
                return Const.TASK_SUCCESS;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return Const.TASK_ERROR;
    }
}
