#ifndef _CODEPAD_TIME_H
#define _CODEPAD_TIME_H

typedef long time_t;
typedef long clock_t;

#define CLOCKS_PER_SEC 1000000L

#ifdef __cplusplus
extern "C" {
#endif

time_t time(time_t *t);
clock_t clock(void);

#ifdef __cplusplus
}
#endif

#endif
