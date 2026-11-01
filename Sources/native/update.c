#include "nemesis.h"

#include <dirent.h>
#include <signal.h>
#include <sys/wait.h>

/*
 * nemesis update <zip>: replaces the core files (binary, Manager APK,
 * install-recovery.sh, QB hook) from a type=update package. Every file is
 * checked against MD5SUMS first; the current files are copied to
 * UPDATE_DIR/backup and put back if any later step fails.
 *
 * Exit codes: 0 updated, 1 usage or bad zip, 7 older than installed,
 * 8 MD5SUMS check failed, 9 already up to date, 10 failed and restored,
 * 11 failed and the restore failed too, 20 incompatible (pass --accept-risk).
 */

/* ---- MD5 (RFC 1321) ---- */

typedef struct {
    uint32_t a, b, c, d;
    uint64_t len;
    unsigned char buf[64];
    size_t used;
} md5_ctx;

static const uint32_t MD5_K[64] = {
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
    0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
    0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
    0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
    0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
    0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391};
static const unsigned char MD5_S[64] = {7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
                                        5, 9,  14, 20, 5, 9,  14, 20, 5, 9,  14, 20, 5, 9,  14, 20,
                                        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
                                        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};

static void md5_block(md5_ctx *c, const unsigned char *p) {
    uint32_t m[16], a = c->a, b = c->b, cc = c->c, d = c->d, f, t;
    int i, g;
    for (i = 0; i < 16; i++)
        m[i] = (uint32_t)p[i * 4] | (uint32_t)p[i * 4 + 1] << 8 | (uint32_t)p[i * 4 + 2] << 16 |
               (uint32_t)p[i * 4 + 3] << 24;
    for (i = 0; i < 64; i++) {
        if (i < 16) {
            f = (b & cc) | (~b & d);
            g = i;
        } else if (i < 32) {
            f = (d & b) | (~d & cc);
            g = (5 * i + 1) & 15;
        } else if (i < 48) {
            f = b ^ cc ^ d;
            g = (3 * i + 5) & 15;
        } else {
            f = cc ^ (b | ~d);
            g = (7 * i) & 15;
        }
        t = d;
        d = cc;
        cc = b;
        f += a + MD5_K[i] + m[g];
        b += (f << MD5_S[i]) | (f >> (32 - MD5_S[i]));
        a = t;
    }
    c->a += a;
    c->b += b;
    c->c += cc;
    c->d += d;
}

static void md5_init(md5_ctx *c) {
    c->a = 0x67452301;
    c->b = 0xefcdab89;
    c->c = 0x98badcfe;
    c->d = 0x10325476;
    c->len = 0;
    c->used = 0;
}

static void md5_update(md5_ctx *c, const unsigned char *p, size_t n) {
    c->len += n;
    while (n) {
        size_t k = 64 - c->used;
        if (k > n) k = n;
        memcpy(c->buf + c->used, p, k);
        c->used += k;
        p += k;
        n -= k;
        if (c->used == 64) {
            md5_block(c, c->buf);
            c->used = 0;
        }
    }
}

static void md5_final(md5_ctx *c, unsigned char out[16]) {
    uint64_t bits = c->len * 8;
    unsigned char pad = 0x80, zero = 0, lb[8];
    int i;
    md5_update(c, &pad, 1);
    while (c->used != 56) md5_update(c, &zero, 1);
    for (i = 0; i < 8; i++) lb[i] = (unsigned char)(bits >> (8 * i));
    md5_update(c, lb, 8);
    for (i = 0; i < 4; i++) {
        out[i] = (unsigned char)(c->a >> (8 * i));
        out[4 + i] = (unsigned char)(c->b >> (8 * i));
        out[8 + i] = (unsigned char)(c->c >> (8 * i));
        out[12 + i] = (unsigned char)(c->d >> (8 * i));
    }
}

/* Empty string when the file cannot be read. */
void md5_file_hex(const char *path, char out[33]) {
    unsigned char buf[8192], dig[16];
    md5_ctx c;
    ssize_t n;
    int fd = open(path, O_RDONLY), i;
    out[0] = 0;
    if (fd < 0) return;
    md5_init(&c);
    while ((n = read(fd, buf, sizeof(buf))) > 0) md5_update(&c, buf, (size_t)n);
    close(fd);
    if (n < 0) return;
    md5_final(&c, dig);
    for (i = 0; i < 16; i++) snprintf(out + i * 2, 3, "%02x", dig[i]);
}

