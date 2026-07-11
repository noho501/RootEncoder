/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.encoder.input.audio;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.pedro.common.TimeUtils;
import com.pedro.common.debug.DebugCategory;
import com.pedro.common.debug.DebugEvent;
import com.pedro.common.debug.DebugLevel;
import com.pedro.common.debug.DebugListener;
import com.pedro.encoder.Frame;
import com.pedro.encoder.audio.AudioEncoder;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by pedro on 19/01/17.
 */

@SuppressLint("MissingPermission")
public class MicrophoneManager {

  private final String TAG = "MicrophoneManager";
  private static final long MICROPHONE_START_DELAY_MS = 300L;
  protected AudioRecord audioRecord;
  protected AudioRecord audioRecordDevice;
  private final GetMicrophoneData getMicrophoneData;
  protected byte[] pcmBuffer = new byte[AudioEncoder.inputSize];
  protected byte[] pcmBufferDevice = new byte[AudioEncoder.inputSize];
  protected byte[] pcmBufferMix = new byte[AudioEncoder.inputSize];
  protected byte[] pcmBufferMuted = new byte[AudioEncoder.inputSize];
  protected boolean running = false;
  private boolean created = false;
  //default parameters for microphone
  private int sampleRate = 32000; //hz
  private final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
  private int channel = AudioFormat.CHANNEL_IN_STEREO;
  protected boolean muted = false;
  private AudioPostProcessEffect audioPostProcessEffect;
  protected HandlerThread handlerThread;
  protected CustomAudioEffect customAudioEffect = new NoAudioEffect();
  private Mode mode = Mode.MICROPHONE;
  private float microphoneVolume = 1f;
  private float internalVolume = 1f;
  private final AudioUtils audioUtils = new AudioUtils();
  // Debug listener — volatile so it can be swapped safely from any thread
  @Nullable
  public volatile DebugListener debugListener = null;
  // State for throttled AudioRead events (at most once per second)
  private long lastAudioReadTs = 0L;
  private int lastMicReadSize = 0;
  private int lastInternalReadSize = 0;
  private int lastMicReadResult = 0;
  private int lastInternalReadResult = 0;
  private boolean internalFirstReadEmitted = false;
  private boolean microphoneFirstReadEmitted = false;

  enum Mode {
    MICROPHONE, INTERNAL, MIX
  }

  public MicrophoneManager(GetMicrophoneData getMicrophoneData) {
    this.getMicrophoneData = getMicrophoneData;
  }

  // --- Debug helpers ---

  private void emitDebug(DebugLevel level, DebugCategory category, String event, Map<String, Object> payload) {
    DebugListener listener = debugListener;
    if (listener == null) return;
    listener.onDebugEvent(new DebugEvent(TimeUtils.getCurrentTimeMillis(), level, category, event, payload));
  }

  private void emitDebug(DebugLevel level, DebugCategory category, String event) {
    DebugListener listener = debugListener;
    if (listener == null) return;
    Map<String, Object> payload = new HashMap<>();
    listener.onDebugEvent(new DebugEvent(TimeUtils.getCurrentTimeMillis(), level, category, event, payload));
  }

