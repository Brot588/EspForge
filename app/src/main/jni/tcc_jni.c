// CodePad TinyCC 桥：在进程内把 C 源码编译到内存并执行（JIT）。
// 不依赖 libc.so 解析：用 dlsym 把未定义符号绑定到当前进程（bionic）。
#include <jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "libtcc.h"

typedef struct {
    char msg[8192];
    size_t len;
} ErrBuf;

typedef struct {
    char *name;
    void *addr;
} SymEntry;

#define MAX_SYMS 512
static SymEntry g_syms[MAX_SYMS];
static int g_sym_count = 0;

static void tcc_err(void *opaque, const char *msg) {
    ErrBuf *e = (ErrBuf *) opaque;
    size_t n = strlen(msg);
    if (e->len + n + 1 < sizeof(e->msg)) {
        memcpy(e->msg + e->len, msg, n);
        e->len += n;
        e->msg[e->len] = '\0';
    }
}

static void json_escape_into(char *dst, size_t cap, const char *s) {
    size_t o = 0;
    if (cap == 0) return;
    for (const unsigned char *p = (const unsigned char *) s; *p && o + 8 < cap; p++) {
        switch (*p) {
            case '"': dst[o++] = '\\'; dst[o++] = '"'; break;
            case '\\': dst[o++] = '\\'; dst[o++] = '\\'; break;
            case '\n': dst[o++] = '\\'; dst[o++] = 'n'; break;
            case '\r': dst[o++] = '\\'; dst[o++] = 'r'; break;
            case '\t': dst[o++] = '\\'; dst[o++] = 't'; break;
            default:
                if (*p < 0x20) {
                    o += (size_t) snprintf(dst + o, cap - o, "\\u%04x", *p);
                } else {
                    dst[o++] = (char) *p;
                }
        }
    }
    dst[o] = '\0';
}

/* 从 tcc 的错误信息里取出未定义符号名，并尝试 dlsym 绑定 */
static int resolve_missing(const char *msg) {
    const char *p = strstr(msg, "undefined symbol '");
    if (p == NULL) return 0;
    p += strlen("undefined symbol '");
    const char *q = strchr(p, '\'');
    if (q == NULL) return 0;
    size_t n = (size_t) (q - p);
    if (n == 0 || n >= 120 || g_sym_count >= MAX_SYMS) return 0;

    char name[128];
    memcpy(name, p, n);
    name[n] = '\0';

    for (int i = 0; i < g_sym_count; i++) {
        if (strcmp(g_syms[i].name, name) == 0) return 0;
    }
    void *addr = dlsym(RTLD_DEFAULT, name);
    if (addr == NULL) return 0;

    g_syms[g_sym_count].name = strdup(name);
    g_syms[g_sym_count].addr = addr;
    g_sym_count++;
    return 1;
}

static void add_common_symbols(TCCState *s) {
    static const char *names[] = {
        "printf", "fprintf", "sprintf", "snprintf", "puts", "putchar", "getchar",
        "fputs", "fputc", "fflush", "scanf", "sscanf", "malloc", "calloc", "realloc",
        "free", "atoi", "atol", "atof", "strtol", "strtoul", "strtod", "abs", "labs",
        "rand", "srand", "exit", "abort", "getenv", "system", "qsort",
        "strlen", "strnlen", "strcpy", "strncpy", "strcat", "strncat", "strcmp",
        "strncmp", "strchr", "strrchr", "strstr", "strdup", "memcpy", "memmove",
        "memset", "memcmp", "sqrt", "cbrt", "pow", "fabs", "floor", "ceil", "round",
        "fmod", "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "exp", "log",
        "log2", "log10", "hypot", "fmin", "fmax", "isalpha", "isdigit", "isalnum",
        "isspace", "isupper", "islower", "ispunct", "isprint", "toupper", "tolower",
        "time", "clock", "__assert", NULL
    };
    for (int i = 0; names[i] != NULL; i++) {
        if (g_sym_count >= MAX_SYMS) break;
        for (int k = 0; k < g_sym_count; k++) {
            if (strcmp(g_syms[k].name, names[i]) == 0) goto next;
        }
        {
            void *addr = dlsym(RTLD_DEFAULT, names[i]);
            if (addr != NULL) {
                g_syms[g_sym_count].name = strdup(names[i]);
                g_syms[g_sym_count].addr = addr;
                g_sym_count++;
            }
        }
    next:;
    }
}

