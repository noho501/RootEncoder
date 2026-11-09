/*
 * Copyright 2023 Stream.IO, Inc. All Rights Reserved.
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

package com.pedro.streamer.webrtc.session

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.Surface
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager
import com.pedro.streamer.R
import com.pedro.streamer.webrtc.MessageType
import com.pedro.streamer.webrtc.SignalingClient
import com.pedro.streamer.webrtc.audio.AudioHandler
import com.pedro.streamer.webrtc.audio.AudioSwitchHandler
import com.pedro.streamer.webrtc.handler.DataChannelMessageHandler
import com.pedro.streamer.webrtc.model.DataChannelMessage
import com.pedro.streamer.webrtc.model.OverlayPeerAction
import com.pedro.streamer.webrtc.model.OverlayPeerState
import com.pedro.streamer.webrtc.model.Payload
import com.pedro.streamer.webrtc.model.RemoteCandidate
import com.pedro.streamer.webrtc.model.RemoteSdp
import com.pedro.streamer.webrtc.peer.StreamPeerConnectionFactory
import com.pedro.streamer.webrtc.peer.StreamPeerType
import com.pedro.streamer.webrtc.util.GsonUtils
import com.pedro.streamer.webrtc.util.resettableLazy
import com.pedro.streamer.webrtc.video.ExternalSurfaceCapturer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpParameters
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack
import java.util.Stack
import java.util.UUID

class WebRtcSessionManagerImpl(
    private val context: Context,
    override val signalingClient: SignalingClient,
    override val peerConnectionFactory: StreamPeerConnectionFactory
) : WebRtcSessionManager {

    companion object {
        private const val TAG = "WebRtcSessionManager"
    }

    private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // used to send local video track to the fragment
    private val _localVideoTrackFlow = MutableSharedFlow<VideoTrack>()
    override val localVideoTrackFlow: SharedFlow<VideoTrack> = _localVideoTrackFlow

    // used to send remote video track to the sender
    private val _remoteVideoTrackFlow = MutableSharedFlow<VideoTrack>()
    override val remoteVideoTrackFlow: SharedFlow<VideoTrack> = _remoteVideoTrackFlow

    private val _eventAction = MutableSharedFlow<Pair<Int, String?>>()
    override val eventAction: SharedFlow<Pair<Int, String?>> = _eventAction

    private val _updateStateRemote = MutableSharedFlow<Int>()
    override val updateStateRemote: SharedFlow<Int> = _updateStateRemote

    private val _isDataChannelReady = MutableStateFlow(false)
    override val isDataChannelReady: StateFlow<Boolean> = _isDataChannelReady

    private val _webrtcSurfaceFlow = MutableStateFlow<Surface?>(null)
    override val webrtcSurfaceFlow: StateFlow<Surface?> = _webrtcSurfaceFlow

    private val _bitrateFlow = MutableSharedFlow<Long>()
    override val bitrateFlow: SharedFlow<Long> = _bitrateFlow

    private var lastBytesSent: Long = 0
    private var lastStatsTime: Long = 0

    // declaring video constraints and setting OfferToReceiveVideo to true
    // this step is mandatory to create valid offer and answer
    private val mediaConstraints = MediaConstraints().apply {
        mandatory.addAll(
            listOf(
                MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"),
                MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
            )
        )
    }

    private val videoCapturer: VideoCapturer by lazy {
        ExternalSurfaceCapturer(
            onSurfaceReady = { surface -> _webrtcSurfaceFlow.value = surface },
            onSurfaceDestroyed = { _webrtcSurfaceFlow.value = null }
        )
    }

    // 3. Khởi tạo videoSource: Chỉ định độ phân giải mong muốn
    private val videoSource by lazy {
        peerConnectionFactory.makeVideoSource(videoCapturer.isScreencast).apply {
            videoCapturer.initialize(surfaceTextureHelper, context, this.capturerObserver)
        }
    }

    // getting front camera
//  private val videoCapturer: VideoCapturer by lazy { buildCameraCapturer() }
//  private val cameraManager by lazy { context.getSystemService<CameraManager>() }
//  private val cameraEnumerator: Camera2Enumerator by lazy {
//    Camera2Enumerator(context)
//  }

//  private val resolution: CameraEnumerationAndroid.CaptureFormat
//    get() {
//      val frontCamera = cameraEnumerator.deviceNames.first { cameraName ->
//        cameraEnumerator.isFrontFacing(cameraName)
//      }
//      val supportedFormats = cameraEnumerator.getSupportedFormats(frontCamera) ?: emptyList()
//      return supportedFormats.firstOrNull {
//        (it.width == 720 || it.width == 480 || it.width == 360)
//      } ?: error("There is no matched resolution!")
//    }

    // we need it to initialize video capturer
    private val surfaceTextureHelper = SurfaceTextureHelper.create(
        "SurfaceTextureHelperThread",
        peerConnectionFactory.eglBaseContext
    )

//  private val videoSource by lazy {
//    peerConnectionFactory.makeVideoSource(videoCapturer.isScreencast).apply {
//      videoCapturer.initialize(surfaceTextureHelper, context, this.capturerObserver)
//      videoCapturer.startCapture(resolution.width, resolution.height, 30)
//    }
//  }

    private val localVideoTrack: VideoTrack by lazy {
        peerConnectionFactory.makeVideoTrack(
            source = videoSource,
            trackId = "Video${UUID.randomUUID()}"
        )
    }

    /** Audio properties */

    private val audioHandler: AudioHandler by lazy {
        AudioSwitchHandler(context)
    }

    private val audioManager by lazy {
        context.getSystemService<AudioManager>()
    }
