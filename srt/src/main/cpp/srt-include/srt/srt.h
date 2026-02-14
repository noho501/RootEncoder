/*
 * SRT - Secure, Reliable, Transport
 * Minimal header stub for Android compilation
 * 
 * This is a stub header. For production use, replace this with the actual
 * SRT headers from https://github.com/Haivision/srt
 */

#ifndef SRT_H
#define SRT_H

#ifdef __cplusplus
extern "C" {
#endif

#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>

// SRT socket type
typedef int SRTSOCKET;

// SRT constants
#define SRT_INVALID_SOCK -1
#define SRT_ERROR -1

// SRT socket options
#define SRTO_RCVSYN 28
#define SRTO_LATENCY 18
#define SRTO_TSBPDMODE 34

// SRT API functions
int srt_startup(void);
int srt_cleanup(void);

SRTSOCKET srt_create_socket(void);
SRTSOCKET srt_socket(int af, int type, int protocol);

int srt_bind(SRTSOCKET u, const struct sockaddr* name, int namelen);
int srt_listen(SRTSOCKET u, int backlog);
SRTSOCKET srt_accept(SRTSOCKET u, struct sockaddr* addr, int* addrlen);

int srt_connect(SRTSOCKET u, const struct sockaddr* name, int namelen);

int srt_send(SRTSOCKET u, const char* buf, int len);
int srt_recv(SRTSOCKET u, char* buf, int len);
int srt_sendmsg(SRTSOCKET u, const char* buf, int len, int ttl, int inorder);
int srt_recvmsg(SRTSOCKET u, char* buf, int len);

int srt_close(SRTSOCKET u);

int srt_setsockopt(SRTSOCKET u, int level, int optname, const void* optval, int optlen);
int srt_getsockopt(SRTSOCKET u, int level, int optname, void* optval, int* optlen);

// Error handling
int srt_getlasterror(int* errno_loc);
const char* srt_getlasterror_str(void);

// Error codes
#define SRT_EASYNCRCV 6003

#ifdef __cplusplus
}
#endif

#endif // SRT_H
