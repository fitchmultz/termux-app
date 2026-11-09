/*
 * Included-source fault tests for the real terminal-emulator JNI entry point.
 * All PTM operations and fork() are stubs: no real PTY, fork, child or JVM.
 * Fake JNI callbacks enforce pending-exception rules, reference ownership and
 * UTF release pairing. Tracked native allocations must all be freed per case.
 * This is not an Android/ART integration test or proof of child reaping.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define MAX_BLOCKS 32
#define PTM_FD 77
#define CHILD_PID 4242
#define UNSET_PID (-1234)

enum ObjectKind { STRING, STRING_ARRAY, PID_ARRAY, EXCEPTION_CLASS };
enum Pending { NO_EXCEPTION, OUT_OF_MEMORY, RUNTIME_EXCEPTION, CLASS_LOOKUP_EXCEPTION };
enum NativeFault { NATIVE_OK, OPEN_FAIL, GRANT_FAIL, UNLOCK_FAIL, PTSNAME_FAIL, FORK_FAIL, PID_COPY_FAIL };
struct Object {
    enum ObjectKind kind;
    const char* text;
    jsize length;
    jobject elements[2];
    jint pids[2];
    int local_refs;
};
struct Block { void* pointer; bool active; };
struct Utf { jstring string; const char* chars; bool active; };
struct State {
    const char* name;
    enum Pending pending;
    enum NativeFault native_fault;
    int fail_array_at, fail_strdup_at, fail_utf_at, fail_element_at;
    bool fail_pid_length, fail_find_class;
    int array_calls, strdup_calls, utf_calls, element_calls;
    int active_blocks, active_utf, local_refs;
    int find_class_calls, throw_calls, pid_copy_calls, critical_calls;
    int open_calls, close_calls, fork_calls;
    int expected_blocks_at_fork;
    bool ptm_open;
    struct Block blocks[MAX_BLOCKS];
    struct Utf utf[8];
} state;
static struct Object command, directory, arg0, arg1, env0, env1, args_array, env_array, pid_array, exception_class;

static void fail(const char* condition, int line)
{
    fprintf(stderr, "FAIL [%s] line %d: %s\n", state.name, line, condition);
    abort();
}
#define CHECK(condition) do { if (!(condition)) fail(#condition, __LINE__); } while (0)

static void no_pending_exception(void) { CHECK(state.pending == NO_EXCEPTION); }
static struct Object* object(jobject value)
{
    CHECK(value != NULL);
    return (struct Object*) value;
}
static void add_local_ref(struct Object* value)
{
    ++value->local_refs;
    ++state.local_refs;
}
static void track(void* pointer)
{
    CHECK(pointer != NULL);
    for (int i = 0; i < MAX_BLOCKS; ++i) {
        if (!state.blocks[i].active) {
            state.blocks[i] = (struct Block) { pointer, true };
            ++state.active_blocks;
            return;
        }
    }
    fail("allocation tracking capacity", __LINE__);
}
/* malloc is hooked too, so the same harness can examine the pre-calloc source. */
static __attribute__((unused)) void* checked_malloc(size_t size)
{
    if (++state.array_calls == state.fail_array_at) return NULL;
    void* pointer = malloc(size);
    CHECK(pointer != NULL);
    memset(pointer, 0xa5, size);
    track(pointer);
    return pointer;
}
static __attribute__((unused)) void* checked_calloc(size_t count, size_t size)
{
    if (++state.array_calls == state.fail_array_at) return NULL;
    void* pointer = calloc(count, size);
    track(pointer);
    return pointer;
}
static char* checked_strdup(const char* text)
{
    if (++state.strdup_calls == state.fail_strdup_at) return NULL;
    char* pointer = strdup(text);
    track(pointer);
    return pointer;
}
static void checked_free(void* pointer)
{
    if (!pointer) return;
    for (int i = 0; i < MAX_BLOCKS; ++i) {
        if (state.blocks[i].active && state.blocks[i].pointer == pointer) {
            state.blocks[i].active = false;
            --state.active_blocks;
            free(pointer);
            return;
        }
    }
    fail("free must own a live allocation (no invalid/double free)", __LINE__);
}