/* ---- MD5SUMS ---- */

static int rel_path_ok(const char *rel) {
    const char *p = rel;
    if (!*rel || *rel == '/') return 0;
    while (*p) {
        if (p[0] == '.' && p[1] == '.' && (p[2] == '/' || p[2] == 0) && (p == rel || p[-1] == '/'))
            return 0;
        p++;
    }
    return 1;
}

/* Counts the regular files under dir, except MD5SUMS at the top. */
static int count_files(const char *dir, const char *top) {
    DIR *d = opendir(dir);
    struct dirent *e;
    int n = 0;
    if (!d) return -1;
    while ((e = readdir(d))) {
        char p[MAX_PATH];
        struct stat st;
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        snprintf(p, sizeof(p), "%s/%s", dir, e->d_name);
        if (lstat(p, &st) != 0) continue;
        if (S_ISDIR(st.st_mode)) {
            int k = count_files(p, top);
            if (k < 0) {
                closedir(d);
                return -1;
            }
            n += k;
        } else if (S_ISREG(st.st_mode)) {
            if (!strcmp(dir, top) && !strcmp(e->d_name, "MD5SUMS")) continue;
            n++;
        }
    }
    closedir(d);
    return n;
}

/* Every file in the package must be listed in MD5SUMS and match. */
static int verify_md5sums(const char *root) {
    char path[MAX_PATH], line[MAX_LINE];
    FILE *f;
    int listed = 0, have_bin = 0, total;
    snprintf(path, sizeof(path), "%s/MD5SUMS", root);
    f = fopen(path, "r");
    if (!f) {
        fprintf(stderr, "update: MD5SUMS missing\n");
        return -1;
    }
    while (fgets(line, sizeof(line), f)) {
        char *sum = line, *rel, *e, want[33], have[33], fp[MAX_PATH];
        int i;
        e = line + strlen(line);
        while (e > line && (e[-1] == '\n' || e[-1] == '\r' || e[-1] == ' ')) *--e = 0;
        if (!line[0] || line[0] == '#') continue;
        for (i = 0; i < 32; i++) {
            char ch = sum[i];
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F'))) break;
            want[i] = (char)(ch >= 'A' && ch <= 'F' ? ch + 32 : ch);
        }
        want[32] = 0;
        if (i != 32 || (sum[32] != ' ' && sum[32] != '\t')) {
            fprintf(stderr, "update: bad MD5SUMS line: %s\n", line);
            fclose(f);
            return -1;
        }
        rel = sum + 32;
        while (*rel == ' ' || *rel == '\t') rel++;
        if (*rel == '*') rel++;
        if (!rel_path_ok(rel)) {
            fprintf(stderr, "update: bad path in MD5SUMS: %s\n", rel);
            fclose(f);
            return -1;
        }
        snprintf(fp, sizeof(fp), "%s/%s", root, rel);
        md5_file_hex(fp, have);
        if (!have[0] || strcmp(have, want)) {
            fprintf(stderr, "update: md5 mismatch: %s\n", rel);
            log_msg("update: md5 mismatch %s want=%s have=%s", rel, want, have[0] ? have : "missing");
            fclose(f);
            return -1;
        }
        if (!strcmp(rel, "core/nemesis")) have_bin = 1;
        listed++;
    }
    fclose(f);
    if (!have_bin) {
        fprintf(stderr, "update: core/nemesis missing from MD5SUMS\n");
        return -1;
    }
    total = count_files(root, root);
    if (total != listed) {
        fprintf(stderr, "update: %d files in the package, %d listed in MD5SUMS\n", total, listed);
        return -1;
    }
    return 0;
}

/* ---- Root CAs ---- */

static int owned_has(const char *name) {
    char buf[8192], *p, *nl;
    if (read_file(OWNED_CACERTS, buf, sizeof(buf)) <= 0) return 0;
    for (p = buf; *p; p = nl ? nl + 1 : p + strlen(p)) {
        nl = strchr(p, '\n');
        if (nl) *nl = 0;
        if (!strcmp(p, name)) return 1;
        if (!nl) break;
    }
    return 0;
}

