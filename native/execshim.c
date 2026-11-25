/*
 * execshim —— glibc 工具链的入口垫片。
 *
 * 为什么需要它：工具链是 glibc 二进制，Android 是 bionic，不能直接 exec。
 * 最初用 shell 脚本包装（#!... exec ld.so），但 ninja/cmake 用 posix_spawn
 * 直接 exec 时，脚本形式会失败（exit 127），而手工在 shell 里跑又正常。
 *
 * 所以改成一个极小的原生 ELF：它自己就是 bionic 程序，能被 ninja 正常执行，
 * 然后把参数拼成 `ld.so --library-path <libs> <自身>.real <原参数>` 再 execv。
 *
 * 路径全部从 /proc/self/exe 推导，不需要环境变量：
 *   /data/.../files/pack/tools/<tc>/bin/<tool>            ← 本垫片
 *   /data/.../files/pack/tools/<tc>/bin/<tool>.real       ← 真正的 glibc 程序
 *   /data/.../files/pack/glibc/lib/ld-linux-aarch64.so.1  ← glibc loader
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <libgen.h>

#define MAXPATH 4096

/*
 * 工具可能位于三种深度：
 *   <pack>/tools/<tc>/bin/<tool>
 *   <pack>/tools/<tc>/libexec/gcc/<tc>/<ver>/<tool>
 * 所以不按固定层级推算，而是从自身路径逐级向上找「含有 glibc/lib/ld-linux-aarch64.so.1 的目录」，
 * 那就是 pack 根。
 */
static int find_pack_root(const char *self, char *out, size_t outsz) {
    char dir[MAXPATH];
    snprintf(dir, sizeof(dir), "%s", self);

    for (int level = 0; level < 9; level++) {
        char *slash = strrchr(dir, '/');
        if (!slash) break;
        *slash = '\0';                      /* 上跳一级 */

        char probe[MAXPATH];
        snprintf(probe, sizeof(probe), "%s/glibc/lib/ld-linux-aarch64.so.1", dir);
        if (access(probe, X_OK) == 0) {
            snprintf(out, outsz, "%s", dir);
            return 0;
        }
    }
    return -1;
}

int main(int argc, char **argv) {
    char self[MAXPATH];
    ssize_t n = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (n <= 0) {
        perror("execshim: readlink /proc/self/exe");
        return 127;
    }
    self[n] = '\0';

    /* 本身已经是 .real 就别再套一层，避免死循环 */
    size_t len = strlen(self);
    if (len > 5 && strcmp(self + len - 5, ".real") == 0) {
        execv(self, argv);
        perror("execshim: execv self");
        return 127;
    }

    /* 优先用 App 传进来的环境变量，其次自己找 */
    const char *root_env = getenv("ESPSTUDIO_PACK_ROOT");
    char root[MAXPATH];
    if (root_env && root_env[0]) {
        snprintf(root, sizeof(root), "%s", root_env);
    } else if (find_pack_root(self, root, sizeof(root)) != 0) {
        fprintf(stderr, "execshim: 找不到 pack 根目录（self=%s）\n", self);
        return 127;
    }

    char loader[MAXPATH], libs[MAXPATH], real[MAXPATH];
    snprintf(loader, sizeof(loader), "%s/glibc/lib/ld-linux-aarch64.so.1", root);
    snprintf(libs, sizeof(libs), "%s/glibc/lib", root);
    snprintf(real, sizeof(real), "%s.real", self);

    if (access(real, R_OK) != 0) {
        fprintf(stderr, "execshim: missing %s\n", real);
        return 127;
    }

    /* argv: loader --library-path LIBS REAL args... */
    char **na = calloc((size_t)argc + 4, sizeof(char *));
    if (!na) return 127;
    na[0] = loader;
    na[1] = "--library-path";
    na[2] = libs;
    na[3] = real;
    for (int i = 1; i < argc; i++) na[i + 3] = argv[i];
    na[argc + 3] = NULL;

    /* glibc 会继承 LD_LIBRARY_PATH/LD_PRELOAD，必须清掉，否则会去加载 bionic 的 libc */
    unsetenv("LD_LIBRARY_PATH");
    unsetenv("LD_PRELOAD");

    /*
     * GCC 驱动给自己子进程传的记账变量。实测：cc1 的环境里只要带着
     * GCC_EXEC_PREFIX，在 Android 的 app seccomp 下就会走到被杀的路径
     * （Bad system call / SIGSYS），去掉它就正常。工具链程序本身都不需要
     * 这几个变量（路径由驱动按 argv[0] 自己算），统一清掉最稳。
     */
    unsetenv("GCC_EXEC_PREFIX");
    unsetenv("COLLECT_GCC");
    unsetenv("COLLECT_GCC_OPTIONS");

    execv(loader, na);
    perror("execshim: execv loader");
    return 127;
}