static jboolean JNICALL jni_exception_check(JNIEnv* env)
{
    (void) env;
    return state.pending == NO_EXCEPTION ? JNI_FALSE : JNI_TRUE;
}
static jclass JNICALL jni_find_class(JNIEnv* env, const char* name)
{
    (void) env;
    no_pending_exception();
    CHECK(strcmp(name, "java/lang/RuntimeException") == 0);
    ++state.find_class_calls;
    if (state.fail_find_class) {
        state.pending = CLASS_LOOKUP_EXCEPTION;
        return NULL;
    }
    add_local_ref(&exception_class);
    return (jclass) &exception_class;
}
static jint JNICALL jni_throw_new(JNIEnv* env, jclass clazz, const char* message)
{
    (void) env;
    no_pending_exception();
    CHECK(clazz == (jclass) &exception_class);
    CHECK(exception_class.local_refs > 0);
    CHECK(message != NULL && message[0] != '\0');
    ++state.throw_calls;
    state.pending = RUNTIME_EXCEPTION;
    return 0;
}
static void JNICALL jni_delete_local_ref(JNIEnv* env, jobject ref)
{
    (void) env;
    /* DeleteLocalRef and ReleaseStringUTFChars are legal while an exception is pending. */
    if (ref == NULL) return; /* JNI specifies DeleteLocalRef(NULL) as a no-op. */
    struct Object* value = object(ref);
    CHECK(value->local_refs > 0);
    --value->local_refs;
    --state.local_refs;
}
static jsize JNICALL jni_array_length(JNIEnv* env, jarray array)
{
    (void) env;
    no_pending_exception();
    struct Object* value = object(array);
    CHECK(value->kind == STRING_ARRAY || value->kind == PID_ARRAY);
    if (value == &pid_array && state.fail_pid_length) {
        state.pending = OUT_OF_MEMORY;
        return 0;
    }
    return value->length;
}
static jobject JNICALL jni_array_element(JNIEnv* env, jobjectArray array, jsize index)
{
    (void) env;
    no_pending_exception();
    struct Object* value = object(array);
    CHECK(value->kind == STRING_ARRAY);
    CHECK(index >= 0 && index < value->length);
    if (++state.element_calls == state.fail_element_at) {
        state.pending = OUT_OF_MEMORY;
        return NULL;
    }
    jobject result = value->elements[index];
    if (result) add_local_ref(object(result));
    return result;
}
static const char* JNICALL jni_get_utf(JNIEnv* env, jstring string, jboolean* copy)
{
    (void) env;
    no_pending_exception();
    struct Object* value = object(string); /* Rejects unchecked null cmd/cwd/elements. */
    CHECK(value->kind == STRING);
    if (++state.utf_calls == state.fail_utf_at) {
        state.pending = OUT_OF_MEMORY;
        return NULL;
    }
    if (copy) *copy = JNI_FALSE;
    for (int i = 0; i < 8; ++i) {
        if (!state.utf[i].active) {
            state.utf[i] = (struct Utf) { string, value->text, true };
            ++state.active_utf;
            return value->text;
        }
    }
    fail("UTF tracking capacity", __LINE__);
    return NULL;
}
static void JNICALL jni_release_utf(JNIEnv* env, jstring string, const char* chars)
{
    (void) env;
    CHECK(string != NULL && chars != NULL);
    for (int i = 0; i < 8; ++i) {
        if (state.utf[i].active && state.utf[i].string == string && state.utf[i].chars == chars) {
            state.utf[i].active = false;
            --state.active_utf;
            return;
        }
    }
    fail("UTF release must match the acquired jstring and pointer", __LINE__);
}
static void JNICALL jni_set_pid(JNIEnv* env, jintArray array, jsize start, jsize length, const jint* data)
{
    (void) env;
    no_pending_exception();
    CHECK(array == (jintArray) &pid_array);
    CHECK(start == 0 && length == 1 && pid_array.length >= 1);
    CHECK(data != NULL && *data == CHILD_PID);
    CHECK(state.active_blocks == 0 && state.active_utf == 0 && state.local_refs == 0);
    ++state.pid_copy_calls;
    if (state.native_fault == PID_COPY_FAIL) {
        state.pending = OUT_OF_MEMORY;
        return;
    }
    pid_array.pids[0] = *data;
}
/* Old source can run far enough to produce a useful regression failure. */
static void* JNICALL jni_get_critical(JNIEnv* env, jarray array, jboolean* copy)
{
    (void) env;
    no_pending_exception();
    CHECK(array == (jarray) &pid_array && pid_array.length >= 1);
    if (copy) *copy = JNI_FALSE;
    ++state.critical_calls;
    return pid_array.pids;
}
static void JNICALL jni_release_critical(JNIEnv* env, jarray array, void* data, jint mode)
{
    (void) env;
    CHECK(array == (jarray) &pid_array && data == pid_array.pids && mode == 0);
}
static const struct JNINativeInterface_ callbacks = {
    .FindClass = jni_find_class,
    .ThrowNew = jni_throw_new,
    .DeleteLocalRef = jni_delete_local_ref,
    .GetArrayLength = jni_array_length,
    .GetObjectArrayElement = jni_array_element,
    .GetStringUTFChars = jni_get_utf,
    .ReleaseStringUTFChars = jni_release_utf,
    .SetIntArrayRegion = jni_set_pid,
    .GetPrimitiveArrayCritical = jni_get_critical,
    .ReleasePrimitiveArrayCritical = jni_release_critical,
    .ExceptionCheck = jni_exception_check,
};
static JNIEnv environment = &callbacks;

