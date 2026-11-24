#ifndef _CODEPAD_ASSERT_H
#define _CODEPAD_ASSERT_H

#ifdef __cplusplus
extern "C" {
#endif

void __assert(const char *file, int line, const char *msg);
void abort(void);

#ifdef __cplusplus
}
#endif

#define assert(expr) ((expr) ? (void)0 : __assert(__FILE__, __LINE__, #expr))

#endif
