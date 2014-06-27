/*
 *  Copyright (C) 2013 Stichting Akvo (Akvo Foundation)
 *
 *  This file is part of Akvo FLOW.
 *
 *  Akvo FLOW is free software: you can redistribute it and modify it under the terms of
 *  the GNU Affero General Public License (AGPL) as published by the Free Software Foundation,
 *  either version 3 of the License or any later version.
 *
 *  Akvo FLOW is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Affero General Public License included below for more details.
 *
 *  The full license text can also be seen at <http://www.gnu.org/licenses/agpl.html>.
 */

package org.akvo.flow.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;

import java.io.File;

/**
 * Utilities class to provide Android related functionalities
 */
public class PlatformUtil {
    private static final String TAG = PlatformUtil.class.getSimpleName();

    /**
     * Get the version name assigned in AndroidManifest.xml
     * 
     * @param context
     * @return versionName
     */
    public static String getVersionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0).versionName;
        } catch (NameNotFoundException e) {
            Log.e(TAG, e.getMessage());
            return "";
        }
    }

    /**
     * Check if a given version is newer than the current one.
     * Versions are expected to be formatted in a dot-decimal notation: X.Y.Z,
     * being X, Y, and Z integers, and each number separated by a full stop (dot).
     *
     * @param installedVersion
     * @param newVersion
     * @return true if the second version is newer than the first one, false otherwise
     */
    public static boolean isNewerVersion(String installedVersion, String newVersion) {
        // Ensure the Strings are properly formatted
        final String regex = "^\\d+(\\.\\d+)*$";// Check dot-decimal notation
        if (!installedVersion.matches(regex) || !newVersion.matches(regex)) {
            return false;
        }

        String[] currentParts = installedVersion.split("\\.");
        String[] newPartsParts = newVersion.split("\\.");
        int length = Math.max(currentParts.length, newPartsParts.length);
        for (int i=0; i< length; i++) {
            int currentPart = i < currentParts.length ?
                    Integer.parseInt(currentParts[i]) : 0;
            int newPart = i < newPartsParts.length ?
                    Integer.parseInt(newPartsParts[i]) : 0;

            if (currentPart < newPart) {
                return true;// Newer version
            } else if (newPart < currentPart) {
                return false;// Older version
            }
        }

        return false;// Same version
    }

    public static float dp2Pixel(Context context, int dp) {
        Resources r = context.getResources();
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, r.getDisplayMetrics());
    }

    public static int getResource(Context context, int attr) {
        TypedArray a = context.getTheme().obtainStyledAttributes(new int[]{attr});
        return a.getResourceId(0, 0);
    }

    /**
     * Install the newest version of the app. This method will be called
     * either after the file download is completed, or upon the app being started,
     * if the newest version is found in the filesystem.
     * @param context Context
     * @param filename Absolute path to the newer APK
     */
    public static void installAppUpdate(Context context, String filename) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(new File(filename)),
                "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

}
