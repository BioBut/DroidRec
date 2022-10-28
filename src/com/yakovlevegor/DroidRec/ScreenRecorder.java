/*
 * This is free and unencumbered software released into the public domain.
 *
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 *
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the
 * software to the public domain. We make this dedication for the benefit
 * of the public at large and to the detriment of our heirs and
 * successors. We intend this dedication to be an overt act of
 * relinquishment in perpetuity of all present and future rights to this
 * software under copyright law.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 *
 * For more information, please refer to <http://unlicense.org/>
*/

package com.yakovlevegor.DroidRec;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.CamcorderProfile;
import android.net.Uri;
import android.os.Build;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.OrientationEventListener;
import android.view.Display;
import android.widget.Toast;
import android.provider.DocumentsContract;
import android.content.SharedPreferences;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.util.Calendar;
import java.text.SimpleDateFormat;


import com.yakovlevegor.DroidRec.R;

public class ScreenRecorder extends Service {

    public boolean runningService = false;
    private Intent data;
    private int result;

    private Uri recordFilePath;
    private Uri recordFileFullPath;

    public static final int RECORDING_START = 100;
    public static final int RECORDING_STOP = 101;
    public static final int RECORDING_PAUSE = 102;
    public static final int RECORDING_RESUME = 103;

    private static String appName = "com.yakovlevegor.DroidRec";
    public static String ACTION_START = appName+".START_RECORDING";
    public static String ACTION_PAUSE = appName+".PAUSE_RECORDING";
    public static String ACTION_CONTINUE = appName+".CONTINUE_RECORDING";
    public static String ACTION_STOP = appName+".STOP_RECORDING";
    public static String ACTION_ACTIVITY_CONNECT = appName+".ACTIVITY_CONNECT";
    public static String ACTION_ACTIVITY_DISCONNECT = appName+".ACTIVITY_DISCONNECT";
    public static String ACTION_ACTIVITY_FINISHED_FILE = appName+".ACTIVITY_FINISHED_FILE";

    private static String NOTIFICATIONS_RECORDING_CHANNEL = "notifications";

    private static int NOTIFICATION_RECORDING_ID = 7023;
    private static int NOTIFICATION_RECORDING_FINISHED_ID = 7024;

    private long timeStart = 0;
    private long timeRecorded = 0;
    private boolean recordMicrophone = false;
    private boolean isPaused = false;

    private FileDescriptor recordingFileDescriptor;

    private NotificationManager recordingNotificationManager;
    private MediaProjection recordingMediaProjection;
    private VirtualDisplay recordingVirtualDisplay;
    private MediaRecorder recordingMediaRecorder;

    private MainActivity.ActivityBinder activityBinder = null;

    private PlaybackRecorder recorderPlayback;

    private boolean isRestarting = false;

    private int orientationOnStart = 0;

    private SharedPreferences appSettings;

    public static final String prefsident = "DroidRecPreferences";

    public class RecordingBinder extends Binder {
        boolean isStarted() {
            return ScreenRecorder.this.runningService;
        }

        void recordingPause() {
            ScreenRecorder.this.screenRecordingPause();
        }

        void recordingResume() {
            ScreenRecorder.this.screenRecordingResume();
        }

        void stopService() {
            ScreenRecorder.this.screenRecordingStop();
        }

        long getTimeStart() {
            return ScreenRecorder.this.timeStart;
        }

        long getTimeRecorded() {
            return ScreenRecorder.this.timeRecorded;
        }

        void setConnect(MainActivity.ActivityBinder lbinder) {
            ScreenRecorder.this.actionConnect(lbinder);
        }

        void setDisconnect() {
            ScreenRecorder.this.actionDisconnect();
        }

        void setPreStart(int resultcode, Intent resultdata) {
            ScreenRecorder.this.result = resultcode;
            ScreenRecorder.this.data = resultdata;
        }
    }