static int fake_open(const char* path, int flags, ...)
{
    CHECK(strcmp(path, "/dev/ptmx") == 0 && flags == (O_RDWR | O_CLOEXEC));
    CHECK(!state.ptm_open);
    ++state.open_calls;
    if (state.native_fault == OPEN_FAIL) return -1;
    state.ptm_open = true;
    return PTM_FD;
}
static int fake_close(int fd)
{
    CHECK(fd == PTM_FD && state.ptm_open);
    ++state.close_calls;
    state.ptm_open = false;
    return 0;
}
static int fake_grantpt(int fd)
{
    CHECK(fd == PTM_FD && state.ptm_open);
    return state.native_fault == GRANT_FAIL ? -1 : 0;
}
static int fake_unlockpt(int fd)
{
    CHECK(fd == PTM_FD && state.ptm_open);
    return state.native_fault == UNLOCK_FAIL ? -1 : 0;
}
static int fake_ptsname_r(int fd, char* buffer, size_t size)
{
    CHECK(fd == PTM_FD && state.ptm_open);
    CHECK(size >= sizeof("/never/opened"));
    if (state.native_fault == PTSNAME_FAIL) return -1;
    memcpy(buffer, "/never/opened", sizeof("/never/opened"));
    return 0;
}
static int fake_tcgetattr(int fd, struct termios* value)
{
    CHECK(fd == PTM_FD && state.ptm_open);
    memset(value, 0, sizeof(*value));
    return 0;
}
static int fake_tcsetattr(int fd, int action, const struct termios* value)
{
    CHECK(fd == PTM_FD && state.ptm_open && action == TCSANOW && value != NULL);
    return 0;
}
static int fake_ioctl(int fd, unsigned long request, ...)
{
    CHECK(fd == PTM_FD && state.ptm_open && request == TIOCSWINSZ);
    va_list ap;
    va_start(ap, request);
    struct winsize* size = va_arg(ap, struct winsize*);
    CHECK(size != NULL && size->ws_row == 24 && size->ws_col == 80);
    va_end(ap);
    return 0;
}
static pid_t fake_fork(void)
{
    CHECK(state.ptm_open);
    /* Resources must survive until fork, not merely be absent at JNI return. */
    CHECK(state.active_blocks == state.expected_blocks_at_fork);
    CHECK(state.active_utf == 2 && state.local_refs == 0);
    bool command_acquired = false, directory_acquired = false;
    for (int i = 0; i < 8; ++i) {
        if (!state.utf[i].active) continue;
        if (state.utf[i].string == (jstring) &command) command_acquired = true;
        if (state.utf[i].string == (jstring) &directory) directory_acquired = true;
    }
    CHECK(command_acquired && directory_acquired);
    ++state.fork_calls;
    /* Never return zero: the production child branch cannot execute. */
    return state.native_fault == FORK_FAIL ? (pid_t) -1 : (pid_t) CHILD_PID;
}

