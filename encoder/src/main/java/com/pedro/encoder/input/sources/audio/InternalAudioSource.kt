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

package com.pedro.encoder.input.sources.audio

import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import com.pedro.common.TimeUtils
import com.pedro.common.debug.DebugCategory
import com.pedro.common.debug.DebugEvent
import com.pedro.common.debug.DebugLevel
import com.pedro.common.debug.DebugListener
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.CustomAudioEffect
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.audio.MicrophoneManager
import com.pedro.encoder.input.sources.MediaProjectionHandler

/**
 * Created by pedro on 12/1/24.
 */
typealias InternalSource = InternalAudioSource

@RequiresApi(Build.VERSION_CODES.Q)
class InternalAudioSource(
  mediaProjection: MediaProjection,
  mediaProjectionCallback: MediaProjection.Callback? = null,
): AudioSource(), GetMicrophoneData {

  private val TAG = "InternalAudioSource"
  private val microphone = MicrophoneManager(this)
  private var handlerThread = HandlerThread(TAG)
  private val mediaProjectionCallback = mediaProjectionCallback ?: object : MediaProjection.Callback() {}

  // Propagate the debug listener to the underlying MicrophoneManager
  override var debugListener: DebugListener?
    get() = super.debugListener
    set(value) {
      super.debugListener = value
      microphone.debugListener = value
    }

  init {
    MediaProjectionHandler.mediaProjection = mediaProjection
  }

  private fun emitDebug(level: DebugLevel, event: String, payload: Map<String, Any> = emptyMap()) {
    debugListener?.onDebugEvent(DebugEvent(TimeUtils.getCurrentTimeMillis(), level, DebugCategory.AUDIO, event, payload))
  }

  override fun create(sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean): Boolean {
    //create microphone to confirm valid parameters
    val result = microphone.createMicrophone(sampleRate, isStereo, echoCanceler, noiseSuppressor)
    if (!result) {
      throw IllegalArgumentException("Some parameters specified are not valid");
    }
    return true
  }

  override fun start(getMicrophoneData: GetMicrophoneData) {
    this.getMicrophoneData = getMicrophoneData
    if (!isRunning()) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val mediaProjection = MediaProjectionHandler.mediaProjection
        if (mediaProjection == null) {
          emitDebug(DebugLevel.ERROR, "InternalAudioStartFailed", mapOf("reason" to "MediaProjection is null"))
          throw IllegalStateException("MediaProjection is null")
        }
        handlerThread = HandlerThread(TAG)
        handlerThread.start()
        mediaProjection.registerCallback(mediaProjectionCallback, Handler(handlerThread.looper))
        val config = try {
          AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        } catch (e: Exception) {
          emitDebug(DebugLevel.ERROR, "InternalAudioStartFailed", mapOf("reason" to "AudioPlaybackCaptureConfiguration failed", "error" to (e.message ?: "")))
          throw IllegalArgumentException("AudioPlaybackCaptureConfiguration failed: ${e.message}", e)
        }
        val bufferSize = AudioEncoder.inputSize * 5
        emitDebug(DebugLevel.INFO, "InternalAudioCreated", mapOf(
          "audioPlaybackCaptureEnabled" to true,
          "sampleRate" to sampleRate,
          "channels" to (if (isStereo) 2 else 1),
          "bufferSize" to bufferSize
        ))
        try {
          val result = microphone.createInternalMicrophone(config, sampleRate, isStereo,
            echoCanceler, noiseSuppressor)
          if (!result) throw IllegalArgumentException("Failed to create internal audio source")
        } catch (e: UnsupportedOperationException) {
          emitDebug(DebugLevel.ERROR, "InternalAudioStartFailed", mapOf("reason" to "invalid MediaProjection used", "error" to (e.message ?: "")))
          throw IllegalArgumentException("invalid MediaProjection used")
        }
      } else {
        throw IllegalStateException("Using internal audio in a invalid Android version. Android 10+ is necessary")
      }
      emitDebug(DebugLevel.INFO, "InternalAudioStarted")
      microphone.start()
    }
  }

  override fun stop() {
    if (isRunning()) {
      this.getMicrophoneData = null
      microphone.stop()
      handlerThread.quitSafely()
      emitDebug(DebugLevel.INFO, "InternalAudioStopped")
    }
  }

  override fun isRunning(): Boolean = microphone.isRunning

  override fun release() {
    MediaProjectionHandler.mediaProjection?.unregisterCallback(mediaProjectionCallback)
    emitDebug(DebugLevel.INFO, "InternalAudioReleased")
  }

  override fun inputPCMData(frame: Frame) {
    getMicrophoneData?.inputPCMData(frame)
  }

  fun mute() {
    microphone.mute()
  }

  fun unMute() {
    microphone.unMute()
  }

  fun isMuted(): Boolean = microphone.isMuted

  fun setAudioEffect(effect: CustomAudioEffect) {
    microphone.setCustomAudioEffect(effect)
  }

  var internalVolume: Float
    set(value) { microphone.internalVolume = value }
    get() = microphone.internalVolume
}