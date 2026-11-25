/*
 * sigsysprobe.so —— 通过 LD_PRELOAD 塞进 glibc 程序，捕获 app seccomp 的 SIGSYS，
 * 把触发它的 syscall 号打到 stderr。
 *
 * 完全是自包含的：不链接 libc，所有 syscall 用内联汇编发，避免 bionic/glibc ABI 混用。
 * 用法：
 *   LD_PRELOAD=/path/sigsysprobe.so <glibc 程序> ...
 */
typedef unsigned long u64;
typedef long i64;

#define SYS_write 64
#define SYS_exit_group 94
#define SYS_rt_sigaction 134
#define SIGSYS 31
#define SA_SIGINFO 4

static i64 sys4(i64 n, i64 a, i64 b, i64 c, i64 d) {
    register i64 x8 asm("x8") = n;
    register i64 x0 asm("x0") = a;
    register i64 x1 asm("x1") = b;
    register i64 x2 asm("x2") = c;
    register i64 x3 asm("x3") = d;
    asm volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory");
    return x0;
}

static i64 sys3(i64 n, i64 a, i64 b, i64 c) {
    register i64 x8 asm("x8") = n;
    register i64 x0 asm("x0") = a;
    register i64 x1 asm("x1") = b;
    register i64 x2 asm("x2") = c;
    asm volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "memory");
    return x0;
}

static void put(const char *s, u64 len) { sys3(SYS_write, 2, (i64) s, (i64) len); }

static u64 u2s(u64 v, char *out) {
    char tmp[24];
    int i = 0;
    if (v == 0) tmp[i++] = '0';
    while (v > 0) { tmp[i++] = (char) ('0' + (v % 10)); v /= 10; }
    for (int j = 0; j < i; j++) out[j] = tmp[i - 1 - j];
    return (u64) i;
}

/* 内核 siginfo_t：si_signo/errno/code 各 4 字节，union 从偏移 16 开始；
   SIGSYS 的 _sigsys = { void* call_addr; int syscall; unsigned arch }。 */
static void handler(int sig, void *info, void *ctx) {
    (void) sig;
    (void) ctx;
    unsigned char *si = (unsigned char *) info;
    i64 nr = *(int *) (si + 24);
    void *addr = *(void **) (si + 16);
    char buf[96];
    u64 n = 0;
    const char *pre = "\n[SIGSYS] syscall=";
    while (*pre) buf[n++] = *pre++;
    char num[24];
    u64 k = u2s((u64) nr, num);
    for (u64 i = 0; i < k; i++) buf[n++] = num[i];
    const char *mid = " addr=0x";
    while (*mid) buf[n++] = *mid++;
    char hex[20];
    u64 a = (u64) addr;
    for (int i = 0; i < 16; i++) { hex[i] = "0123456789abcdef"[(a >> (60 - 4 * i)) & 0xF]; }
    for (int i = 0; i < 16; i++) buf[n++] = hex[i];
    buf[n++] = '\n';
    put(buf, n);
    sys3(SYS_exit_group, 97, 0, 0);
    for (;;) { }
}

/* 内核 struct sigaction（arm64）：handler, flags, restorer, mask */
struct ksa {
    void *handler;
    u64 flags;
    void *restorer;
    u64 mask;
};

__attribute__((constructor)) static void init_probe(void) {
    struct ksa sa;
    sa.handler = (void *) handler;
    sa.flags = SA_SIGINFO;
    sa.restorer = 0;
    sa.mask = 0;
    sys4(SYS_rt_sigaction, SIGSYS, (i64) &sa, 0, 8);
}
