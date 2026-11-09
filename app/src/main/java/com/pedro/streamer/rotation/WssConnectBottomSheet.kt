package com.pedro.streamer.rotation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.pedro.streamer.R
import com.pedro.streamer.utils.toast
import com.pedro.streamer.webrtc.WebRTCSessionState
import com.pedro.streamer.webrtc.model.OverlayPeerAction
import com.pedro.streamer.webrtc.session.WebRtcSessionManager
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.android.ext.android.inject

class WssConnectBottomSheet : BottomSheetDialogFragment() {

    private val webRtcSessionManager: WebRtcSessionManager by inject()
    private val sp by lazy { PreferenceManager.getDefaultSharedPreferences(requireContext()) }
    private val codeKey by lazy { getString(R.string.wss_remote_code_key) }

    private val channelCode get() = sp.getString(codeKey, "1111-2222")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.layout_wss_connect_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvCode = view.findViewById<TextView>(R.id.tvCode)
        val tvStatus = view.findViewById<TextView>(R.id.tvStatus)

        tvCode.text = channelCode

        webRtcSessionManager.connect(OverlayPeerAction.OPEN_CONTROL_LIVE.value)

        viewLifecycleOwner.lifecycleScope.launch {
            webRtcSessionManager.signalingClient.sessionStateFlow.collect { state ->
                when (state) {
                    WebRTCSessionState.Online -> {
                        tvStatus.text = "Đang đồng bộ..."
                        val jsonObject = JSONObject().apply {
                            put("type", "subscribe")
                            put("channel", channelCode)
                            put("name", "app")
                        }
                        webRtcSessionManager.sendMsg(jsonObject.toString())
                    }
                    WebRTCSessionState.JoinChannelSuccess -> {
                        toast("Kết nối Remote thành công!")
                        dismiss()
                    }
                    WebRTCSessionState.Offline -> {
                        tvStatus.text = "Lỗi kết nối. Đang thử lại..."
                    }
                    else -> {}
                }
            }
        }
    }
}