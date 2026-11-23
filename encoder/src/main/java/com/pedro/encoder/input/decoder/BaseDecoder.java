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

package com.pedro.encoder.input.decoder;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import com.pedro.common.TimeUtils;
import com.pedro.encoder.CodecErrorCallback;
import com.pedro.encoder.utils.CodecUtil;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class BaseDecoder {

  protected String TAG = "BaseDecoder";
  protected MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
  protected MediaCodec codec;
  protected volatile boolean running = false;
  protected MediaFormat mediaFormat;
  private ExecutorService executor;
  private static final int MAX_EOS_DRAIN_RETRIES = 15;
  protected String mime = "";
  protected volatile boolean loopMode = false;
  private volatile long startTs = 0;
  protected long duration;
  protected final Object sync = new Object();
  protected AtomicBoolean pause = new AtomicBoolean(false);
  protected volatile boolean looped = false;
  private final DecoderInterface decoderInterface;
  private Extractor extractor = new AndroidExtractor();
  private CodecErrorCallback codecErrorCallback;
  protected CodecUtil.CodecTypeError typeError;

  public BaseDecoder(DecoderInterface decoderInterface) {
    this.decoderInterface = decoderInterface;
  }

  public boolean initExtractor(String filePath) throws IOException {
    extractor.initialize(filePath);
    return extract(extractor);
  }

  public boolean initExtractor(FileDescriptor fileDescriptor) throws IOException {
    extractor.initialize(fileDescriptor);
    return extract(extractor);
  }

  public boolean initExtractor(Context context, Uri uri) throws IOException {
    extractor.initialize(context, uri);
    return extract(extractor);
  }

  public void setCodecErrorCallback(CodecErrorCallback codecErrorCallback) {
    this.codecErrorCallback = codecErrorCallback;
  }

  public void start() {
    start(0);
  }

  public void start(long startTs) {
    Log.i(TAG, "start decoder");
    if (startTs > 0) {
      this.startTs = startTs;
    }
    running = true;
    try {
      codec.start();
    } catch (IllegalStateException e) {
      Log.e(TAG, "start decoder failed", e);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        if (e instanceof MediaCodec.CodecException) {
          CodecErrorCallback callback = codecErrorCallback;
          if (callback != null) callback.onCodecError(typeError, (MediaCodec.CodecException) e);
        }
      }
    }
    executor = Executors.newSingleThreadExecutor();
    executor.execute(() -> {
      try {
        decode();
      } catch (IllegalStateException e) {
        Log.i(TAG, "Decoding error", e);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          if (e instanceof MediaCodec.CodecException) {
            CodecErrorCallback callback = codecErrorCallback;
            if (callback != null) callback.onCodecError(typeError, (MediaCodec.CodecException) e);
          }
        }
      } catch (NullPointerException e) {
        Log.i(TAG, "Decoder maybe was stopped");
        Log.i(TAG, "Decoding error", e);
      }
    });
  }

  public void stop() {
    Log.i(TAG, "stop decoder");
    running = false;
    stopDecoder();
    startTs = 0;
    extractor.release();
  }

  public void reset(Surface surface) {
    boolean wasRunning = running;
    stopDecoder(!wasRunning);
    moveTo(0);
    if (!wasRunning) resetExtractorTiming();
    boolean prepared = prepare(surface);
    if (wasRunning && prepared) start();
  }

  public boolean prepareReset(Surface surface) {
    return prepareReset(surface, 0);
  }

  public boolean prepareReset(Surface surface, double time) {
    stopDecoder(true);
    moveTo(time);
    resetExtractorTiming();
    return prepare(surface);
  }

  public void resetExtractorTiming() {
    synchronized (sync) {
      extractor.resetTiming();
    }
  }

  protected boolean prepare(Surface surface) {
    try {
      codec = MediaCodec.createDecoderByType(mime);
      codec.configure(mediaFormat, surface, null, 0);
      return true;
    } catch (IOException e) {
      Log.e(TAG, "Prepare decoder error:", e);
      return false;
    }
  }

  protected void resetCodec(Surface surface) {
    boolean wasRunning = running;
    stopDecoder(!wasRunning);
    boolean prepared = prepare(surface);
    if (wasRunning && prepared) {
      start();
    }
  }

  protected void stopDecoder() {
    stopDecoder(true);
  }

  protected void stopDecoder(boolean clearTs) {
    running = false;
    looped = false;
    if (clearTs) startTs = 0;
    ExecutorService oldExecutor = executor;
    executor = null;
    if (oldExecutor != null) {
      oldExecutor.shutdownNow();
    }
    synchronized (sync) {
      if (codec != null) {
        try {
          codec.flush();
        } catch (IllegalStateException ignored) { }
        try {
          codec.stop();
          codec.release();
        } catch (IllegalStateException | NullPointerException ignored) { }
        codec = null;
      }
    }
  }

  public void moveTo(double time) {
    synchronized (sync) {
      extractor.seekTo((long) (time * 10E5));
    }
  }

  public void setLoopMode(boolean loopMode) {
    this.loopMode = loopMode;
  }

  public boolean isLoopMode() {
    return loopMode;
  }

  public double getDuration() {
    if (duration < 0) return duration; //fail to extract duration from file.
    return duration / 10E5;
  }

  public double getTime() {
    if (running) {
      return extractor.getTimeStamp() / 10E5;
    } else {
      return 0;
    }
  }

  public boolean isRunning() {
    return running;
  }

  protected abstract boolean extract(Extractor extractor) throws IOException;

  protected abstract boolean decodeOutput(ByteBuffer outputBuffer, long timeStamp);

  protected abstract void finished();

  private void decode() {
    if (startTs == 0) {
      moveTo(0); //make sure that we are on the start
      startTs = TimeUtils.getCurrentTimeMicro();
    }
    boolean shouldFinish = false;
    boolean inputEosReached = false;
    int eosDrainEmptyCount = 0;
    long sleepTime = 0;
    while (running) {
        if (pause.get()) continue;
        if (shouldFinish) {
          finished();
          break;
        }
        if (looped) {
          looped = false;
          decoderInterface.onLoop();
          return;
        }
      synchronized (sync) {
        if (!running || codec == null) break;
        int inIndex = inputEosReached ? -1 : codec.dequeueInputBuffer(10000);
        int sampleSize;
        long timeStamp = TimeUtils.getCurrentTimeMicro();
        boolean finished = false;
        if (inIndex >= 0) {
          ByteBuffer input;
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            input = codec.getInputBuffer(inIndex);
          } else {
            input = codec.getInputBuffers()[inIndex];
          }
          if (input == null) continue;
          sampleSize = extractor.readFrame(input);
          if (sampleSize <= 0) {
            inputEosReached = true;
            finished = true;
            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
          } else {
            long ts = TimeUtils.getCurrentTimeMicro() - startTs;
            sleepTime = extractor.getSleepTime(ts);
            finished = !extractor.advance();
            if (finished) {
              inputEosReached = true;
              codec.queueInputBuffer(inIndex, 0, sampleSize, ts + sleepTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            } else {
              codec.queueInputBuffer(inIndex, 0, sampleSize, ts + sleepTime, 0);
            }
          }
        }
        int outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000);
        if (outIndex >= 0) {
          eosDrainEmptyCount = 0;
          long effectiveSleep = inputEosReached
              ? (bufferInfo.size > 0 ? Math.min(sleepTime, 50L) : 0L)
              : sleepTime;
          if (!sleep(effectiveSleep)) return;
          ByteBuffer output;
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            output = codec.getOutputBuffer(outIndex);
          } else {
            output = codec.getOutputBuffers()[outIndex];
          }
          boolean render = bufferInfo.size > 0 && output != null && decodeOutput(output, timeStamp);
          codec.releaseOutputBuffer(outIndex, render && bufferInfo.size != 0);
          if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            if (loopMode) {
              looped = true;
            } else {
              Log.i(TAG, "end of file");
              shouldFinish = true;
            }
          }
        } else if (inputEosReached) {
          eosDrainEmptyCount++;
          if (eosDrainEmptyCount >= MAX_EOS_DRAIN_RETRIES) {
            if (loopMode) {
              looped = true;
            } else {
              Log.i(TAG, "end of file");
              shouldFinish = true;
            }
          }
        }
      }
    }
  }

  private boolean sleep(long sleepTime) {
    try {
      Thread.sleep(sleepTime);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  public void setExtractor(Extractor extractor) {
    this.extractor = extractor;
  }

  public Extractor getExtractor() {
    return extractor;
  }
}