static int cacert_name_ok(const char *n) {
    size_t len = strlen(n), i;
    if (len < 3 || len > 16 || n[len - 2] != '.' || !strchr("0123456789", n[len - 1])) return 0;
    for (i = 0; i + 2 < len; i++)
        if (!((n[i] >= '0' && n[i] <= '9') || (n[i] >= 'a' && n[i] <= 'f'))) return 0;
    return 1;
}

/* Copies each <hash>.N from dir that /system does not have yet and records
 * it in OWNED_CACERTS. Existing files are left alone. /system must be rw.
 * `added` gets the names, newline separated. Returns the count, or -1. */
int cacerts_install(const char *dir, char *added, size_t cap) {
    DIR *d = opendir(dir);
    struct dirent *e;
    int n = 0;
    if (added && cap) added[0] = 0;
    if (!d) return 0;
    mkdir_p(CACERTS_DIR, 0755);
    while ((e = readdir(d))) {
        char src[MAX_PATH], dst[MAX_PATH], line[64];
        if (!cacert_name_ok(e->d_name)) continue;
        snprintf(src, sizeof(src), "%s/%s", dir, e->d_name);
        snprintf(dst, sizeof(dst), "%s/%s", CACERTS_DIR, e->d_name);
        if (path_exists(dst)) continue;
        if (copy_file(src, dst, 0644) != 0) {
            log_msg("cacerts: copy %s failed errno=%d", e->d_name, errno);
            unlink(dst);
            closedir(d);
            return -1;
        }
        chown(dst, 0, 0);
        chmod(dst, 0644);
        if (!owned_has(e->d_name)) {
            snprintf(line, sizeof(line), "%s\n", e->d_name);
            append_file(OWNED_CACERTS, line);
        }
        if (added && strlen(added) + strlen(e->d_name) + 2 < cap) {
            strcat(added, e->d_name);
            strcat(added, "\n");
        }
        n++;
    }
    closedir(d);
    if (n) log_msg("cacerts: added %d root certificate(s)", n);
    return n;
}

/* First install (OTA bootstrap): add the bundled roots the unit lacks. */
int cmd_cacerts_install(const char *dir) {
    int n;
    if (!dir || !is_dir(dir)) {
        fprintf(stderr, "cacerts-install: no such directory\n");
        return 1;
    }
    if (remount_system(1) != 0) {
        fprintf(stderr, "cacerts-install: cannot remount /system rw\n");
        return 1;
    }
    n = cacerts_install(dir, NULL, 0);
    sync();
    remount_system(0);
    if (n < 0) {
        fprintf(stderr, "cacerts-install: copy failed\n");
        return 1;
    }
    printf("ok added %d root certificate(s)\n", n);
    return 0;
}

/* Removes the CA files NEMESIS added. `only` limits it to those names
 * (newline separated); NULL removes all of them. /system must be rw. */
int cacerts_remove_owned(const char *only) {
    char buf[8192], keep[8192], wanted[2056], *p, *nl, path[MAX_PATH];
    int n = 0;
    keep[0] = 0;
    snprintf(wanted, sizeof(wanted), "\n%s", only ? only : "");
    if (read_file(OWNED_CACERTS, buf, sizeof(buf)) <= 0) return 0;
    for (p = buf; *p; p = nl + 1) {
        nl = strchr(p, '\n');
        if (nl) *nl = 0;
        if (cacert_name_ok(p)) {
            int hit = 1;
            if (only) {
                char pat[32];
                snprintf(pat, sizeof(pat), "\n%s\n", p);
                hit = strstr(wanted, pat) != NULL;
            }
            if (hit) {
                snprintf(path, sizeof(path), "%s/%s", CACERTS_DIR, p);
                if (unlink(path) == 0 || errno == ENOENT) n++;
            } else if (strlen(keep) + strlen(p) + 2 < sizeof(keep)) {
                strcat(keep, p);
                strcat(keep, "\n");
            }
        }
        if (!nl) break;
    }
    if (keep[0])
        write_file(OWNED_CACERTS, keep, 0644);
    else
        unlink(OWNED_CACERTS);
    return n;
}

/* ---- Core file replacement ---- */

typedef struct {
    const char *rel; /* in the package */
    const char *dst;
    mode_t mode;
    const char *bak; /* name in the backup dir */
} core_file;

