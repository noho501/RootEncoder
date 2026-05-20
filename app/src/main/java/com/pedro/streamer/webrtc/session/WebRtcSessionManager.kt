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

import android.view.Surface
import com.pedro.streamer.webrtc.SignalingClient
import com.pedro.streamer.webrtc.peer.StreamPeerConnectionFactory
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.VideoTrack

interface WebRtcSessionManager {

  val signalingClient: SignalingClient

  val peerConnectionFactory: StreamPeerConnectionFactory

  val localVideoTrackFlow: SharedFlow<VideoTrack>

  val remoteVideoTrackFlow: SharedFlow<VideoTrack>
  val isDataChannelReady: SharedFlow<Boolean>

  val eventAction: SharedFlow<Pair<Int, String?>>

  val updateStateRemote: SharedFlow<Int>

  val webrtcSurfaceFlow: StateFlow<Surface?>
  val bitrateFlow: SharedFlow<Long>

  fun onSessionScreenReady()

//  fun flipCamera()
//
//  fun enableMicrophone(enabled: Boolean)
//
//  fun enableCamera(enabled: Boolean)

  fun disconnect()

  fun connect(openControlType: Int)

  fun sendMsg(msg: String)

  fun sendAction(action: Int, extraData: String?)
  fun startLocalVideoCapture(width: Int, height: Int, fps: Int)
  fun stopLocalVideoCapture()
}
