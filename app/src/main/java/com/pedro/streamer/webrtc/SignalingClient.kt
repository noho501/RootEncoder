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

package com.pedro.streamer.webrtc

import android.util.Log
import com.pedro.streamer.webrtc.model.RemoteSdp
import com.pedro.streamer.webrtc.util.GsonUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SignalingClient {
  companion object {
    private const val TAG = "SignalingClient"
  }
  private var signalingScope: CoroutineScope? = null
  private val client = OkHttpClient()
    .newBuilder()
    .pingInterval(20, TimeUnit.SECONDS)
    .build()
  private val request = Request
    .Builder()
    .url("wss://ws.livenow.one")
    .build()

  // opening web socket with signaling server
  private var ws: WebSocket? = null

  // session flow to send information about the session state to the subscribers
  private val _sessionStateFlow = MutableStateFlow(WebRTCSessionState.Offline)
  val sessionStateFlow: StateFlow<WebRTCSessionState> = _sessionStateFlow

  // signaling commands to send commands to value pairs to the subscribers
  private val _signalingCommandFlow = MutableSharedFlow<Pair<MessageType, String>>()
  val signalingCommandFlow: SharedFlow<Pair<MessageType, String>> = _signalingCommandFlow

  fun connect() {
    Log.d(TAG, "connect")
    disconnect()
    if (signalingScope == null) {
      signalingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    ws = client.newWebSocket(request, SignalingWebSocketListener())
  }

  fun dispose() {
    Log.d(TAG, "dispose")
    _sessionStateFlow.value = WebRTCSessionState.Offline
    signalingScope?.cancel()
    signalingScope = null
    disconnect()
  }

  private fun disconnect() {
    Log.d(TAG, "disconnect")
    ws?.close(1000, "dispose")
    ws?.cancel()
    ws = null
  }

  fun sendCommand(type: MessageType, message: String) {
    Log.d(TAG, "sendCommand 1 type= $type -> $message")
    ws?.send(message)
  }

  fun sendCommand(message: String) {
    Log.d(TAG, "sendCommand 2 type -> $message")
    ws?.send(message)
  }



  private inner class SignalingWebSocketListener : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
      Log.d(TAG, "Signaling onMessage ${response.message.toString()}")
      _sessionStateFlow.value = WebRTCSessionState.Online
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      handleMessage(text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
      Log.d(TAG, "Signaling onMessage ${ByteString.toString()}")
    }

    private fun handleMessage(json: String) {
      try {
        val jsonObject = JSONObject(json)
        Log.d(TAG, "Signaling handleMessage [START]")
        when (jsonObject.optString("type")) {
          MessageType.SUBSCRIBE.type -> {}
          MessageType.UNSUBSCRIBE.type -> {}
          MessageType.JOIN.type -> {
            _sessionStateFlow.value = WebRTCSessionState.JoinChannelSuccess
          }
          //Session Description Protocol
          MessageType.SDP.type -> {
            Log.d(TAG, "Signaling handleMessage: MessageType.SDP.type ${json}")
            signalingScope?.launch {
              val sdpOffer = GsonUtils.jsonToObject<RemoteSdp>(json) ?: return@launch
              _signalingCommandFlow.emit(MessageType.SDP to sdpOffer.payload?.sdp.orEmpty())
            }
          }
          MessageType.ICE_CANDIDATE.type -> {
            Log.d(TAG, "Signaling handleMessage: MessageType.ICE_CANDIDATE.type ${json}")
            signalingScope?.launch {
              _signalingCommandFlow.emit(MessageType.ICE_CANDIDATE to json)
            }
          }
        }
      } catch (e: Exception) {

      }
    }
  }

}

enum class WebRTCSessionState(val value: Int) {
  Impossible(-2), // We have less than two clients connected to the server
  Offline(-1), // unable to connect signaling server
  Online(0), // connected signaling server
  JoinChannelSuccess(1), // join channel success
  Active(2), // Offer and Answer messages has been sent
  Creating(3), // Creating session, offer has been sent
  Ready(4), // Both clients available and ready to initiate session
}

enum class MessageType(val type: String) {
  SUBSCRIBE("subscribe"),
  UNSUBSCRIBE("unsubscribe"),
  JOIN("join"),
  SDP("SessionDescription"),
  ICE_CANDIDATE("IceCandidate"),
}
