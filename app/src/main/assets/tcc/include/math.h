#ifndef _CODEPAD_MATH_H
#define _CODEPAD_MATH_H

#ifdef __cplusplus
extern "C" {
#endif

double sqrt(double x);
double cbrt(double x);
double pow(double x, double y);
double fabs(double x);
double floor(double x);
double ceil(double x);
double round(double x);
double fmod(double x, double y);
double sin(double x);
double cos(double x);
double tan(double x);
double asin(double x);
double acos(double x);
double atan(double x);
double atan2(double y, double x);
double exp(double x);
double log(double x);
double log2(double x);
double log10(double x);
double hypot(double x, double y);
double fmin(double x, double y);
double fmax(double x, double y);

#define M_PI 3.14159265358979323846
#define M_E 2.71828182845904523536

#ifdef __cplusplus
}
#endif

#endif
