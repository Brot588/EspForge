// CodePad PTY 桥：在 Android 上创建真正的伪终端并 exec shell。
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static int open_master(char *slave, size_t slen) {
    int m = posix_openpt(O_RDWR | O_NOCTTY);
    if (m < 0) return -1;
    if (grantpt(m) != 0 || unlockpt(m) != 0) {
        close(m);
        return -1;
    }
    char *name = ptsname(m);
    if (name == NULL) {
        close(m);
        return -1;
    }
    snprintf(slave, slen, "%s", name);
    return m;
}

static char **to_c_array(JNIEnv *env, jobjectArray arr) {
    if (arr == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = (char **) calloc((size_t) n + 1, sizeof(char *));
    if (out == NULL) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(c != NULL ? c : "");
        if (c != NULL) (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    out[n] = NULL;
    return out;
}

static void free_c_array(char **arr) {
    if (arr == NULL) return;
    for (int i = 0; arr[i] != NULL; i++) free(arr[i]);
    free(arr);
}

JNIEXPORT jstring JNICALL
Java_com_codepad_ide_term_Pty_start(JNIEnv *env, jobject thiz, jstring program,
                                    jobjectArray args, jstring cwd, jobjectArray envs,
                                    jint rows, jint cols) {
    (void) thiz;

    const char *prog = (*env)->GetStringUTFChars(env, program, NULL);
    const char *dir = (*env)->GetStringUTFChars(env, cwd, NULL);
    char **argv = to_c_array(env, args);
    char **envp = to_c_array(env, envs);

    char slave[256];
    int master = open_master(slave, sizeof(slave));
    if (master < 0) {
        if (prog) (*env)->ReleaseStringUTFChars(env, program, prog);
        if (dir) (*env)->ReleaseStringUTFChars(env, cwd, dir);
        free_c_array(argv);
        free_c_array(envp);
        return NULL;
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ioctl(master, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid == 0) {
        setsid();
        int sfd = open(slave, O_RDWR);
        if (sfd < 0) _exit(127);
        ioctl(sfd, TIOCSCTTY, 0);
        dup2(sfd, 0);
        dup2(sfd, 1);
        dup2(sfd, 2);
        if (sfd > 2) close(sfd);
        close(master);

        if (envp != NULL) {
            for (int i = 0; envp[i] != NULL; i++) {
                char *eq = strchr(envp[i], '=');
                if (eq != NULL) {
                    *eq = '\0';
                    setenv(envp[i], eq + 1, 1);
                    *eq = '=';
                }
            }
        }
        if (dir != NULL) chdir(dir);

        char *fallback[] = {(char *) "sh", NULL};
        char **use = (argv != NULL && argv[0] != NULL) ? argv : fallback;
        execv(prog != NULL ? prog : "/system/bin/sh", use);
        _exit(127);
    }

    char result[64];
    snprintf(result, sizeof(result), "%d:%d", master, (int) pid);

    if (prog) (*env)->ReleaseStringUTFChars(env, program, prog);
    if (dir) (*env)->ReleaseStringUTFChars(env, cwd, dir);
    free_c_array(argv);
    free_c_array(envp);

    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT void JNICALL
Java_com_codepad_ide_term_Pty_resize(JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    (void) env;
    (void) thiz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(fd, TIOCSWINSZ, &ws);
    kill(0, SIGWINCH);
}

JNIEXPORT jint JNICALL
Java_com_codepad_ide_term_Pty_waitFor(JNIEnv *env, jobject thiz, jint pid) {
    (void) env;
    (void) thiz;
    int status = 0;
    if (waitpid((pid_t) pid, &status, 0) < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_codepad_ide_term_Pty_kill(JNIEnv *env, jobject thiz, jint pid) {
    (void) env;
    (void) thiz;
    kill((pid_t) pid, SIGKILL);
    kill(-(pid_t) pid, SIGKILL);
}
