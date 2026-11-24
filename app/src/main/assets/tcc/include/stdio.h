#ifndef _CODEPAD_STDIO_H
#define _CODEPAD_STDIO_H

#include <stddef.h>
#include <stdarg.h>

typedef struct _IO_FILE FILE;

#ifdef __cplusplus
extern "C" {
#endif

int printf(const char *format, ...);
int fprintf(FILE *stream, const char *format, ...);
int sprintf(char *str, const char *format, ...);
int snprintf(char *str, size_t size, const char *format, ...);
int scanf(const char *format, ...);
int sscanf(const char *str, const char *format, ...);
int puts(const char *s);
int putchar(int c);
int getchar(void);
int fputs(const char *s, FILE *stream);
int fputc(int c, FILE *stream);
int fflush(FILE *stream);

extern FILE *stdin;
extern FILE *stdout;
extern FILE *stderr;

#define EOF (-1)
#define NULL ((void *)0)

#ifdef __cplusplus
}
#endif

#endif