static const core_file CORE_FILES[] = {
    {"core/nemesis", BIN_PATH, 04755, "nemesis"},
    {"core/NEMESIS.apk", MANAGER_APK, 0644, "NEMESIS.apk"},
    {"core/install-recovery.sh", INSTALL_RECOVERY, 0755, "install-recovery.sh"},
};
#define N_CORE (int)(sizeof(CORE_FILES) / sizeof(CORE_FILES[0]))

/* Writes a temp file next to dst, then renames it over dst, so a running
 * binary keeps its old inode and a crash never leaves half a file. */
static int place_file(const char *src, const char *dst, mode_t mode) {
    char tmp[MAX_PATH];
    snprintf(tmp, sizeof(tmp), "%s.nemesis-new", dst);
    unlink(tmp);
    if (copy_file(src, tmp, mode & 0777) != 0) {
        log_msg("update: write %s failed errno=%d", tmp, errno);
        unlink(tmp);
        return -1;
    }
    /* chown clears setuid, so the mode goes on after it. */
    if (chown(tmp, 0, 0) != 0 || chmod(tmp, mode) != 0) {
        log_msg("update: chown/chmod %s failed errno=%d", tmp, errno);
        unlink(tmp);
        return -1;
    }
    {
        int fd = open(tmp, O_RDONLY);
        if (fd >= 0) {
            fsync(fd);
            close(fd);
        }
    }
    if (rename(tmp, dst) != 0) {
        log_msg("update: rename %s failed errno=%d", dst, errno);
        unlink(tmp);
        return -1;
    }
    return 0;
}

static void restart_watchdog(void) {
    run_cmd(BIN_PATH " watchdog");
}

static void remove_tmp_files(void) {
    int i;
    for (i = 0; i < N_CORE; i++) {
        char tmp[MAX_PATH];
        snprintf(tmp, sizeof(tmp), "%s.nemesis-new", CORE_FILES[i].dst);
        unlink(tmp);
    }
}

/* had[i]: the file existed before the update. */
static int restore_backup(const char *bak, const int *had, int have_qb, const char *added_ca) {
    int i, bad = 0;
    char p[MAX_PATH];
    log_msg("update: restoring backup");
    if (remount_system(1) != 0) {
        log_msg("update: restore: remount rw failed errno=%d", errno);
        return -1;
    }
    remove_tmp_files();
    for (i = 0; i < N_CORE; i++) {
        snprintf(p, sizeof(p), "%s/%s", bak, CORE_FILES[i].bak);
        if (had[i]) {
            if (place_file(p, CORE_FILES[i].dst, CORE_FILES[i].mode) != 0) bad = 1;
        } else {
            unlink(CORE_FILES[i].dst);
        }
    }
    if (have_qb) {
        snprintf(p, sizeof(p), "%s/qb_runtime.sh", bak);
        if (replace_keep_attrs(p, QB_RUNTIME) != 0) bad = 1;
    }
    if (added_ca && added_ca[0]) cacerts_remove_owned(added_ca);
    sync();
    remount_system(0);
    purge_manager_dalvik();
    return bad ? -1 : 0;
}

static int has_type(const char *types, const char *want) {
    const char *p = types;
    char item[64];
    while (meta_list_next(&p, item, sizeof(item)))
        if (!strcmp(item, want)) return 1;
    return 0;
}

/* A binary that cannot run on this unit must not replace the working one.
 * SELinux may forbid running from /data; that says nothing about the binary. */
static int staged_binary_runs(const char *path) {
    pid_t pid;
    int st, null;
    chmod(path, 0700);
    pid = fork();
    if (pid < 0) return 0;
    if (pid == 0) {
        null = open("/dev/null", O_RDWR);
        if (null >= 0) {
            dup2(null, 0);
            dup2(null, 1);
            dup2(null, 2);
        }
        alarm(15);
        execl(path, "nemesis", "version", (char *)NULL);
        _exit(errno == EACCES ? 125 : 126);
    }
    if (waitpid(pid, &st, 0) < 0) return 0;
    if (WIFEXITED(st) && WEXITSTATUS(st) == 125) {
        log_msg("update: test run of the new binary not permitted; skipped");
        return 1;
    }
    return WIFEXITED(st) && WEXITSTATUS(st) == 0;
}

