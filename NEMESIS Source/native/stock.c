/* Is a file the stock AOSP app_process? The rules of compat_stock_app_process (boot/compat.sh) and
 * the installer's InstallerActivity.Compat; tests/compat_test.sh checks that all three agree.
 * Only libc here, so the host test links this file alone. */
#define _GNU_SOURCE
#include "nemesis.h"

int stock_app_process(const char *path) {
    static char buf[256 * 1024];
    static const char *const need[] = {"/system/bin/linker", "com.android.internal.os.ZygoteInit",
                                       "NO_ADDR_COMPAT_LAYOUT_FIXUP"};
    /* a wrapper names what it runs next or preloads; the AOSP binary names only itself */
    static const char *const deny[] = {"app_process.", "LD_PRELOAD", "/data/", "hmgapi", "HMG-API",
                                       "leafia", "Leafia", "xposed", "Xposed"};
    FILE *f = fopen(path, "rb");
    size_t n, i;
    int big;
    if (!f) return 0;
    n = fread(buf, 1, sizeof(buf), f);
    big = fgetc(f) != EOF; /* the stock binary is ~10 KB */
    fclose(f);
    if (big) return 0;
    for (i = 0; i < sizeof(need) / sizeof(need[0]); i++)
        if (!memmem(buf, n, need[i], strlen(need[i]))) return 0;
    for (i = 0; i < sizeof(deny) / sizeof(deny[0]); i++)
        if (memmem(buf, n, deny[i], strlen(deny[i]))) return 0;
    return 1;
}

#ifdef STOCK_MAIN
/* tests/compat_test.sh: stock_check <file> prints ok or refused, like compat_stock_app_process */
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    puts(stock_app_process(argv[1]) ? "ok" : "refused");
    return 0;
}
#endif