  /** Returns a human-readable name for an AudioRecord error code. */
  private static String audioRecordErrorName(int error) {
    if (error == AudioRecord.ERROR) return "ERROR";
    if (error == AudioRecord.ERROR_BAD_VALUE) return "ERROR_BAD_VALUE";
    if (error == AudioRecord.ERROR_INVALID_OPERATION) return "ERROR_INVALID_OPERATION";
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && error == AudioRecord.ERROR_DEAD_OBJECT) {
      return "ERROR_DEAD_OBJECT";
    }
    return "UNKNOWN(" + error + ")";
  }

  @Nullable
  private static Context getApplicationContext() {
    try {
      Application application = (Application) Class.forName("android.app.ActivityThread")
          .getMethod("currentApplication")
          .invoke(null);
      return application != null ? application.getApplicationContext() : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  @Nullable
  private static AudioManager getAudioManager() {
    Context context = getApplicationContext();
    if (context == null) return null;
    Object service = context.getSystemService(Context.AUDIO_SERVICE);
    return service instanceof AudioManager ? (AudioManager) service : null;
  }

  private static String getAudioModeName(int mode) {
    if (mode == AudioManager.MODE_NORMAL) return "MODE_NORMAL";
    if (mode == AudioManager.MODE_RINGTONE) return "MODE_RINGTONE";
    if (mode == AudioManager.MODE_IN_CALL) return "MODE_IN_CALL";
    if (mode == AudioManager.MODE_IN_COMMUNICATION) return "MODE_IN_COMMUNICATION";
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mode == AudioManager.MODE_CALL_SCREENING) {
      return "MODE_CALL_SCREENING";
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mode == AudioManager.MODE_CALL_REDIRECT) {
      return "MODE_CALL_REDIRECT";
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mode == AudioManager.MODE_COMMUNICATION_REDIRECT) {
      return "MODE_COMMUNICATION_REDIRECT";
    }
    return "UNKNOWN(" + mode + ")";
  }

  @Nullable
  private AudioRecord getRouteAudioRecord() {
    if (mode == Mode.MICROPHONE) return audioRecord;
    if (mode == Mode.INTERNAL) return audioRecordDevice;
    return audioRecordDevice != null ? audioRecordDevice : audioRecord;
  }

  private void putAudioRuntimeState(Map<String, Object> payload) {
    AudioManager audioManager = getAudioManager();
    if (audioManager != null) {
      int audioMode = audioManager.getMode();
      payload.put("audioMode", getAudioModeName(audioMode));
    } else {
      payload.put("audioMode", "UNKNOWN");
    }

    Context context = getApplicationContext();
    payload.put("foregroundPackage", context != null ? context.getPackageName() : "unknown");

    boolean speaker = false;
    boolean wiredHeadset = false;
    boolean bluetooth = false;
    AudioRecord routedAudioRecord = getRouteAudioRecord();
    if (routedAudioRecord != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      AudioDeviceInfo routedDevice = routedAudioRecord.getRoutedDevice();
      if (routedDevice != null) {
        int type = routedDevice.getType();
        speaker = type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            || type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE;
        wiredHeadset = type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            || type == AudioDeviceInfo.TYPE_USB_HEADSET
            || type == AudioDeviceInfo.TYPE_USB_DEVICE;
        bluetooth = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
      }
    } else if (audioManager != null) {
      speaker = audioManager.isSpeakerphoneOn();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : devices) {
          int type = device.getType();
          if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
              || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            bluetooth = true;
          }
          if (type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
              || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
              || type == AudioDeviceInfo.TYPE_USB_HEADSET
              || type == AudioDeviceInfo.TYPE_USB_DEVICE) {
            wiredHeadset = true;
          }
        }
      } else {
        wiredHeadset = audioManager.isWiredHeadsetOn();
        bluetooth = audioManager.isBluetoothScoOn() || audioManager.isBluetoothA2dpOn();
      }
    }
    payload.put("speaker", speaker);
    payload.put("wiredHeadset", wiredHeadset);
    payload.put("bluetooth", bluetooth);
    payload.put("mediaProjectionAvailable", audioRecordDevice != null);
    payload.put("audioPlaybackCaptureEnabled", mode == Mode.INTERNAL || mode == Mode.MIX);
  }

  private void emitStartRecordingEvent(boolean internal) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("timestamp", TimeUtils.getCurrentTimeMillis());
    emitDebug(
        DebugLevel.INFO,
        DebugCategory.AUDIO,
        internal ? "InternalAudioStartRecording" : "MicrophoneStartRecording",
        payload
    );
  }

  private void maybeEmitFirstReadEvent(boolean internal, int readResult) {
    if (internal) {
      if (internalFirstReadEmitted) return;
      internalFirstReadEmitted = true;
    } else {
      if (microphoneFirstReadEmitted) return;
      microphoneFirstReadEmitted = true;
    }
    Map<String, Object> payload = new HashMap<>();
    payload.put("timestamp", TimeUtils.getCurrentTimeMillis());
    payload.put("readResult", readResult);
    emitDebug(
        DebugLevel.INFO,
        DebugCategory.AUDIO,
        internal ? "InternalAudioFirstRead" : "MicrophoneFirstRead",
        payload
    );
  }

  public void setCustomAudioEffect(CustomAudioEffect customAudioEffect) {
    this.customAudioEffect = customAudioEffect;
  }

  /**
   * Create audio record
   */
  public void createMicrophone() {
    createMicrophone(sampleRate, true, false, false);
    Log.i(TAG, "Microphone created, " + sampleRate + "hz, Stereo");
  }

  /**
   * Create audio record with params and default audio source
   */
  public boolean createMicrophone(int sampleRate, boolean isStereo, boolean echoCanceler,
      boolean noiseSuppressor) {
    return createMicrophone(MediaRecorder.AudioSource.DEFAULT, sampleRate, isStereo, echoCanceler,
        noiseSuppressor);
  }

  /**
   * Create audio record with params and selected audio source
   *
   * @param audioSource - the recording source. See {@link MediaRecorder.AudioSource} for the
   * recording source definitions.
   */
  public boolean createMicrophone(int audioSource, int sampleRate, boolean isStereo,
                                  boolean echoCanceler, boolean noiseSuppressor) {
      try {
          this.sampleRate = sampleRate;
          channel = isStereo ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
          getPcmBufferSize(sampleRate, channel);

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
              audioRecord = new AudioRecord.Builder()
                      .setAudioFormat(new AudioFormat.Builder().setEncoding(audioFormat)
                              .setSampleRate(sampleRate)
                              .setChannelMask(channel)
                              .build())
                      .setAudioSource(audioSource)
                      .setBufferSizeInBytes(AudioEncoder.inputSize * 5)
                      .build();
          } else {
              audioRecord = new AudioRecord(audioSource, sampleRate, channel, audioFormat, AudioEncoder.inputSize * 5);
          }

          audioPostProcessEffect = new AudioPostProcessEffect(audioRecord.getAudioSessionId());
          if (echoCanceler) audioPostProcessEffect.enableEchoCanceler();
          if (noiseSuppressor) audioPostProcessEffect.enableNoiseSuppressor();
          String chl = (isStereo) ? "Stereo" : "Mono";
          if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
              throw new IllegalArgumentException("Some parameters specified are not valid");
          }
          Log.i(TAG, "Microphone created, " + sampleRate + "hz, " + chl);
          mode = Mode.MICROPHONE;
          created = true;
          Map<String, Object> payload = new HashMap<>();
          payload.put("sampleRate", sampleRate);
          payload.put("channelCount", isStereo ? 2 : 1);
          payload.put("stereo", isStereo);
          payload.put("bufferSize", pcmBuffer.length);
          payload.put("audioSource", audioSource);
          payload.put("mode", mode.name());
          payload.put("recordState", audioRecord.getState());
          payload.put("sessionId", audioRecord.getAudioSessionId());
          emitDebug(DebugLevel.INFO, DebugCategory.AUDIO, "AudioRecordCreated", payload);

      } catch (Exception e) {
          Log.e(TAG, "create microphone error", e);
          Map<String, Object> payload = new HashMap<>();
          payload.put("sampleRate", sampleRate);
          payload.put("channelCount", isStereo ? 2 : 1);
          payload.put("stereo", isStereo);
          payload.put("audioSource", audioSource);
          payload.put("error", e.getMessage());
          emitDebug(DebugLevel.ERROR, DebugCategory.AUDIO, "AudioRecordInitFailed", payload);
      }
      return created;
  }

  /**
   * Create audio record with params and AudioPlaybackCaptureConfig used for capturing internal
   * audio
   * Notice that you should granted {@link android.Manifest.permission#RECORD_AUDIO} before calling
   * this!
   *
   * @param config - AudioPlaybackCaptureConfiguration received from {@link
   * android.media.projection.MediaProjection}
   * @see AudioPlaybackCaptureConfiguration.Builder#Builder(MediaProjection)
   * @see "https://developer.android.com/guide/topics/media/playback-capture"
   * @see "https://medium.com/@debuggingisfun/android-10-audio-capture-77dd8e9070f9"
   */
  public boolean createInternalMicrophone(AudioPlaybackCaptureConfiguration config, int sampleRate,
                                          boolean isStereo, boolean echoCanceler, boolean noiseSuppressor) {
      try {
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
              throw new IllegalStateException("Internal microphone unsupported in this Android version");
          }

          this.sampleRate = sampleRate;
          channel = isStereo ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
          getPcmBufferSize(sampleRate, channel);
          audioRecordDevice = new AudioRecord.Builder()
                  .setAudioPlaybackCaptureConfig(config)
                  .setAudioFormat(new AudioFormat.Builder().setEncoding(audioFormat)
                          .setSampleRate(sampleRate)
                          .setChannelMask(channel)
                          .build())
                  .setBufferSizeInBytes(AudioEncoder.inputSize * 5)
                  .build();
          audioPostProcessEffect = new AudioPostProcessEffect(audioRecordDevice.getAudioSessionId());
          if (echoCanceler) audioPostProcessEffect.enableEchoCanceler();
          if (noiseSuppressor) audioPostProcessEffect.enableNoiseSuppressor();
          String chl = (isStereo) ? "Stereo" : "Mono";
          if (audioRecordDevice.getState() != AudioRecord.STATE_INITIALIZED) {
              throw new IllegalArgumentException("Some parameters specified are not valid");
          }
          Log.i(TAG, "Internal microphone created, " + sampleRate + "hz, " + chl);
          mode = Mode.INTERNAL;
          created = true;
          Map<String, Object> payload = new HashMap<>();
          payload.put("sampleRate", sampleRate);
          payload.put("channelCount", isStereo ? 2 : 1);
          payload.put("stereo", isStereo);
          payload.put("bufferSize", pcmBufferDevice.length);
          payload.put("audioSource", "AudioPlaybackCapture");
          payload.put("mode", mode.name());
          payload.put("recordState", audioRecordDevice.getState());
          payload.put("sessionId", audioRecordDevice.getAudioSessionId());
          emitDebug(DebugLevel.INFO, DebugCategory.AUDIO, "AudioRecordCreated", payload);

      } catch (Exception e) {
          Log.e(TAG, "create microphone error", e);
          Map<String, Object> payload = new HashMap<>();
          payload.put("sampleRate", sampleRate);
          payload.put("channelCount", isStereo ? 2 : 1);
          payload.put("stereo", isStereo);
          payload.put("audioSource", "AudioPlaybackCapture");
          payload.put("error", e.getMessage());
          emitDebug(DebugLevel.ERROR, DebugCategory.AUDIO, "AudioRecordInitFailed", payload);
      }
      return created;
  }

    public boolean createMixMicrophone(
            int audioSource, AudioPlaybackCaptureConfiguration config, int sampleRate,
            boolean isStereo, boolean echoCanceler, boolean noiseSuppressor
    ) {
        boolean internalResult = createInternalMicrophone(config, sampleRate, isStereo, echoCanceler, noiseSuppressor);
        if (!internalResult) return false;

        boolean micResult = createMicrophone(audioSource, sampleRate, isStereo, echoCanceler, noiseSuppressor);
        if (!micResult) return false;

        mode = Mode.MIX;

        // Phần log debug giữ nguyên
        Map<String, Object> payload = new HashMap<>();
        payload.put("sampleRate", sampleRate);
        payload.put("channelCount", isStereo ? 2 : 1);
        payload.put("stereo", isStereo);
        payload.put("bufferSize", pcmBuffer.length);
        payload.put("audioSource", audioSource);
        payload.put("mode", mode.name());
        payload.put("recordState", audioRecord.getState());
        payload.put("sessionId", audioRecord.getAudioSessionId());
        emitDebug(DebugLevel.INFO, DebugCategory.AUDIO, "AudioRecordCreated", payload);

        return true;
    }

  public boolean createInternalMicrophone(AudioPlaybackCaptureConfiguration config, int sampleRate,
      boolean isStereo) {
    return createInternalMicrophone(config, sampleRate, isStereo, false, false);
  }

  /**
   * Start record and get data
   */
  public synchronized void start() {
    internalFirstReadEmitted = false;
    microphoneFirstReadEmitted = false;
    init();
    if (mode == Mode.MIX) {
      Map<String, Object> compatibilityPayload = new HashMap<>();
      compatibilityPayload.put("startOrder", "INTERNAL_MICROPHONE");
      compatibilityPayload.put("microphoneStartDelay", MICROPHONE_START_DELAY_MS);
      emitDebug(DebugLevel.INFO, DebugCategory.AUDIO, "AudioCompatibility", compatibilityPayload);
    }
    Map<String, Object> payload = new HashMap<>();
    putAudioRuntimeState(payload);
    emitDebug(DebugLevel.INFO, DebugCategory.AUDIO, "AudioRecordStarted", payload);
    handlerThread = new HandlerThread(TAG);
    handlerThread.start();
    Handler handler = new Handler(handlerThread.getLooper());
    handler.post(() -> {
      while (running) {
        Frame frame = read();
        if (frame != null) {
          getMicrophoneData.inputPCMData(frame);
        }
      }
    });
  }

  private void init() {
    switch (mode) {
        case MICROPHONE -> {
          if (audioRecord != null) {
            audioRecord.startRecording();
            emitStartRecordingEvent(false);
          } else {
            throw new IllegalStateException("Error starting, microphone was stopped or not created, use createMicrophone() before start()");
          }
        }
        case INTERNAL -> {
          if (audioRecordDevice != null) {
            audioRecordDevice.startRecording();
            emitStartRecordingEvent(true);
          } else {
            throw new IllegalStateException("Error starting, microphone was stopped or not created, use createMicrophone() before start()");
          }
        }
        case MIX -> {
            if (audioRecord != null && audioRecordDevice != null) {
                audioRecordDevice.startRecording();
                emitStartRecordingEvent(true);

                try {
                    Thread.sleep(MICROPHONE_START_DELAY_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }

                audioRecord.startRecording();
                emitStartRecordingEvent(false);
            } else {
                throw new IllegalStateException("Error starting, microphone was stopped or not created, use createMicrophone() before start()");
            }
        }
    }
    running = true;
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  public boolean setPreferredDevice(AudioDeviceInfo deviceInfo){
    if(audioRecord == null) {
      Log.w(TAG, "audioRecord not created");
      return false;
    }
    return audioRecord.setPreferredDevice(deviceInfo);
  }

  public void mute() {
    muted = true;
  }

  public void unMute() {
    muted = false;
  }

  public boolean isMuted() {
    return muted;
  }

  /**
   * @return Object with size and PCM buffer data
   */
  protected Frame read() {
    long timeStamp = TimeUtils.getCurrentTimeMicro();
    switch (mode) {
        case MICROPHONE -> {
          int size = audioRecord.read(pcmBuffer, 0, pcmBuffer.length);
          maybeEmitFirstReadEvent(false, size);
          if (size < 0) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("readResult", size);
            payload.put("errorName", audioRecordErrorName(size));
            payload.put("recordingState", audioRecord.getRecordingState());
            emitDebug(DebugLevel.ERROR, DebugCategory.AUDIO, "AudioReadError", payload);
            return null;
          }
          lastMicReadSize = size;
          lastMicReadResult = size;
          audioUtils.applyVolume(pcmBuffer, microphoneVolume);
          maybeEmitAudioReadStats();
          return new Frame(muted ? pcmBufferMuted : customAudioEffect.process(pcmBuffer), 0, size, timeStamp);
        }
        case INTERNAL -> {
          int size = audioRecordDevice.read(pcmBufferDevice, 0, pcmBufferDevice.length);
          maybeEmitFirstReadEvent(true, size);
          if (size < 0) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("readResult", size);
            payload.put("errorName", audioRecordErrorName(size));
            payload.put("recordingState", audioRecordDevice.getRecordingState());
            emitDebug(DebugLevel.ERROR, DebugCategory.AUDIO, "AudioReadError", payload);
            return null;
          }
          lastInternalReadSize = size;
          lastInternalReadResult = size;
          audioUtils.applyVolume(pcmBufferDevice, internalVolume);
          maybeEmitAudioReadStats();
          return new Frame(muted ? pcmBufferMuted : customAudioEffect.process(pcmBufferDevice), 0, size, timeStamp);
        }
        case MIX -> {
          int size = audioRecord.read(pcmBuffer, 0, pcmBuffer.length);
          maybeEmitFirstReadEvent(false, size);
          if (size < 0) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("readResult", size);
            payload.put("errorName", audioRecordErrorName(size));
            payload.put("source", "mic");
            payload.put("recordingState", audioRecord.getRecordingState());
            emitDebug(DebugLevel.ERROR, DebugCategory.AUDIO, "AudioReadError", payload);
            return null;
          }
          lastMicReadSize = size;
          lastMicReadResult = size;
          int sizeInternal = audioRecordDevice.read(pcmBufferDevice, 0, pcmBufferDevice.length);
          maybeEmitFirstReadEvent(true, sizeInternal);
          if (sizeInternal < 0) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("readResult", sizeInternal);
            payload.put("errorName", audioRecordErrorName(sizeInternal));
            payload.put("source", "internal");
            payload.put("recordingState", audioRecordDevice.getRecordingState());
            emitDebug(DebugLevel.ERROR, DebugCategory.AUDIO, "AudioReadError", payload);
            return null;
          }
          lastInternalReadSize = sizeInternal;
          lastInternalReadResult = sizeInternal;
          audioUtils.applyVolumeAndMix(pcmBuffer, microphoneVolume, pcmBufferDevice, internalVolume, pcmBufferMix);
          maybeEmitAudioReadStats();
          return new Frame(muted ? pcmBufferMuted : customAudioEffect.process(pcmBufferMix), 0, size, timeStamp);
        }
        default -> { return null; }
    }
  }

  /**
   * Emit a throttled AudioRead stats event — at most once per second.
   * Called from the read loop; no-ops when no listener is registered.
   */
  private void maybeEmitAudioReadStats() {
    if (debugListener == null) return;
    long now = TimeUtils.getCurrentTimeMillis();
    if (now - lastAudioReadTs < 1000) return;
    lastAudioReadTs = now;

    Map<String, Object> payload = new HashMap<>();

    if (mode == Mode.MICROPHONE || mode == Mode.MIX) {
      float micRms = audioUtils.calculateAmplitude(pcmBuffer);
      payload.put("micReadSize", lastMicReadSize);
      payload.put("micReadResult", lastMicReadResult);
      payload.put("micRms", micRms);
      payload.put("micSilent", micRms < 1f);
      payload.put("micRecordingState", audioRecord != null ? audioRecord.getRecordingState() : -1);
      payload.put("microphoneVolume", microphoneVolume);
    }

    if (mode == Mode.INTERNAL || mode == Mode.MIX) {
      float internalRms = audioUtils.calculateAmplitude(pcmBufferDevice);
      payload.put("internalReadSize", lastInternalReadSize);
      payload.put("internalReadResult", lastInternalReadResult);
      payload.put("internalRms", internalRms);
      payload.put("internalSilent", internalRms < 1f);
      payload.put("internalRecordingState", audioRecordDevice != null ? audioRecordDevice.getRecordingState() : -1);
      payload.put("internalVolume", internalVolume);
    }

    if (mode == Mode.MIX) {
      float mixRms = audioUtils.calculateAmplitude(pcmBufferMix);
      payload.put("mixRms", mixRms);
    }
    putAudioRuntimeState(payload);

    emitDebug(DebugLevel.INFO, DebugCategory.AUDIO, "AudioRead", payload);
  }

  /**
   * Stop and release microphone
   */
  public synchronized void stop() {
    running = false;
    created = false;
    internalFirstReadEmitted = false;
    microphoneFirstReadEmitted = false;
    emitDebug(DebugLevel.INFO, DebugCategory.AUDIO, "AudioRecordStopped");
    if (handlerThread != null) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
        handlerThread.quitSafely();
      } else {
        handlerThread.quit();
      }
    }
    if (audioRecord != null) {
      audioRecord.setRecordPositionUpdateListener(null);
      audioRecord.stop();
      audioRecord.release();
      audioRecord = null;
    }
    if (audioRecordDevice != null) {
      audioRecordDevice.setRecordPositionUpdateListener(null);
      audioRecordDevice.stop();
      audioRecordDevice.release();
      audioRecordDevice = null;
    }
    if (audioPostProcessEffect != null) {
      audioPostProcessEffect.release();
    }
    emitDebug(DebugLevel.INFO, DebugCategory.AUDIO, "AudioRecordReleased");
    Log.i(TAG, "Microphone stopped");
  }

  /**
   * Get PCM buffer size
   */
  private void getPcmBufferSize(int sampleRate, int channel) {
    int minSize = AudioRecord.getMinBufferSize(sampleRate, channel, audioFormat);
    int bufferSize = Math.max(minSize, AudioEncoder.inputSize);
    pcmBuffer = new byte[bufferSize];
    pcmBufferDevice = new byte[bufferSize];
    pcmBufferMix = new byte[bufferSize];
    pcmBufferMuted = new byte[bufferSize];
  }

  public int getSampleRate() {
    return sampleRate;
  }

  public void setSampleRate(int sampleRate) {
    this.sampleRate = sampleRate;
  }

  public int getAudioFormat() {
    return audioFormat;
  }

  public int getChannel() {
    return channel;
  }

  public boolean isRunning() {
    return running;
  }

  public boolean isCreated() {
    return created;
  }

  public void setVolume(float audioUtils) {
    setMicrophoneVolume(audioUtils);
    setInternalVolume(audioUtils);
  }

  public void setMicrophoneVolume(float microphoneVolume) {
    this.microphoneVolume = microphoneVolume;
  }

  public void setInternalVolume(float internalVolume) {
    this.internalVolume = internalVolume;
  }

  public float getMicrophoneVolume() {
    return microphoneVolume;
  }

  public float getInternalVolume() {
    return internalVolume;
  }
}