//
//  private val audioConstraints: MediaConstraints by lazy {
//    buildAudioConstraints()
//  }
//
//  private val audioSource by lazy {
//    peerConnectionFactory.makeAudioSource(audioConstraints)
//  }
//
//  private val localAudioTrack: AudioTrack by lazy {
//    peerConnectionFactory.makeAudioTrack(
//      source = audioSource,
//      trackId = "Audio${UUID.randomUUID()}"
//    )
//  }

    private var isLocalDescriptionSetup = false
    private val stackCandidates: Stack<RemoteCandidate> = Stack()
    private val sp by lazy { PreferenceManager.getDefaultSharedPreferences(context) }
    private val codeKey by lazy { context.getString(R.string.wss_remote_code_key) }
    private val channelCode get() = sp.getString(codeKey, "1111-2222")

    private lateinit var dataMsgChannelHandler: DataChannelMessageHandler
    private val peerConnectionDelegate = resettableLazy {
        peerConnectionFactory.makePeerConnection(
            coroutineScope = sessionManagerScope,
            configuration = peerConnectionFactory.rtcConfig,
            type = StreamPeerType.SUBSCRIBER,
            mediaConstraints = mediaConstraints,
            onIceCandidateRequest = { iceCandidate, _ ->
                val candidate = RemoteCandidate(
                    type = MessageType.ICE_CANDIDATE.type,
                    channel = channelCode,
                    payload = Payload(
                        sdp = iceCandidate.sdp,
                        sdpMid = iceCandidate.sdpMid,
                        sdpMLineIndex = iceCandidate.sdpMLineIndex,
                        channel = channelCode,
                    )
                )
                sendIceCandidate(candidate)
            },
            onVideoTrack = { rtpTransceiver ->
                val track = rtpTransceiver?.receiver?.track() ?: return@makePeerConnection
                if (track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    val videoTrack = track as VideoTrack
                    sessionManagerScope.launch {
                        _remoteVideoTrackFlow.emit(videoTrack)
                    }
                }
            }
        ).apply {
            onIceStateChange = { state ->
                //Dispose the signalingClient if the peer is disconnected, failed or closed.
                when (state) {
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        signalingClient.dispose()
                    }
                    else -> {
                        // Implement other states if needed
                    }
                }
            }

            onPeerConnectionStateChange = { state ->
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        _isDataChannelReady.value = true
                    }

                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED -> {
                        _isDataChannelReady.value = false
                    }

                    else -> {
                        _isDataChannelReady.value = false
                    }
                }
            }
            onSignalingStateChange = { state ->
                if (state == PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
                    sessionManagerScope.launch {
                        sendAnswer()
                    }
                }
            }
            onDataChannelMessage = { msg ->
                dataMsgChannelHandler.handleReceiveMsg(msg)
            }

            try {
                val sender = connection.addTrack(localVideoTrack)
                val parameters = sender.parameters
                parameters.encodings.forEach { encoding ->
                    encoding.minBitrateBps = 2000 * 1000 // 2 Mbps
                    encoding.maxBitrateBps = 4000 * 1000 // 4 Mbps
                    encoding.maxFramerate = 30
                    encoding.scaleResolutionDownBy = 1.0
                }
                parameters.degradationPreference =
                    RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                sender.parameters = parameters

//                startStatsCollection()
            } catch (_: Exception) {
            }
        }
    }

    private fun startStatsCollection() {
        sessionManagerScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(2000)
                if (peerConnectionDelegate.isInitialized()) {
                    peerConnection.connection.getStats { report ->
                        val now = System.currentTimeMillis()
                        val outboundVideoStats = report.statsMap.values.find { stats ->
                            stats.type == "outbound-rtp" && stats.members["kind"] == "video"
                        }
                        if (outboundVideoStats != null) {
                            val bytesSent = outboundVideoStats.members["bytesSent"] as? Long ?: 0L
                            if (lastBytesSent > 0 && lastStatsTime > 0) {
                                val diffBytes = bytesSent - lastBytesSent
                                val diffTime = now - lastStatsTime
                                if (diffTime > 0) {
                                    val bitrate = (diffBytes * 8 * 1000) / diffTime // bps
                                    sessionManagerScope.launch {
                                        _bitrateFlow.emit(bitrate)
                                    }
                                }
                            }
                            lastBytesSent = bytesSent
                            lastStatsTime = now
                        }
                    }
                }
            }
        }
    }
    private val peerConnection by peerConnectionDelegate

    init {

        dataMsgChannelHandler = DataChannelMessageHandler(
            sendDataMsg = { msg ->
                peerConnection.sendDataMessage(msg)
            },
            updateRemoteState = { state ->
                sessionManagerScope.launch {
                    _updateStateRemote.emit(state)
                }
            },
            handleAction = { action, data ->
                sessionManagerScope.launch {
                    _eventAction.emit(action to data)
                }
            }
        )

        sessionManagerScope.launch {
            signalingClient.signalingCommandFlow
                .collect { commandToValue ->
                    when (commandToValue.first) {
                        MessageType.SDP -> handleReceiveOffer(commandToValue.second)
                        MessageType.ICE_CANDIDATE -> handleReceiveIce(commandToValue.second)
                        else -> Unit
                    }
                }
        }
    }

    override fun onSessionScreenReady() {
//    setupAudio()
//    peerConnection.connection.addTrack(localVideoTrack)
//    peerConnection.connection.addTrack(localAudioTrack)
        sessionManagerScope.launch {
            // sending local video track to show local video from start
//      _localVideoTrackFlow.emit(localVideoTrack)
            sendOffer()
        }
    }
