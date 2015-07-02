/*
 *  Copyright (C) 2010-2015 Stichting Akvo (Akvo Foundation)
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

package org.akvo.flow.service;

import android.app.IntentService;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.akvo.flow.R;
import org.akvo.flow.api.FlowApi;
import org.akvo.flow.api.S3Api;
import org.akvo.flow.api.response.FormInstance;
import org.akvo.flow.api.response.Response;
import org.akvo.flow.broadcast.FormDeletedReceiver;
import org.akvo.flow.dao.SurveyDbAdapter;
import org.akvo.flow.dao.SurveyDbAdapter.ResponseColumns;
import org.akvo.flow.dao.SurveyDbAdapter.SurveyInstanceColumns;
import org.akvo.flow.dao.SurveyDbAdapter.UserColumns;
import org.akvo.flow.dao.SurveyDbAdapter.TransmissionStatus;
import org.akvo.flow.dao.SurveyDbAdapter.SurveyInstanceStatus;
import org.akvo.flow.domain.FileTransmission;
import org.akvo.flow.exception.HttpException;
import org.akvo.flow.exception.PersistentUncaughtExceptionHandler;
import org.akvo.flow.util.Base64;
import org.akvo.flow.util.ConstantUtil;
import org.akvo.flow.util.FileUtil;
import org.akvo.flow.util.FileUtil.FileType;
import org.akvo.flow.util.HttpUtil;
import org.akvo.flow.util.PropertyUtil;
import org.akvo.flow.util.StatusUtil;
import org.akvo.flow.util.StringUtil;
import org.akvo.flow.util.ViewUtil;

import org.apache.http.HttpStatus;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.Adler32;
import java.util.zip.CheckedOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 *
 * Handle survey export and sync in a background thread. The export process takes
 * no arguments, and will try to zip all the survey instances with a SUBMITTED status
 * but with no EXPORT_DATE (export hasn't happened yet). Ideally, and if the service has been
 * triggered by a survey submission, only one survey instance will be exported. However, if for
 * whatever reason, a previous export attempt has failed, a new export will be tried on each
 * execution of the service, until the zip file finally gets exported. A possible scenario for
 * this is the submission of a survey when the external storage is not available, postponing the
 * export until it gets ready.
 *
 * After the export of the zip files, the sync will be run, attempting to upload all the non synced
 * files to the datastore.
 *
 * @author Christopher Fagiani
 *
 */
public class DataSyncService extends IntentService {
    private static final String TAG = "SyncService";
    private static final String DELIMITER = "\t";
    private static final String SPACE = "\u0020"; // safe from source whitespace reformatting

    private static final String SIGNING_KEY_PROP = "signingKey";
    private static final String SIGNING_ALGORITHM = "HmacSHA1";

    private static final String SURVEY_DATA_FILE_JSON = "data.json";
    private static final String SIG_FILE_NAME = ".sig";

    // Sync constants
    private static final String DEVICE_NOTIFICATION_PATH = "/devicenotification";
    private static final String NOTIFICATION_PATH = "/processor?action=";
    private static final String FILENAME_PARAM = "&fileName=";
    private static final String FORMID_PARAM = "&formID=";

    private static final String DATA_CONTENT_TYPE = "application/zip";
    private static final String IMAGE_CONTENT_TYPE = "image/jpeg";
    private static final String VIDEO_CONTENT_TYPE = "video/mp4";

    private static final String ACTION_SUBMIT = "submit";
    private static final String ACTION_IMAGE = "image";

    /**
     * Number of retries to upload a file to S3
     */
    private static final int FILE_UPLOAD_RETRIES = 2;

    private static final int ERROR_UNKNOWN = -1;

    private PropertyUtil mProps;
    private SurveyDbAdapter mDatabase;

