#pragma once

#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_WARN 5

inline int __android_log_write(int, const char*, const char*) {
    return 0;
}
