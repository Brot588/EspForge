#ifndef _CODEPAD_ERRNO_H
#define _CODEPAD_ERRNO_H

int *__errno(void);
#define errno (*__errno())

#define EDOM 33
#define ERANGE 34

#endif
