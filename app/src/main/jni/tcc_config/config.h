/* CodePad: hand-written tcc config for aarch64 Android (no configure run). */
#ifndef _TCC_CONFIG_H
#define _TCC_CONFIG_H

#define TCC_VERSION "0.9.28"

#define CC_NAME CC_clang
#define GCC_MAJOR 13
#define GCC_MINOR 0

#define TCC_TARGET_ARM64 1
#define TARGETOS_ANDROID 1

#define CONFIG_TCCDIR "/data/local/tmp/codepad-tcc"
#define CONFIG_TCC_ELFINTERP "/system/bin/linker64"
#define CONFIG_LDDIR "lib64"

#define CONFIG_TCC_SYSINCLUDEPATHS "/data/local/tmp/codepad-tcc/include"
#define CONFIG_TCC_LIBPATHS "/system/lib64"
#define CONFIG_TCC_CRTPREFIX "/system/lib64"

#define CONFIG_TCC_BCHECK 0
#define CONFIG_TCC_BACKTRACE 0
#define CONFIG_TCC_SEMLOCK 0
#define CONFIG_TCC_STATIC 1
#define ONE_SOURCE 1

#endif
