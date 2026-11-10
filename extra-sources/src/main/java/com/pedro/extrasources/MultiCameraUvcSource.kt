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

package com.pedro.extrasources

import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.VideoSource
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.UVCParam
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Up to two UVC cameras at once. The selected camera feeds the stream, and every camera can also
 * draw into its own preview surface (the thumbnails of the Multi UVC panel).
 *
 * Camera handling follows MultiCameraActivity of UVCAndroid: one CameraHelper per camera,
 * USB permission requested for one device at a time, a camera that cannot start for lack of USB
 * bandwidth is started again once the other camera closes, the frame counters of every camera are
 * polled to tell "no picture" from a live camera, and a camera whose frames never decode is moved
 * to a lower resolution.
 *
 * Every open camera keeps drawing into its own input of a [UvcStreamMixer], which copies the
 * selected input to the stream, so switching the streamed camera is immediate.
 *
 * All methods must be called on the main thread, [listener] is called on the main thread.
 */
class MultiCameraUvcSource : VideoSource() {

  enum class CameraState {
    /** No device in this slot */
    EMPTY,
    /** Device plugged in and opening, or open without a decoded frame yet */
    CONNECTING,
    /** Open, but the stream could not start: another camera uses the USB bandwidth */
    NO_BANDWIDTH,
    /** Stream running, but no frame decoded for a while */
    NO_PICTURE,
    LIVE
  }

  /**
   * @param receivesFrames in [CameraState.NO_PICTURE]: true when frames arrive but none decodes,
   * false when the camera sends no frames at all
   * @param tryingWidth / tryingHeight lower resolution being tried for a camera whose frames did not
   * decode, 0 otherwise
   */
  data class CameraInfo(
    val index: Int,
    val state: CameraState,
    val width: Int,
    val height: Int,
    val receivesFrames: Boolean,
    val tryingWidth: Int,
    val tryingHeight: Int
  )

  interface Listener {
    /** Called whenever a camera changes state, or the streamed camera changes. */
    fun onCamerasChanged(cameras: List<CameraInfo>, selectedIndex: Int, deviceCount: Int)
  }

  companion object {
    const val CAMERA_COUNT = 2
    private const val MAX_BUSY_RETRIES = 3
    private const val BUSY_RETRY_DELAY_MS = 1000L
    private const val BANDWIDTH_RETRY_DELAY_MS = 1000L
    private const val STATS_POLL_MS = 500L
    // a running stream without a decoded frame for this long has no picture
    private const val NO_PICTURE_TIMEOUT_MS = 3000L
    // frames that must arrive, none of them decodable, before a lower resolution is tried
    private const val MIN_UNDECODABLE_FRAMES = 10
    private const val MAX_RESOLUTION_FALLBACKS = 2
    // polls without counters before a camera stops waiting for them and is shown as live
    private const val MAX_MISSING_COUNTS = 6
    // stream size used when the source is started before the encoder size is known
    private const val DEFAULT_WIDTH = 1280
    private const val DEFAULT_HEIGHT = 720

    // resolution that decoded after a fallback, per camera model (vid:pid), reused when it reconnects
    private val fallbackSizes = ConcurrentHashMap<String, Size>()

    private fun modelKey(device: UsbDevice) =
      String.format(Locale.US, "%04x:%04x", device.vendorId, device.productId)
  }

  var listener: Listener? = null
    set(value) {
      field = value
      notifyChanged()
    }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val slots = List(CAMERA_COUNT) { Slot(it) }
  private var selectedIndex = -1
  private var running = false
  private var streamSurface: Surface? = null
  private val mixer = UvcStreamMixer(CAMERA_COUNT)

  // USB permission is requested for one device at a time
  private var selectThread: HandlerThread? = null
  private var selectHandler: Handler? = null
  private val readyDevices = ConcurrentLinkedQueue<UsbDevice>()
  private val readyDeviceCondition = ConditionVariable()

  // Frame counters are read off the main thread: the call waits while a camera starts its preview
  private var statsThread: HandlerThread? = null
  private var statsHandler: Handler? = null

  override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean = true

