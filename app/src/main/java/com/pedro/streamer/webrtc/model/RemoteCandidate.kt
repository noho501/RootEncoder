package com.pedro.streamer.webrtc.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class RemoteCandidate(
    @SerializedName("type")
    var type: String? = null,
    @SerializedName("channel")
    var channel: String? = null,
    @SerializedName("payload")
    var payload: Payload? = null,
): Serializable