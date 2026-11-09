package com.pedro.streamer.webrtc.handler

import android.util.Log
import com.pedro.streamer.webrtc.model.DataChannelMessage
import com.pedro.streamer.webrtc.model.OverlayPeerAction
import com.pedro.streamer.webrtc.model.OverlayPeerState
import com.pedro.streamer.webrtc.util.GsonUtils

class DataChannelMessageHandler(
    val sendDataMsg: (String) -> Unit,
    val updateRemoteState: (Int) -> Unit,
    val handleAction: (Int, String?) -> Unit,
    var openControlType:  Int = OverlayPeerAction.OPEN_CONTROL_RECORD.value
) {
    companion object {
        private const val TAG = "DataChannelMsgHandler"
    }

    fun handleReceiveMsg(msg: DataChannelMessage) {
        Log.d(TAG, "handleReceiveMsg: ${msg.state}")
        when (val state = msg.state) {
            OverlayPeerState.REMOTE_PREPARING.value -> {
                sendOpenControl()
            }
            OverlayPeerState.TRANSFER.value -> {
                msg.action?.let {
                    handleAction(it, msg.dictionary)
                }
            }
            OverlayPeerState.REMOTE_READY.value -> {
                updateRemoteState(state)
            }
            else -> {}
        }
    }

    private fun sendOpenControl() {
        Log.d(TAG, "sendOpenControl: ${openControlType}")
        val initControl = DataChannelMessage(
            isHost = true,
            message = "[MainApp] Send action:${OverlayPeerAction.entries.firstOrNull { it.value == openControlType }?.name}",
            state = OverlayPeerState.TRANSFER.value,
            action = openControlType,
            dictionary = null
        )
        sendDataMsg(GsonUtils.objectToJson(initControl))
    }
}