/*
 * scprobe —— 探测当前进程的 seccomp 过滤器杀哪些 syscall。
 * 每个候选 syscall 开一个子进程去调用，父进程 waitpid 看是正常返回还是被信号杀掉。
 * 用来确认「glibc 二进制在 App 进程里为什么一启动就 Bad system call」。
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

struct probe {
    const char *name;
    long nr;
};

static const struct probe probes[] = {
    {"rseq", 293},
    {"membarrier", 283},
    {"clone3", 435},
    {"get_mempolicy", 236},
    {"mbind", 235},
    {"set_mempolicy", 237},
    {"move_pages", 239},
    {"migrate_pages", 238},
    {"process_vm_readv", 270},
    {"process_vm_writev", 271},
    {"perf_event_open", 241},
    {"userfaultfd", 282},
    {"io_uring_setup", 425},
    {"pkey_alloc", 289},
    {"set_robust_list", 99},
    {"get_robust_list", 100},
};

int main(void) {
    printf("pid=%d seccomp-probe\n", getpid());
    for (size_t i = 0; i < sizeof(probes) / sizeof(probes[0]); i++) {
        pid_t pid = fork();
        if (pid == 0) {
            long r = syscall(probes[i].nr, 0, 0, 0, 0, 0, 0);
            _exit((int) (r < 0 ? 1 : 0));
        }
        int status = 0;
        waitpid(pid, &status, 0);
        if (WIFSIGNALED(status)) {
            printf("%-18s signal=%d (%s)\n", probes[i].name, WTERMSIG(status),
                   WTERMSIG(status) == SIGSYS ? "SIGSYS" : "other");
        } else {
            printf("%-18s ok exit=%d\n", probes[i].name, WEXITSTATUS(status));
        }
    }
    return 0;
}
