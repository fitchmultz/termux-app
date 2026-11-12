// Linux socket boundary tests. JNI values use a small table, not an Android VM.
#include <cassert>
#include <atomic>
#include <chrono>
#include <future>
#include <iostream>
#include <vector>
#include <linux/sockios.h>
#include <unistd.h>

static std::promise<void>* firstReadCompleted;
static std::atomic<bool> observedFirstRead;
static ssize_t observedRead(int fd, void* buffer, size_t length) {
    ssize_t count = ::read(fd, buffer, length);
    if (firstReadCompleted && count > 0 && !observedFirstRead.exchange(true)) {
        firstReadCompleted->set_value();
    }
    return count;
}

#ifndef LOCAL_SOCKET_SOURCE
#define LOCAL_SOCKET_SOURCE "../../main/cpp/local-socket.cpp"
#endif
#define read observedRead
#include LOCAL_SOCKET_SOURCE
#undef read

struct ByteArray {
    std::vector<jbyte> bytes;
};

struct NativeResult {
    int retval;
    int error;
    int data;
};
thread_local NativeResult result;

static jclass JNICALL findClass(JNIEnv*, const char*) {
    return reinterpret_cast<jclass>(1);
}
static jmethodID JNICALL getMethodID(JNIEnv*, jclass, const char*, const char*) {
    return reinterpret_cast<jmethodID>(1);
}
static jboolean JNICALL exceptionCheck(JNIEnv*) { return JNI_FALSE; }
static jstring JNICALL newStringUTF(JNIEnv*, const char*) {
    return reinterpret_cast<jstring>(1);
}
static jobject JNICALL newObjectV(JNIEnv*, jclass, jmethodID, va_list arguments) {
    result.retval = va_arg(arguments, int);
    result.error = va_arg(arguments, int);
    va_arg(arguments, jstring);
    result.data = va_arg(arguments, int);
    return reinterpret_cast<jobject>(1);
}
static jbyte* JNICALL getByteArrayElements(JNIEnv*, jbyteArray array, jboolean*) {
    return reinterpret_cast<ByteArray*>(array)->bytes.data();
}
static jsize JNICALL getArrayLength(JNIEnv*, jarray array) {
    return reinterpret_cast<ByteArray*>(array)->bytes.size();
}
static void JNICALL releaseByteArrayElements(JNIEnv*, jbyteArray, jbyte*, jint) {}

static JNIEnv environment() {
    static const JNINativeInterface_ functions = [] {
        JNINativeInterface_ table{};
        table.FindClass = findClass;
        table.GetMethodID = getMethodID;
        table.ExceptionCheck = exceptionCheck;
        table.NewStringUTF = newStringUTF;
        table.NewObjectV = newObjectV;
        table.GetByteArrayElements = getByteArrayElements;
        table.GetArrayLength = getArrayLength;
        table.ReleaseByteArrayElements = releaseByteArrayElements;
        return table;
    }();
    return JNIEnv{&functions};
}

static void closeWakesBlockedAccept() {
    JNIEnv env = environment();
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    std::string name = "termux-native-test-" + std::to_string(getpid());
    memcpy(address.sun_path + 1, name.data(), name.size());
    ByteArray path{{reinterpret_cast<jbyte*>(address.sun_path),
                    reinterpret_cast<jbyte*>(address.sun_path) + name.size() + 1}};
    Java_com_termux_shared_net_socket_local_LocalSocketManager_createServerSocketNative(
        &env, nullptr, nullptr, reinterpret_cast<jbyteArray>(&path), 1);
    assert(result.retval == 0);
    int server = result.data;
    std::promise<void> entered;
    auto accepted = std::async(std::launch::async, [&] {
        JNIEnv listenerEnv = environment();
        entered.set_value();
        Java_com_termux_shared_net_socket_local_LocalSocketManager_acceptNative(
            &listenerEnv, nullptr, nullptr, server);
        return result;
    });
    entered.get_future().wait();
    assert(accepted.wait_for(std::chrono::milliseconds(50)) == std::future_status::timeout);
    Java_com_termux_shared_net_socket_local_LocalSocketManager_closeSocketNative(
        &env, nullptr, nullptr, server);
    assert(result.retval == 0);
    bool stopped = accepted.wait_for(std::chrono::seconds(1)) == std::future_status::ready;
    if (!stopped) {
        // Let the baseline's leaked accept finish, so a failing test leaves no blocked worker.
        int client = socket(AF_UNIX, SOCK_STREAM, 0);
        assert(connect(client, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == 0);
        close(client);
    }
    NativeResult acceptResult = accepted.get();
    if (acceptResult.retval == 0) close(acceptResult.data);
    if (!stopped || acceptResult.retval == 0) {
        std::cerr << "closeSocketNative left accept blocked or accepted after close\n";
        std::exit(1);
    }
    std::cout << "PASS closeSocketNative wakes blocked Linux accept\n";
}

static void fragmentedReadStaysWithinArray() {
    int sockets[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    ByteArray output{std::vector<jbyte>(8)};
    assert(write(sockets[1], "A", 1) == 1);
    std::promise<void> firstFragment;
    firstReadCompleted = &firstFragment;
    observedFirstRead = false;
    auto reader = std::async(std::launch::async, [&] {
        JNIEnv env = environment();
        Java_com_termux_shared_net_socket_local_LocalSocketManager_readNative(
            &env, nullptr, nullptr, sockets[0], reinterpret_cast<jbyteArray>(&output), 0);
        return result;
    });
    // Observe completion, not queue emptiness: recv can still copy newly arriving data before returning.
    assert(firstFragment.get_future().wait_for(std::chrono::seconds(1)) ==
           std::future_status::ready);
    assert(write(sockets[1], "BCDEFGHI", 8) == 8);
    shutdown(sockets[1], SHUT_WR);
    assert(reader.wait_for(std::chrono::seconds(1)) == std::future_status::ready);
    NativeResult readResult = reader.get();
    firstReadCompleted = nullptr;
    assert(readResult.retval == 0);
    assert(readResult.data == 8);
    assert(std::string(output.bytes.begin(), output.bytes.end()) == "ABCDEFGH");
    char remaining = 0;
    assert(read(sockets[0], &remaining, 1) == 1);
    assert(remaining == 'I');
    close(sockets[0]);
    close(sockets[1]);
    std::cout << "PASS fragmented JNI read stays within array and leaves remaining byte\n";
}

int main(int argc, char** argv) {
    if (argc == 1 || std::string(argv[1]) == "close") closeWakesBlockedAccept();
    if (argc == 1 || std::string(argv[1]) == "read") fragmentedReadStaysWithinArray();
}
