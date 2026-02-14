# SRT Receiver Testing Guide

## Testing Setup

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    SRT Streaming Test Setup                     │
└─────────────────────────────────────────────────────────────────┘

┌──────────────────┐                        ┌──────────────────┐
│   Sender Device  │                        │  Android Device  │
│  (Caller Mode)   │                        │ (Listener Mode)  │
├──────────────────┤                        ├──────────────────┤
│                  │                        │                  │
│  FFmpeg/OBS      │   SRT Stream (TS)     │  SrtReceiver     │
│  Camera/Video    │ ─────────────────────>│  Activity        │
│                  │  Port 9991 (caller)   │                  │
│  Encodes H.264   │                        │  Decodes Video   │
│  Encodes AAC     │                        │  Plays Audio     │
│                  │                        │                  │
└──────────────────┘                        └──────────────────┘
      Sender                                     Receiver
    mode=caller                                mode=listener
```

## Test Scenarios

### Scenario 1: Android as Receiver (Listener)

**What was implemented:** New `SrtReceiverActivity`

1. **Android Device (Receiver - Listener Mode):**
   ```
   Launch app → "SRT Receiver" → Port: 9991 → "Start Receiver"
   
   URL: srt://192.168.1.100:9991?mode=listener
   Status: "Listening on port 9991, waiting for connection..."
   ```

2. **Computer/Another Device (Sender - Caller Mode):**
   ```bash
   ffmpeg -re -i video.mp4 -c copy -f mpegts "srt://192.168.1.100:9991?mode=caller"
   ```

3. **Expected Result:**
   - Android receives SRT stream
   - Video displays on SurfaceView
   - Audio plays through speakers
   - Status updates to show connection

### Scenario 2: Android as Sender (Caller) - Already Exists

**What already exists:** Existing SRT streaming activities (OldApiActivity, etc.)

1. **Android Device (Sender - Caller Mode):**
   ```
   Launch app → "Old API" or any streaming activity
   Enter URL: srt://192.168.1.200:9991?mode=caller
   Start camera → Start Stream
   ```

2. **Computer/Media Player (Receiver - Listener Mode):**
   ```bash
   ffplay srt://0.0.0.0:9991?mode=listener
   ```

3. **Expected Result:**
   - Android streams camera to remote player
   - Remote player displays video/audio

## Demo Activity Features

### SrtReceiverActivity UI Components

```
┌─────────────────────────────────────────┐
│  Status: Listening on port 9991...     │  ← Status text
├─────────────────────────────────────────┤
│                                         │
│        [Port: 9991]                     │  ← Port input
│                                         │
├─────────────────────────────────────────┤
│                                         │
│                                         │
│         SurfaceView (Video)             │  ← Video display
│                                         │
│                                         │
├─────────────────────────────────────────┤
│       [Start Receiver Button]           │  ← Control button
└─────────────────────────────────────────┘
```

### Activity Features

- ✅ SurfaceView for video rendering
- ✅ Port configuration (default: 9991)
- ✅ Start/Stop controls
- ✅ Status display with connection info
- ✅ Auto-detect device IP address
- ✅ Shows FFmpeg command for easy testing
- ✅ Proper lifecycle management (stops on destroy)

## Testing Checklist

### Pre-requisites
- [ ] libsrt built for Android (all ABIs)
- [ ] libsrt.so placed in `srt/src/main/jniLibs/<ABI>/`
- [ ] SRT headers in `srt/src/main/cpp/srt-include/srt/`
- [ ] Build project successfully
- [ ] Install app on Android device

### Receiver Test (mode=listener)
- [ ] Launch app
- [ ] Select "SRT Receiver" from menu
- [ ] Enter port 9991
- [ ] Click "Start Receiver"
- [ ] Note the IP address displayed
- [ ] From computer, run FFmpeg with caller mode
- [ ] Verify video displays on Android
- [ ] Verify audio plays on Android
- [ ] Check logcat for connection logs
- [ ] Stop receiver, verify clean shutdown

### Expected Logs

```
I/SrtReceiver: Starting SRT receiver on port 9991
I/SrtServerSocket: SRT server started on port 9991, fd=3
I/SrtServerSocket: Waiting for client connection...
I/SrtServerSocket: Client connected, fd=4
I/SrtReceiver: Client connected
I/TsDemuxer: PMT PID updated: 4096
I/TsDemuxer: Video PID updated: 256
I/TsDemuxer: Audio PID updated: 257
I/H264Parser: SPS received, size=24
I/H264Parser: PPS received, size=8
I/SrtReceiver: H264 config ready (SPS/PPS)
I/VideoDecoder: Configuring video decoder: 1920x1080
I/VideoDecoder: Video decoder started
I/AudioDecoder: Configuring audio decoder: sampleRate=48000, channels=2
I/AudioDecoder: Audio decoder started
I/SrtServerSocket: Bitrate: 2500 kbps
```

## Troubleshooting

### No connection
- Verify devices are on same network
- Check firewall allows port 9991
- Confirm IP address is correct
- Ensure sender uses `mode=caller`

### No video
- Check logs for "H264 config ready"
- Verify sender encodes H.264
- Check SurfaceView is visible
- Verify SPS/PPS received

### No audio
- Verify sender encodes AAC
- Check "Audio decoder started" in logs
- Test with different audio source

### Build errors
- Verify libsrt.so exists for all ABIs
- Check CMakeLists.txt paths
- Ensure NDK is installed
- Check SRT headers are present

## FFmpeg Test Commands

### Send test pattern with video and audio:
```bash
ffmpeg -f lavfi -i testsrc=size=1280x720:rate=30 \
       -f lavfi -i sine=frequency=1000:sample_rate=48000 \
       -c:v libx264 -preset ultrafast -tune zerolatency \
       -c:a aac -b:a 128k \
       -f mpegts "srt://ANDROID_IP:9991?mode=caller"
```

### Send from webcam (Linux):
```bash
ffmpeg -f v4l2 -i /dev/video0 \
       -f alsa -i default \
       -c:v libx264 -preset ultrafast \
       -c:a aac -b:a 128k \
       -f mpegts "srt://ANDROID_IP:9991?mode=caller"
```

### Send existing video file:
```bash
ffmpeg -re -i input.mp4 -c copy \
       -f mpegts "srt://ANDROID_IP:9991?mode=caller"
```

## Code Location

### New Demo Activity
- **Activity**: `app/src/main/java/com/pedro/streamer/srtreceiver/SrtReceiverActivity.kt`
- **Layout**: `app/src/main/res/layout/activity_srt_receiver.xml`
- **Manifest**: Updated `app/src/main/AndroidManifest.xml`
- **Menu**: Added to `MainActivity.kt`

### SRT Receiver Implementation
- **Package**: `srt/src/main/java/com/pedro/srtreceiver/`
- **12 Kotlin files**: Main implementation
- **3 C++ files**: JNI wrapper for libsrt
- **Docs**: README.md, IMPLEMENTATION.md, SRT_RECEIVER_QUICKSTART.md
