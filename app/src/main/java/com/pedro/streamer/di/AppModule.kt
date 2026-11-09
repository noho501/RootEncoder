package com.pedro.streamer.di

import com.pedro.streamer.webrtc.SignalingClient
import com.pedro.streamer.webrtc.peer.StreamPeerConnectionFactory
import com.pedro.streamer.webrtc.session.WebRtcSessionManager
import com.pedro.streamer.webrtc.session.WebRtcSessionManagerImpl
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single { SignalingClient() }

    single { StreamPeerConnectionFactory(androidContext()) }

    single<WebRtcSessionManager> {
        WebRtcSessionManagerImpl(
            context = androidContext(),
            signalingClient = get(),
            peerConnectionFactory = get()
        )
    }
}