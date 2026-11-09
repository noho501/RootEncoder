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

package com.pedro.streamer.webrtc.peer

import android.util.Log
import com.pedro.streamer.webrtc.model.DataChannelMessage
import com.pedro.streamer.webrtc.util.GsonUtils
import com.pedro.streamer.webrtc.util.addRtcIceCandidate
import com.pedro.streamer.webrtc.util.createValue
import com.pedro.streamer.webrtc.util.setValue
import com.pedro.streamer.webrtc.util.stringify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.SignalingState
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer


/**
 * Wrapper around the WebRTC connection that contains tracks.
 *
 * @param coroutineScope The scope used to listen to stats events.
 * @param type The internal type of the PeerConnection. Check [StreamPeerType].
 * @param mediaConstraints Constraints used for the connections.
 * @param onStreamAdded Handler when a new [MediaStream] gets added.
 * @param onNegotiationNeeded Handler when there's a new negotiation.
 * @param onIceCandidate Handler whenever we receive [IceCandidate]s.
 */
class StreamPeerConnection(
    private val coroutineScope: CoroutineScope,
    private val type: StreamPeerType,
    private val mediaConstraints: MediaConstraints,
    private val onStreamAdded: ((MediaStream) -> Unit)?,
    private val onNegotiationNeeded: ((StreamPeerConnection, StreamPeerType) -> Unit)?,
    private val onIceCandidate: ((IceCandidate, StreamPeerType) -> Unit)?,
    private val onVideoTrack: ((RtpTransceiver?) -> Unit)?
) : PeerConnection.Observer {

    companion object {
        private const val TAG = "StreamPeerConnection"
    }

    private val typeTag = type.stringify()

    /**
     * The wrapped connection for all the WebRTC communication.
     */
    lateinit var connection: PeerConnection
        private set

    /**
     * Used to manage the stats observation lifecycle.
     */
    private var statsJob: Job? = null

    /**
     * Used to pool together and store [IceCandidate]s before consuming them.
     */
    private val pendingIceMutex = Mutex()
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    /**
     * Contains stats events for observation.
     */
    private val statsFlow: MutableStateFlow<RTCStatsReport?> = MutableStateFlow(null)
    private var receiveDataChannel: DataChannel? = null
    private var sendDataChannel: DataChannel? = null

    var onIceStateChange: ((PeerConnection.IceConnectionState?) -> Unit)? = null
    var onPeerConnectionStateChange: ((PeerConnection.PeerConnectionState?) -> Unit)? = null
    var onSignalingStateChange: ((SignalingState?) -> Unit)? = null
    var onDataChannelMessage: ((DataChannelMessage) -> Unit)? = null


    /**
     * Initialize a [StreamPeerConnection] using a WebRTC [PeerConnection].
     *
     * @param peerConnection The connection that holds audio and video tracks.
     */
    fun initialize(peerConnection: PeerConnection) {
        Log.d(TAG, "initialize type= $typeTag")
        this.connection = peerConnection
    }

    /**
     * Used to create an offer whenever there's a negotiation that we need to process on the
     * publisher side.
     *
     * @return [Result] wrapper of the [SessionDescription] for the publisher.
     */
    suspend fun createOffer(): Result<SessionDescription> {
        Log.d(TAG, "createOffer")
        return createValue { connection.createOffer(it, mediaConstraints) }
    }

    /**
     * Used to create an answer whenever there's a subscriber offer.
     *
     * @return [Result] wrapper of the [SessionDescription] for the subscriber.
     */
    suspend fun createAnswer(): Result<SessionDescription> {
        Log.d(TAG, "createAnswer")
        return createValue { connection.createAnswer(it, mediaConstraints) }
    }

    /**
     * Used to set up the SDP on underlying connections and to add [pendingIceCandidates] to the
     * connection for listening.
     *
     * @param sessionDescription That contains the remote SDP.
     * @return An empty [Result], if the operation has been successful or not.
     */
    suspend fun setRemoteDescription(sessionDescription: SessionDescription): Result<Unit> {
        Log.d(TAG, "setRemoteDescription type= ${sessionDescription.type} \n description= ${sessionDescription.description}")
        return setValue {
            connection.setRemoteDescription(
                it,
                SessionDescription(
                    sessionDescription.type,
                    sessionDescription.description.mungeCodecs()
                )
            )
        }.also {
            pendingIceMutex.withLock {
                pendingIceCandidates.forEach { iceCandidate ->
                    connection.addRtcIceCandidate(iceCandidate)
                }
                pendingIceCandidates.clear()
            }
        }
    }

    /**
     * Sets the local description for a connection either for the subscriber or publisher based on
     * the flow.
     *
     * @param sessionDescription That contains the subscriber or publisher SDP.
     * @return An empty [Result], if the operation has been successful or not.
     */
    suspend fun setLocalDescription(sessionDescription: SessionDescription): Result<Unit> {
        Log.d(TAG, "setLocalDescription type= ${sessionDescription.type} \n description= ${sessionDescription.description}")
        val sdp = SessionDescription(
            sessionDescription.type,
            sessionDescription.description.mungeCodecs()
        )
        return setValue { connection.setLocalDescription(it, sdp) }
    }

    /**
     * Adds an [IceCandidate] to the underlying [connection] if it's already been set up, or stores
     * it for later consumption.
     *
     * @param iceCandidate To process and add to the connection.
     * @return An empty [Result], if the operation has been successful or not.
     */
    suspend fun addIceCandidate(iceCandidate: IceCandidate): Result<Unit> {
        Log.d(TAG, "addIceCandidate")
        if (connection.remoteDescription == null) {
            pendingIceMutex.withLock {
                pendingIceCandidates.add(iceCandidate)
            }
            return Result.failure(RuntimeException("RemoteDescription is not set"))
        }
        return connection.addRtcIceCandidate(iceCandidate).also {
        }
    }

    fun createReceiveDataChannel() {
        Log.d(TAG, "createReceiveDataChannel")
        receiveDataChannel?.close()
        val dc = connection.createDataChannel("WebRTCData", DataChannel.Init().apply { id = 1 })
        dc.registerObserver(DataChannelObserver())
        receiveDataChannel = dc
    }

    fun sendDataMessage(msg: String) {
        Log.d(TAG, "sendDataMessage ${msg} -> ${sendDataChannel}")
        val buffer = ByteBuffer.wrap(msg.toByteArray())
        sendDataChannel?.send(DataChannel.Buffer(buffer, true))
    }

    /**
     * Peer connection listeners.
     */

    /**
     * Triggered whenever there's a new [RtcIceCandidate] for the call. Used to update our tracks
     * and subscriptions.
     *
     * @param candidate The new candidate.
     */
    override fun onIceCandidate(candidate: IceCandidate?) {
        Log.d(TAG, "onIceCandidate: $candidate")
        if (candidate == null) return

        onIceCandidate?.invoke(candidate, type)
    }

    /**
     * Triggered whenever there's a new [MediaStream] that was added to the connection.
     *
     * @param stream The stream that contains audio or video.
     */
    override fun onAddStream(stream: MediaStream?) {
        Log.d(TAG, "onAddStream")
        if (stream != null) {
            onStreamAdded?.invoke(stream)
        }
    }

    /**
     * Triggered whenever there's a new [MediaStream] or [MediaStreamTrack] that's been added
     * to the call. It contains all audio and video tracks for a given session.
     *
     * @param receiver The receiver of tracks.
     * @param mediaStreams The streams that were added containing their appropriate tracks.
     */
    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
        Log.d(TAG, "onAddTrack")
        mediaStreams?.forEach { mediaStream ->
            mediaStream.audioTracks?.forEach { remoteAudioTrack ->
                remoteAudioTrack.setEnabled(true)
            }
            onStreamAdded?.invoke(mediaStream)
        }
    }

    /**
     * Triggered whenever there's a new negotiation needed for the active [PeerConnection].
     */
    override fun onRenegotiationNeeded() {
        onNegotiationNeeded?.invoke(this, type)
    }

    /**
     * Triggered whenever a [MediaStream] was removed.
     *
     * @param stream The stream that was removed from the connection.
     */
    override fun onRemoveStream(stream: MediaStream?) {
    }

    /**
     * Triggered when the connection state changes.  Used to start and stop the stats observing.
     *
     * @param newState The new state of the [PeerConnection].
     */
    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        Log.d(TAG, "onIceConnectionChange $newState")
        when (newState) {
            PeerConnection.IceConnectionState.CLOSED,
            PeerConnection.IceConnectionState.FAILED,
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                statsJob?.cancel()
                sendDataChannel?.unregisterObserver()
                sendDataChannel?.close()
                sendDataChannel = null
                receiveDataChannel?.unregisterObserver()
                receiveDataChannel?.close()
                receiveDataChannel = null
            }
            PeerConnection.IceConnectionState.CONNECTED -> statsJob = observeStats()
            else -> Unit
        }
        onIceStateChange?.invoke(newState)
    }

    /**
     * @return The [RTCStatsReport] for the active connection.
     */
    fun getStats(): StateFlow<RTCStatsReport?> {
        return statsFlow
    }

    /**
     * Observes the local connection stats and emits it to [statsFlow] that users can consume.
     */
    private fun observeStats() = coroutineScope.launch {
        while (isActive) {
            delay(10_000L)
            connection.getStats {
                statsFlow.value = it
            }
        }
    }

    override fun onTrack(transceiver: RtpTransceiver?) {
        Log.d(TAG, "onTrack")
        onVideoTrack?.invoke(transceiver)
    }

    /**
     * Domain - [PeerConnection] and [PeerConnection.Observer] related callbacks.
     */
    override fun onRemoveTrack(receiver: RtpReceiver?) {
    }

    override fun onSignalingChange(newState: SignalingState?) {
        Log.d(TAG, "onSignalingChange ${newState}")
        onSignalingStateChange?.invoke(newState)
    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) {
        Log.d(TAG, "onIceConnectionReceivingChange")
    }

    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
        Log.d(TAG, "onIceGatheringChange")
    }

    override fun onIceCandidatesRemoved(iceCandidates: Array<out IceCandidate>?) {
        Log.d(TAG, "onIceCandidatesRemoved")
    }

    override fun onIceCandidateError(event: IceCandidateErrorEvent?) {
        Log.d(TAG, "onIceCandidateError")
    }

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
        Log.d(TAG, "onConnectionChange ${newState}")
        onPeerConnectionStateChange?.invoke(newState)
    }

    override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {
        Log.d(TAG, "onSelectedCandidatePairChanged")
    }

    override fun onDataChannel(channel: DataChannel?) {
        Log.d(TAG, "onDataChannel id= ${channel?.id()} name= ${channel?.label()} state= ${channel?.state()}")
        sendDataChannel?.close()
        sendDataChannel = channel
    }

    override fun toString(): String =
        "StreamPeerConnection(type='$typeTag', constraints=$mediaConstraints)"

    private fun String.mungeCodecs(): String {
        return this.replace("vp9", "VP9").replace("vp8", "VP8").replace("h264", "H264")
    }

    inner class DataChannelObserver: DataChannel.Observer {
        override fun onBufferedAmountChange(p0: Long) {
        }

        override fun onStateChange() {
            when(receiveDataChannel?.state()) {
                DataChannel.State.OPEN -> {
                    Log.d(TAG, "onStateChange: OPEN")
                }
                else -> {
                    Log.d(TAG, "onStateChange: else")
                }
            }
        }

        override fun onMessage(p0: DataChannel.Buffer?) {
            p0 ?: return
            val data = p0.data
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            val msg = String(bytes, Charsets.UTF_8)
            Log.d(TAG, "onMessage msg= $msg")
            val msgData = GsonUtils.jsonToObject<DataChannelMessage>(msg) ?: return
            onDataChannelMessage?.invoke(msgData)
        }
    }
}