    public DataSyncService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        try {
            mProps = new PropertyUtil(getResources());
            mDatabase = new SurveyDbAdapter(this);
            mDatabase.open();

            exportSurveys();// Create zip files, if necessary

            if (StatusUtil.hasDataConnection(this)) {
                syncFiles();// Sync everything
            }

            mDatabase.close();
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            PersistentUncaughtExceptionHandler.recordException(e);
        } finally {
            if (mDatabase != null) {
                mDatabase.close();
            }
        }
    }

    // ================================================================= //
    // ============================ EXPORT ============================= //
    // ================================================================= //

    private void exportSurveys() {
        // First off, ensure surveys marked as 'exported' are indeed found in the external storage.
        // Missing surveys will be set to 'submitted', so the next step re-creates these files too.
        checkExportedFiles();

        for (long id : getUnexportedSurveys()) {
            ZipFileData zipFileData = formZip(id);
            if (zipFileData != null) {
                displayNotification(id, getString(R.string.exportcomplete), getDestName(zipFileData.filename));

                // Create new entries in the transmission queue
                mDatabase.createTransmission(id, zipFileData.formId, zipFileData.filename);
                updateSurveyStatus(id, SurveyInstanceStatus.EXPORTED);

                for (String image : zipFileData.imagePaths) {
                    mDatabase.createTransmission(id, zipFileData.formId, image);
                }
            }
        }
    }

    private File getSurveyInstanceFile(String uuid) {
        return new File(FileUtil.getFilesDir(FileType.DATA), uuid + ConstantUtil.ARCHIVE_SUFFIX);
    }

    private void checkExportedFiles() {
        Cursor cursor = mDatabase.getSurveyInstancesByStatus(SurveyInstanceStatus.EXPORTED);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                do {
                    long id = cursor.getLong(cursor.getColumnIndexOrThrow(SurveyInstanceColumns._ID));
                    String uuid = cursor.getString(cursor.getColumnIndexOrThrow(SurveyInstanceColumns.UUID));
                    if (!getSurveyInstanceFile(uuid).exists()) {
                        Log.d(TAG, "Exported file for survey " + uuid +  " not found. It's status " +
                                "will be set to 'submitted', and will be reprocessed");
                        updateSurveyStatus(id, SurveyInstanceStatus.SUBMITTED);
                    }
                } while (cursor.moveToNext());
            }
            cursor.close();
        }
    }

    private long[] getUnexportedSurveys() {
        long[] surveyInstanceIds = new long[0];// Avoid null cases
        Cursor cursor = mDatabase.getSurveyInstancesByStatus(SurveyInstanceStatus.SUBMITTED);
        if (cursor != null) {
            surveyInstanceIds = new long[cursor.getCount()];
            if (cursor.moveToFirst()) {
                do {
                    surveyInstanceIds[cursor.getPosition()] = cursor.getLong(
                            cursor.getColumnIndexOrThrow(SurveyInstanceColumns._ID));
                } while (cursor.moveToNext());
            }
            cursor.close();
        }
        return surveyInstanceIds;
    }

    private ZipFileData formZip(long surveyInstanceId) {
        try {
            ZipFileData zipFileData = new ZipFileData();
            // Process form instance data and collect image filenames
            FormInstance formInstance = processFormInstance(surveyInstanceId, zipFileData.imagePaths);

            if (formInstance == null) {
                return null;
            }

            // Serialize form instance as JSON
            zipFileData.data = new ObjectMapper().writeValueAsString(formInstance);
            zipFileData.uuid = formInstance.getUUID();
            zipFileData.formId = String.valueOf(formInstance.getFormId());

            File zipFile = getSurveyInstanceFile(zipFileData.uuid);// The filename will match the Survey Instance UUID

            // Write the data into the zip file
            String fileName = zipFile.getAbsolutePath();// Will normalize filename.
            zipFileData.filename = fileName;
            Log.i(TAG, "Creating zip file: " + fileName);
            FileOutputStream fout = new FileOutputStream(zipFile);
            CheckedOutputStream checkedOutStream = new CheckedOutputStream(fout, new Adler32());
            ZipOutputStream zos = new ZipOutputStream(checkedOutStream);

            writeTextToZip(zos, zipFileData.data, SURVEY_DATA_FILE_JSON);
            String signingKeyString = mProps.getProperty(SIGNING_KEY_PROP);
            if (!StringUtil.isNullOrEmpty(signingKeyString)) {
                MessageDigest sha1Digest = MessageDigest.getInstance("SHA1");
                byte[] digest = sha1Digest.digest(zipFileData.data.getBytes("UTF-8"));
                SecretKeySpec signingKey = new SecretKeySpec(signingKeyString.getBytes("UTF-8"),
                        SIGNING_ALGORITHM);
                Mac mac = Mac.getInstance(SIGNING_ALGORITHM);
                mac.init(signingKey);
                byte[] hmac = mac.doFinal(digest);
                String encodedHmac = Base64.encodeBytes(hmac);
                writeTextToZip(zos, encodedHmac, SIG_FILE_NAME);
            }

            final String checksum = "" + checkedOutStream.getChecksum().getValue();
            zos.close();
            Log.i(TAG, "Closed zip output stream for file: " + fileName + ". Checksum: " + checksum);
            return zipFileData;
        } catch (IOException | NoSuchAlgorithmException | InvalidKeyException e) {
            PersistentUncaughtExceptionHandler.recordException(e);
            Log.e(TAG, e.getMessage());
            return null;
        }
    }

    /**
     * Writes the contents of text to a zip entry within the Zip file behind zos
     * named fileName
     */
    private void writeTextToZip(ZipOutputStream zos, String text,
            String fileName) throws IOException {
        Log.i(TAG, "Writing zip entry");
        zos.putNextEntry(new ZipEntry(fileName));
        byte[] allBytes = text.getBytes("UTF-8");
        zos.write(allBytes, 0, allBytes.length);
        zos.closeEntry();
        Log.i(TAG, "Entry Complete");
    }

    /**
     * Iterate over the survey data returned from the database and populate the
     * ZipFileData information, setting the UUID, Survey ID, image paths, and String data.
     */
    private FormInstance processFormInstance(long surveyInstanceId, List<String> imagePaths) throws IOException {
        FormInstance formInstance = new FormInstance();
        List<Response> responses = new ArrayList<>();
        Cursor data = mDatabase.getResponsesData(surveyInstanceId);

        if (data != null && data.moveToFirst()) {
            String deviceIdentifier = mDatabase.getPreference(ConstantUtil.DEVICE_IDENT_KEY);
            if (deviceIdentifier == null) {
                deviceIdentifier = "unset";
            } else {
                deviceIdentifier = cleanVal(deviceIdentifier);
            }
            // evaluate indices once, outside the loop
            int survey_fk_col = data.getColumnIndexOrThrow(SurveyInstanceColumns.SURVEY_ID);
            int question_fk_col = data.getColumnIndexOrThrow(ResponseColumns.QUESTION_ID);
            int answer_type_col = data.getColumnIndexOrThrow(ResponseColumns.TYPE);
            int answer_col = data.getColumnIndexOrThrow(ResponseColumns.ANSWER);
            int disp_name_col = data.getColumnIndexOrThrow(UserColumns.NAME);
            int email_col = data.getColumnIndexOrThrow(UserColumns.EMAIL);
            int submitted_date_col = data.getColumnIndexOrThrow(SurveyInstanceColumns.SUBMITTED_DATE);
            int uuid_col = data.getColumnIndexOrThrow(SurveyInstanceColumns.UUID);
            int duration_col = data.getColumnIndexOrThrow(SurveyInstanceColumns.DURATION);
            int localeId_col = data.getColumnIndexOrThrow(SurveyInstanceColumns.RECORD_ID);
            // Note: No need to query the surveyInstanceId, we already have that value

            do {
                // Sanitize answer value. No newlines or tabs!
                String value = data.getString(answer_col);
                if (value != null) {
                    value = value.replace("\n", SPACE);
                    value = value.replace(DELIMITER, SPACE);
                    value = value.trim();
                }
                // never send empty answers
                if (value == null || value.length() == 0) {
                    continue;
                }
                final long submitted_date = data.getLong(submitted_date_col);
                final long surveyal_time = (data.getLong(duration_col)) / 1000;

                if (formInstance.getUUID() == null) {
                    formInstance.setUUID(data.getString(uuid_col));
                    formInstance.setFormId(data.getLong(survey_fk_col));// FormInstance uses a number for this attr
                    formInstance.setDataPointId(data.getString(localeId_col));
                    formInstance.setDeviceId(deviceIdentifier);
                    formInstance.setSubmissionDate(submitted_date);
                    formInstance.setDuration(surveyal_time);
                    formInstance.setUsername(cleanVal(data.getString(disp_name_col)));
                    formInstance.setEmail(cleanVal(data.getString(email_col)));
                }

                String type = data.getString(answer_type_col);
                if (ConstantUtil.IMAGE_RESPONSE_TYPE.equals(type)
                        || ConstantUtil.VIDEO_RESPONSE_TYPE.equals(type)) {
                    imagePaths.add(value);
                }

                Response response = new Response();
                response.setQuestionId(data.getString(question_fk_col));
                response.setAnswerType(type);
                response.setValue(value);
                responses.add(response);
            } while (data.moveToNext());

            formInstance.setResponses(responses);
            data.close();
        }

        return formInstance;
    }

    // replace troublesome chars in user-provided values
    // replaceAll() compiles a Pattern, and so is inefficient inside a loop
    private String cleanVal(String val) {
        if (val != null) {
            if (val.contains(DELIMITER)) {
                val = val.replace(DELIMITER, SPACE);
            }
            if (val.contains(",")) {
                val = val.replace(",", SPACE);
            }
            if (val.contains("\n")) {
                val = val.replace("\n", SPACE);
            }
        }
        return val;
    }

    // ================================================================= //
    // ======================= SYNCHRONISATION ========================= //
    // ================================================================= //

    /**
     * Sync every file (zip file, images, etc) that has a non synced state. This refers to:
     * - Queued transmissions
     * - Failed transmissions
     *
     * Each transmission will be retried up to three times. If the transmission does
     * not succeed in those attempts, it will be marked as failed, and retried in the next sync.
     * Files are uploaded to S3 and the response's ETag is compared against a locally computed
     * MD5 checksum. Only if these fields match the transmission will be considered successful.
     */
    private void syncFiles() {
        final String serverBase = StatusUtil.getServerBase(this);
        // Sync missing files. This will update the status of the transmissions if necessary
        checkMissingFiles(serverBase);

        List<FileTransmission> transmissions = mDatabase.getUnsyncedTransmissions();

        if (transmissions.isEmpty()) {
            return;
        }

        Set<Long> syncedSurveys = new HashSet<Long>();// Successful transmissions
        Set<Long> unsyncedSurveys = new HashSet<Long>();// Unsuccessful transmissions

        final int totalFiles = transmissions.size();
        displayProgressNotification(0, totalFiles);


        for (int i = 0; i < totalFiles; i++) {
            FileTransmission transmission = transmissions.get(i);
            final long surveyInstanceId = transmission.getRespondentId();
            if (syncFile(transmission.getFileName(), transmission.getFormId(), transmission.getStatus(), serverBase)) {
                syncedSurveys.add(surveyInstanceId);
            } else {
                unsyncedSurveys.add(surveyInstanceId);
            }
            displayProgressNotification(i, totalFiles);// Progress is the % of files handled so far
        }

        // Retain successful survey instances, to mark them as SYNCED
        syncedSurveys.removeAll(unsyncedSurveys);

        displaySyncedNotification(syncedSurveys.size(), unsyncedSurveys.size());

        for (long surveyInstanceId : syncedSurveys) {
            updateSurveyStatus(surveyInstanceId, SurveyInstanceStatus.SYNCED);
        }

        // Ensure the unsynced ones are just EXPORTED
        for (long surveyInstanceId : unsyncedSurveys) {
            updateSurveyStatus(surveyInstanceId, SurveyInstanceStatus.EXPORTED);
        }
    }

    private boolean syncFile(String filename, String formId, int status, String serverBase) {
        if (TextUtils.isEmpty(filename)) {
            return false;
        }

        String contentType, dir, action;
        boolean isPublic;
        if (filename.endsWith(ConstantUtil.IMAGE_SUFFIX) || filename.endsWith(ConstantUtil.VIDEO_SUFFIX)) {
            contentType = filename.endsWith(ConstantUtil.IMAGE_SUFFIX) ? IMAGE_CONTENT_TYPE
                    : VIDEO_CONTENT_TYPE;
            dir = ConstantUtil.S3_IMAGE_DIR;
            // Only notify server if the previous attempts have failed
            action = TransmissionStatus.FAILED == status ? ACTION_IMAGE : null;
            isPublic = true;// Images/Videos have a public read policy
        } else {
            contentType = DATA_CONTENT_TYPE;
            dir = ConstantUtil.S3_DATA_DIR;
            action = ACTION_SUBMIT;
            isPublic = false;
        }

        mDatabase.updateTransmissionHistory(filename, TransmissionStatus.IN_PROGRESS);

        boolean ok = sendFile(filename, dir, contentType, isPublic, FILE_UPLOAD_RETRIES);
        final String destName = getDestName(filename);

        if (ok && action != null) {
            // If action is not null, notify GAE back-end that data is available
            // TODO: Do we need to send the checksum?
            switch (sendProcessingNotification(serverBase, formId, action, destName)) {
                case HttpStatus.SC_OK:
                    // Mark everything completed
                    mDatabase.updateTransmissionHistory(filename, TransmissionStatus.SYNCED);
                    break;
                case HttpStatus.SC_NOT_FOUND:
                    // This form has been deleted in the dashboard, thus we cannot sync it
                    displayNotification(Integer.valueOf(formId),
                            "Form " + formId + " does not exist", "It has probably been deleted");
                    mDatabase.updateTransmissionHistory(filename, TransmissionStatus.FORM_DELETED);
                    ok = false;// Consider this a failed transmission
                    break;
                default:// Any error code
                    mDatabase.updateTransmissionHistory(filename, TransmissionStatus.FAILED);
                    ok = false;// Consider this a failed transmission
                    break;
            }
        }

        return ok;
    }

    private boolean sendFile(String fileAbsolutePath, String dir, String contentType,
            boolean isPublic, int retries) {
        final File file = new File(fileAbsolutePath);
        if (!file.exists()) {
            return false;
        }

        boolean ok = false;
        try {
            String fileName = fileAbsolutePath;
            if (fileName.contains(File.separator)) {
                fileName = fileName.substring(fileName.lastIndexOf(File.separator) + 1);
            }

            final String objectKey = dir + fileName;
            S3Api s3Api = new S3Api(this);
            ok = s3Api.put(objectKey, file, contentType, isPublic);
            if (!ok && retries > 0) {
                // If we have not expired all the retry attempts, try again.
                ok = sendFile(fileAbsolutePath, dir, contentType, isPublic, --retries);
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not send file: " + fileAbsolutePath + ". " + e.getMessage(), e);
            PersistentUncaughtExceptionHandler.recordException(e);
        }

        return ok;
    }

    /**
     * Request missing files (images) in the datastore.
     * The server will provide us with a list of missing images,
     * so we can accordingly update their status in the database.
     * This will help us fixing the Issue #55
     *
     * Steps:
     * 1- Request the list of files to the server
     * 2- Update the status of those files in the local database
     */
    private void checkMissingFiles(String serverBase) {
        try {
            String response = getDeviceNotification(serverBase);
            if (!TextUtils.isEmpty(response)) {
                JSONObject jResponse = new JSONObject(response);
                List<String> files = parseFiles(jResponse.optJSONArray("missingFiles"));
                files.addAll(parseFiles(jResponse.optJSONArray("missingUnknown")));

                // Handle missing files. If an unknown file exists in the filesystem
                // it will be marked as failed in the transmission history, so it can
                // be handled and retried in the next sync attempt.
                for (String filename : files) {
                    if (new File(filename).exists()) {
                        setFileTransmissionFailed(filename);
                    }
                }

                JSONArray jForms = jResponse.optJSONArray("deletedForms");
                if (jForms != null) {
                    for (int i=0; i<jForms.length(); i++) {
                        displayFormDeletedNotification(jForms.getString(i));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not retrieve missing files", e);
        }
    }

    /**
     * Given a json array, return the list of contained filenames,
     * formatting the path to match the structure of the sdcard's files.
     */
    private List<String> parseFiles(JSONArray jFiles) throws JSONException {
        List<String> files = new ArrayList<String>();
        if (jFiles != null) {
            for (int i=0; i<jFiles.length(); i++) {
                // Build the sdcard path for each image
                String filename = jFiles.getString(i);
                File file = new File(FileUtil.getFilesDir(FileType.MEDIA), filename);
                files.add(file.getAbsolutePath());
            }
        }
        return files;
    }

    private void setFileTransmissionFailed(String filename) {
        int rows = mDatabase.updateTransmissionHistory(filename, TransmissionStatus.FAILED);
        if (rows == 0) {
            // Use a dummy "-1" as survey_instance_id, as the database needs that attribute
            mDatabase.createTransmission(-1, null, filename, TransmissionStatus.FAILED);
        }
    }

    /**
     * Request the notifications GAE has ready for us, like the list of missing files.
     * @param serverBase
     * @return String body of the HTTP response
     * @throws Exception
     */
    private String getDeviceNotification(String serverBase) throws Exception {
        // Send the list of surveys we've got downloaded, getting notified of the deleted ones
        StringBuilder surveyIds = new StringBuilder();
        for (String id : mDatabase.getSurveyIds()) {
            surveyIds.append("&formId=" + id);
        }
        String url = serverBase + DEVICE_NOTIFICATION_PATH + "?" + FlowApi.getDeviceParams() + surveyIds.toString();
        return HttpUtil.httpGet(url);
    }

    /**
     * Sends a message to the service with the file name that was just uploaded
     * so it can start processing the file
     */
    private int sendProcessingNotification(String serverBase, String formId, String action, String fileName) {
        String url = serverBase + NOTIFICATION_PATH + action
                + FORMID_PARAM + formId
                + FILENAME_PARAM + fileName + "&" + FlowApi.getDeviceParams();
        try {
            HttpUtil.httpGet(url);
            return HttpStatus.SC_OK;
        } catch (HttpException e) {
            Log.e(TAG, e.getStatus() + " response for formId: " + formId);
            return e.getStatus();
        } catch (Exception e) {
            Log.e(TAG, "GAE sync notification failed for file: " + fileName);
            return ERROR_UNKNOWN;
        }
    }

    private static String getDestName(String filename) {
        if (filename.contains("/")) {
            return filename.substring(filename.lastIndexOf("/") + 1);
        } else if (filename.contains("\\")) {
            filename = filename.substring(filename.lastIndexOf("\\") + 1);
        }

        return filename;
    }

    private void updateSurveyStatus(long surveyInstanceId, int status) {
        // First off, update the status
        mDatabase.updateSurveyStatus(surveyInstanceId, status);

        // Dispatch a Broadcast notification to notify of survey instances status change
        Intent intentBroadcast = new Intent(getString(R.string.action_data_sync));
        sendBroadcast(intentBroadcast);
    }

    private void displayNotification(long id, String title, String text) {
        ViewUtil.fireNotification(title, text, this, (int) id, null);
    }

    /**
     * Display a notification showing the up-to-date status of the sync
     * @param synced number of handled transmissions so far (either successful or not)
     * @param total number of transmissions in the batch
     */
    private void displayProgressNotification(int synced, int total) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(getString(R.string.data_sync_title))
                .setContentText(getString(R.string.data_sync_text))
                .setTicker(getString(R.string.data_sync_text))
                .setOngoing(true);

        // Progress will only be displayed in Android versions > 4.0
        builder.setProgress(total, synced, false);

        // Dummy intent. Do nothing when clicked
        PendingIntent intent = PendingIntent.getActivity(this, 0, new Intent(), 0);
        builder.setContentIntent(intent);

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(ConstantUtil.NOTIFICATION_DATA_SYNC, builder.build());
    }

    /**
     * Display a notification showing the final status of the sync
     * @param syncedForms number of successful transmissions
     * @param failedForms number of failed transmissions
     */
    private void displaySyncedNotification(int syncedForms, int failedForms) {
        // Do not show failed if there is none
        String text = failedForms > 0 ? String.format(getString(R.string.data_sync_all),
                syncedForms, failedForms)
                : String.format(getString(R.string.data_sync_synced), syncedForms);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(getString(R.string.data_sync_title))
                .setContentText(text)
                .setTicker(text)
                .setOngoing(false);

        // Progress will only be displayed in Android versions > 4.0
        builder.setProgress(1, 1, false);

        // Dummy intent. Do nothing when clicked
        PendingIntent intent = PendingIntent.getActivity(this, 0, new Intent(), 0);
        builder.setContentIntent(intent);

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(ConstantUtil.NOTIFICATION_DATA_SYNC, builder.build());
    }

    private void displayFormDeletedNotification(String formId) {
        // Create a unique ID for this form's delete notification
        final int notificationId = (int)formId(formId);

        // Do not show failed if there is none
        String text = "Form " + formId + " has been deleted";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.info)
                .setContentTitle("Form deleted")
                .setContentText(text)
                .setTicker(text)
                .setOngoing(false);

        // Delete intent. Once the user dismisses the notification, we'll delete the form.
        Intent intent = new Intent(this, FormDeletedReceiver.class);
        intent.putExtra(FormDeletedReceiver.FORM_ID, formId);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this,
                notificationId, intent, 0);
        builder.setDeleteIntent(pendingIntent);

        // Dummy intent. Do nothing when clicked
        PendingIntent dummyIntent = PendingIntent.getActivity(this, 0, new Intent(), 0);
        builder.setContentIntent(dummyIntent);

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(notificationId, builder.build());
    }

    /**
     * Coerce a form id into its numeric format
     */
    public static long formId(String id) {
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e ){
            Log.e(TAG, id + " is not a valid form id");
            return 0;
        }
    }


    /**
     * Helper class to wrap zip file's meta-data
     */
    class ZipFileData {
        String uuid = null;
        String formId = null;
        String filename = null;
        String data = null;
        List<String> imagePaths = new ArrayList<String>();
    }

}
