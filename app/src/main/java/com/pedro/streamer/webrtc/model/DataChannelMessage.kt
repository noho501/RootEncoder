package com.pedro.streamer.webrtc.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable


data class DataChannelMessage(
    @SerializedName("isHost")
    var isHost: Boolean? = null,
    @SerializedName("message")
    var message: String? = null,
    @SerializedName("state")
    var state: Int? = null,
    @SerializedName("action")
    var action: Int? = null,
    @SerializedName("dictionary")
    var dictionary: String? = null,
): Serializable