  override fun start(surfaceTexture: SurfaceTexture) {
    this.surfaceTexture = surfaceTexture
    val surface = Surface(surfaceTexture)
    streamSurface = surface
    mixer.start(surface, if (width > 0) width else DEFAULT_WIDTH, if (height > 0) height else DEFAULT_HEIGHT)
    running = true
    selectThread = HandlerThread("MultiUvcSelect").also {
      it.start()
      selectHandler = Handler(it.looper)
    }
    statsThread = HandlerThread("MultiUvcStats").also {
      it.start()
      statsHandler = Handler(it.looper).apply { post(statsPoll) }
    }
    mainHandler.post {
      if (running) slots.forEach { it.createHelper() }
    }
  }

  override fun stop() {
    running = false
    mainHandler.removeCallbacksAndMessages(null)
    statsHandler?.removeCallbacksAndMessages(null)
    statsThread?.quit()
    statsThread = null
    statsHandler = null
    slots.forEach { it.releaseHelper() }
    // Unblock a select that is still waiting for another device's permission
    readyDevices.clear()
    readyDeviceCondition.open()
    selectThread?.quitSafely()
    selectThread = null
    selectHandler = null
    mixer.stop()
    streamSurface?.release()
    streamSurface = null
    selectedIndex = -1
    notifyChanged()
  }

  override fun release() {}

  override fun isRunning(): Boolean = running

  override fun getOrientationConfig() = OrientationConfig(forced = OrientationForced.LANDSCAPE)

  /** Surface the camera at [index] also draws into, e.g. its thumbnail, or null to stop. */
  fun setPreviewSurface(index: Int, surface: Surface?) {
    slots.getOrNull(index)?.setPreviewSurface(surface)
  }

  /** Streams the camera at [index]. Only a camera that shows a picture can be selected. */
  fun selectCamera(index: Int) {
    val slot = slots.getOrNull(index) ?: return
    if (slot.state() != CameraState.LIVE || index == selectedIndex) return
    streamFrom(slot)
    notifyChanged()
  }

  /** Streams [slot], or nothing when null. The mixer shows its last frame at once. */
  private fun streamFrom(slot: Slot?) {
    selectedIndex = slot?.index ?: -1
    mixer.select(selectedIndex)
  }

  fun getCameras(): List<CameraInfo> = slots.map { it.info() }

  fun getSelectedIndex() = selectedIndex

  /** Cameras plugged in, open or not. */
  fun getDeviceCount() = slots.count { it.device != null }

  private fun notifyChanged() {
    listener?.onCamerasChanged(getCameras(), selectedIndex, getDeviceCount())
  }

  private fun selectFirstOpened() {
    streamFrom(slots.firstOrNull { it.opened })
  }

  /**
   * Puts a newly attached device into the first free slot. Every helper reports every device,
   * so this runs once per helper for the same device and ignores the repeats.
   */
  private fun assignDevice(device: UsbDevice) {
    if (slots.any { it.device == device }) return
    val free = slots.firstOrNull { it.device == null && it.helper != null } ?: return
    free.device = device
    free.busyRetries = 0
    selectDevice(free, device)
    notifyChanged()
  }

  private fun releaseDevice(device: UsbDevice) {
    slots.filter { it.device == device }.forEach { it.device = null }
    removeSelectedDevice(device)
    notifyChanged()
  }

  private fun selectDevice(slot: Slot, device: UsbDevice) {
    selectHandler?.post {
      waitCanSelectDevice(device)
      mainHandler.post {
        val helper = slot.helper
        if (running && helper != null && device == slot.device) {
          helper.selectDevice(device)
        } else {
          removeSelectedDevice(device)
        }
      }
    }
  }

  /** Waits until no other device is asking for its USB permission. */
  private fun waitCanSelectDevice(device: UsbDevice) {
    readyDevices.add(device)
    while (readyDevices.size > 1) {
      readyDeviceCondition.block()
      readyDeviceCondition.close()
    }
  }

  private fun removeSelectedDevice(device: UsbDevice) {
    readyDevices.remove(device)
    readyDeviceCondition.open()
  }

  /**
   * A camera whose stream could not start for lack of USB bandwidth gets another startPreview
   * once another camera has closed and released its bandwidth.
   */
  private fun retryNoBandwidthCameras() {
    slots.filter { it.noBandwidth && it.opened && it.helper != null }.forEach {
      it.noBandwidth = false
      it.helper?.startPreview()
      it.resetPictureWatch()
    }
    notifyChanged()
  }

