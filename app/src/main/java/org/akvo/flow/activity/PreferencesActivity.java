/*
 *  Copyright (C) 2010-2016 Stichting Akvo (Akvo Foundation)
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

package org.akvo.flow.activity;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.TextView;

import org.akvo.flow.R;
import org.akvo.flow.app.FlowApp;
import org.akvo.flow.data.database.SurveyDbAdapter;
import org.akvo.flow.data.preference.Prefs;
import org.akvo.flow.service.SurveyDownloadService;
import org.akvo.flow.util.ArrayPreferenceUtil;
import org.akvo.flow.util.ConstantUtil;
import org.akvo.flow.util.LangsPreferenceData;
import org.akvo.flow.util.LangsPreferenceUtil;
import org.akvo.flow.util.StatusUtil;
import org.akvo.flow.util.StringUtil;
import org.akvo.flow.util.ViewUtil;

import java.lang.ref.WeakReference;
import java.util.HashMap;

/**
 * Displays user editable preferences and takes care of persisting them to the
 * database. Some options require the user to enter an administrator passcode
 * via a dialog box before the operation can be performed.
 *
 * @author Christopher Fagiani
 */
public class PreferencesActivity extends BackActivity implements OnClickListener,
        OnCheckedChangeListener {
    private CheckBox screenOnCheckbox;
    private CheckBox mobileDataCheckbox;
    private TextView languageTextView;
    private TextView serverTextView;
    private TextView identTextView;
    private TextView maxImgSizeTextView;
    private TextView localeTextView;

    private SurveyDbAdapter database;

    private LangsPreferenceData langsPrefData;
    private String[] langsSelectedNameArray;
    private boolean[] langsSelectedBooleanArray;
    private int[] langsSelectedMasterIndexArray;

    private String[] maxImgSizes;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.preferences);

        screenOnCheckbox = (CheckBox) findViewById(R.id.screenoptcheckbox);
        mobileDataCheckbox = (CheckBox) findViewById(R.id.uploadoptioncheckbox);
        languageTextView = (TextView) findViewById(R.id.surveylangvalue);
        serverTextView = (TextView) findViewById(R.id.servervalue);
        identTextView = (TextView) findViewById(R.id.identvalue);
        maxImgSizeTextView = (TextView) findViewById(R.id.max_img_size_txt);
        localeTextView = (TextView) findViewById(R.id.locale_name);

        Resources res = getResources();

        maxImgSizes = res.getStringArray(R.array.max_image_size_pref);

        // Setup event listeners
        screenOnCheckbox.setOnCheckedChangeListener(this);
        mobileDataCheckbox.setOnCheckedChangeListener(this);
        findViewById(R.id.pref_locale).setOnClickListener(this);
        findViewById(R.id.pref_surveylang).setOnClickListener(this);
        findViewById(R.id.pref_server).setOnClickListener(this);
        findViewById(R.id.pref_deviceid).setOnClickListener(this);
        findViewById(R.id.pref_resize).setOnClickListener(this);
    }

    /**
     * loads the preferences from the DB and sets their current value in the UI
     */
    private void populateFields() {
        populateFromSharedPreferences();
        populateLanguagePreferences();
    }

    private void populateLanguagePreferences() {
        localeTextView.setText(FlowApp.getApp().getAppDisplayLanguage());

        HashMap<String, String> settings = database.getPreferences();
        String val = settings.get(ConstantUtil.SURVEY_LANG_SETTING_KEY);
        String langsPresentIndexes = settings.get(ConstantUtil.SURVEY_LANG_PRESENT_KEY);
        langsPrefData = LangsPreferenceUtil.createLangPrefData(this, val, langsPresentIndexes);

        languageTextView.setText(ArrayPreferenceUtil.formSelectedItemString(
                langsPrefData.getLangsSelectedNameArray(),
                langsPrefData.getLangsSelectedBooleanArray()));
    }

    private void populateFromSharedPreferences() {
        screenOnCheckbox.setChecked(
                Prefs.getBoolean(this, Prefs.SCREEN_ON_KEY, Prefs.DEFAULT_SCREEN_ON_PREF_VALUE));

        mobileDataCheckbox.setChecked(Prefs.getBoolean(this, Prefs.CELL_UPLOAD_SETTING_KEY,
                Prefs.DEFAULT_CELLULAR_DATA_UPLOAD_PREF_VALUE));

        serverTextView.setText(StatusUtil.getServerBase(this));

        int maxImgSize = Prefs
                .getInt(this, Prefs.MAX_IMG_SIZE, Prefs.DEFAULT_IMAGE_SIZE_PREF_VALUE);
        maxImgSizeTextView.setText(maxImgSizes[maxImgSize]);
        identTextView.setText(Prefs.getString(this, Prefs.DEVICE_IDENT_KEY,
                Prefs.DEFAULT_DEVICE_IDENTIFIER_PREF_VALUE));
    }

    /**
     * opens db connection and sets up listeners (after we hydrate values so we
     * don't trigger the onCheckChanged listener when we set initial values)
     */
    public void onResume() {
        super.onResume();
        database = new SurveyDbAdapter(this);
        database.open();
        populateFields();
    }

    public void onPause() {
        database.close();
        super.onPause();
    }

    /**
     * displays a pop-up dialog containing the upload or language options
     * depending on what was clicked
     */
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.pref_surveylang:
                langsSelectedNameArray = langsPrefData.getLangsSelectedNameArray();
                langsSelectedBooleanArray = langsPrefData.getLangsSelectedBooleanArray();
                langsSelectedMasterIndexArray = langsPrefData.getLangsSelectedMasterIndexArray();

                ViewUtil.displayLanguageSelector(this, langsSelectedNameArray,
                        langsSelectedBooleanArray,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int clicked) {
                                database.savePreference(
                                        ConstantUtil.SURVEY_LANG_SETTING_KEY,
                                        LangsPreferenceUtil
                                                .formLangPreferenceString(langsSelectedBooleanArray,
                                                        langsSelectedMasterIndexArray)
                                );

                                languageTextView.setText(ArrayPreferenceUtil
                                        .formSelectedItemString(langsSelectedNameArray,
                                                langsSelectedBooleanArray));
                                if (dialog != null) {
                                    dialog.dismiss();
                                }
                            }
                        }
                );
                break;
            case R.id.pref_locale:
                showLanguageDialog();
                break;
            case R.id.pref_server:
                StringPrefInputDialogListener dialogListener = new StringPrefInputDialogListener(
                        this) {
                    @Override
                    public void usePreference(String userInput) {
                        PreferencesActivity preferencesActivity = activityWeakReference.get();
                        if (preferencesActivity != null) {
                            preferencesActivity.saveBackendUrl(userInput);
                        }
                    }
                };
                //TODO: check duplicated title and subtitle --> remove subtitle
                ViewUtil.showAdminAuthDialog(this,
                        new StringPreferencesAuthDialogListener(this, dialogListener,
                                R.string.serverlabel,
                                R.string.serverlabel,
                                StatusUtil.getServerBase(PreferencesActivity.this))
                );
                break;
            case R.id.pref_deviceid:
                StringPrefInputDialogListener dialogListenerId = new StringPrefInputDialogListener(
                        this) {
                    @Override
                    public void usePreference(String userInput) {
                        PreferencesActivity preferencesActivity = activityWeakReference.get();
                        if (preferencesActivity != null) {
                            preferencesActivity.saveNewDeviceId(userInput);
                        }
                    }
                };
                ViewUtil.showAdminAuthDialog(this,
                        new StringPreferencesAuthDialogListener(this, dialogListenerId,
                                R.string.identlabel,
                                R.string.setidentlabel,
                                Prefs.getString(this, Prefs.DEVICE_IDENT_KEY,
                                        Prefs.DEFAULT_DEVICE_IDENTIFIER_PREF_VALUE))
                );
                break;
            case R.id.pref_resize:
                showPreferenceDialog(R.string.resize_large_images,
                        Prefs.MAX_IMG_SIZE,
                         maxImgSizes, maxImgSizeTextView);
                break;
        }
    }

    private void saveBackendUrl(String userInput) {
        Prefs.setString(PreferencesActivity.this, Prefs.SERVER_SETTING_KEY, userInput);
        serverTextView.setText(userInput);
    }

    /**
     * displays a dialog that allows the user to choose a setting from a string
     * array
     *
     * @param titleId        - resource id of dialog title
     * @param settingKey     - key of setting to edit
     * @param values         - string array containing values (text mapping of the key)
     * @param currentValView - view to update with value selected
     */
    private void showPreferenceDialog(int titleId, final String settingKey, final String[] values,
            final TextView currentValView) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(titleId).setItems(values,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.setInt(currentValView.getContext(), settingKey, which);
                        currentValView.setText(values[which]);
                        if (dialog != null) {
                            dialog.dismiss();
                        }
                    }
                }
        );
        builder.show();
    }

    /**
     * saves the value of the checkbox to the database
     */
    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView == screenOnCheckbox) {
            Prefs.setBoolean(this, Prefs.SCREEN_ON_KEY, isChecked);
        } else if (buttonView == mobileDataCheckbox) {
            Prefs.setBoolean(this, Prefs.CELL_UPLOAD_SETTING_KEY, isChecked);
        }
    }

    private void showLanguageDialog() {
        final String[] languageCodes = getResources().getStringArray(R.array.app_language_codes);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.app_language).setItems(R.array.app_languages,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        FlowApp.getApp().setAppLanguage(languageCodes[which], true);
                    }
                }
        );
        builder.show();
    }

    private void saveNewDeviceId(String deviceId) {
        identTextView.setText(deviceId);
        Prefs.setString(this, Prefs.DEVICE_IDENT_KEY, deviceId);
        // Trigger the SurveyDownload Service, in order to force
        // a backend connection with the new Device ID
        startService(new Intent(this,
                SurveyDownloadService.class));
    }

    static class StringPreferencesAuthDialogListener implements ViewUtil.AdminAuthDialogListener {

        private final WeakReference<PreferencesActivity> activityWeakReference;
        private final StringPrefInputDialogListener dialogListener;
        private final int title;
        private final int subtitle;
        private final String originalValue;

        StringPreferencesAuthDialogListener(PreferencesActivity activity,
                StringPrefInputDialogListener dialogListener, int title,
                int subtitle, String originalValue) {
            this.activityWeakReference = new WeakReference<>(activity);
            this.dialogListener = dialogListener;
            this.title = title;
            this.subtitle = subtitle;
            this.originalValue = originalValue;
        }

        @Override
        public void onAuthenticated() {
            final PreferencesActivity preferencesActivity = activityWeakReference.get();
            if (preferencesActivity != null) {
                final EditText inputView = new EditText(preferencesActivity);
                // one line only
                inputView.setSingleLine();
                inputView.setText(originalValue);
                ViewUtil.ShowTextInputDialog(preferencesActivity, title,
                        subtitle, inputView, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                /**
                                 * Drop any control chars, especially tabs
                                 */
                                String newDeviceId = StringUtil.controlToSpace(inputView
                                        .getText().toString());
                                dialogListener.usePreference(newDeviceId);
                            }
                        });
            }
        }
    }

    public abstract class StringPrefInputDialogListener {

        final WeakReference<PreferencesActivity> activityWeakReference;

        protected StringPrefInputDialogListener(PreferencesActivity activity) {
            this.activityWeakReference = new WeakReference<>(activity);
        }

        public abstract void usePreference(String userInput);
    }
}
