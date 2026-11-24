#ifndef _CODEPAD_STDLIB_H
#define _CODEPAD_STDLIB_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

void *malloc(size_t size);
void *calloc(size_t nmemb, size_t size);
void *realloc(void *ptr, size_t size);
void free(void *ptr);

int atoi(const char *nptr);
long atol(const char *nptr);
double atof(const char *nptr);
long strtol(const char *nptr, char **endptr, int base);
unsigned long strtoul(const char *nptr, char **endptr, int base);
double strtod(const char *nptr, char **endptr);

int abs(int j);
long labs(long j);
int rand(void);
void srand(unsigned int seed);
void exit(int status);
void abort(void);
char *getenv(const char *name);
int system(const char *command);
void qsort(void *base, size_t nmemb, size_t size,
           int (*compar)(const void *, const void *));

#define RAND_MAX 32767
#define EXIT_SUCCESS 0
#define EXIT_FAILURE 1

#ifdef __cplusplus
}
#endif

#endif
