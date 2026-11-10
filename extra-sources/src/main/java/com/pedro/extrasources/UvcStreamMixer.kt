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
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.pedro.encoder.input.gl.SurfaceManager
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lets several cameras feed one stream surface and switch between them instantly.
 *
 * Every camera draws all the time into its own input surface ([inputSurface]), backed by a
 * SurfaceTexture of this class. On its GL thread the mixer copies the frames of the selected input
 * to the stream surface. Switching only changes which input is copied, and the last frame of the
 * new input is drawn at once, so the stream never waits for a camera to let go of a surface.
 */
internal class UvcStreamMixer(private val inputCount: Int) {

  companion object {
    private const val TAG = "UvcStreamMixer"
    private const val START_TIMEOUT_MS = 2000L

    private const val VERTEX_SHADER = """
      attribute vec4 aPosition;
      attribute vec4 aTexCoord;
      uniform mat4 uTexMatrix;
      varying vec2 vTexCoord;
      void main() {
        gl_Position = aPosition;
        vTexCoord = (uTexMatrix * aTexCoord).xy;
      }
    """

    private const val FRAGMENT_SHADER = """
      #extension GL_OES_EGL_image_external : require
      precision mediump float;
      uniform samplerExternalOES sTexture;
      varying vec2 vTexCoord;
      void main() {
        gl_FragColor = texture2D(sTexture, vTexCoord);
      }
    """

    // full screen quad: x, y, u, v
    private val QUAD = floatArrayOf(
      -1f, -1f, 0f, 0f,
      1f, -1f, 1f, 0f,
      -1f, 1f, 0f, 1f,
      1f, 1f, 1f, 1f
    )
  }

  private var thread: HandlerThread? = null
  private var handler: Handler? = null
  private val surfaceManager = SurfaceManager()
  private val textures = IntArray(inputCount)
  private val surfaceTextures = arrayOfNulls<SurfaceTexture>(inputCount)
  private val inputSurfaces = arrayOfNulls<Surface>(inputCount)
  // an input has drawn at least one frame since it was last cleared
  private val hasFrame = BooleanArray(inputCount)
  private val texMatrix = FloatArray(16)
  private var program = 0
  private var positionLocation = 0
  private var texCoordLocation = 0
  private var texMatrixLocation = 0
  private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4)
    .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD).position(0) }

  @Volatile private var selected = -1

  /**
   * Starts drawing into [streamSurface]. The input surfaces are [width] x [height], the stream
   * resolution, so every camera is scaled to it. Blocks until the input surfaces exist.
   */
  fun start(streamSurface: Surface, width: Int, height: Int) {
    if (thread != null) return
    val glThread = HandlerThread("MultiUvcGl").also { it.start() }
    val glHandler = Handler(glThread.looper)
    thread = glThread
    handler = glHandler
    val ready = CountDownLatch(1)
    glHandler.post {
      try {
        surfaceManager.eglSetup(streamSurface)
        surfaceManager.makeCurrent()
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        texMatrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")
        GlUtil.createExternalTextures(inputCount, textures, 0)
        for (i in 0 until inputCount) {
          val surfaceTexture = SurfaceTexture(textures[i])
          surfaceTexture.setDefaultBufferSize(width, height)
          surfaceTexture.setOnFrameAvailableListener({ onFrameAvailable(i) }, glHandler)
          surfaceTextures[i] = surfaceTexture
          inputSurfaces[i] = Surface(surfaceTexture)
        }
      } catch (e: RuntimeException) {
        Log.e(TAG, "GL setup failed", e)
      }
      ready.countDown()
    }
    ready.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  }

  fun stop() {
    val glThread = thread ?: return
    val done = CountDownLatch(1)
    handler?.post {
      for (i in 0 until inputCount) {
        inputSurfaces[i]?.release()
        inputSurfaces[i] = null
        surfaceTextures[i]?.release()
        surfaceTextures[i] = null
        hasFrame[i] = false
      }
      if (program != 0) GLES20.glDeleteProgram(program)
      program = 0
      GLES20.glDeleteTextures(inputCount, textures, 0)
      surfaceManager.release()
      done.countDown()
    }
    done.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    glThread.quitSafely()
    thread = null
    handler = null
    selected = -1
  }

  /** Surface the camera at [index] draws into, null when the mixer is not started. */
  fun inputSurface(index: Int): Surface? = inputSurfaces.getOrNull(index)

  /** Streams input [index] from now on, or nothing when -1. Its last frame is shown at once. */
  fun select(index: Int) {
    selected = index
    handler?.post {
      if (index != selected) return@post
      if (index >= 0 && hasFrame[index]) draw(index) else drawBlack()
    }
  }

  /** The camera at [index] went away: its old picture must not be shown again. */
  fun clear(index: Int) {
    handler?.post {
      if (index !in 0 until inputCount) return@post
      hasFrame[index] = false
      if (index == selected) drawBlack()
    }
  }

  private fun drawBlack() {
    if (!surfaceManager.isReady) return
    surfaceManager.makeCurrent()
    GLES20.glClearColor(0f, 0f, 0f, 1f)
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    surfaceManager.swapBuffer()
  }

  private fun onFrameAvailable(index: Int) {
    val surfaceTexture = surfaceTextures[index] ?: return
    if (!surfaceManager.isReady) return
    surfaceManager.makeCurrent()
    // always latch the frame, so the camera can keep drawing into this input
    surfaceTexture.updateTexImage()
    hasFrame[index] = true
    if (index == selected) draw(index)
  }

  private fun draw(index: Int) {
    val surfaceTexture = surfaceTextures[index] ?: return
    if (!surfaceManager.isReady || program == 0) return
    surfaceManager.makeCurrent()
    val size = IntArray(2)
    EGL14.eglQuerySurface(surfaceManager.eglDisplay, surfaceManager.eglSurface, EGL14.EGL_WIDTH, size, 0)
    EGL14.eglQuerySurface(surfaceManager.eglDisplay, surfaceManager.eglSurface, EGL14.EGL_HEIGHT, size, 1)
    GLES20.glViewport(0, 0, size[0], size[1])
    GLES20.glClearColor(0f, 0f, 0f, 1f)
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    GLES20.glUseProgram(program)
    surfaceTexture.getTransformMatrix(texMatrix)
    GLES20.glUniformMatrix4fv(texMatrixLocation, 1, false, texMatrix, 0)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[index])
    quad.position(0)
    GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, quad)
    GLES20.glEnableVertexAttribArray(positionLocation)
    quad.position(2)
    GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 16, quad)
    GLES20.glEnableVertexAttribArray(texCoordLocation)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GLES20.glDisableVertexAttribArray(positionLocation)
    GLES20.glDisableVertexAttribArray(texCoordLocation)
    surfaceManager.swapBuffer()
  }
}
