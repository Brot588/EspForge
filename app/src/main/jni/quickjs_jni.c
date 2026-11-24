// CodePad QuickJS 桥：在应用进程内执行 JavaScript（ES2020）。
#include <jni.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "quickjs.h"

static JavaVM *cp_vm;

typedef struct {
    char *p;
    size_t len;
    size_t cap;
} Buf;

static void buf_init(Buf *b) {
    b->cap = 4096;
    b->len = 0;
    b->p = (char *) malloc(b->cap);
    if (b->p) b->p[0] = '\0';
}

static void buf_free(Buf *b) {
    free(b->p);
    b->p = NULL;
    b->len = b->cap = 0;
}

static void buf_add(Buf *b, const char *s, size_t n) {
    if (b->p == NULL) return;
    if (b->len + n + 1 > b->cap) {
        size_t cap = b->cap ? b->cap : 4096;
        while (b->len + n + 1 > cap) cap *= 2;
        char *np = (char *) realloc(b->p, cap);
        if (np == NULL) return;
        b->p = np;
        b->cap = cap;
    }
    memcpy(b->p + b->len, s, n);
    b->len += n;
    b->p[b->len] = '\0';
}

static void buf_puts(Buf *b, const char *s) {
    buf_add(b, s, strlen(s));
}

static JSValue js_print(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void) this_val;
    Buf *b = (Buf *) JS_GetContextOpaque(ctx);
    for (int i = 0; i < argc; i++) {
        if (i > 0) buf_puts(b, " ");
        size_t l = 0;
        const char *s = JS_ToCStringLen(ctx, &l, argv[i]);
        if (s != NULL) {
            buf_add(b, s, l);
            JS_FreeCString(ctx, s);
        }
    }
    buf_puts(b, "\n");
    return JS_UNDEFINED;
}

static const char *BOOTSTRAP =
    "globalThis.console = {"
    "  log: (...a) => __cp_print(...a),"
    "  info: (...a) => __cp_print(...a),"
    "  warn: (...a) => __cp_print(...a),"
    "  error: (...a) => __cp_print(...a),"
    "  debug: (...a) => __cp_print(...a)"
    "};";

static void json_escape(Buf *out, const char *s) {
    buf_puts(out, "\"");
    for (const unsigned char *p = (const unsigned char *) s; *p; p++) {
        switch (*p) {
            case '"': buf_puts(out, "\\\""); break;
            case '\\': buf_puts(out, "\\\\"); break;
            case '\n': buf_puts(out, "\\n"); break;
            case '\r': buf_puts(out, "\\r"); break;
            case '\t': buf_puts(out, "\\t"); break;
            default:
                if (*p < 0x20) {
                    char tmp[8];
                    snprintf(tmp, sizeof(tmp), "\\u%04x", *p);
                    buf_puts(out, tmp);
                } else {
                    char c = (char) *p;
                    buf_add(out, &c, 1);
                }
        }
    }
    buf_puts(out, "\"");
}

JNIEXPORT jlong JNICALL
Java_com_codepad_ide_run_QuickJs_nativeCreate(JNIEnv *env, jclass clazz) {
    (void) clazz;
    if (cp_vm == NULL) (*env)->GetJavaVM(env, &cp_vm);
    JSRuntime *rt = JS_NewRuntime();
    if (rt == NULL) return 0;
    JSContext *ctx = JS_NewContext(rt);
    if (ctx == NULL) {
        JS_FreeRuntime(rt);
        return 0;
    }
    Buf *b = (Buf *) malloc(sizeof(Buf));
    if (b == NULL) {
        JS_FreeContext(ctx);
        JS_FreeRuntime(rt);
        return 0;
    }
    buf_init(b);
    JS_SetContextOpaque(ctx, b);

    JSValue global = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, global, "__cp_print",
                      JS_NewCFunction(ctx, js_print, "__cp_print", 1));
    JS_FreeValue(ctx, global);

    JSValue r = JS_Eval(ctx, BOOTSTRAP, strlen(BOOTSTRAP), "<bootstrap>", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(r)) {
        JSValue e = JS_GetException(ctx);
        JS_FreeValue(ctx, e);
    }
    JS_FreeValue(ctx, r);

    JS_SetMaxStackSize(rt, 2 * 1024 * 1024);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT jstring JNICALL