//
//  override fun flipCamera() {
//    (videoCapturer as? Camera2Capturer)?.switchCamera(null)
//  }
//
//  override fun enableMicrophone(enabled: Boolean) {
//    audioManager?.isMicrophoneMute = !enabled
//  }
//
//  override fun enableCamera(enabled: Boolean) {
//    if (enabled) {
//      videoCapturer.startCapture(resolution.width, resolution.height, 30)
//    } else {
//      videoCapturer.stopCapture()
//    }
//  }

    override fun disconnect() {
//    // dispose audio & video tracks.
//    remoteVideoTrackFlow.replayCache.forEach { videoTrack ->
//      videoTrack.dispose()
//    }
//    localVideoTrackFlow.replayCache.forEach { videoTrack ->
//      videoTrack.dispose()
//    }
//    localAudioTrack.dispose()
//    localVideoTrack.dispose()
//
//    // dispose audio handler and video capturer.
//    audioHandler.stop()
//    videoCapturer.stopCapture()
//    videoCapturer.dispose()

        // dispose signaling clients and socket.
        _isDataChannelReady.value = false
        peerConnectionDelegate.reset()
        signalingClient.dispose()
    }

    override fun connect(openControlType: Int) {
        dataMsgChannelHandler.openControlType = openControlType
        signalingClient.connect()
    }

    override fun sendMsg(msg: String) {
        signalingClient.sendCommand(msg)
    }

    override fun sendAction(action: Int, extraData: String?) {
        val mess = DataChannelMessage(
            isHost = true,
            message = "[MainApp] Send action: ${OverlayPeerAction.entries.firstOrNull { it.value == action }?.name}",
            state = OverlayPeerState.TRANSFER.value,
            action = action,
            dictionary = extraData
        )
        peerConnection.sendDataMessage(GsonUtils.objectToJson(mess))
    }

    private suspend fun sendOffer() {
        val offer = peerConnection.createOffer().getOrThrow()
        val result = peerConnection.setLocalDescription(offer)
        result.onSuccess {
            signalingClient.sendCommand(MessageType.SDP, offer.description)
        }
    }

    private suspend fun sendAnswer() {
        val answer = peerConnection.createAnswer().getOrThrow()
        val result = peerConnection.setLocalDescription(answer)
        result.onSuccess {
            try {
                val sdpAnswer = RemoteSdp(
                    type = MessageType.SDP.type,
                    channel = channelCode,
                    payload = Payload(
                        sdp = answer.description.orEmpty(),
                        type = answer.type.canonicalForm(),
                        channel = channelCode
                    )
                )
                signalingClient.sendCommand(MessageType.SDP, GsonUtils.objectToJson(sdpAnswer))
                sessionManagerScope.launch {
                    isLocalDescriptionSetup = true
                    while (stackCandidates.isNotEmpty()) {
                        sendIceCandidate(stackCandidates.pop())
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun sendIceCandidate(candidate: RemoteCandidate) {
        if (isLocalDescriptionSetup) {
            signalingClient.sendCommand(
                MessageType.ICE_CANDIDATE,
                GsonUtils.objectToJson(candidate)
            )
        } else {
            stackCandidates.push(candidate)
        }
    }

    private suspend fun handleReceiveOffer(sdp: String) {
        Log.d(TAG, "handleReceiveOffer: $sdp")
        peerConnection.createReceiveDataChannel()
        peerConnection.setRemoteDescription(
            SessionDescription(SessionDescription.Type.OFFER, sdp)
        )
    }

    private suspend fun handleReceiveAnswer(sdp: String) {
        peerConnection.setRemoteDescription(
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    private suspend fun handleReceiveIce(json: String) {
        Log.d(TAG, "handleReceiveIce: $json")
        val candidate = GsonUtils.jsonToObject<RemoteCandidate>(json) ?: return
        val payload = candidate.payload ?: return
        if (payload.sdp == null) return

        val ice = IceCandidate(payload.sdpMid, payload.sdpMLineIndex ?: 0, payload.sdp)
        peerConnection.addIceCandidate(ice)
    }

//  private fun buildCameraCapturer(): VideoCapturer {
//    Log.d(TAG, "buildCameraCapturer: ")
//    val manager = cameraManager ?: throw RuntimeException("CameraManager was not initialized!")
//
//    val ids = manager.cameraIdList
//    var foundCamera = false
//    var cameraId = ""
//
//    for (id in ids) {
//      val characteristics = manager.getCameraCharacteristics(id)
//      val cameraLensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
//
//      if (cameraLensFacing == CameraMetadata.LENS_FACING_FRONT) {
//        foundCamera = true
//        cameraId = id
//      }
//    }
//
//    if (!foundCamera && ids.isNotEmpty()) {
//      cameraId = ids.first()
//    }
//
//    val camera2Capturer = Camera2Capturer(context, cameraId, null)
//    return camera2Capturer
//  }

    private fun buildAudioConstraints(): MediaConstraints {
        val mediaConstraints = MediaConstraints()
        val items = listOf(
            MediaConstraints.KeyValuePair(
                "googEchoCancellation",
                true.toString()
            ),
            MediaConstraints.KeyValuePair(
                "googAutoGainControl",
                true.toString()
            ),
            MediaConstraints.KeyValuePair(
                "googHighpassFilter",
                true.toString()
            ),
            MediaConstraints.KeyValuePair(
                "googNoiseSuppression",
                true.toString()
            ),
            MediaConstraints.KeyValuePair(
                "googTypingNoiseDetection",
                true.toString()
            )
        )

        return mediaConstraints.apply {
            with(optional) {
                add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
                addAll(items)
            }
        }
    }

    override fun startLocalVideoCapture(width: Int, height: Int, fps: Int) {
        val source = videoSource // Trigger lazy block
        videoCapturer.startCapture(width, height, fps)
    }

    // Hàm nhận lệnh TẮT từ Activity
    override fun stopLocalVideoCapture() {
        try {
            videoCapturer.stopCapture()
        } catch (e: Exception) {
        }
    }
}