JNIEXPORT jstring JNICALL
Java_com_codepad_ide_run_Tcc_compileAndRun(JNIEnv *env, jclass clazz, jstring code,
                                           jstring filename, jstring includeDir) {
    (void) clazz;
    const char *src = (*env)->GetStringUTFChars(env, code, NULL);
    const char *fname = (*env)->GetStringUTFChars(env, filename, NULL);
    const char *inc = (*env)->GetStringUTFChars(env, includeDir, NULL);

    static char result[64000];
    static char outbuf[256000];
    result[0] = '\0';
    outbuf[0] = '\0';

    ErrBuf errs;
    errs.len = 0;
    errs.msg[0] = '\0';

    if (g_sym_count == 0) add_common_symbols(NULL);

    TCCState *s = NULL;
    int compiled = 0;
    char last_err[8192];
    last_err[0] = '\0';

    for (int attempt = 0; attempt < 60; attempt++) {
        s = tcc_new();
        if (s == NULL) break;
        tcc_set_error_func(s, &errs, tcc_err);
        tcc_set_options(s, "-nostdinc -nostdlib");
        tcc_set_output_type(s, TCC_OUTPUT_MEMORY);
        tcc_define_symbol(s, "__ANDROID__", "1");
        tcc_define_symbol(s, "__aarch64__", "1");
        tcc_define_symbol(s, "__LP64__", "1");

        if (inc != NULL && inc[0] != '\0') {
            tcc_add_include_path(s, inc);
            tcc_add_sysinclude_path(s, inc);
        }
        for (int i = 0; i < g_sym_count; i++) {
            tcc_add_symbol(s, g_syms[i].name, g_syms[i].addr);
        }

        errs.len = 0;
        errs.msg[0] = '\0';
        if (tcc_compile_string(s, src != NULL ? src : "") == 0) {
            compiled = 1;
            break;
        }
        snprintf(last_err, sizeof(last_err), "%s", errs.msg);
        if (!resolve_missing(errs.msg)) {
            tcc_delete(s);
            s = NULL;
            break;
        }
        tcc_delete(s);
        s = NULL;
    }

    if (!compiled || s == NULL) {
        char esc[32000];
        json_escape_into(esc, sizeof(esc), last_err[0] ? last_err : (errs.msg[0] ? errs.msg : "compile error"));
        snprintf(result, sizeof(result), "{\"out\":\"\",\"error\":\"%s\",\"code\":1}", esc);
        goto done;
    }

    {
        int pipefd[2];
        if (pipe(pipefd) != 0) {
            tcc_delete(s);
            snprintf(result, sizeof(result), "{\"out\":\"\",\"error\":\"pipe failed\",\"code\":1}");
            goto done;
        }
        fflush(stdout);
        fflush(stderr);
        int old_out = dup(STDOUT_FILENO);
        int old_err = dup(STDERR_FILENO);
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[1]);

        errs.len = 0;
        errs.msg[0] = '\0';
        int run_rc = -1;
        if (tcc_relocate(s) < 0) {
            run_rc = -1;
        } else {
            int (*entry)(void) = (int (*)(void)) tcc_get_symbol(s, "main");
            if (entry != NULL) {
                run_rc = entry();
            } else {
                snprintf(errs.msg, sizeof(errs.msg), "找不到 main() 函数");
                run_rc = -1;
            }
        }

        fflush(stdout);
        fflush(stderr);
        dup2(old_out, STDOUT_FILENO);
        dup2(old_err, STDERR_FILENO);
        close(old_out);
        close(old_err);

        ssize_t total = 0;
        ssize_t n;
        while ((n = read(pipefd[0], outbuf + total, 200000 - total)) > 0) {
            total += n;
            if (total >= 199000) break;
        }
        close(pipefd[0]);
        if (total < 0) total = 0;
        outbuf[total] = '\0';

        char *esc_out = (char *) malloc(512 * 1024);
        char *esc_err = (char *) malloc(64 * 1024);
        if (esc_out != NULL && esc_err != NULL) {
            json_escape_into(esc_out, 512 * 1024, outbuf);
            json_escape_into(esc_err, 64 * 1024, errs.msg);
            snprintf(result, sizeof(result), "{\"out\":\"%s\",\"error\":\"%s\",\"code\":%d}",
                     esc_out, esc_err, run_rc);
        } else {
            snprintf(result, sizeof(result), "{\"out\":\"\",\"error\":\"out of memory\",\"code\":1}");
        }
        free(esc_out);
        free(esc_err);
        tcc_delete(s);
    }

done:
    if (src) (*env)->ReleaseStringUTFChars(env, code, src);
    if (fname) (*env)->ReleaseStringUTFChars(env, filename, fname);
    if (inc) (*env)->ReleaseStringUTFChars(env, includeDir, inc);
    return (*env)->NewStringUTF(env, result[0] ? result : "{\"out\":\"\",\"error\":\"unknown\",\"code\":1}");
}
