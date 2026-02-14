#include "NativeSrt.h"
#include <srt/srt.h>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "NativeSrt"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL
Java_com_pedro_srtreceiver_SrtServerSocket_nativeInit(JNIEnv *env, jobject thiz) {
    int result = srt_startup();
    if (result < 0) {
        LOGE("srt_startup failed: %s", srt_getlasterror_str());
        return -1;
    }
    LOGI("SRT initialized successfully");
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_pedro_srtreceiver_SrtServerSocket_nativeStartServer(JNIEnv *env, jobject thiz, jint port) {
    SRTSOCKET server_fd = srt_create_socket();
    if (server_fd == SRT_INVALID_SOCK) {
        LOGE("srt_create_socket failed: %s", srt_getlasterror_str());
        return -1;
    }

    // Set options for low latency
    int yes = 1;
    srt_setsockopt(server_fd, 0, SRTO_RCVSYN, &yes, sizeof(yes));
    
    int latency = 120; // 120ms latency
    srt_setsockopt(server_fd, 0, SRTO_LATENCY, &latency, sizeof(latency));

    int tsbpd = 1; // Enable timestamp-based packet delivery
    srt_setsockopt(server_fd, 0, SRTO_TSBPDMODE, &tsbpd, sizeof(tsbpd));

    // Bind to port
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons(port);
    sa.sin_addr.s_addr = INADDR_ANY;

    if (srt_bind(server_fd, (struct sockaddr*)&sa, sizeof(sa)) == SRT_ERROR) {
        LOGE("srt_bind failed on port %d: %s", port, srt_getlasterror_str());
        srt_close(server_fd);
        return -1;
    }

    if (srt_listen(server_fd, 1) == SRT_ERROR) {
        LOGE("srt_listen failed: %s", srt_getlasterror_str());
        srt_close(server_fd);
        return -1;
    }

    LOGI("SRT server started on port %d, fd=%d", port, server_fd);
    return server_fd;
}

JNIEXPORT jint JNICALL
Java_com_pedro_srtreceiver_SrtServerSocket_nativeAccept(JNIEnv *env, jobject thiz, jint serverFd) {
    struct sockaddr_storage client_addr;
    int addr_len = sizeof(client_addr);

    SRTSOCKET client_fd = srt_accept(serverFd, (struct sockaddr*)&client_addr, &addr_len);
    if (client_fd == SRT_INVALID_SOCK) {
        LOGE("srt_accept failed: %s", srt_getlasterror_str());
        return -1;
    }

    LOGI("Client accepted, fd=%d", client_fd);
    return client_fd;
}

JNIEXPORT jint JNICALL
Java_com_pedro_srtreceiver_SrtServerSocket_nativeRecv(JNIEnv *env, jobject thiz, jint socketFd, jbyteArray buffer) {
    jsize bufferSize = env->GetArrayLength(buffer);
    jbyte* bufferPtr = env->GetByteArrayElements(buffer, nullptr);

    int received = srt_recvmsg(socketFd, (char*)bufferPtr, bufferSize);
    
    env->ReleaseByteArrayElements(buffer, bufferPtr, 0);

    if (received == SRT_ERROR) {
        int error = srt_getlasterror(nullptr);
        if (error != SRT_EASYNCRCV) {
            LOGE("srt_recvmsg failed: %s", srt_getlasterror_str());
        }
        return -1;
    }

    return received;
}

JNIEXPORT void JNICALL
Java_com_pedro_srtreceiver_SrtServerSocket_nativeClose(JNIEnv *env, jobject thiz, jint socketFd) {
    if (socketFd >= 0) {
        srt_close(socketFd);
        LOGI("Socket %d closed", socketFd);
    }
}
