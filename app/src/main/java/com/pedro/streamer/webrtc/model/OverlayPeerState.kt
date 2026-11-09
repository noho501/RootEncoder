package com.pedro.streamer.webrtc.model

enum class OverlayPeerState(val value: Int) {
    UNKNOWN(0),
    REMOTE_PREPARING(1),
    REMOTE_REQUEST(2),
    REMOTE_CONNECTED(3),
    REMOTE_READY(4),
    REMOTE_DISCONNECTED(5),
    REMOTE_SWITCH(6),
    TRANSFER(7)
}