# Native socket boundary checks

`local-socket-test.cpp` includes the production JNI source and runs real Linux
AF_UNIX sockets. A small JNI function table supplies byte arrays and records
`JniResult` values. The Android log header is replaced with a no-op test header.
This checks native socket behavior, not Android VM integration.
It covers closing a blocked listener and fragmented bulk reads. The latter uses
AddressSanitizer and verifies that the byte beyond the requested buffer stays on
the socket. A test-local syscall observer waits for the real first read to
return before sending the second fragment; it does not change the read result.

On Linux with a C++ compiler and JDK headers, from this directory:

```sh
c++ -std=c++17 -pthread -fsanitize=address -g \
  -I. -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  local-socket-test.cpp -o /tmp/termux-local-socket-test
/tmp/termux-local-socket-test
```

`LOCAL_SOCKET_SOURCE` can name a baseline copy of the production file to verify
that the regression fails before the fix. No Android build or APK is required.
Pass `close` or `read` to the executable to run one boundary independently.