static int install_core_packages(const char *root) {
    char dir[MAX_PATH];
    DIR *d;
    struct dirent *e;
    int rc = 0;
    snprintf(dir, sizeof(dir), "%s/core-packages", root);
    d = opendir(dir);
    if (!d) return 0;
    while ((e = readdir(d))) {
        char zip[MAX_PATH], cmd[MAX_PATH + 64];
        size_t len = strlen(e->d_name);
        int r;
        if (len < 5 || strcmp(e->d_name + len - 4, ".zip")) continue;
        if (strchr(e->d_name, '\'')) continue;
        snprintf(zip, sizeof(zip), "%s/%s", dir, e->d_name);
        /* The new binary installs them, so they meet its own rules. */
        snprintf(cmd, sizeof(cmd), BIN_PATH " install --update --core --accept-risk '%s'", zip);
        r = run_cmd(cmd);
        log_msg("update: core package %s rc=%d", e->d_name, r);
        if (r != 0) {
            fprintf(stderr, "update: core package %s failed (rc=%d)\n", e->d_name, r);
            rc = -1;
            break;
        }
    }
    closedir(d);
    return rc;
}

int cmd_update(int argc, char **argv) {
    const char *zip = NULL;
    int i, force = 0, accept_risk = 0, reboot = 1, compat, had[N_CORE], have_qb = 0, rc;
    char stage[MAX_PATH], root[MAX_PATH], bak[MAX_PATH], p[MAX_PATH], reasons[1024];
    char added_ca[2048];
    ini_file ini;
    pkg_meta m;

    for (i = 0; i < argc; i++) {
        if (!strcmp(argv[i], "--force")) force = 1;
        else if (!strcmp(argv[i], "--accept-risk")) accept_risk = 1;
        else if (!strcmp(argv[i], "--no-reboot")) reboot = 0;
        else if (argv[i][0] != '-') zip = argv[i];
    }
    if (!zip) {
        fprintf(stderr, "usage: nemesis update [--force] [--accept-risk] [--no-reboot] <zip>\n");
        return 1;
    }
    if (!is_file(zip)) {
        fprintf(stderr, "missing zip: %s\n", zip);
        return 1;
    }
    ensure_dirs();
    if (load_zip_meta(zip, &ini, &m) != 0) return 1;
    ini_free(&ini);
    if (strcmp(m.id, CORE_ID) || !has_type(m.types, "update")) {
        fprintf(stderr, "not a NEMESIS update package (need id=%s type=update)\n", CORE_ID);
        return 1;
    }
    if (!m.pkg_ver[0]) {
        fprintf(stderr, "update package has no package-version\n");
        return 1;
    }
    compat = pkg_compat(&m, reasons, sizeof(reasons));
    if (compat == COMPAT_NONE && !accept_risk) {
        printf("compat=incompatible\n%s", reasons);
        fflush(stdout);
        fprintf(stderr, "update not marked compatible with this unit (pass --accept-risk)\n");
        return 20;
    }
    rc = ver_cmp(m.pkg_ver, NEMESIS_VERSION);
    if (rc < 0 && !force) {
        fprintf(stderr, "update %s is older than the installed %s (pass --force)\n", m.pkg_ver, NEMESIS_VERSION);
        return 7;
    }
    if (rc == 0 && !force) {
        printf("already up to date %s\n", NEMESIS_VERSION);
        return 9;
    }

    /* The USB handler and the Manager's worker may be stopped below; the
     * update must outlive them. */
    setsid();
    signal(SIGHUP, SIG_IGN);
    signal(SIGTERM, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);

    log_msg("update: %s -> %s from %s", NEMESIS_VERSION, m.pkg_ver, zip);
    snprintf(stage, sizeof(stage), "%s/stage", UPDATE_DIR);
    snprintf(bak, sizeof(bak), "%s/backup", UPDATE_DIR);
    rm_rf(stage);
    mkdir_p(stage, 0700);
    if (zip_extract(zip, stage) != 0 || package_root(stage, root, sizeof(root)) != 0) {
        fprintf(stderr, "update: unzip failed\n");
        rm_rf(stage);
        return 1;
    }
    if (verify_md5sums(root) != 0) {
        log_msg("update: verification failed");
        rm_rf(stage);
        return 8;
    }
    snprintf(p, sizeof(p), "%s/core/nemesis", root);
    {
        char magic[4] = {0};
        int fd = open(p, O_RDONLY);
        if (fd < 0 || read(fd, magic, 4) != 4 || memcmp(magic, "\177ELF", 4)) {
            if (fd >= 0) close(fd);
            fprintf(stderr, "update: core/nemesis is not an ELF binary\n");
            rm_rf(stage);
            return 8;
        }
        close(fd);
    }
    if (!staged_binary_runs(p)) {
        log_msg("update: core/nemesis does not run on this unit");
        fprintf(stderr, "update: core/nemesis does not run on this unit\n");
        rm_rf(stage);
        return 8;
    }

    rm_rf(bak);
    mkdir_p(bak, 0700);
    for (i = 0; i < N_CORE; i++) {
        had[i] = is_file(CORE_FILES[i].dst);
        snprintf(p, sizeof(p), "%s/%s", bak, CORE_FILES[i].bak);
        if (had[i] && copy_file(CORE_FILES[i].dst, p, 0600) != 0) {
            fprintf(stderr, "update: backup of %s failed\n", CORE_FILES[i].dst);
            rm_rf(stage);
            return 1;
        }
    }
    if (is_file(QB_RUNTIME)) {
        snprintf(p, sizeof(p), "%s/qb_runtime.sh", bak);
        if (copy_file(QB_RUNTIME, p, 0600) != 0) {
            fprintf(stderr, "update: backup of %s failed\n", QB_RUNTIME);
            rm_rf(stage);
            return 1;
        }
        have_qb = 1;
    }
    snprintf(p, sizeof(p), "%s/version", bak);
    write_file(p, NEMESIS_VERSION "\n", 0600);

    stop_daemons();
    if (remount_system(1) != 0) {
        log_msg("update: remount /system rw failed errno=%d", errno);
        fprintf(stderr, "update: /system stayed read-only; nothing was changed\n");
        rm_rf(stage);
        restart_watchdog();
        return 10;
    }
    added_ca[0] = 0;
    for (i = 0; i < N_CORE; i++) {
        snprintf(p, sizeof(p), "%s/%s", root, CORE_FILES[i].rel);
        if (!is_file(p)) continue;
        if (place_file(p, CORE_FILES[i].dst, CORE_FILES[i].mode) != 0) {
            fprintf(stderr, "update: could not replace %s\n", CORE_FILES[i].dst);
            goto fail;
        }
        log_msg("update: placed %s", CORE_FILES[i].dst);
    }
    snprintf(p, sizeof(p), "%s/core/qb_hook.sh", root);
    if (is_file(p) && qb_rewrite(p) != 0) {
        fprintf(stderr, "update: could not rewrite the QB hook\n");
        goto fail;
    }
    snprintf(p, sizeof(p), "%s/core/cacerts", root);
    if (cacerts_install(p, added_ca, sizeof(added_ca)) < 0) {
        fprintf(stderr, "update: could not add root certificates\n");
        goto fail;
    }
    sync();
    remount_system(0);
    purge_manager_dalvik();
    if (install_core_packages(root) != 0) goto fail;
    snprintf(p, sizeof(p), "%s/scripts/migrate.sh", root);
    if (is_file(p)) {
        rc = run_script(p, root, "root");
        log_msg("update: migrate.sh rc=%d", rc);
        if (rc != 0) {
            fprintf(stderr, "update: migrate.sh exited %d\n", rc);
            goto fail;
        }
    }
    snprintf(p, sizeof(p), "%s/last-result", UPDATE_DIR);
    {
        char line[96];
        snprintf(line, sizeof(line), "ok %s -> %s\n", NEMESIS_VERSION, m.pkg_ver);
        write_file(p, line, 0644);
    }
    log_msg("update: installed %s", m.pkg_ver);
    rm_rf(stage);
    printf("ok updated %s\n", m.pkg_ver);
    fflush(stdout);
    sync();
    if (reboot) {
        sleep(1);
        run_cmd("/system/bin/reboot");
    } else {
        restart_watchdog();
    }
    return 0;

fail:
    rc = restore_backup(bak, had, have_qb, added_ca);
    rm_rf(stage);
    snprintf(p, sizeof(p), "%s/last-result", UPDATE_DIR);
    write_file(p, rc == 0 ? "failed restored\n" : "failed restore-failed\n", 0644);
    log_msg("update: failed; restore %s", rc == 0 ? "ok" : "FAILED");
    printf("update failed%s\n", rc == 0 ? ", previous version restored" : ", restore failed");
    fflush(stdout);
    restart_watchdog();
    return rc == 0 ? 10 : 11;
}