#define malloc checked_malloc
#define calloc checked_calloc
#define strdup checked_strdup
#define free checked_free
#define open fake_open
#define close fake_close
#define grantpt fake_grantpt
#define unlockpt fake_unlockpt
#define ptsname_r fake_ptsname_r
#define tcgetattr fake_tcgetattr
#define tcsetattr fake_tcsetattr
#define ioctl fake_ioctl
#define fork fake_fork
#ifndef TERMUX_JNI_SOURCE
#define TERMUX_JNI_SOURCE "../../terminal-emulator/src/main/jni/termux.c"
#endif
#include TERMUX_JNI_SOURCE
#undef malloc
#undef calloc
#undef strdup
#undef free
#undef open
#undef close
#undef grantpt
#undef unlockpt
#undef ptsname_r
#undef tcgetattr
#undef tcsetattr
#undef ioctl
#undef fork

static void reset(const char* name)
{
    CHECK(state.active_blocks == 0 && state.active_utf == 0 && state.local_refs == 0 && !state.ptm_open);
    memset(&state, 0, sizeof(state));
    state.name = name;
    command = (struct Object) { .kind = STRING, .text = "/bin/example" };
    directory = (struct Object) { .kind = STRING, .text = "/example/directory" };
    arg0 = (struct Object) { .kind = STRING, .text = "example" };
    arg1 = (struct Object) { .kind = STRING, .text = "argument" };
    env0 = (struct Object) { .kind = STRING, .text = "ONE=1" };
    env1 = (struct Object) { .kind = STRING, .text = "TWO=2" };
    args_array = (struct Object) { .kind = STRING_ARRAY, .length = 2, .elements = { (jobject) &arg0, (jobject) &arg1 } };
    env_array = (struct Object) { .kind = STRING_ARRAY, .length = 2, .elements = { (jobject) &env0, (jobject) &env1 } };
    pid_array = (struct Object) { .kind = PID_ARRAY, .length = 2, .pids = { UNSET_PID, UNSET_PID } };
    exception_class = (struct Object) { .kind = EXCEPTION_CLASS };
}
struct Case {
    const char* name;
    enum Pending expected_exception;
    enum NativeFault native_fault;
    int fail_array_at, fail_strdup_at, fail_utf_at, fail_element_at;
    int null_element; /* 1..4 for argv[0..1], envp[0..1]. */
    bool null_cmd, null_cwd, null_pid, empty_pid, fail_pid_length, fail_find_class, empty_vectors;
};
static void run_case(const struct Case* test)
{
    reset(test->name);
    state.native_fault = test->native_fault;
    state.fail_array_at = test->fail_array_at;
    state.fail_strdup_at = test->fail_strdup_at;
    state.fail_utf_at = test->fail_utf_at;
    state.fail_element_at = test->fail_element_at;
    state.fail_pid_length = test->fail_pid_length;
    state.fail_find_class = test->fail_find_class;
    state.expected_blocks_at_fork = test->empty_vectors ? 0 : 6;
    if (test->empty_pid) pid_array.length = 0;
    if (test->null_element > 0 && test->null_element <= 2) args_array.elements[test->null_element - 1] = NULL;
    if (test->null_element > 2) env_array.elements[test->null_element - 3] = NULL;
    jint result = Java_com_termux_terminal_JNI_createSubprocess(
        &environment, NULL,
        test->null_cmd ? NULL : (jstring) &command,
        test->null_cwd ? NULL : (jstring) &directory,
        test->empty_vectors ? NULL : (jobjectArray) &args_array,
        test->empty_vectors ? NULL : (jobjectArray) &env_array,
        test->null_pid ? NULL : (jintArray) &pid_array,
        24, 80, 8, 16);
    CHECK(state.pending == test->expected_exception);
    CHECK(state.active_blocks == 0 && state.active_utf == 0 && state.local_refs == 0);
    CHECK(state.critical_calls == 0);
    CHECK(pid_array.pids[1] == UNSET_PID); /* Only one jint may be written. */
    if (test->expected_exception == NO_EXCEPTION) {
        CHECK(result == PTM_FD && state.ptm_open);
        CHECK(pid_array.pids[0] == CHILD_PID && state.pid_copy_calls == 1);
        CHECK(state.fork_calls == 1 && state.close_calls == 0);
        fake_close(result); /* The successful caller owns the returned PTM. */
    } else {
        CHECK(result == -1 && !state.ptm_open);
        CHECK(pid_array.pids[0] == UNSET_PID);
        CHECK(state.pid_copy_calls == (test->native_fault == PID_COPY_FAIL ? 1 : 0));
        CHECK(state.fork_calls == (test->native_fault == FORK_FAIL || test->native_fault == PID_COPY_FAIL ? 1 : 0));
        CHECK(state.close_calls == (test->native_fault >= GRANT_FAIL ? 1 : 0));
        if (test->expected_exception == OUT_OF_MEMORY) CHECK(state.find_class_calls == 0 && state.throw_calls == 0);
        if (test->fail_utf_at) CHECK(state.utf_calls == test->fail_utf_at);
        if (test->null_cmd || test->null_cwd || test->null_pid || test->empty_pid || test->fail_pid_length) {
            CHECK(state.array_calls == 0 && state.utf_calls == 0 && state.open_calls == 0);
        }
    }
    printf("PASS %s\n", test->name);
}

