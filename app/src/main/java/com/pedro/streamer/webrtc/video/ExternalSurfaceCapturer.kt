package com.pedro.streamer.webrtc.video

import android.content.Context
import android.util.Log
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

class ExternalSurfaceCapturer(
    private val onSurfaceReady: (Surface) -> Unit,
    private val onSurfaceDestroyed: () -> Unit
) : VideoCapturer, VideoSink {

    private var capturerObserver: CapturerObserver? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var surface: Surface? = null
    private var frameCount = 0

    override fun initialize(surfaceTextureHelper: SurfaceTextureHelper, context: Context, capturerObserver: CapturerObserver) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        surfaceTextureHelper?.let { helper ->
            helper.setTextureSize(width, height)
            val surfaceTexture = helper.surfaceTexture
            surfaceTexture.setDefaultBufferSize(width, height)
            val newSurface = Surface(surfaceTexture)
            surface = newSurface
            onSurfaceReady(newSurface)
            helper.startListening(this)
        }
    }

    override fun stopCapture() {
        capturerObserver?.onCapturerStopped()
        surfaceTextureHelper?.stopListening()
        onSurfaceDestroyed()
        surface?.release()
        surface = null
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        surfaceTextureHelper?.surfaceTexture?.setDefaultBufferSize(width, height)
    }

    override fun dispose() { stopCapture() }
    override fun isScreencast() = false

    override fun onFrame(frame: VideoFrame) {
        frameCount++
        frame.buffer.retain()
        val validFrame = VideoFrame(frame.buffer, frame.rotation, System.nanoTime())
        capturerObserver?.onFrameCaptured(validFrame)
        validFrame.release()
    }
}