  /** Reads the frame counters of every open camera and hands them to the camera on the main thread. */
  private val statsPoll = object : Runnable {
    override fun run() {
      for (slot in slots) {
        val helper = slot.helper
        val generation = slot.previewGeneration
        if (helper != null && slot.opened) {
          val counts = helper.previewFrameCounts
          mainHandler.post { slot.onFrameCounts(helper, generation, counts) }
        }
      }
      if (running) {
        statsHandler?.let {
          // one pending poll at most
          it.removeCallbacks(this)
          it.postDelayed(this, STATS_POLL_MS)
        }
      }
    }
  }

  private inner class Slot(val index: Int) : ICameraHelper.StateCallback {

    // helper, opened and previewGeneration are also read by the stats thread
    @Volatile var helper: ICameraHelper? = null
    var device: UsbDevice? = null
    @Volatile var opened = false
    // the camera is open but its stream could not start (UVC_ERROR_NO_BANDWIDTH)
    var noBandwidth = false
    var busyRetries = 0
    private var previewSurface: Surface? = null
    // the camera draws into its mixer input
    private var inputAttached = false

    // Picture watch, restarted with every startPreview. Counters read for an older generation
    // are dropped, since the native counters restart at 0 with each preview.
    @Volatile var previewGeneration = 0
    private var lastDecodeAt = 0L
    private var lastReceived = 0
    private var lastDecoded = 0
    private var everDecoded = false
    private var noPicture = false
    private var missingCounts = 0
    // no counters arrived, so the camera is shown as live without checking
    private var countsMissing = false

    // resolution fallback for cameras whose frames do not decode
    private var previewSize: Size? = null
    private var supportedSizes: List<Size>? = null
    private var fallbacks = 0
    // the lower resolution being tried, until it shows a picture
    private var fallbackSize: Size? = null

    fun createHelper() {
      if (helper == null) {
        helper = CameraHelper().also { it.setStateCallback(this) }
      }
    }

    fun releaseHelper() {
      detachInput()
      previewSurface?.let { helper?.removeSurface(it) }
      helper?.release()
      helper = null
      device = null
      opened = false
      noBandwidth = false
      resetPictureWatch()
      fallbackSize = null
      previewSize = null
    }

    fun state(): CameraState = when {
      opened && noBandwidth -> CameraState.NO_BANDWIDTH
      opened && noPicture -> CameraState.NO_PICTURE
      opened -> if (everDecoded || countsMissing) CameraState.LIVE else CameraState.CONNECTING
      device != null -> CameraState.CONNECTING
      else -> CameraState.EMPTY
    }

    fun info() = CameraInfo(
      index = index,
      state = state(),
      width = previewSize?.width ?: 0,
      height = previewSize?.height ?: 0,
      receivesFrames = lastReceived > 0,
      tryingWidth = fallbackSize?.width ?: 0,
      tryingHeight = fallbackSize?.height ?: 0
    )

    fun setPreviewSurface(surface: Surface?) {
      if (surface == previewSurface) return
      val camera = helper
      if (opened && camera != null) {
        previewSurface?.let { camera.removeSurface(it) }
        surface?.let { camera.addSurface(it, false) }
      }
      previewSurface = surface
    }

    /** Lets the open camera draw into its mixer input, which feeds the stream when selected. */
    fun attachInput() {
      val camera = helper ?: return
      val surface = mixer.inputSurface(index) ?: return
      if (opened && !inputAttached) {
        camera.addSurface(surface, false)
        inputAttached = true
      }
    }

    fun detachInput() {
      val surface = mixer.inputSurface(index)
      if (inputAttached && surface != null) helper?.removeSurface(surface)
      inputAttached = false
      mixer.clear(index)
    }

    fun resetPictureWatch() {
      previewGeneration++
      lastDecodeAt = SystemClock.uptimeMillis()
      lastReceived = 0
      lastDecoded = 0
      everDecoded = false
      noPicture = false
      countsMissing = false
      missingCounts = 0
    }

    /** Frame counters {received, decoded} since startPreview, or null when the camera gave none. */
    fun onFrameCounts(from: ICameraHelper, generation: Int, counts: IntArray?) {
      if (from !== helper || generation != previewGeneration || !opened || noBandwidth) return
      val now = SystemClock.uptimeMillis()
      if (counts == null || counts.size < 2) {
        if (!everDecoded && !countsMissing && ++missingCounts >= MAX_MISSING_COUNTS) {
          countsMissing = true
          notifyChanged()
        }
        return
      }
      missingCounts = 0
      val received = counts[0]
      val decoded = counts[1]
      var changed = false
      if (decoded > lastDecoded) {
        lastDecodeAt = now
        if (!everDecoded) {
          everDecoded = true
          changed = true
          val camera = device
          val fallback = fallbackSize
          if (fallback != null && camera != null) fallbackSizes[modelKey(camera)] = fallback
          fallbackSize = null
        }
        if (noPicture) {
          noPicture = false
          changed = true
        }
      }
      lastReceived = received
      lastDecoded = decoded
      if (!noPicture && now - lastDecodeAt >= NO_PICTURE_TIMEOUT_MS) {
        noPicture = true
        changed = true
        if (!everDecoded && received >= MIN_UNDECODABLE_FRAMES) tryLowerResolution()
      }
      if (changed) notifyChanged()
    }

    /** Frames arrive but none decodes at this resolution: restart at the next lower one. */
    private fun tryLowerResolution() {
      val camera = helper
      val next = if (fallbacks < MAX_RESOLUTION_FALLBACKS) nextLowerSize(previewSize) else null
      if (next == null || camera == null) {
        fallbackSize = null
        return
      }
      fallbacks++
      fallbackSize = next
      previewSize = next
      camera.stopPreview()
      camera.setPreviewSize(next)
      camera.startPreview()
      resetPictureWatch()
    }

    /** The largest supported size of the same frame type (MJPEG / YUV) below the current one. */
    private fun nextLowerSize(current: Size?): Size? {
      val sizes = supportedSizes ?: return null
      if (current == null) return null
      val currentArea = current.width.toLong() * current.height
      return sizes
        .filter { it.type == current.type && it.width.toLong() * it.height < currentArea }
        .maxByOrNull { it.width.toLong() * it.height }
    }

    private fun openCamera() {
      val param = UVCParam()
      param.setQuirks(UVCCamera.UVC_QUIRK_FIX_BANDWIDTH)
      device?.let { fallbackSizes[modelKey(it)] }?.let { param.setPreviewSize(it) }
      helper?.openCamera(param)
    }

    override fun onAttach(device: UsbDevice) {
      assignDevice(device)
    }

    override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
      if (helper != null && device == this.device) openCamera()
      removeSelectedDevice(device)
    }

