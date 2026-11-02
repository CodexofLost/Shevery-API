#include <jni.h>
#include <unistd.h>
#include <termios.h>
#include <fcntl.h>
#include <cerrno>
#include <cstdlib>
#include <android/log.h>
#include <pthread.h>
#include <memory>
#include <atomic>
#include "logging.h"
#include "pts.h"

static pthread_mutex_t mutex;
static int winch_pipe[2] = {-1, -1};
static struct termios old_stdin{};
static int tty_in_raw = 0;

static int64_t getWindowSize(int fd) {
    static_assert(sizeof(jlong) == sizeof(winsize));
    jlong screen_size;

    if (ioctl(fd, TIOCGWINSZ, &screen_size) == -1) {
        PLOGE("ioctl TIOCGWINSZ");
        return 0;
    }

    return screen_size;
}

static jbyte RishTerminal_prepare(JNIEnv *env, jclass clazz) {
    jbyte atty = 0;
    if (isatty(STDIN_FILENO)) atty |= ATTY_IN;
    if (isatty(STDOUT_FILENO)) atty |= ATTY_OUT;
    if (isatty(STDERR_FILENO)) atty |= ATTY_ERR;

    LOGD("istty stdin %d stdout %d stderr %d", (atty & ATTY_IN) ? 1 : 0, (atty & ATTY_OUT) ? 1 : 0,
         (atty & ATTY_ERR) ? 1 : 0);

    if (atty & ATTY_OUT) {
        sigset_t winch;
        sigemptyset(&winch);
        sigaddset(&winch, SIGWINCH);
        pthread_sigmask(SIG_BLOCK, &winch, nullptr);
    }

    return atty;
}

static jint RishTerminal_start(
        JNIEnv *env, jclass clazz, jbyte tty,
        jint stdin_pipe, jint stdout_pipe, jint stderr_pipe) {

    int tty_fd;
    bool in_tty = tty & ATTY_IN;
    bool out_tty = tty & ATTY_OUT;
    bool err_tty = tty & ATTY_ERR;
    if (in_tty) {
        tty_fd = STDIN_FILENO;
    } else if (out_tty) {
        tty_fd = STDOUT_FILENO;
    } else if (err_tty) {
        tty_fd = STDERR_FILENO;
    } else {
        tty_fd = -1;
    }

    if (tty == ATTY_ALL) {
        if (make_tty_raw(tty_fd, old_stdin) == 0) {
            tty_in_raw = 1;
        }
    }

    if (pthread_mutex_lock(&mutex) != 0) {
        PLOGE("pthread_mutex_lock");
    }

    // Wait for both stdout and stderr (when piped) to finish draining before unlocking mutex.
    // Adapted from Porter API (porsh_terminal.cpp).
    auto pending = std::make_shared<std::atomic_int>(err_tty ? 1 : 2);
    auto func = [=](bool) {
        if (pending->fetch_sub(1) != 1) {
            return;
        }

        LOGI("remote exit");

        if (tty_in_raw) {
            if (restore_fd(tty_fd, old_stdin) == 0) {
                tty_in_raw = 0;
            }
        }

        if (pthread_mutex_unlock(&mutex) != 0) {
            PLOGE("pthread_mutex_unlock");
        }
    };

    transfer_async(STDIN_FILENO, stdin_pipe);
    transfer_async(stdout_pipe, STDOUT_FILENO, func);
    if (!err_tty) {
        transfer_async(stderr_pipe, STDERR_FILENO, func);
    }

    auto sigwinch_handler = [](int sig) {
        // async-signal-safe: notify the waiter through a self-pipe instead of
        // touching a mutex from the signal handler.
        char b = 1;
        if (winch_pipe[1] != -1) {
            ssize_t ignored = write(winch_pipe[1], &b, 1);
            (void) ignored;
        }
    };

    signal(SIGWINCH, sigwinch_handler);

    return tty_fd;
}

static jlong RishTerminal_waitForWindowSizeChange(JNIEnv *env, jclass clazz, jint fd) {
    if (winch_pipe[0] != -1) {
        char b;
        TEMP_FAILURE_RETRY(read(winch_pipe[0], &b, 1));
    }

    return (jlong) getWindowSize(fd);
}

static void RishTerminal_waitForProcessExit(JNIEnv *env, jclass clazz) {
    if (pthread_mutex_lock(&mutex) != 0) {
        PLOGE("pthread_mutex_lock");
    }

    if (pthread_mutex_unlock(&mutex) != 0) {
        PLOGE("pthread_mutex_unlock");
    }
}

int rikka_rish_RishTerminal_registerNatives(JNIEnv *env) {
    pthread_mutex_init(&mutex, nullptr);

    if (pipe2(winch_pipe, O_CLOEXEC) == 0) {
        int flags = fcntl(winch_pipe[1], F_GETFL);
        if (flags != -1) {
            fcntl(winch_pipe[1], F_SETFL, flags | O_NONBLOCK);
        }
    } else {
        PLOGE("pipe2");
    }

    auto clazz = env->FindClass("rikka/rish/RishTerminal");
    JNINativeMethod methods[] = {
            {"prepare",                 "()B",     (void *) RishTerminal_prepare},
            {"start",                   "(BIII)I", (void *) RishTerminal_start},
            {"waitForWindowSizeChange", "(I)J",    (void *) RishTerminal_waitForWindowSizeChange},
            {"waitForProcessExit",      "()V",     (void *) RishTerminal_waitForProcessExit},
    };
    return env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0]));
}