    private final IBinder recordingBinder = new RecordingBinder();

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return recordingBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.getAction() == ACTION_START) {
                actionStart();
            } else if (intent.getAction() == ACTION_STOP) {
                screenRecordingStop();
            } else if (intent.getAction() == ACTION_PAUSE) {
                screenRecordingPause();
            } else if (intent.getAction() == ACTION_CONTINUE) {
                screenRecordingResume();
            } else if (intent.getAction() == ACTION_ACTIVITY_FINISHED_FILE) {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(recordFileFullPath, "video/mp4");
                startActivity(i);
            }
        } else {
            if (runningService == false) {
                Toast.makeText(this, R.string.error_recorder_failed, Toast.LENGTH_SHORT).show();
                stopSelf();
            }
        }

        return START_STICKY;
    } 

    public void actionStart() {
        recordingNotificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (recordingNotificationManager.getNotificationChannel(NOTIFICATIONS_RECORDING_CHANNEL) == null) {
                NotificationChannel recordingNotifications = new NotificationChannel(NOTIFICATIONS_RECORDING_CHANNEL, getString(R.string.notifications_channel), NotificationManager.IMPORTANCE_HIGH);
                recordingNotifications.enableLights(true);
                recordingNotifications.setLightColor(Color.RED);
                recordingNotifications.setShowBadge(true);
                recordingNotifications.enableVibration(true);

                recordingNotificationManager.createNotificationChannel(recordingNotifications);
            }
        }

        runningService = true;

        screenRecordingStart();
    }

    public void actionConnect(MainActivity.ActivityBinder service) {
        activityBinder = service;

        if (runningService == true) {
            if (isPaused == false) {
                if (activityBinder != null) {
                    activityBinder.recordingStart(timeStart);
                }
            } else if (isPaused == true) {
                if (activityBinder != null) {
                    activityBinder.recordingPause(timeRecorded);
                }
            }
        }
    }

    public void actionDisconnect() {
        activityBinder = null;
    }

    private void recordingError() {
        Toast.makeText(this, R.string.error_recorder_failed, Toast.LENGTH_SHORT).show();

        screenRecordingStop();
    }

    private void screenRecordingStart() {

        appSettings = getSharedPreferences(prefsident, 0);

        recordMicrophone = appSettings.getBoolean("checksoundmic", false);

        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd_HHmmss");

        String fullFileName = "ScreenRecording_" + formatter.format(Calendar.getInstance().getTime());
        String providertree = "^content://[^/]*/tree/";

        String filetreepattern = "^content://com\\.android\\.externalstorage\\.documents/tree/.*";

        Uri filefulluri = null;

        String documentspath = appSettings.getString("folderpath", "").replaceFirst(providertree, "");

        if (appSettings.getString("folderpath", "").matches(filetreepattern)) {
            if (documentspath.startsWith("primary%3A")) {
                filefulluri = Uri.parse("/storage/emulated/0/" + Uri.decode(documentspath.replaceFirst("primary%3A", "")) + "/" + fullFileName + ".mp4");
            } else {
                filefulluri = Uri.parse("/storage/" + Uri.decode(documentspath.replaceFirst("%3A", "/")) + "/" + fullFileName + ".mp4");
            }
        }

        try {
            Uri outdocpath = DocumentsContract.createDocument(getContentResolver(), Uri.parse(appSettings.getString("folderpath", "") + "/document/" + documentspath), "video/mp4", fullFileName);

            if (outdocpath == null) {
                recordingError();
                activityBinder.resetDir();
                stopSelf();
                return;
            } else {
                recordFilePath = outdocpath;
                recordFileFullPath = filefulluri;
            }
        } catch (FileNotFoundException e) {
            if (activityBinder != null) {
                recordingError();
                activityBinder.resetDir();
                stopSelf();
                return;
            }
        }

        timeStart = SystemClock.elapsedRealtime();

        Icon stopIcon = Icon.createWithResource(this, R.drawable.icon_stop_color_action);

        Icon recordingIcon = Icon.createWithResource(this, R.drawable.icon_record_status);

        Icon recordingIconLarge = Icon.createWithResource(this, R.drawable.icon_record_color_action_large);

        Intent stopRecordIntent = new Intent(this, ScreenRecorder.class);

        stopRecordIntent.setAction(ACTION_STOP);

        PendingIntent stopRecordActionIntent = PendingIntent.getService(this, 0, stopRecordIntent, 0);

        Notification.Action.Builder stopRecordAction = new Notification.Action.Builder(stopIcon, getString(R.string.notifications_stop), stopRecordActionIntent);


        Icon pauseIcon = Icon.createWithResource(this, R.drawable.icon_pause_color_action);

        Intent pauseRecordIntent = new Intent(this, ScreenRecorder.class);

        pauseRecordIntent.setAction(ACTION_PAUSE);

        PendingIntent pauseRecordActionIntent = PendingIntent.getService(this, 0, pauseRecordIntent, 0);

        Notification.Action.Builder pauseRecordAction = new Notification.Action.Builder(pauseIcon, getString(R.string.notifications_pause), pauseRecordActionIntent);

        Notification.Builder notification;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, NOTIFICATIONS_RECORDING_CHANNEL);
        } else {
            notification = new Notification.Builder(this);
        }

        notification = notification
            .setContentTitle(getString(R.string.recording_started_title))
            .setContentText(getString(R.string.recording_started_text))
            .setTicker(getString(R.string.recording_started_text))
            .setSmallIcon(recordingIcon)
            .setLargeIcon(recordingIconLarge)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis()-(SystemClock.elapsedRealtime()-timeStart))
            .setOngoing(true)
            .addAction(stopRecordAction.build());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            notification.addAction(pauseRecordAction.build());
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_RECORDING_ID, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_RECORDING_ID, notification.build());
        }



        isRestarting = false;

        if (activityBinder != null) {
            activityBinder.recordingStart(timeStart);
        }

        DisplayMetrics metrics = new DisplayMetrics();

        ((WindowManager)getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);

        try {
            recordingFileDescriptor = getContentResolver().openFileDescriptor(recordFilePath, "rw").getFileDescriptor();
        } catch (Exception e) {
            recordingError();
        }

        MediaProjectionManager recordingMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        recordingMediaProjection = recordingMediaProjectionManager.getMediaProjection(result, data);

        recordingVirtualDisplay = recordingMediaProjection.createVirtualDisplay("DroidRec", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null);


        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            recordingMediaRecorder = new MediaRecorder();

            recordingMediaRecorder.setOnErrorListener(new MediaRecorder.OnErrorListener() {
                @Override
                public void onError(MediaRecorder mr, int what, int extra) {
                    recordingError();
                }
            });

            try {
                String sampleRateValue = ((AudioManager)getSystemService(Context.AUDIO_SERVICE)).getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);

                int sampleRate = 44100;

                if (sampleRateValue != null) {
                    sampleRate = Integer.parseInt(sampleRateValue);
                }

                if (recordMicrophone == true) {
                    recordingMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                    recordingMediaRecorder.setAudioEncodingBitRate(sampleRate*32*2);
                    recordingMediaRecorder.setAudioSamplingRate(sampleRate);
                }

                recordingMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                recordingMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);


                recordingMediaRecorder.setOutputFile(recordingFileDescriptor);

                recordingMediaRecorder.setVideoSize(metrics.widthPixels, metrics.heightPixels);

                recordingMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);

                if (recordMicrophone == true) {
                    recordingMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                }


                recordingMediaRecorder.setVideoEncodingBitRate(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH).videoBitRate);

                recordingMediaRecorder.setVideoFrameRate(30);
                recordingMediaRecorder.prepare();
            } catch (IOException e) {
                recordingError();
            }
            try {
                recordingMediaRecorder.start();
            } catch (IllegalStateException e) {
                recordingMediaProjection.stop();
                recordingError();
            }
            recordingVirtualDisplay.setSurface(recordingMediaRecorder.getSurface());
        } else {
            recorderPlayback = new PlaybackRecorder(recordingVirtualDisplay, recordingFileDescriptor, recordingMediaProjection, metrics.widthPixels, metrics.heightPixels, recordMicrophone);

            recorderPlayback.start();
        }

        final Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();

        orientationOnStart = display.getRotation();

        OrientationEventListener restartRecordingOrientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientationOnStart != display.getRotation()) {
                    this.disable();
                    isRestarting = true;
                    screenRecordingStop();
                    screenRecordingStart();
                }
            }
        };

        restartRecordingOrientationListener.enable();

    }

    private void screenRecordingStop() {
        timeStart = 0;
        timeRecorded = 0;
        isPaused = false;

        if (isRestarting == false) {
            runningService = false;
        }

        if (activityBinder != null) {
            activityBinder.recordingStop();
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                recordingMediaRecorder.stop();
                recordingMediaRecorder.reset();
                recordingMediaRecorder.release();
            } catch (RuntimeException e) {
                recordingError();
            }
        } else {
            recorderPlayback.quit();
        }

        Intent openFolderIntent = new Intent(this, ScreenRecorder.class);

        openFolderIntent.setAction(ACTION_ACTIVITY_FINISHED_FILE);

        PendingIntent openFolderActionIntent = PendingIntent.getService(this, 0, openFolderIntent, 0);

        if (isRestarting == false) {
            Icon finishedIcon = Icon.createWithResource(this, R.drawable.icon_record_finished_status);

            Icon finishedIconLarge = Icon.createWithResource(this, R.drawable.icon_record_finished_color_action_large);

            Notification.Builder finishedNotification;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                finishedNotification = new Notification.Builder(this, NOTIFICATIONS_RECORDING_CHANNEL);
            } else {
                finishedNotification = new Notification.Builder(this);
            }

            finishedNotification = finishedNotification
                .setContentTitle(getString(R.string.recording_finished_title))
                .setContentText(getString(R.string.recording_finished_text))
                .setContentIntent(openFolderActionIntent)
                .setSmallIcon(finishedIcon)
                .setLargeIcon(finishedIconLarge)
                .setAutoCancel(true);

            recordingNotificationManager.notify(NOTIFICATION_RECORDING_FINISHED_ID, finishedNotification.build());
        } else {
            Icon restartIcon = Icon.createWithResource(this, R.drawable.icon_rotate_status);

            Icon restartIconLarge = Icon.createWithResource(this, R.drawable.icon_rotate_color_action_large);

            Notification.Builder restartNotification;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                restartNotification = new Notification.Builder(this, NOTIFICATIONS_RECORDING_CHANNEL);
            } else {
                restartNotification = new Notification.Builder(this);
            }

            restartNotification = restartNotification
                .setContentTitle(getString(R.string.recording_rotated_title))
                .setContentText(getString(R.string.recording_rotated_text))
                .setContentIntent(openFolderActionIntent)
                .setSmallIcon(restartIcon)
                .setLargeIcon(restartIconLarge)
                .setAutoCancel(true);

            recordingNotificationManager.notify(NOTIFICATION_RECORDING_FINISHED_ID, restartNotification.build());

        }

        stopForeground(Service.STOP_FOREGROUND_REMOVE);

        if (isRestarting == false) {
            stopSelf();
        }
    }

    private void screenRecordingPause() {
        isPaused = true;
        timeRecorded += SystemClock.elapsedRealtime() - timeStart;
        timeStart = 0;

        if (activityBinder != null) {
            activityBinder.recordingPause(timeRecorded);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            recordingMediaRecorder.pause();
        } else {
            recorderPlayback.pause();
        }

        Icon stopIcon = Icon.createWithResource(this, R.drawable.icon_stop_continue_color_action);

        Icon pausedIcon = Icon.createWithResource(this, R.drawable.icon_pause_status);

        Icon pausedIconLarge = Icon.createWithResource(this, R.drawable.icon_pause_color_action_large);

        Intent stopRecordIntent = new Intent(this, ScreenRecorder.class);

        stopRecordIntent.setAction(ACTION_STOP);

        PendingIntent stopRecordActionIntent = PendingIntent.getService(this, 0, stopRecordIntent, 0);

        Notification.Action.Builder stopRecordAction = new Notification.Action.Builder(stopIcon, getString(R.string.notifications_stop), stopRecordActionIntent);

        Icon continueIcon = Icon.createWithResource(this, R.drawable.icon_record_continue_color_action);

        Intent continueRecordIntent = new Intent(this, ScreenRecorder.class);

        continueRecordIntent.setAction(ACTION_CONTINUE);

        PendingIntent continueRecordActionIntent = PendingIntent.getService(this, 0, continueRecordIntent, 0);

        Notification.Action.Builder continueRecordAction = new Notification.Action.Builder(continueIcon, getString(R.string.notifications_resume), continueRecordActionIntent);

        Notification.Builder notification;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, NOTIFICATIONS_RECORDING_CHANNEL);
        } else {
            notification = new Notification.Builder(this);
        }

        notification = notification
            .setContentTitle(getString(R.string.recording_paused_title))    
            .setContentText(getString(R.string.recording_paused_text))
            .setSmallIcon(pausedIcon)
            .setLargeIcon(pausedIconLarge)
            .setOngoing(true)
            .addAction(stopRecordAction.build())
            .addAction(continueRecordAction.build());

        recordingNotificationManager.notify(NOTIFICATION_RECORDING_ID, notification.build());
    }

    private void screenRecordingResume() {
        isPaused = false;
        timeStart = SystemClock.elapsedRealtime() - timeRecorded;
        timeRecorded = 0;

        if (activityBinder != null) {
            activityBinder.recordingResume(timeStart);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            recordingMediaRecorder.resume();
        } else {
            recorderPlayback.resume();
        }

        Icon stopIcon = Icon.createWithResource(this, R.drawable.icon_stop_color_action);

        Icon recordingIcon = Icon.createWithResource(this, R.drawable.icon_record_status);

        Icon recordingIconLarge = Icon.createWithResource(this, R.drawable.icon_record_color_action_large);

        Intent stopRecordIntent = new Intent(this, ScreenRecorder.class);

        stopRecordIntent.setAction(ACTION_STOP);

        PendingIntent stopRecordActionIntent = PendingIntent.getService(this, 0, stopRecordIntent, 0);

        Notification.Action.Builder stopRecordAction = new Notification.Action.Builder(stopIcon, getString(R.string.notifications_stop), stopRecordActionIntent);


        Icon pauseIcon = Icon.createWithResource(this, R.drawable.icon_pause_color_action);

        Intent pauseRecordIntent = new Intent(this, ScreenRecorder.class);

        pauseRecordIntent.setAction(ACTION_PAUSE);

        PendingIntent pauseRecordActionIntent = PendingIntent.getService(this, 0, pauseRecordIntent, 0);

        Notification.Action.Builder pauseRecordAction = new Notification.Action.Builder(pauseIcon, getString(R.string.notifications_pause), pauseRecordActionIntent);

        Notification.Builder notification;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, NOTIFICATIONS_RECORDING_CHANNEL);
        } else {
            notification = new Notification.Builder(this);
        }

        notification = notification
            .setContentTitle(getString(R.string.recording_started_title))
            .setContentText(getString(R.string.recording_started_text))
            .setTicker(getString(R.string.recording_started_text))
            .setSmallIcon(recordingIcon)
            .setLargeIcon(recordingIconLarge)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis()-(SystemClock.elapsedRealtime()-timeStart))
            .setOngoing(true)
            .addAction(stopRecordAction.build())
            .addAction(pauseRecordAction.build());

        recordingNotificationManager.notify(NOTIFICATION_RECORDING_ID, notification.build());
    }

}