    override fun onCameraOpen(device: UsbDevice) {
      val camera = helper ?: return
      if (device != this.device) return
      supportedSizes = camera.supportedSizeList
      noBandwidth = false
      fallbacks = 0
      fallbackSize = null
      camera.startPreview()
      previewSize = camera.previewSize
      opened = true
      resetPictureWatch()
      previewSurface?.let { camera.addSurface(it, false) }
      attachInput()
      if (selectedIndex < 0) streamFrom(this)
      notifyChanged()
    }

    override fun onCameraClose(device: UsbDevice) {
      if (device != this.device && !opened) return
      detachInput()
      opened = false
      if (selectedIndex == index) selectFirstOpened()
      previewSurface?.let { helper?.removeSurface(it) }
      noBandwidth = false
      resetPictureWatch()
      fallbackSize = null
      previewSize = null
      notifyChanged()
      if (slots.any { it.noBandwidth }) {
        mainHandler.postDelayed({ retryNoBandwidthCameras() }, BANDWIDTH_RETRY_DELAY_MS)
      }
    }

    override fun onDeviceClose(device: UsbDevice) {}

    override fun onDetach(device: UsbDevice) {
      releaseDevice(device)
    }

    override fun onCancel(device: UsbDevice) {
      releaseDevice(device)
    }

    override fun onError(device: UsbDevice, e: CameraException) {
      if (e.code == CameraException.CAMERA_PREVIEW_ERROR_NO_BANDWIDTH) {
        // Open but not streaming; retried when another camera closes
        if (device == this.device && opened) {
          noBandwidth = true
          notifyChanged()
        }
        return
      }
      // The camera may still be held by a previous user for a moment
      if (e.code == CameraException.CAMERA_OPEN_ERROR_BUSY && device == this.device &&
        busyRetries < MAX_BUSY_RETRIES
      ) {
        busyRetries++
        mainHandler.postDelayed({
          if (helper != null && device == this.device && !opened) openCamera()
        }, BUSY_RETRY_DELAY_MS)
      }
    }
  }
}
