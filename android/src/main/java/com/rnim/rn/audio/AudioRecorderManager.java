package com.rnim.rn.audio;

import android.Manifest;


import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.LifecycleEventListener;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableArray;

import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.ArrayList;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.BatteryManager;
import android.media.MediaRecorder;
import android.media.MediaMetadataRetriever;
import android.media.AudioManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Base64;
import android.util.Log;


import java.io.FileInputStream;

import java.lang.Math;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.lang.IllegalAccessException;
import java.lang.NoSuchMethodException;
import java.lang.Long;
import java.lang.System;

import static com.rnim.rn.audio.Constants.ERROR_INVALID_CONFIG;
import static com.rnim.rn.audio.Constants.NOTIFICATION_CONFIG;

class AudioRecorderManager extends ReactContextBaseJavaModule implements LifecycleEventListener {

  private static final String TAG = "ReactNativeAudio";

  private static final String DocumentDirectoryPath = "DocumentDirectoryPath";
  private static final String PicturesDirectoryPath = "PicturesDirectoryPath";
  private static final String MainBundlePath = "MainBundlePath";
  private static final String CachesDirectoryPath = "CachesDirectoryPath";
  private static final String LibraryDirectoryPath = "LibraryDirectoryPath";
  private static final String MusicDirectoryPath = "MusicDirectoryPath";
  private static final String DownloadsDirectoryPath = "DownloadsDirectoryPath";

  private Context context;
  private MediaRecorder recorder;
  private String currentOutputFile;
  private boolean isRecording = false;
  private boolean isPaused = false;
  private boolean includeBase64 = false;
  private Timer timer;
  private StopWatch stopWatch;
  private ArrayList<Long> timestamps;
  
  private boolean isPauseResumeCapable = false;
  private Method pauseMethod = null;
  private Method resumeMethod = null;

  BroadcastReceiver receiver;


  public AudioRecorderManager(ReactApplicationContext reactContext) {
    super(reactContext);
    reactContext.addLifecycleEventListener(this);
    this.context = reactContext;
    stopWatch = new StopWatch();
    
    isPauseResumeCapable = Build.VERSION.SDK_INT > Build.VERSION_CODES.M;
    if (isPauseResumeCapable) {
      try {
        pauseMethod = MediaRecorder.class.getMethod("pause");
        resumeMethod = MediaRecorder.class.getMethod("resume");
      } catch (NoSuchMethodException e) {
        Log.d("ERROR", "Failed to get a reference to pause and/or resume method");
      }
    }
  }