Java_com_codepad_ide_run_QuickJs_nativeEval(JNIEnv *env, jclass clazz, jlong handle,
                                            jstring code, jstring filename) {
    (void) clazz;
    JSContext *ctx = (JSContext *) (intptr_t) handle;
    if (ctx == NULL) {
        return (*env)->NewStringUTF(env, "{\"out\":\"\",\"error\":\"js context is null\"}");
    }
    Buf *b = (Buf *) JS_GetContextOpaque(ctx);
    b->len = 0;
    if (b->p) b->p[0] = '\0';

    const char *src = (*env)->GetStringUTFChars(env, code, NULL);
    const char *fn = (*env)->GetStringUTFChars(env, filename, NULL);

    JSValue val = JS_Eval(ctx, src != NULL ? src : "", strlen(src != NULL ? src : ""),
                          fn != NULL ? fn : "<eval>", JS_EVAL_TYPE_GLOBAL);

    Buf out;
    buf_init(&out);
    buf_puts(&out, "{\"out\":");
    json_escape(&out, b->p ? b->p : "");
    buf_puts(&out, ",\"error\":");

    if (JS_IsException(val)) {
        JSValue e = JS_GetException(ctx);
        const char *msg = NULL;
        size_t mlen = 0;
        JSValue st = JS_GetPropertyStr(ctx, e, "stack");
        if (!JS_IsUndefined(st)) msg = JS_ToCStringLen(ctx, &mlen, st);
        JS_FreeValue(ctx, st);
        if (msg == NULL) msg = JS_ToCStringLen(ctx, &mlen, e);

        Buf err;
        buf_init(&err);
        if (msg != NULL) buf_add(&err, msg, mlen);
        else buf_puts(&err, "JavaScript error");
        json_escape(&out, err.p ? err.p : "JavaScript error");
        buf_puts(&out, ",\"value\":\"\"}");
        buf_free(&err);
        if (msg != NULL) JS_FreeCString(ctx, msg);
        JS_FreeValue(ctx, e);
    } else {
        Buf valbuf;
        buf_init(&valbuf);
        if (!JS_IsUndefined(val)) {
            const char *s = JS_ToCString(ctx, val);
            if (s != NULL) {
                buf_puts(&valbuf, s);
                JS_FreeCString(ctx, s);
            }
        }
        json_escape(&out, "");
        buf_puts(&out, ",\"value\":");
        json_escape(&out, valbuf.p ? valbuf.p : "");
        buf_puts(&out, "}");
        buf_free(&valbuf);
    }

    JS_FreeValue(ctx, val);
    if (src != NULL) (*env)->ReleaseStringUTFChars(env, code, src);
    if (fn != NULL) (*env)->ReleaseStringUTFChars(env, filename, fn);

    jstring result = (*env)->NewStringUTF(env, out.p ? out.p : "{\"out\":\"\",\"error\":\"\"}");
    buf_free(&out);
    return result;
}

JNIEXPORT void JNICALL
Java_com_codepad_ide_run_QuickJs_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    JSContext *ctx = (JSContext *) (intptr_t) handle;
    if (ctx == NULL) return;
    JSRuntime *rt = JS_GetRuntime(ctx);
    Buf *b = (Buf *) JS_GetContextOpaque(ctx);
    if (b != NULL) {
        buf_free(b);
        free(b);
    }
    JS_SetContextOpaque(ctx, NULL);
    JS_FreeContext(ctx);
    JS_FreeRuntime(rt);
}

/* ---------------- CodePad JS debugger bridge ---------------- */
int cp_dbg_enabled = 0;
static int cp_dbg_mode = 0;      /* 0 run, 1 step, 2 next, 3 return */
static int cp_dbg_stop_depth = 0;
static int cp_dbg_bps[512];
static int cp_dbg_bp_count = 0;
static char *cp_dbg_file = NULL;


static JNIEnv *cp_get_env(void) {
    JNIEnv *env = NULL;
    if (cp_vm == NULL) return NULL;
    if ((*cp_vm)->GetEnv(cp_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*cp_vm)->AttachCurrentThread(cp_vm, &env, NULL) != JNI_OK) return NULL;
    }
    return env;
}

