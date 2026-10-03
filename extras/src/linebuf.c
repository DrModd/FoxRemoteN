/* liblinebuf.so — построчный вывод stdout для qobuz-connect (через LD_PRELOAD).
 * Без него вывод в канал (pipe) копится в буфере 4 КБ, и pfmeta не видит
 * название трека вовремя. Собирается GitHub Actions (.github/workflows/fox-lib.yml). */
#include <stdio.h>

__attribute__((constructor))
static void pf_linebuf(void)
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
}
