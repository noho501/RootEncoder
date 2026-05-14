package com.pedro.streamer.webrtc.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class RemoteSdp(
    @SerializedName("type")
    var type: String? = null,
    @SerializedName("channel")
    var channel: String? = null,
    @SerializedName("payload")
    var payload: Payload? = null,
): Serializable

data class Payload(
    @SerializedName("sdp")
    var sdp: String? = null,
    @SerializedName("sdpMid")
    var sdpMid: String? = null,
    @SerializedName("sdpMLineIndex")
    var sdpMLineIndex: Int? = null,
    @SerializedName("channel")
    var channel: String? = null,
    @SerializedName("type")
    var type: String? = null,
): Serializable