int main(int argc, char** argv)
{
    const struct Case cases[] = {
        { .name = "success" },
        { .name = "null-argument-and-environment-vectors", .empty_vectors = true },
        { .name = "argv-array-allocation-failure", .expected_exception = RUNTIME_EXCEPTION, .fail_array_at = 1 },
        { .name = "envp-array-allocation-failure-frees-argv", .expected_exception = RUNTIME_EXCEPTION, .fail_array_at = 2 },
        { .name = "strdup-argv-first", .expected_exception = RUNTIME_EXCEPTION, .fail_strdup_at = 1 },
        { .name = "strdup-argv-partial", .expected_exception = RUNTIME_EXCEPTION, .fail_strdup_at = 2 },
        { .name = "strdup-envp-first", .expected_exception = RUNTIME_EXCEPTION, .fail_strdup_at = 3 },
        { .name = "strdup-envp-partial", .expected_exception = RUNTIME_EXCEPTION, .fail_strdup_at = 4 },
        { .name = "utf-argv-first", .expected_exception = OUT_OF_MEMORY, .fail_utf_at = 1 },
        { .name = "utf-argv-partial", .expected_exception = OUT_OF_MEMORY, .fail_utf_at = 2 },
        { .name = "utf-envp-first", .expected_exception = OUT_OF_MEMORY, .fail_utf_at = 3 },
        { .name = "utf-envp-partial", .expected_exception = OUT_OF_MEMORY, .fail_utf_at = 4 },
        { .name = "utf-cwd-stops-before-cmd", .expected_exception = OUT_OF_MEMORY, .fail_utf_at = 5 },
        { .name = "utf-cmd-releases-cwd", .expected_exception = OUT_OF_MEMORY, .fail_utf_at = 6 },
        { .name = "null-argv-first", .expected_exception = RUNTIME_EXCEPTION, .null_element = 1 },
        { .name = "null-argv-partial", .expected_exception = RUNTIME_EXCEPTION, .null_element = 2 },
        { .name = "null-envp-first", .expected_exception = RUNTIME_EXCEPTION, .null_element = 3 },
        { .name = "null-envp-partial", .expected_exception = RUNTIME_EXCEPTION, .null_element = 4 },
        { .name = "element-exception-argv-first", .expected_exception = OUT_OF_MEMORY, .fail_element_at = 1 },
        { .name = "element-exception-argv-partial", .expected_exception = OUT_OF_MEMORY, .fail_element_at = 2 },
        { .name = "element-exception-envp-first", .expected_exception = OUT_OF_MEMORY, .fail_element_at = 3 },
        { .name = "element-exception-envp-partial", .expected_exception = OUT_OF_MEMORY, .fail_element_at = 4 },
        { .name = "null-command", .expected_exception = RUNTIME_EXCEPTION, .null_cmd = true },
        { .name = "null-directory", .expected_exception = RUNTIME_EXCEPTION, .null_cwd = true },
        { .name = "null-pid-array", .expected_exception = RUNTIME_EXCEPTION, .null_pid = true },
        { .name = "empty-pid-array", .expected_exception = RUNTIME_EXCEPTION, .empty_pid = true },
        { .name = "pid-length-exception", .expected_exception = OUT_OF_MEMORY, .fail_pid_length = true },
        { .name = "exception-class-lookup-failure", .expected_exception = CLASS_LOOKUP_EXCEPTION, .null_pid = true, .fail_find_class = true },
        { .name = "ptm-open-failure", .expected_exception = RUNTIME_EXCEPTION, .native_fault = OPEN_FAIL },
        { .name = "ptm-grant-failure", .expected_exception = RUNTIME_EXCEPTION, .native_fault = GRANT_FAIL },
        { .name = "ptm-unlock-failure", .expected_exception = RUNTIME_EXCEPTION, .native_fault = UNLOCK_FAIL },
        { .name = "ptm-name-failure", .expected_exception = RUNTIME_EXCEPTION, .native_fault = PTSNAME_FAIL },
        { .name = "fork-failure", .expected_exception = RUNTIME_EXCEPTION, .native_fault = FORK_FAIL },
        { .name = "pid-copy-exception-closes-ptm-not-proof-of-child-reaping", .expected_exception = OUT_OF_MEMORY, .native_fault = PID_COPY_FAIL },
    };
    CHECK(argc <= 2);
    size_t count = 0;
    for (size_t i = 0; i < sizeof(cases) / sizeof(cases[0]); ++i) {
        if (argc == 2 && strcmp(argv[1], cases[i].name) != 0) continue;
        run_case(&cases[i]);
        ++count;
    }
    CHECK(count != 0);
    reset("pending-exception-helper");
    state.pending = OUT_OF_MEMORY;
    CHECK(throw_runtime_exception(&environment, "must preserve original") == -1);
    CHECK(state.pending == OUT_OF_MEMORY && state.find_class_calls == 0 && state.throw_calls == 0);
    CHECK(state.active_blocks == 0 && state.active_utf == 0 && state.local_refs == 0 && !state.ptm_open);
    puts("PASS pending-exception-helper");
    printf("%zu included-source JNI fault cases passed; no real JVM, PTY or fork\n", count + 1);
    return 0;
}
