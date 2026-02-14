# SRT Receiver Implementation - Quick Start Guide

## What Was Implemented

A complete SRT listener/receiver for Android that implements the full pipeline:
**SRT → MPEG-TS → PES → H264/AAC → MediaCodec → SurfaceView + AudioTrack**

## Files Created (17 total)

### Kotlin Files (12)
1. `SrtReceiver.kt` - Main public API
2. `SrtServerSocket.kt` - JNI wrapper for SRT
3. `BlockingByteQueue.kt` - Thread-safe queue
4. `TsPacket.kt` - TS packet data class
5. `TsDemuxer.kt` - MPEG-TS demultiplexer
6. `PatParser.kt` - PAT parser
7. `PmtParser.kt` - PMT parser
8. `PesAssembler.kt` - PES assembler
9. `H264Parser.kt` - H.264 NAL parser
10. `AacParser.kt` - AAC ADTS parser
11. `VideoDecoder.kt` - H.264 MediaCodec decoder
12. `AudioDecoder.kt` - AAC MediaCodec decoder

### C++/JNI Files (3)
13. `NativeSrt.cpp` - libsrt JNI implementation
14. `NativeSrt.h` - JNI header
15. `CMakeLists.txt` - CMake build config

### Documentation (2)
16. `README.md` - Usage guide
17. `IMPLEMENTATION.md` - Technical details

## How to Use

```kotlin
import com.pedro.srtreceiver.SrtReceiver

class MainActivity : AppCompatActivity() {
    private lateinit var receiver: SrtReceiver
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        val surfaceView = findViewById<SurfaceView>(R.id.surfaceView)
        receiver = SrtReceiver(surfaceView)
    }
    
    fun startReceiving() {
        receiver.start(9991)  // Listen on port 9991
    }
    
    override fun onDestroy() {
        super.onDestroy()
        receiver.stop()
    }
}
```

## Sending Video to the Receiver

From FFmpeg:
```bash
ffmpeg -re -i input.mp4 -c copy -f mpegts "srt://ANDROID_IP:9991?mode=caller"
```

From OBS Studio:
- Settings → Stream
- Service: Custom
- Server: `srt://ANDROID_IP:9991?mode=caller`
- Stream Key: (leave empty)

## CRITICAL: LibSRT Dependency

**This implementation requires libsrt to be built for Android.**

### Option 1: Build LibSRT Yourself

1. Clone SRT:
```bash
git clone https://github.com/Haivision/srt.git
cd srt
```

2. Build for Android using NDK (you need Android NDK installed):
```bash
# For each ABI: armeabi-v7a, arm64-v8a, x86, x86_64
mkdir build-android-<ABI>
cd build-android-<ABI>
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=<ABI> \
  -DANDROID_PLATFORM=android-24 \
  -DENABLE_SHARED=ON
make
```

3. Copy files to RootEncoder:
```
libsrt.so files → srt/src/main/jniLibs/<ABI>/libsrt.so
SRT headers → srt/src/main/cpp/srt-include/srt/
```

### Option 2: Use Pre-built LibSRT

If you have pre-built libsrt binaries for Android:
1. Place `libsrt.so` in `srt/src/main/jniLibs/<ABI>/`
2. Place headers in `srt/src/main/cpp/srt-include/srt/`

## Directory Structure

```
srt/
├── src/main/
│   ├── cpp/
│   │   ├── CMakeLists.txt
│   │   ├── NativeSrt.cpp
│   │   ├── NativeSrt.h
│   │   └── srt-include/srt/
│   │       └── srt.h (stub - replace with real headers)
│   ├── java/com/pedro/srtreceiver/
│   │   ├── SrtReceiver.kt
│   │   ├── SrtServerSocket.kt
│   │   ├── BlockingByteQueue.kt
│   │   ├── TsPacket.kt
│   │   ├── TsDemuxer.kt
│   │   ├── PatParser.kt
│   │   ├── PmtParser.kt
│   │   ├── PesAssembler.kt
│   │   ├── H264Parser.kt
│   │   ├── AacParser.kt
│   │   ├── VideoDecoder.kt
│   │   ├── AudioDecoder.kt
│   │   ├── README.md
│   │   └── IMPLEMENTATION.md
│   └── jniLibs/
│       ├── armeabi-v7a/  (add libsrt.so here)
│       ├── arm64-v8a/    (add libsrt.so here)
│       ├── x86/          (add libsrt.so here)
│       └── x86_64/       (add libsrt.so here)
└── build.gradle.kts (updated with NDK config)
```

## Build Configuration

The `srt/build.gradle.kts` has been updated to include:
- CMake external native build
- NDK configuration for all ABIs
- C++11 standard
- Shared C++ STL

## Permissions Required

Add to AndroidManifest.xml:
```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Features

✅ SRT listener mode (server)
✅ MPEG-TS demuxing
✅ H.264 video decoding
✅ AAC audio decoding
✅ Low latency (120ms)
✅ Hardware accelerated decoding
✅ Automatic reconnection
✅ Bitrate monitoring
✅ Thread-safe architecture

## Supported Codecs

- **Video**: H.264/AVC only (H.265 not yet supported)
- **Audio**: AAC only (MP3 not yet supported)
- **Container**: MPEG-TS

## Limitations

1. Requires libsrt to be manually built and integrated
2. Single client connection at a time
3. H.264 only (no HEVC/H.265)
4. AAC only (no MP3)
5. Simplified SPS parsing (defaults to 1920x1080)

## API Level

- Minimum: API 16 (Android 4.1)
- Recommended: API 24+ (Android 7.0+) for best MediaCodec support

## Threading Model

- **SRT Receive Thread**: Blocks on socket, feeds queue
- **Demux Thread**: Processes TS packets, parses streams
- **Decode Threads**: Implicit in MediaCodec (hardware)

## Logging

Enable logs with tag filter:
```
adb logcat -s SrtReceiver SrtServerSocket TsDemuxer H264Parser AacParser VideoDecoder AudioDecoder
```

Expected output:
```
SRT server started on port 9991
Client connected
PMT PID updated: 4096
Video PID updated: 256
Audio PID updated: 257
H264 config ready (SPS/PPS)
Video decoder started
Audio decoder started
Bitrate: 2500 kbps
```

## Troubleshooting

### Native library not loaded
- Ensure libsrt.so is present for all target ABIs
- Check CMakeLists.txt paths are correct
- Verify NDK is installed

### No video
- Check SPS/PPS received (look for "H264 config ready" in logs)
- Verify sender is encoding H.264
- Check SurfaceView is visible

### No audio
- Verify sender is encoding AAC
- Check "Audio decoder started" in logs
- Verify audio permissions

### Build fails
- Ensure Android Gradle Plugin version matches your environment
- Verify CMake 3.18.1+ is available
- Check NDK installation

## Next Steps

1. Build and integrate libsrt
2. Build the project
3. Test with a simple MPEG-TS stream
4. Add error handling as needed
5. Customize for your use case

## Support

For detailed implementation information, see `IMPLEMENTATION.md`.
For usage examples, see `README.md`.

## License

Apache 2.0 (same as RootEncoder)
