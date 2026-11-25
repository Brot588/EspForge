/*
 * libptybridge —— 给终端用的原生 PTY。
 *
 * Kotlin 侧（com.espforge.ide.term.Pty）只负责把 master fd 当文件读写，
 * forkpty + execve 这些事在这里做，这样 vim / top / python 之类的交互式
 * 程序才有真正的 TTY（bionic 上 ProcessBuilder 给的是 pipe，不是 tty）。
 *
 * 编译（NDK r27c，产物直接进 app/src/main/jniLibs）：
 *   aarch64-linux-android26-clang -O2 -shared -fPIC -o libptybridge.so ptybridge.c
 *
 * 注意：JNI 符号名必须跟着 Kotlin 包名走（Java_com_espforge_ide_term_Pty_*）。
 * 以前这个 .so 是从 CodePad 继承的 com.codepad.ide 版本，包名改了就静默
 * UnsatisfiedLinkError，终端一片黑 —— 所以源码必须留在仓库里。
 */
#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

/* jobjectArray -> NULL 结尾的 char*[]；失败返回 NULL */
static char **to_cstr_array(JNIEnv *env, jobjectArray arr) {
    jsize n = arr == NULL ? 0 : (*env)->GetArrayLength(env, arr);
    char **out = (char **) calloc((size_t) n + 1, sizeof(char *));
    if (out == NULL) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        if (s == NULL) continue;
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(c == NULL ? "" : c);
        if (c != NULL) (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
        if (out[i] == NULL) {
            for (jsize k = 0; k < i; k++) free(out[k]);
            free(out);
            return NULL;
        }
    }
    return out;
}

static void free_cstr_array(char **a) {
    if (a == NULL) return;
    for (char **p = a; *p != NULL; p++) free(*p);
    free(a);
}

JNIEXPORT jstring JNICALL
Java_com_espforge_ide_term_Pty_start(JNIEnv *env, jobject thiz,
                                     jstring program, jobjectArray args,
                                     jstring cwd, jobjectArray envv,
                                     jint rows, jint cols) {
    (void) thiz;
    const char *prog = (*env)->GetStringUTFChars(env, program, NULL);
    const char *dir = cwd == NULL ? NULL : (*env)->GetStringUTFChars(env, cwd, NULL);
    char **argv = to_cstr_array(env, args);
    char **envp = to_cstr_array(env, envv);

    if (prog == NULL || argv == NULL || envp == NULL) {
        free_cstr_array(argv);
        free_cstr_array(envp);
        if (prog != NULL) (*env)->ReleaseStringUTFChars(env, program, prog);
        if (dir != NULL) (*env)->ReleaseStringUTFChars(env, cwd, dir);
        return NULL;
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) {
        free_cstr_array(argv);
        free_cstr_array(envp);
        (*env)->ReleaseStringUTFChars(env, program, prog);
        if (dir != NULL) (*env)->ReleaseStringUTFChars(env, cwd, dir);
        return NULL;
    }

    if (pid == 0) {
        /* 子进程：只做 exec，别碰 ART / malloc */
        if (dir != NULL && dir[0] != '\0') chdir(dir);
        execve(prog, argv, envp);
        _exit(127);
    }

    free_cstr_array(argv);
    free_cstr_array(envp);
    (*env)->ReleaseStringUTFChars(env, program, prog);
    if (dir != NULL) (*env)->ReleaseStringUTFChars(env, cwd, dir);

    char buf[64];
    snprintf(buf, sizeof(buf), "%d:%d", master, (int) pid);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT void JNICALL
Java_com_espforge_ide_term_Pty_resize(JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    (void) env;
    (void) thiz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_com_espforge_ide_term_Pty_waitFor(JNIEnv *env, jobject thiz, jint pid) {
    (void) env;
    (void) thiz;
    int status = 0;
    pid_t r;
    do {
        r = waitpid((pid_t) pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_espforge_ide_term_Pty_kill(JNIEnv *env, jobject thiz, jint pid) {
    (void) env;
    (void) thiz;
    if (pid > 0) kill((pid_t) pid, SIGKILL);
}