  @Override
  public Map<String, Object> getConstants() {
    Map<String, Object> constants = new HashMap<>();
    constants.put(DocumentDirectoryPath, this.getReactApplicationContext().getFilesDir().getAbsolutePath());
    constants.put(PicturesDirectoryPath, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath());
    constants.put(MainBundlePath, "");
    constants.put(CachesDirectoryPath, this.getReactApplicationContext().getCacheDir().getAbsolutePath());
    constants.put(LibraryDirectoryPath, "");
    constants.put(MusicDirectoryPath, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getAbsolutePath());
    constants.put(DownloadsDirectoryPath, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
    return constants;
  }

  @Override
  public String getName() {
    return "AudioRecorderManager";
  }

  @Override
  public void onHostResume() {
    // Log.d(TAG, "onHostResume");
  }

  @Override
  public void onHostPause() {
    // Log.d(TAG, "onHostPause");
  }

  @Override
  public void onHostDestroy() {
    // Log.d(TAG, "onHostDestroy");
    if (!isRecording || recorder == null){
      return;
    }

    stopTimer();
    isRecording = false;
    isPaused = false;

    try {
      saveTimestamp();
      recorder.stop();
      recorder.release();
      stopWatch.stop();
      stopService();
    } catch (final RuntimeException e) {
      // https://developer.android.com/reference/android/media/MediaRecorder.html#stop()
      Log.e("RUNTIME_EXCEPTION", "No valid audio data received. You may be using a device that can't record audio.");
    } finally {
      recorder = null;
    }
    unregisterBatteryListener();
  }

  @ReactMethod
  public void checkAuthorizationStatus(Promise promise) {
    int permissionCheck = ContextCompat.checkSelfPermission(getCurrentActivity(),
            Manifest.permission.RECORD_AUDIO);
    boolean permissionGranted = permissionCheck == PackageManager.PERMISSION_GRANTED;
    promise.resolve(permissionGranted);
  }

  @ReactMethod
  public void createNotificationChannel(ReadableMap channelConfig, Promise promise) {
    if (channelConfig == null) {
      logAndRejectPromise(promise, ERROR_INVALID_CONFIG, "ForegroundService: Channel config is invalid");
      return;
    }
    NotificationHelper.getInstance(getReactApplicationContext()).createNotificationChannel(channelConfig, promise);
  }

  @ReactMethod
  public void prepareRecordingAtPath(String recordingPath, ReadableMap recordingSettings, Promise promise) {
    if (isRecording){
      logAndRejectPromise(promise, "INVALID_STATE", "Please call stopRecording before starting recording");
    }
    File destFile = new File(recordingPath);
    if (destFile.getParentFile() != null) {
      destFile.getParentFile().mkdirs();
    }
    recorder = new MediaRecorder();
    timestamps = new ArrayList<Long>();
    try {
      recorder.setAudioSource(recordingSettings.getInt("AudioSource"));
      int outputFormat = getOutputFormatFromString(recordingSettings.getString("OutputFormat"));
      recorder.setOutputFormat(outputFormat);
      int audioEncoder = getAudioEncoderFromString(recordingSettings.getString("AudioEncoding"));
      recorder.setAudioEncoder(audioEncoder);
      recorder.setAudioSamplingRate(recordingSettings.getInt("SampleRate"));
      recorder.setAudioChannels(recordingSettings.getInt("Channels"));
      recorder.setAudioEncodingBitRate(recordingSettings.getInt("AudioEncodingBitRate"));
      recorder.setOutputFile(destFile.getPath());
      includeBase64 = recordingSettings.getBoolean("IncludeBase64");
    }
    catch(final Exception e) {
      logAndRejectPromise(promise, "COULDNT_CONFIGURE_MEDIA_RECORDER" , "Make sure you've added RECORD_AUDIO permission to your AndroidManifest.xml file "+e.getMessage());
      return;
    }

    currentOutputFile = recordingPath;
    try {
      recorder.prepare();
      promise.resolve(currentOutputFile);
    } catch (final Exception e) {
      logAndRejectPromise(promise, "COULDNT_PREPARE_RECORDING_AT_PATH "+recordingPath, e.getMessage());
    }

  }

  private int getAudioEncoderFromString(String audioEncoder) {
   switch (audioEncoder) {
     case "aac":
       return MediaRecorder.AudioEncoder.AAC;
     case "aac_eld":
       return MediaRecorder.AudioEncoder.AAC_ELD;
     case "amr_nb":
       return MediaRecorder.AudioEncoder.AMR_NB;
     case "amr_wb":
       return MediaRecorder.AudioEncoder.AMR_WB;
     case "he_aac":
       return MediaRecorder.AudioEncoder.HE_AAC;
     case "vorbis":
      return MediaRecorder.AudioEncoder.VORBIS;
     default:
       Log.d("INVALID_AUDIO_ENCODER", "USING MediaRecorder.AudioEncoder.DEFAULT instead of "+audioEncoder+": "+MediaRecorder.AudioEncoder.DEFAULT);
       return MediaRecorder.AudioEncoder.DEFAULT;
   }
  }

  private int getOutputFormatFromString(String outputFormat) {
    switch (outputFormat) {
      case "mpeg_4":
        return MediaRecorder.OutputFormat.MPEG_4;
      case "aac_adts":
        return MediaRecorder.OutputFormat.AAC_ADTS;
      case "amr_nb":
        return MediaRecorder.OutputFormat.AMR_NB;
      case "amr_wb":
        return MediaRecorder.OutputFormat.AMR_WB;
      case "three_gpp":
        return MediaRecorder.OutputFormat.THREE_GPP;
      case "webm":
        return MediaRecorder.OutputFormat.WEBM;
      default:
        Log.d("INVALID_OUPUT_FORMAT", "USING MediaRecorder.OutputFormat.DEFAULT : "+MediaRecorder.OutputFormat.DEFAULT);
        return MediaRecorder.OutputFormat.DEFAULT;

    }
  }

  @ReactMethod
  public void getRecordStatus(Promise promise) {
    WritableMap status = Arguments.createMap();
    status.putBoolean("isRecording", isRecording);
    status.putBoolean("isPaused", isPaused);

    promise.resolve(status);
  }

  @ReactMethod
  public void startRecording(Promise promise){
    if (recorder == null){
      logAndRejectPromise(promise, "RECORDING_NOT_PREPARED", "Please call prepareRecordingAtPath before starting recording");
      return;
    }
    if (isRecording){
      logAndRejectPromise(promise, "INVALID_STATE", "Please call stopRecording before starting recording");
      return;
    }
    saveTimestamp();
    recorder.start();
    stopWatch.reset();
    stopWatch.start();
    isRecording = true;
    isPaused = false;
    startTimer();
    broadcastRecordStatus();
    promise.resolve(currentOutputFile);
    registerBatteryListener();
  }

  @ReactMethod
  public void stopRecording(Promise promise){
    if (!isRecording){
      logAndRejectPromise(promise, "INVALID_STATE", "Please call startRecording before stopping recording");
      return;
    }

    stopTimer();
    isRecording = false;
    isPaused = false;

    try {
      saveTimestamp();
      recorder.stop();
      recorder.release();
      stopWatch.stop();
      stopService();
    }
    catch (final RuntimeException e) {
      // https://developer.android.com/reference/android/media/MediaRecorder.html#stop()
      logAndRejectPromise(promise, "RUNTIME_EXCEPTION", "No valid audio data received. You may be using a device that can't record audio.");
      return;
    }
    finally {
      recorder = null;
    }
    broadcastRecordStatus();
    promise.resolve(currentOutputFile);

    WritableMap result = Arguments.createMap();
    result.putString("status", "OK");
    result.putString("audioFileURL", "file://" + currentOutputFile);
    result.putDouble("duration", getDuration(currentOutputFile));

    String base64 = "";
    if (includeBase64) {
      try {
        InputStream inputStream = new FileInputStream(currentOutputFile);
        byte[] bytes;
        byte[] buffer = new byte[8192];
        int bytesRead;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
          while ((bytesRead = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
          }
        } catch (IOException e) {
          Log.e(TAG, "FAILED TO PARSE FILE");
        }
        bytes = output.toByteArray();
        base64 = Base64.encodeToString(bytes, Base64.DEFAULT);
      } catch(FileNotFoundException e) {
        Log.e(TAG, "FAILED TO FIND FILE");
      }
    }
    result.putString("base64", base64);

    WritableArray nativeArray = Arguments.fromList(timestamps);
    result.putArray("timestamps", nativeArray);
    
    sendEvent("recordingFinished", result);
    unregisterBatteryListener();
  }

  @ReactMethod
  public void pauseRecording(Promise promise) {
    if (!isPauseResumeCapable || pauseMethod==null) {
      logAndRejectPromise(promise, "RUNTIME_EXCEPTION", "Method not available on this version of Android.");
      return;
    }

    if (!isPaused) {
      try {
        pauseMethod.invoke(recorder);
        stopWatch.stop();
      } catch (InvocationTargetException | RuntimeException | IllegalAccessException e) {
        e.printStackTrace();
        logAndRejectPromise(promise, "RUNTIME_EXCEPTION", "Method not available on this version of Android.");
        return;
      }
    }

    isPaused = true;
    broadcastRecordStatus();
    promise.resolve(null);
  }

  @ReactMethod
  public void resumeRecording(Promise promise) {
    if (!isPauseResumeCapable || resumeMethod == null) {
      logAndRejectPromise(promise, "RUNTIME_EXCEPTION", "Method not available on this version of Android.");
      return;
    }

    if (isPaused) {
      try {
        resumeMethod.invoke(recorder);
        stopWatch.start();
      } catch (InvocationTargetException | RuntimeException | IllegalAccessException e) {
        e.printStackTrace();
        logAndRejectPromise(promise, "RUNTIME_EXCEPTION", "Method not available on this version of Android.");
        return;
      }
    }
    
    isPaused = false;
    broadcastRecordStatus();
    promise.resolve(null);
  }

  private void startTimer(){
    timer = new Timer();
    timer.scheduleAtFixedRate(new TimerTask() {
      @Override
      public void run() {
        if (!isPaused) {
          WritableMap body = Arguments.createMap();
          float currentTime = stopWatch.getTimeSeconds();
          body.putDouble("currentTime", currentTime);
          sendEvent("recordingProgress", body);

          startService("회의중", "회의 시간 - " + secondsToString(Math.round(currentTime)));
        }
      }
    }, 0, 1000);
  }

  private void stopTimer(){
    if (timer != null) {
      timer.cancel();
      timer.purge();
      timer = null;
    }
  }

  private void sendEvent(String eventName, Object params) {
    getReactApplicationContext()
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(eventName, params);
  }

  private void logAndRejectPromise(Promise promise, String errorCode, String errorMessage) {
    Log.e(TAG, errorMessage);
    promise.reject(errorCode, errorMessage);
  }

  private String secondsToString(int secs) {
    return String.format("%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
  }  

  private void startService(String title, String text) {
    Bundle notificationConfig = new Bundle();

    notificationConfig.putDouble("id", 345);
    notificationConfig.putString("title", title);
    notificationConfig.putString("text", text);
    notificationConfig.putString("icon", "ic_launcher");
    notificationConfig.putInt("priority", 0);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      notificationConfig.putString("channelId", "ForegroundServiceChannel");
    }

    Intent intent = new Intent(getReactApplicationContext(), ForegroundService.class);
    intent.setAction(Constants.ACTION_FOREGROUND_SERVICE_START);
    intent.putExtra(NOTIFICATION_CONFIG, notificationConfig);
    ComponentName componentName = getReactApplicationContext().startService(intent);
    if (componentName == null) {
        Log.e(TAG, "ForegroundService: Foreground service is not started");
    }
  }

  private void stopService() {
      Intent intent = new Intent(getReactApplicationContext(), ForegroundService.class);
      intent.setAction(Constants.ACTION_FOREGROUND_SERVICE_STOP);
      boolean stopped = getReactApplicationContext().stopService(intent);
      if (!stopped) {
        Log.e(TAG, "ForegroundService: Foreground service failed to stop");         
      } 
  }

  private void broadcastRecordStatus() {
    WritableMap status = Arguments.createMap();
    status.putBoolean("isRecording", isRecording);
    status.putBoolean("isPaused", isPaused);

    sendEvent("recordingStatus", status);
  }

  private void saveTimestamp() {
    long timestamp = System.currentTimeMillis();

    timestamps.add(timestamp);
  }

  private double getDuration(String mediaPath) {
    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
    mmr.setDataSource(mediaPath);
    String time = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
    mmr.release();

    long timeInmillisec = Long.parseLong(time);
    return (double)timeInmillisec / 1000;
  }

  private void registerBatteryListener() {
    IntentFilter intentFilter = new IntentFilter();
    intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
    if (this.receiver == null) {
      this.receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          updateBatteryState(intent);
        }
      };
      getReactApplicationContext().registerReceiver(this.receiver, intentFilter);
    }
  }

  private void unregisterBatteryListener() {
    if (this.receiver != null) {
      try {
        getReactApplicationContext().unregisterReceiver(this.receiver);
        this.receiver = null;
      } catch (Exception e) {
        Log.e(TAG, "Error unregistering battery receiver: " + e.getMessage(), e);
      }
    }
  }

  private void updateBatteryState(Intent batteryIntent) {
    int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
    int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
    boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL;

    if (level <= 5 && !isCharging) {
      forceStopRecording();
    }
  }

  private void forceStopRecording(){
    if (!isRecording){
      return;
    }

    stopTimer();
    isRecording = false;
    isPaused = false;

    try {
      saveTimestamp();
      recorder.stop();
      recorder.release();
      stopWatch.stop();
      stopService();
    }
    catch (final RuntimeException e) {
      // https://developer.android.com/reference/android/media/MediaRecorder.html#stop()
      Log.e("RUNTIME_EXCEPTION", "No valid audio data received. You may be using a device that can't record audio.");
      return;
    }
    finally {
      recorder = null;
    }
    broadcastRecordStatus();

    WritableMap result = Arguments.createMap();
    result.putString("status", "OK");
    result.putString("audioFileURL", "file://" + currentOutputFile);
    result.putDouble("duration", getDuration(currentOutputFile));

    String base64 = "";
    if (includeBase64) {
      try {
        InputStream inputStream = new FileInputStream(currentOutputFile);
        byte[] bytes;
        byte[] buffer = new byte[8192];
        int bytesRead;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
          while ((bytesRead = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
          }
        } catch (IOException e) {
          Log.e(TAG, "FAILED TO PARSE FILE");
        }
        bytes = output.toByteArray();
        base64 = Base64.encodeToString(bytes, Base64.DEFAULT);
      } catch(FileNotFoundException e) {
        Log.e(TAG, "FAILED TO FIND FILE");
      }
    }
    result.putString("base64", base64);

    WritableArray nativeArray = Arguments.fromList(timestamps);
    result.putArray("timestamps", nativeArray);
    
    sendEvent("recordingFinished", result);
    unregisterBatteryListener();
  }
}
