package com.pedro.streamer.rotation

import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.pedro.extrasources.MultiCameraUvcSource
import com.pedro.extrasources.MultiCameraUvcSource.CameraInfo
import com.pedro.extrasources.MultiCameraUvcSource.CameraState
import com.pedro.streamer.R

/**
 * The Multi UVC panel below the stream preview: one thumbnail per UVC camera, tapping a thumbnail
 * streams that camera. It shows once two cameras are plugged in, and the close button hides it
 * until the number of cameras changes.
 */
class MultiUvcPanel(private val panel: View) : MultiCameraUvcSource.Listener {

  private val context = panel.context
  private val items = listOf(R.id.multi_uvc_cam1, R.id.multi_uvc_cam2).mapIndexed { index, id ->
    Item(index, panel.findViewById(id))
  }
  private var source: MultiCameraUvcSource? = null
  private var closedByUser = false
  private var lastDeviceCount = 0

  init {
    panel.findViewById<View>(R.id.multi_uvc_close).setOnClickListener {
      closedByUser = true
      panel.visibility = View.GONE
    }
  }

  /** Binds the panel to [newSource], or hides it when the UVC source is no longer used (null). */
  fun attach(newSource: MultiCameraUvcSource?) {
    source?.listener = null
    source = newSource
    closedByUser = false
    lastDeviceCount = 0
    items.forEach { it.bindSurface() }
    if (newSource == null) {
      panel.visibility = View.GONE
    } else {
      newSource.listener = this
    }
  }

  override fun onCamerasChanged(cameras: List<CameraInfo>, selectedIndex: Int, deviceCount: Int) {
    if (deviceCount != lastDeviceCount) {
      lastDeviceCount = deviceCount
      closedByUser = false
    }
    panel.visibility =
      if (deviceCount >= MultiCameraUvcSource.CAMERA_COUNT && !closedByUser) View.VISIBLE else View.GONE
    cameras.forEach { camera -> items.getOrNull(camera.index)?.update(camera, camera.index == selectedIndex) }
  }

  private inner class Item(private val index: Int, root: View) : TextureView.SurfaceTextureListener {
    private val container: FrameLayout = root.findViewById(R.id.multi_uvc_preview_container)
    private val placeholder: View = root.findViewById(R.id.multi_uvc_placeholder)
    private val placeholderText: TextView = root.findViewById(R.id.multi_uvc_placeholder_text)
    private val statusDot: View = root.findViewById(R.id.multi_uvc_status_dot)
    private val selectedBorder: View = root.findViewById(R.id.multi_uvc_selected_border)
    private val textureView = TextureView(context)
    private var surface: Surface? = null

    init {
      root.findViewById<TextView>(R.id.multi_uvc_label).text =
        context.getString(R.string.multi_uvc_cam_label, index + 1)
      container.addView(textureView, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
      textureView.surfaceTextureListener = this
      root.setOnClickListener { source?.selectCamera(index) }
    }

    /** Hands the thumbnail surface to the current source, if it is ready. */
    fun bindSurface() {
      source?.setPreviewSurface(index, surface)
    }

    fun update(camera: CameraInfo, selected: Boolean) {
      selectedBorder.visibility = if (selected) View.VISIBLE else View.GONE
      placeholder.visibility = if (camera.state == CameraState.LIVE) View.GONE else View.VISIBLE
      placeholderText.text = when (camera.state) {
        CameraState.EMPTY -> context.getString(R.string.multi_uvc_slot_empty)
        CameraState.CONNECTING ->
          if (camera.tryingWidth > 0) {
            context.getString(R.string.multi_uvc_trying_size, camera.tryingWidth, camera.tryingHeight)
          } else {
            context.getString(R.string.multi_uvc_connecting)
          }
        CameraState.NO_BANDWIDTH -> context.getString(R.string.multi_uvc_no_bandwidth)
        CameraState.NO_PICTURE -> context.getString(
          if (camera.receivesFrames) R.string.multi_uvc_no_picture_undecodable
          else R.string.multi_uvc_no_picture_no_frames
        )
        CameraState.LIVE -> ""
      }
      val warning = camera.state == CameraState.NO_BANDWIDTH || camera.state == CameraState.NO_PICTURE
      placeholderText.setTextColor(ContextCompat.getColor(context,
        if (warning) R.color.multi_uvc_status_busy else R.color.multi_uvc_text_secondary))
      setDotColor(when (camera.state) {
        CameraState.EMPTY -> R.color.multi_uvc_status_idle
        CameraState.LIVE -> R.color.multi_uvc_status_live
        else -> R.color.multi_uvc_status_busy
      })
      fitAspectRatio(camera.width, camera.height)
    }

    private fun setDotColor(colorRes: Int) {
      (statusDot.background.mutate() as? GradientDrawable)?.setColor(ContextCompat.getColor(context, colorRes))
    }

    /** Letterboxes the thumbnail so a 4:3 camera is not stretched into the 16:9 slot. */
    private fun fitAspectRatio(width: Int, height: Int) {
      val maxWidth = container.width
      val maxHeight = container.height
      val params = textureView.layoutParams as FrameLayout.LayoutParams
      if (width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0) {
        params.width = FrameLayout.LayoutParams.MATCH_PARENT
        params.height = FrameLayout.LayoutParams.MATCH_PARENT
      } else if (width.toLong() * maxHeight > height.toLong() * maxWidth) {
        params.width = maxWidth
        params.height = (maxWidth.toLong() * height / width).toInt()
      } else {
        params.width = (maxHeight.toLong() * width / height).toInt()
        params.height = maxHeight
      }
      textureView.layoutParams = params
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
      surface = Surface(surfaceTexture)
      bindSurface()
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
      source?.setPreviewSurface(index, null)
      surface?.release()
      surface = null
      return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
  }
}
