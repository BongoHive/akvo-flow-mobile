/*
* Copyright (C) 2010-2016 Stichting Akvo (Akvo Foundation)
*
* This file is part of Akvo FLOW.
*
* Akvo FLOW is free software: you can redistribute it and modify it under the terms of
* the GNU Affero General Public License (AGPL) as published by the Free Software Foundation,
* either version 3 of the License or any later version.
*
* Akvo FLOW is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
* without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
* See the GNU Affero General Public License included below for more details.
*
* The full license text can also be seen at <http://www.gnu.org/licenses/agpl.html>.
*/

package org.akvo.flow.service;

import android.content.Context;
import android.support.v4.util.Pair;

import com.google.android.gms.gcm.GcmNetworkManager;
import com.google.android.gms.gcm.GcmTaskService;
import com.google.android.gms.gcm.PeriodicTask;
import com.google.android.gms.gcm.Task;
import com.google.android.gms.gcm.TaskParams;

import org.akvo.flow.domain.apkupdate.ViewApkData;
import org.akvo.flow.util.ConstantUtil;
import org.akvo.flow.util.Prefs;
import org.akvo.flow.util.StatusUtil;

import timber.log.Timber;

/**
 * This background service will check the rest api for a new version of the APK.
 * If found, it will display a {@link org.akvo.flow.activity.AppUpdateActivity}, requesting
 * permission to download and installAppUpdate it.
 *
 * @author Christopher Fagiani
 */
public class ApkUpdateService extends GcmTaskService {

    private static final String TAG = "APK_UPDATE_SERVICE";

    private final ApkUpdateHelper apkUpdateHelper = new ApkUpdateHelper();

    public static void scheduleRepeat(Context context) {
        try {
            PeriodicTask periodic = new PeriodicTask.Builder()
                    .setService(ApkUpdateService.class)
                    //repeat every x seconds
                    .setPeriod(ConstantUtil.REPEAT_INTERVAL_IN_SECONDS)
                    //specify how much earlier the task can be executed (in seconds)
                    .setFlex(ConstantUtil.FLEX_IN_SECONDS)
                    //tag that is unique to this task (can be used to cancel task)
                    .setTag(TAG)
                    //whether the task persists after device reboot
                    .setPersisted(true)
                    //if another task with same tag is already scheduled, replace it with this task
                    .setUpdateCurrent(true)
                    //set required network state
                    .setRequiredNetwork(Task.NETWORK_STATE_CONNECTED)
                    //request that charging needs not be connected
                    .setRequiresCharging(false).build();
            GcmNetworkManager.getInstance(context).schedule(periodic);
        } catch (Exception e) {
            Timber.e(e, "scheduleRepeat failed");
        }
    }

    /**
     * Cancels the repeated task
     */
    public static void cancelRepeat(Context context) {
        GcmNetworkManager.getInstance(context).cancelTask(TAG, ApkUpdateService.class);
    }

    /**
     *  Called when app is updated to a new version, reinstalled etc.
     *  Repeating tasks have to be rescheduled
     */
    @Override
    public void onInitializeTasks() {
        super.onInitializeTasks();
        scheduleRepeat(this);
    }

    /**
     * Check if new FLOW versions are available to installAppUpdate. If a new version is available,
     * we display {@link org.akvo.flow.activity.AppUpdateActivity}, requesting the user to download
     * it.
     */
    @Override
    public int onRunTask(TaskParams taskParams) {
        if (!StatusUtil.isConnectionAllowed(this)) {
            Timber.d("No available authorised connection. Can't perform the requested operation");
            return GcmNetworkManager.RESULT_SUCCESS;
        }

        try {
            Pair<Boolean, ViewApkData> booleanApkDataPair = apkUpdateHelper.shouldUpdate(this);
            if (booleanApkDataPair.first) {
                //save to shared preferences
                Prefs.saveApkData(this, booleanApkDataPair.second);
            }
            return GcmNetworkManager.RESULT_SUCCESS;
        } catch (Exception e) {
            Timber.e(e, "Error with apk version service");
            return GcmNetworkManager.RESULT_FAILURE;
        }
    }
}