int cp_dbg_check(const char *file, int line, int depth) {
    if (!cp_dbg_enabled) return 0;
    if (cp_dbg_file == NULL || strcmp(file, cp_dbg_file) != 0) return 0;
    for (int i = 0; i < cp_dbg_bp_count; i++) {
        if (cp_dbg_bps[i] == line) return 1;
    }
    if (cp_dbg_mode == 1) return 1;
    if (cp_dbg_mode == 2 && depth <= cp_dbg_stop_depth) return 1;
    if (cp_dbg_mode == 3 && depth < cp_dbg_stop_depth) return 1;
    return 0;
}

void cp_dbg_enter(const char *json, int depth) {
    JNIEnv *env = cp_get_env();
    if (env == NULL) return;
    jclass cls = (*env)->FindClass(env, "com/codepad/ide/run/DebugBridge");
    if (cls == NULL) return;
    jmethodID mid_pub = (*env)->GetStaticMethodID(env, cls, "publish", "(Ljava/lang/String;)V");
    jmethodID mid_wait = (*env)->GetStaticMethodID(env, cls, "awaitCommand", "()Ljava/lang/String;");
    if (mid_pub != NULL && mid_wait != NULL) {
        jstring js = (*env)->NewStringUTF(env, json);
        if (js != NULL) {
            (*env)->CallStaticVoidMethod(env, cls, mid_pub, js);
            (*env)->DeleteLocalRef(env, js);
        }
        jstring cmd = (jstring) (*env)->CallStaticObjectMethod(env, cls, mid_wait);
        const char *c = "continue";
        if (cmd != NULL) c = (*env)->GetStringUTFChars(env, cmd, NULL);
        if (c == NULL) c = "continue";
        if (strcmp(c, "step") == 0) {
            cp_dbg_mode = 1;
            cp_dbg_stop_depth = depth;
        } else if (strcmp(c, "next") == 0) {
            cp_dbg_mode = 2;
            cp_dbg_stop_depth = depth;
        } else if (strcmp(c, "return") == 0) {
            cp_dbg_mode = 3;
            cp_dbg_stop_depth = depth;
        } else {
            cp_dbg_mode = 0;
        }
        if (cmd != NULL) {
            (*env)->ReleaseStringUTFChars(env, cmd, c);
            (*env)->DeleteLocalRef(env, cmd);
        }
    }
    (*env)->DeleteLocalRef(env, cls);
}

JNIEXPORT void JNICALL
Java_com_codepad_ide_run_QuickJs_nativeDebugStart(JNIEnv *env, jclass clazz, jstring file, jstring bps) {
    (void) clazz;
    cp_dbg_bp_count = 0;
    cp_dbg_mode = 0;
    cp_dbg_stop_depth = 0;
    if (cp_dbg_file != NULL) {
        free(cp_dbg_file);
        cp_dbg_file = NULL;
    }
    const char *f = (*env)->GetStringUTFChars(env, file, NULL);
    if (f != NULL) {
        cp_dbg_file = strdup(f);
        (*env)->ReleaseStringUTFChars(env, file, f);
    }
    const char *s = (*env)->GetStringUTFChars(env, bps, NULL);
    if (s != NULL) {
        const char *p = s;
        while (*p != '\0' && cp_dbg_bp_count < 512) {
            while (*p != '\0' && (*p < '0' || *p > '9')) p++;
            if (*p == '\0') break;
            int v = 0;
            while (*p >= '0' && *p <= '9') {
                v = v * 10 + (*p - '0');
                p++;
            }
            cp_dbg_bps[cp_dbg_bp_count++] = v;
        }
        (*env)->ReleaseStringUTFChars(env, bps, s);
    }
    cp_dbg_enabled = 1;
}

JNIEXPORT void JNICALL
Java_com_codepad_ide_run_QuickJs_nativeDebugStop(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    cp_dbg_enabled = 0;
    cp_dbg_mode = 0;
    cp_dbg_bp_count = 0;
    if (cp_dbg_file != NULL) {
        free(cp_dbg_file);
        cp_dbg_file = NULL;
    }
}
