/* LivePatch: patched system files are kept in /data/nemesis/livepatch and bind-mounted over the
 * stock ones. Package syntax and rules: 09-LivePatch.md (repository root). */
#include "nemesis.h"
#include <dirent.h>
#include <fnmatch.h>
#include <signal.h>
#include <stdint.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/wait.h>
#include <time.h>
#include <zlib.h>

#define LP_DIR NEMESIS_ROOT "/livepatch"
#define LP_TABLE LP_DIR "/table"
#define LP_BOOT LP_DIR "/boot"
#define LP_SAFE LP_DIR "/safe"
#define LP_DAMAGED LP_DIR "/damaged"
#define LP_OUT LP_DIR "/out"
#define LP_STOCKB LP_DIR "/stock"
#define LP_PKG LP_DIR "/pkg"
#define LP_WORK LP_DIR "/work"
#define LP_CACHE LP_DIR "/cache"
#define LP_INDEX LP_CACHE "/index"
#define LP_TMP LP_DIR "/tmp"
#define LP_NEW LP_TMP "/new"
#define LP_ENGINE LP_DIR "/engine"
#define LP_LOWER LP_DIR "/lower"
#define LP_FARM LP_DIR "/farm"
#define LP_SWAPJOB LP_DIR "/swap.job"
#define LP_SWAP QUEUE_DIR "/swap"
#define LP_ASIDE LP_DIR "/aside"
#define WRAPPER "/system/bin/app_process"
#define WRAPPER_REAL "/system/bin/app_process.real"
#define ZYGOTE_STOCK NEMESIS_ROOT "/stock/app_process"
#define HMG_STOCK "/data/hmgapi/stock/system/bin/app_process"
#define STOCK_USAGE "Usage: app_process [java-options] cmd-dir start-class-name"
#define LINK_MAX_SIZE (8 * MB)
#define LP_MAX 128
#define CHAIN_MAX 16
#define MB (1LL << 20)
#define CACHE_CAP (100 * MB)
#define NAMES_CAP (10 * MB)
#define DATA_FLOOR (100 * MB)

/* Only the zygote start does any work; am, pm and friends run app_process too. Stock app_process
 * execs /system/bin/app_process once more with NO_ADDR_COMPAT_LAYOUT_FIXUP=1: that pass skips. */
static const char WRAPPER_TEXT[] =
    "#!/system/bin/sh\n"
    "# NEMESIS LivePatch: apply patches before the zygote starts, then run the real binary.\n"
    "case \" $* \" in\n"
    "*\" --zygote \"*) [ -z \"$NO_ADDR_COMPAT_LAYOUT_FIXUP\" ] && [ -x " BIN_PATH " ] && " BIN_PATH " boot-apply ;;\n"
    "esac\n"
    "exec " WRAPPER_REAL " \"$@\"\n";

/* ---- SHA-1 (chain keys) ---- */

typedef struct { uint32_t h[5]; uint64_t len; unsigned char buf[64]; unsigned n; } sha1_ctx;
#define ROL(x, k) (((x) << (k)) | ((x) >> (32 - (k))))

static void sha1_block(sha1_ctx *c, const unsigned char *p) {
    uint32_t w[80], a, b, d, e, f, k, t, cc;
    int i;
    for (i = 0; i < 16; i++) w[i] = (uint32_t)p[4 * i] << 24 | (uint32_t)p[4 * i + 1] << 16 | (uint32_t)p[4 * i + 2] << 8 | p[4 * i + 3];
    for (; i < 80; i++) w[i] = ROL(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
    a = c->h[0]; b = c->h[1]; cc = c->h[2]; d = c->h[3]; e = c->h[4];
    for (i = 0; i < 80; i++) {
        if (i < 20) { f = (b & cc) | (~b & d); k = 0x5A827999; }
        else if (i < 40) { f = b ^ cc ^ d; k = 0x6ED9EBA1; }
        else if (i < 60) { f = (b & cc) | (b & d) | (cc & d); k = 0x8F1BBCDC; }
        else { f = b ^ cc ^ d; k = 0xCA62C1D6; }
        t = ROL(a, 5) + f + e + k + w[i];
        e = d; d = cc; cc = ROL(b, 30); b = a; a = t;
    }
    c->h[0] += a; c->h[1] += b; c->h[2] += cc; c->h[3] += d; c->h[4] += e;
}

static void sha1_init(sha1_ctx *c) {
    static const uint32_t h0[5] = {0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0};
    memcpy(c->h, h0, sizeof(h0));
    c->len = 0;
    c->n = 0;
}

static void sha1_update(sha1_ctx *c, const void *data, size_t len) {
    const unsigned char *p = data;
    c->len += len;
    while (len--) {
        c->buf[c->n++] = *p++;
        if (c->n == 64) {
            sha1_block(c, c->buf);
            c->n = 0;
        }
    }
}

static void sha1_hex(sha1_ctx *c, char out[41]) {
    uint64_t bits = c->len * 8;
    unsigned char pad = 0x80, z = 0, lb[8];
    int i;
    sha1_update(c, &pad, 1);
    while (c->n != 56) sha1_update(c, &z, 1);
    for (i = 0; i < 8; i++) lb[i] = (unsigned char)(bits >> (56 - 8 * i));
    sha1_update(c, lb, 8);
    for (i = 0; i < 5; i++) snprintf(out + 8 * i, 9, "%08x", c->h[i]);
}

/* ---- small helpers ---- */

static int write_atomic(const char *path, const char *data) {
    char tmp[MAX_PATH];
    FILE *f;
    int bad;
    snprintf(tmp, sizeof(tmp), "%s.tmp", path);
    if (!(f = fopen(tmp, "w"))) return -1;
    bad = fputs(data, f) < 0;
    bad |= fflush(f) != 0;
    fsync(fileno(f));
    bad |= fclose(f) != 0;
    if (bad || rename(tmp, path) != 0) {
        unlink(tmp);
        return -1;
    }
    return 0;
}

static void parent_of(const char *path, char *out, size_t cap) {
    char *sl;
    snprintf(out, cap, "%s", path);
    sl = strrchr(out, '/');
    if (sl) *sl = 0;
}

static const char *base_of(const char *path) {
    const char *b = strrchr(path, '/');
    return b ? b + 1 : path;
}

static long long file_size(const char *p) {
    struct stat st;
    return stat(p, &st) == 0 ? (long long)st.st_size : 0;
}

static long long data_free(void) {
    struct statfs fs;
    if (statfs("/data", &fs) != 0) return 1LL << 40;
    return (long long)fs.f_bavail * fs.f_bsize;
}

static int safe_rel(const char *v) { return v && *v && *v != '/' && !strstr(v, ".."); }

/* Traversable by apps: the farm's symlinks into out/ and stock/ are resolved in their processes. */
static void mkdir_x(const char *path) {
    char tmp[MAX_PATH], *p;
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (p = tmp + strlen(LP_DIR); ; p++) {
        if (*p == '/' || !*p) {
            char c = *p;
            *p = 0;
            mkdir(tmp, 0711);
            chmod(tmp, 0711);
            *p = c;
            if (!c) break;
        }
    }
}

static void lp_dirs(void) {
    umask(022);
    mkdir_p(LP_DIR, 0711);
    chmod(NEMESIS_ROOT, 0755);
    chmod(LP_DIR, 0711);
    mkdir_x(LP_OUT);
    mkdir_x(LP_STOCKB);
    mkdir_p(LP_PKG, 0700);
    mkdir_p(LP_WORK, 0700);
    mkdir_p(LP_CACHE "/out", 0700);
    mkdir_p(LP_CACHE "/deodex", 0700);
    mkdir_p(LP_CACHE "/names", 0700);
    mkdir_p(LP_ENGINE, 0700);
}

static void engine_ver(char *out, size_t cap) {
    if (read_file(LP_ENGINE "/porter.out", out, cap) <= 0) snprintf(out, cap, "0");
    out[strcspn(out, "\r\n \t")] = 0;
}

/* ---- table: target output stock-md5 output-md5 ("-" stock = new file) ---- */

typedef struct { char target[MAX_PATH], output[MAX_PATH], stock[33], out_md5[33]; } lp_row;

static int lp_load(lp_row *rows, int cap) {
    static char buf[LP_MAX * 1200];
    char *p, *nl;
    int n = 0;
    if (read_file(LP_TABLE, buf, sizeof(buf)) <= 0) return 0;
    for (p = buf; *p && n < cap; p = nl ? nl + 1 : p + strlen(p)) {
        nl = strchr(p, '\n');
        if (nl) *nl = 0;
        if (sscanf(p, "%511s %511s %32s %32s", rows[n].target, rows[n].output, rows[n].stock, rows[n].out_md5) == 4) n++;
        if (!nl) break;
    }
    return n;
}

static int lp_save(const lp_row *rows, int n) {
    static char buf[LP_MAX * 1200];
    size_t o = 0;
    int i;
    buf[0] = 0;
    for (i = 0; i < n; i++)
        o += snprintf(buf + o, sizeof(buf) - o, "%s %s %s %s\n", rows[i].target, rows[i].output, rows[i].stock, rows[i].out_md5);
    return write_atomic(LP_TABLE, buf);
}

static int lp_mounted(const char *target) {
    char line[1024], mp[512];
    FILE *f = fopen("/proc/self/mounts", "r");
    int hit = 0;
    if (!f) return 0;
    while (fgets(line, sizeof(line), f))
        if (sscanf(line, "%*s %511s", mp) == 1 && !strcmp(mp, target)) hit = 1;
    fclose(f);
    return hit;
}

static int lp_bind(const char *output, const char *target) {
    if (mount(output, target, NULL, MS_BIND, NULL) != 0) return -1;
    /* /system stays read-only through the bind. */
    if (mount(NULL, target, NULL, MS_BIND | MS_REMOUNT | MS_RDONLY, NULL) != 0)
        log_msg("livepatch: ro remount %s errno=%d", target, errno);
    return 0;
}

/* Lazy, so a process that maps the file does not block it; stacked mounts all go. */
static void lp_unbind(const char *target) {
    int guard = 8;
    while (guard-- > 0 && lp_mounted(target) && umount2(target, MNT_DETACH) == 0)
        ;
}

/* The stock file under the outputs: a non-recursive bind of /system shows no mounts. */
static int lower_refs;
static int lower_on(void) {
    if (lower_refs++) return 0;
    mkdir_p(LP_LOWER, 0700);
    umount2(LP_LOWER, MNT_DETACH);
    if (mount("/system", LP_LOWER, NULL, MS_BIND, NULL) != 0) {
        lower_refs = 0;
        log_msg("livepatch: lower view errno=%d", errno);
        return -1;
    }
    /* Private, so a later umount of /system does not take this view with it. */
    mount(NULL, LP_LOWER, NULL, MS_PRIVATE, NULL);
    return 0;
}

static void lower_off(void) {
    if (lower_refs > 0 && --lower_refs == 0) umount2(LP_LOWER, MNT_DETACH);
}

static void stock_view(const char *target, char *out, size_t cap) {
    if (lower_refs && !strncmp(target, "/system/", 8)) snprintf(out, cap, "%s%s", LP_LOWER, target + 7);
    else snprintf(out, cap, "%s", target);
}

/* ---- new files: a tmpfs symlink farm over the directory ---- */

static int dir_farmed(const char *dir) {
    char s[MAX_PATH];
    snprintf(s, sizeof(s), "%s%s", LP_STOCKB, dir);
    return lp_mounted(s);
}

/* The farm resolves entries of a subdirectory through stock/, so a single bind below a farmed
 * directory is also placed on its stock/ path. */
static int under_farm(const char *target, char *spath, size_t cap) {
    char d[MAX_PATH];
    parent_of(target, d, sizeof(d));
    while (strlen(d) > 7) {
        char *sl;
        if (dir_farmed(d)) {
            snprintf(spath, cap, "%s%s", LP_STOCKB, target);
            return 1;
        }
        if (!(sl = strrchr(d, '/'))) break;
        *sl = 0;
    }
    return 0;
}

static void single_bind(const lp_row *r) {
    char sp[MAX_PATH];
    if (lp_bind(r->output, r->target) != 0) log_msg("livepatch: mount %s errno=%d", r->target, errno);
    else log_msg("livepatch: %s -> %s", r->output, r->target);
    if (under_farm(r->target, sp, sizeof(sp)) && !lp_mounted(sp)) lp_bind(r->output, sp);
}

static void single_unbind(const char *target) {
    char sp[MAX_PATH];
    lp_unbind(target);
    if (under_farm(target, sp, sizeof(sp))) lp_unbind(sp);
}

static int in_dir(const char *path, const char *dir) {
    size_t l = strlen(dir);
    return !strncmp(path, dir, l) && path[l] == '/' && !strchr(path + l + 1, '/');
}

/* ok[i] == 0 leaves row i out (boot-apply: damaged or changed). A refarm stacks a new bind. */
static int farm_dir(const char *dir, const lp_row *rows, int n, const int *ok) {
    char sdir[MAX_PATH], low[MAX_PATH], p[MAX_PATH], l[MAX_PATH];
    DIR *d;
    struct dirent *e;
    int i, j, r = -1;
    snprintf(sdir, sizeof(sdir), "%s%s", LP_STOCKB, dir);
    if (!lp_mounted(sdir)) {
        if (lower_on() != 0) return -1;
        snprintf(low, sizeof(low), "%s%s", LP_LOWER, dir + 7);
        mkdir_x(sdir);
        if (mount(low, sdir, NULL, MS_BIND, NULL) != 0) {
            log_msg("livepatch: stock bind %s errno=%d", dir, errno);
            lower_off();
            return -1;
        }
        /* Detach from lower's peer group before lower_off, or the umount clears this bind
         * and the farm is built from the empty directory underneath. */
        mount(NULL, sdir, NULL, MS_PRIVATE, NULL);
        mount(NULL, sdir, NULL, MS_BIND | MS_REMOUNT | MS_RDONLY, NULL);
        lower_off();
        for (i = 0; i < n; i++)
            if (in_dir(rows[i].target, dir)) lp_unbind(rows[i].target);
    }
    mkdir_p(LP_FARM, 0700);
    umount2(LP_FARM, MNT_DETACH);
    if (mount("tmpfs", LP_FARM, "tmpfs", MS_NOSUID | MS_NODEV, "mode=0755,uid=0,gid=0") != 0) {
        log_msg("livepatch: farm tmpfs errno=%d", errno);
        return -1;
    }
    i = 0;
    if ((d = opendir(sdir))) {
        while ((e = readdir(d))) {
            int managed = 0;
            if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
            i++;
            snprintf(p, sizeof(p), "%s/%s", dir, e->d_name);
            for (j = 0; j < n; j++)
                if (!strcmp(rows[j].target, p) && (!ok || ok[j])) managed = 1;
            snprintf(l, sizeof(l), "%s/%s", LP_FARM, e->d_name);
            snprintf(p, sizeof(p), "%s%s/%s", managed ? LP_OUT : LP_STOCKB, dir, e->d_name);
            symlink(p, l);
        }
        closedir(d);
    }
    if (i == 0) {
        log_msg("livepatch: farm %s has no stock entries, left unmounted", dir);
        umount2(LP_FARM, MNT_DETACH);
        return -1;
    }
    for (i = 0; i < n; i++) {
        if (!in_dir(rows[i].target, dir) || strcmp(rows[i].stock, "-") || (ok && !ok[i])) continue;
        snprintf(l, sizeof(l), "%s/%s", LP_FARM, base_of(rows[i].target));
        symlink(rows[i].output, l);
    }
    if (mount(LP_FARM, dir, NULL, MS_BIND, NULL) == 0) {
        mount(NULL, dir, NULL, MS_PRIVATE, NULL);
        mount(NULL, dir, NULL, MS_BIND | MS_REMOUNT | MS_RDONLY, NULL);
        log_msg("livepatch: farm over %s", dir);
        r = 0;
    } else log_msg("livepatch: farm bind %s errno=%d", dir, errno);
    umount2(LP_FARM, MNT_DETACH);
    return r;
}

static void unfarm(const char *dir) {
    char sdir[MAX_PATH];
    snprintf(sdir, sizeof(sdir), "%s%s", LP_STOCKB, dir);
    lp_unbind(dir);
    lp_unbind(sdir);
    log_msg("livepatch: farm over %s removed", dir);
}

static void lp_unmount_all(void) {
    static lp_row rows[LP_MAX];
    char d[MAX_PATH];
    int i, n = lp_load(rows, LP_MAX);
    for (i = 0; i < n; i++) single_unbind(rows[i].target);
    for (i = 0; i < n; i++) {
        parent_of(rows[i].target, d, sizeof(d));
        if (dir_farmed(d)) unfarm(d);
    }
}

/* ---- boot ---- */

static void lp_boot_write(const char *bid, int tries, int zy) {
    char line[160];
    snprintf(line, sizeof(line), "%s %d %d\n", bid, tries, zy);
    write_atomic(LP_BOOT, line);
}

/* Watchdog calls this once the framework is up: the boot made it. */
void lp_boot_ok(void) {
    char buf[200], bid[64];
    int tries = 0, zy = 0;
    if (read_file(LP_BOOT, buf, sizeof(buf)) <= 0) return;
    if (sscanf(buf, "%63s %d %d", bid, &tries, &zy) != 3 || tries == 0) return;
    lp_boot_write(bid, 0, zy);
}

/* ---- package entries: pkg/<id>/entries, the chain of each target ---- */

/* kind: 0 [file.N] source, 1 engine=exec tool, 2 engine=dex pack. Paths are under dir. */
typedef struct {
    int kind, seq;
    char id[MAX_ID], ver[32], name[MAX_NAME], dir[MAX_PATH];
    char path[MAX_PATH], args[256], app[64], stop[256], restart[128];
} lp_step;

static int opt_set(const ini_file *ini, const char *sec, const char *key) {
    const char *v = ini_get(ini, sec, key);
    return v && !strcmp(v, "1");
}

static void pkg_label(const char *id, char *out, size_t cap) {
    pkg_rec p;
    char e[MAX_PATH];
    ini_file ini;
    const char *n = NULL;
    snprintf(e, sizeof(e), "%s/%s/entries", LP_PKG, id);
    if (ini_load(&ini, e) == 0) {
        n = ini_get(&ini, "livepatch", "name");
        if (n && *n) {
            snprintf(out, cap, "%s", n);
            ini_free(&ini);
            return;
        }
        ini_free(&ini);
    }
    if (pkg_find(id, &p) == 0 && p.name[0]) snprintf(out, cap, "%s", p.name);
    else snprintf(out, cap, "%s", id);
}

/* The [file.N] or [patch.N] of the package in dir that names this target. */
static int step_in(const char *dir, const char *target, lp_step *s) {
    ini_file ini;
    char meta[MAX_PATH], sec[24];
    int k, f, rc = -1;
    snprintf(meta, sizeof(meta), "%s/entries", dir);
    if (ini_load(&ini, meta) != 0) return -1;
    for (k = 1; k < 100 && rc != 0; k++)
        for (f = 0; f < 2 && rc != 0; f++) {
            const char *t, *rel, *v;
            snprintf(sec, sizeof(sec), "%s.%d", f ? "patch" : "file", k);
            t = ini_get(&ini, sec, "target");
            if (!t || fnmatch(t, target, FNM_PATHNAME)) continue;
            memset(s, 0, sizeof(*s));
            if (!f) rel = ini_get(&ini, sec, "source");
            else {
                const char *eng = ini_get(&ini, sec, "engine");
                if (eng && !strcmp(eng, "exec")) {
                    s->kind = 1;
                    rel = ini_get(&ini, sec, "tool");
                } else if (eng && !strcmp(eng, "dex")) {
                    const char *app = ini_get(&ini, sec, "app");
                    s->kind = 2;
                    rel = ini_get(&ini, sec, "pack");
                    if (!app || strspn(app, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-") != strlen(app)) continue;
                    snprintf(s->app, sizeof(s->app), "%s", app);
                } else continue;
                v = ini_get(&ini, sec, "args");
                snprintf(s->args, sizeof(s->args), "%s", v ? v : "");
            }
            if (!safe_rel(rel)) continue;
            v = ini_get(&ini, sec, "stop");
            snprintf(s->stop, sizeof(s->stop), "%s", v ? v : "");
            v = ini_get(&ini, sec, "restart");
            snprintf(s->restart, sizeof(s->restart), "%s", v ? v : "");
            snprintf(s->path, sizeof(s->path), "%s/%s", dir, rel);
            snprintf(s->dir, sizeof(s->dir), "%s", dir);
            v = ini_get(&ini, "livepatch", "id");
            snprintf(s->id, sizeof(s->id), "%s", v ? v : base_of(dir));
            v = ini_get(&ini, "livepatch", "version");
            snprintf(s->ver, sizeof(s->ver), "%s", v ? v : "");
            v = ini_get(&ini, "livepatch", "name");
            snprintf(s->name, sizeof(s->name), "%s", v && *v ? v : s->id);
            v = ini_get(&ini, "livepatch", "seq");
            s->seq = v ? atoi(v) : 0;
            rc = 0;
        }
    ini_free(&ini);
    return rc;
}

/* Registered packages (pkg/<id>) that change target, by seq. skip leaves one out; extra (a staged
 * pkg dir) is added; all=0 also leaves out packages being uninstalled (pkg/<id>/removing). */
static int chain_for(const char *target, const char *skip, const char *extra, int all, lp_step *ch, int cap) {
    DIR *d = opendir(LP_PKG);
    struct dirent *e;
    char dir[MAX_PATH], rm[MAX_PATH];
    int n = 0, i, j;
    while (d && (e = readdir(d)) && n < cap) {
        if (e->d_name[0] == '.' || (skip && !strcmp(e->d_name, skip))) continue;
        snprintf(dir, sizeof(dir), "%s/%s", LP_PKG, e->d_name);
        snprintf(rm, sizeof(rm), "%s/removing", dir);
        if (!all && path_exists(rm)) continue;
        if (step_in(dir, target, &ch[n]) == 0) n++;
    }
    if (d) closedir(d);
    if (extra && n < cap && step_in(extra, target, &ch[n]) == 0) n++;
    for (i = 1; i < n; i++)
        for (j = i; j > 0 && ch[j - 1].seq > ch[j].seq; j--) {
            lp_step t = ch[j];
            ch[j] = ch[j - 1];
            ch[j - 1] = t;
        }
    return n;
}

/* <stock md5>.<SHA-1 of the ordered id@version list + engine version>, "-" for an empty chain. */
static void chain_key(const lp_step *ch, int n, const char *smd5, char *key, size_t cap, char *over, size_t ocap) {
    sha1_ctx c;
    char hex[41], ev[32], m[33], line[MAX_ID + 120];
    int i, dex = 0;
    if (!n) {
        snprintf(key, cap, "-");
        snprintf(over, ocap, "-");
        return;
    }
    engine_ver(ev, sizeof(ev));
    sha1_init(&c);
    for (i = 0; i < n; i++) {
        if (ch[i].kind == 2) {
            dex = 1;
            snprintf(line, sizeof(line), "%s@%s porter %s\n", ch[i].id, ch[i].ver, ev);
        } else {
            md5_file_hex(ch[i].path, m);
            snprintf(line, sizeof(line), "%s@%s %s %s\n", ch[i].id, ch[i].ver, ch[i].kind ? "tool" : "file", m);
        }
        sha1_update(&c, line, strlen(line));
    }
    sha1_hex(&c, hex);
    snprintf(key, cap, "%s.%s", smd5, hex);
    snprintf(over, ocap, "%s", dex ? ev : "-");
}

/* ---- pkg/<id>/status: target \t state \t detail ---- */

static void status_set(const char *dir, const char *target, const char *state, const char *detail) {
    static char buf[LP_MAX * 900], out[LP_MAX * 900];
    char path[MAX_PATH], *p, *nl, keep[400] = "";
    size_t o = 0, tl = strlen(target);
    snprintf(path, sizeof(path), "%s/status", dir);
    if (!is_dir(dir)) return;
    out[0] = 0;
    if (read_file(path, buf, sizeof(buf)) > 0)
        for (p = buf; *p; p = nl ? nl + 1 : p + strlen(p)) {
            nl = strchr(p, '\n');
            if (nl) *nl = 0;
            if (!strncmp(p, target, tl) && p[tl] == '\t') {
                char *d = strchr(p + tl + 1, '\t');
                if (d) snprintf(keep, sizeof(keep), "%s", d + 1);
            } else if (*p) o += snprintf(out + o, sizeof(out) - o, "%s\n", p);
            if (!nl) break;
        }
    if (state) o += snprintf(out + o, sizeof(out) - o, "%s\t%s\t%s\n", target, state, detail ? detail : keep);
    write_atomic(path, out);
}

/* Every pkg/<id>/status line in state from becomes state to (to NULL: dropped). */
static void status_all(const char *from, const char *to) {
    DIR *d = opendir(LP_PKG);
    struct dirent *e;
    static char buf[LP_MAX * 900], out[LP_MAX * 900];
    while (d && (e = readdir(d))) {
        char path[MAX_PATH], *p, *nl;
        size_t o = 0;
        int ch = 0;
        if (e->d_name[0] == '.') continue;
        snprintf(path, sizeof(path), "%s/%s/status", LP_PKG, e->d_name);
        if (read_file(path, buf, sizeof(buf)) <= 0) continue;
        out[0] = 0;
        for (p = buf; *p; p = nl ? nl + 1 : p + strlen(p)) {
            char *t1, *t2;
            nl = strchr(p, '\n');
            if (nl) *nl = 0;
            t1 = strchr(p, '\t');
            t2 = t1 ? strchr(t1 + 1, '\t') : NULL;
            if (t1 && t2 && (size_t)(t2 - t1 - 1) == strlen(from) && !strncmp(t1 + 1, from, strlen(from))) {
                ch = 1;
                if (to) o += snprintf(out + o, sizeof(out) - o, "%.*s\t%s\t%s\n", (int)(t1 - p), p, to, t2 + 1);
            } else if (*p) o += snprintf(out + o, sizeof(out) - o, "%s\n", p);
            if (!nl) break;
        }
        if (ch) write_atomic(path, out);
    }
    if (d) closedir(d);
}

/* ---- damaged outputs ---- */

static void lp_damaged_add(const char *id) {
    char buf[2048] = "", *p;
    size_t l = strlen(id);
    int n = read_file(LP_DAMAGED, buf, sizeof(buf) - MAX_ID - 2);
    if (n < 0) buf[0] = 0;
    for (p = buf; (p = strstr(p, id)); p += l)
        if ((p == buf || p[-1] == '\n') && (p[l] == '\n' || !p[l])) return;
    snprintf(buf + strlen(buf), sizeof(buf) - strlen(buf), "%s\n", id);
    if (write_atomic(LP_DAMAGED, buf) != 0) log_msg("livepatch: cannot record %s as damaged: %s", id, strerror(errno));
}

/* queue_scan_boot: the damaged ids, one per line. lp_damaged_clear drops the note once every job is saved. */
int lp_damaged_take(char *buf, size_t cap) {
    int n = read_file(LP_DAMAGED, buf, cap);
    return n > 0 ? n : 0;
}

void lp_damaged_clear(void) { unlink(LP_DAMAGED); }

/* ---- cache: cache/index "kind name bytes last-hit over" ---- */

typedef struct { char kind[8], name[160], ver[16]; long long bytes; long hit; } cent;
#define CMAX 512
static cent cidx[CMAX];
static int cn;

static void cpath(const char *kind, const char *name, char *out, size_t cap) {
    snprintf(out, cap, "%s/%s/%s", LP_CACHE, kind, name);
}

static void idx_load(void) {
    static char buf[CMAX * 220];
    char *p, *nl, path[MAX_PATH];
    cn = 0;
    if (read_file(LP_INDEX, buf, sizeof(buf)) <= 0) return;
    for (p = buf; *p && cn < CMAX; p = nl ? nl + 1 : p + strlen(p)) {
        cent *c = &cidx[cn];
        nl = strchr(p, '\n');
        if (nl) *nl = 0;
        if (sscanf(p, "%7s %159s %lld %ld %15s", c->kind, c->name, &c->bytes, &c->hit, c->ver) == 5) {
            cpath(c->kind, c->name, path, sizeof(path));
            if (is_file(path)) cn++; /* an index line without its file is dropped */
        }
        if (!nl) break;
    }
}

static void idx_save(void) {
    static char buf[CMAX * 220];
    size_t o = 0;
    int i;
    buf[0] = 0;
    for (i = 0; i < cn; i++)
        o += snprintf(buf + o, sizeof(buf) - o, "%s %s %lld %ld %s\n", cidx[i].kind, cidx[i].name, cidx[i].bytes,
                      cidx[i].hit, cidx[i].ver);
    write_atomic(LP_INDEX, buf);
}

static int idx_find(const char *kind, const char *name) {
    int i;
    for (i = 0; i < cn; i++)
        if (!strcmp(cidx[i].kind, kind) && !strcmp(cidx[i].name, name)) return i;
    return -1;
}

static void idx_drop(int i) {
    char path[MAX_PATH];
    cpath(cidx[i].kind, cidx[i].name, path, sizeof(path));
    unlink(path);
    cidx[i] = cidx[--cn];
}

/* Files without an index line are orphans (a write cut before its index line). */
static void cache_sweep(void) {
    static const char *const kinds[] = {"out", "deodex", "names"};
    int k;
    idx_load();
    for (k = 0; k < 3; k++) {
        char dir[MAX_PATH], p[MAX_PATH];
        DIR *d;
        struct dirent *e;
        snprintf(dir, sizeof(dir), "%s/%s", LP_CACHE, kinds[k]);
        if (!(d = opendir(dir))) continue;
        while ((e = readdir(d))) {
            if (e->d_name[0] == '.' || idx_find(kinds[k], e->d_name) >= 0) continue;
            snprintf(p, sizeof(p), "%s/%s", dir, e->d_name);
            unlink(p);
        }
        closedir(d);
    }
    idx_save();
}

/* Names have their own 10 MB; the rest share the remaining 90 MB: dead outputs first, then LRU.
 * shortcut: a cache/out entry hardlinked to a live output counts its bytes twice; fine at these sizes. */
static void cache_evict(void) {
    char ev[32];
    long long names = 0, rest = 0;
    int i, old;
    engine_ver(ev, sizeof(ev));
    for (i = cn - 1; i >= 0; i--)
        if (!strcmp(cidx[i].kind, "out") && strcmp(cidx[i].ver, "-") && strcmp(cidx[i].ver, ev)) idx_drop(i);
    for (;;) {
        names = rest = 0;
        for (i = 0; i < cn; i++) *(strcmp(cidx[i].kind, "names") ? &rest : &names) += cidx[i].bytes;
        if (names > NAMES_CAP) {
            old = -1;
            for (i = 0; i < cn; i++)
                if (!strcmp(cidx[i].kind, "names") && (old < 0 || cidx[i].hit < cidx[old].hit)) old = i;
            idx_drop(old);
            continue;
        }
        if (rest > CACHE_CAP - NAMES_CAP) {
            old = -1;
            for (i = 0; i < cn; i++)
                if (strcmp(cidx[i].kind, "names") && (old < 0 || cidx[i].hit < cidx[old].hit)) old = i;
            idx_drop(old);
            continue;
        }
        break;
    }
}

/* src -> cache/<kind>/<name> by rename (move=1) or hardlink, then the index line. */
static void cache_add(const char *kind, const char *src, const char *name, const char *ver, int move) {
    char dst[MAX_PATH];
    long long sz = file_size(src);
    int i;
    if (!name[0] || !strcmp(name, "-") || strchr(name, '/')) {
        if (move) unlink(src);
        return;
    }
    if (data_free() - sz < DATA_FLOOR) {
        log_msg("livepatch: cache write skipped (/data low)");
        if (move) unlink(src);
        return;
    }
    cpath(kind, name, dst, sizeof(dst));
    idx_load();
    if ((i = idx_find(kind, name)) >= 0) cidx[i] = cidx[--cn];
    unlink(dst);
    if ((move ? rename(src, dst) : link(src, dst)) != 0) {
        if (move) unlink(src);
        return;
    }
    if (cn < CMAX) {
        cent *c = &cidx[cn++];
        snprintf(c->kind, sizeof(c->kind), "%s", kind);
        snprintf(c->name, sizeof(c->name), "%s", name);
        snprintf(c->ver, sizeof(c->ver), "%s", ver && *ver ? ver : "-");
        c->bytes = sz;
        c->hit = (long)time(NULL);
    }
    cache_evict();
    idx_save();
}

static int cache_touch(const char *kind, const char *name) {
    int i;
    idx_load();
    if ((i = idx_find(kind, name)) < 0) return 0;
    cidx[i].hit = (long)time(NULL);
    idx_save();
    return 1;
}

static void cache_forget(const char *kind, const char *name) {
    int i;
    idx_load();
    if ((i = idx_find(kind, name)) >= 0) {
        idx_drop(i);
        idx_save();
    }
}

/* ---- progress + status line ---- */

static int ui_pct = -1;

static void ui_bar(double pct) {
    char line[32];
    int p = (int)pct;
    if (p > 100) p = 100;
    if (p <= ui_pct) return;
    ui_pct = p;
    snprintf(line, sizeof(line), "0 100 %d\n", p);
    write_file(UI_PROGRESS, line, 0644);
}

/* pre + name + post in at most 40 bytes; the name is cut, not the verb. */
static void ui_text(const char *pre, const char *name, const char *post) {
    char line[128], nm[96];
    int room = 40 - (int)strlen(pre) - (int)strlen(post);
    if (room < 4) room = 4;
    if ((int)strlen(name) > room) snprintf(nm, sizeof(nm), "%.*s\xe2\x80\xa6", room - 1, name);
    else snprintf(nm, sizeof(nm), "%s", name);
    snprintf(line, sizeof(line), "%s%s%s\n", pre, nm, post);
    write_file(UI_STATUS, line, 0644);
}

static int ui_fixed; /* uninstall re-port: the row keeps "Re-porting <app>", the steps only move the bar */

static void ui_app(const char *app, const char *verb) {
    char post[48];
    if (ui_fixed) return;
    snprintf(post, sizeof(post), ": %s", verb);
    ui_text("", app, post);
}

/* ---- swap: binding a new output over a live target ---- */

/* 1 when a process of package pkg runs (cmdline is pkg, or pkg:sub); sig != 0 also sends it. */
static int proc_pkg(const char *pkg, int sig) {
    DIR *d = opendir("/proc");
    struct dirent *e;
    size_t l = strlen(pkg);
    int hit = 0;
    while (d && (e = readdir(d))) {
        char cp[64], cl[256];
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        snprintf(cp, sizeof(cp), "/proc/%s/cmdline", e->d_name);
        if (read_file(cp, cl, sizeof(cl)) <= 0) continue;
        if (strncmp(cl, pkg, l) || (cl[l] && cl[l] != ':')) continue;
        hit = 1;
        if (!sig) break;
        kill(atoi(e->d_name), sig);
    }
    if (d) closedir(d);
    return hit;
}

/* Processes whose maps hold target (or its out/ path, through a farm symlink): bit 1 an app,
 * bit 2 a native daemon (cmdline starts with '/', system_server, zygote). sig kills the apps. */
static int maps_users(const char *target, int sig) {
    DIR *d = opendir("/proc");
    struct dirent *e;
    char outp[MAX_PATH];
    int r = 0, self = getpid();
    snprintf(outp, sizeof(outp), "%s%s", LP_OUT, target);
    while (d && (e = readdir(d))) {
        char p[64], cl[256] = "", line[1024];
        FILE *f;
        int pid, hit = 0;
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        pid = atoi(e->d_name);
        if (pid == self) continue;
        snprintf(p, sizeof(p), "/proc/%d/maps", pid);
        if (!(f = fopen(p, "r"))) continue;
        while (!hit && fgets(line, sizeof(line), f)) {
            char *s = strchr(line, '/');
            if (!s) continue;
            s[strcspn(s, "\n")] = 0;
            if (!strncmp(s, target, strlen(target)) || !strncmp(s, outp, strlen(outp))) {
                size_t l = !strncmp(s, target, strlen(target)) ? strlen(target) : strlen(outp);
                if (!s[l] || s[l] == ' ') hit = 1;
            }
        }
        fclose(f);
        if (!hit) continue;
        snprintf(p, sizeof(p), "/proc/%d/cmdline", pid);
        read_file(p, cl, sizeof(cl));
        if (cl[0] == '/' || !strcmp(cl, "system_server") || !strncmp(cl, "zygote", 6)) r |= 2;
        else {
            r |= 1;
            if (sig) kill(pid, sig);
        }
    }
    if (d) closedir(d);
    return r;
}

/* am force-stop for each package name in a "a,b c" list; skip what is in done.
 * force-stop spares persistent apps, so what still runs is killed (AMS restarts persistent ones). */
static void force_stop_list(const char *stops, char *done, size_t cap) {
    char list[1024], *t, *sv = NULL, cmd[200], key[100];
    snprintf(list, sizeof(list), "%s", stops);
    for (t = strtok_r(list, ", ", &sv); t; t = strtok_r(NULL, ", ", &sv)) {
        if (strspn(t, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._") != strlen(t) || strlen(t) > 90) continue;
        snprintf(key, sizeof(key), " %s ", t);
        if (strstr(done, key)) continue;
        if (strlen(done) + strlen(key) < cap) strcat(done, key + (done[0] ? 1 : 0));
        snprintf(cmd, sizeof(cmd), "am force-stop %s >/dev/null 2>&1", t);
        run_cmd(cmd);
        proc_pkg(t, SIGKILL);
    }
}

static void restart_list(const char *svcs) {
    char list[256], *t, *sv = NULL, cmd[200];
    snprintf(list, sizeof(list), "%s", svcs);
    for (t = strtok_r(list, ", ", &sv); t; t = strtok_r(NULL, ", ", &sv)) {
        if (strspn(t, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-") != strlen(t)) continue;
        snprintf(cmd, sizeof(cmd), "stop %s; start %s", t, t);
        run_cmd(cmd);
    }
}

/* The mounts of target's directory made to match the table. */
static int swap_one(const char *target) {
    static lp_row rows[LP_MAX];
    char dir[MAX_PATH];
    int i, n = lp_load(rows, LP_MAX), news = 0, r = 0;
    parent_of(target, dir, sizeof(dir));
    for (i = 0; i < n; i++)
        if (in_dir(rows[i].target, dir) && !strcmp(rows[i].stock, "-")) news = 1;
    if (news) return farm_dir(dir, rows, n, NULL);
    if (dir_farmed(dir)) {
        unfarm(dir);
        for (i = 0; i < n; i++)
            if (in_dir(rows[i].target, dir)) single_bind(&rows[i]);
        return 0;
    }
    single_unbind(target);
    for (i = 0; i < n; i++)
        if (!strcmp(rows[i].target, target)) {
            single_bind(&rows[i]);
            if (!lp_mounted(target)) r = -1;
        }
    return r;
}

static void after_swap(const char *target, const char *stops, const char *restart, char *done, size_t cap) {
    if (!framework_up()) return;
    if (stops[0]) force_stop_list(stops, done, cap);
    maps_users(target, SIGKILL);
    if (restart[0]) restart_list(restart);
}

/* Inside a queue job with Android up, the apps would need a restart and some (EPP) only pick the
 * change up after a full reboot: ask for one; boot-apply mounts the new outputs. Otherwise (CLI,
 * Android down) at once. */
static void lp_swap_add(const char *target, const char *stops, const char *restart) {
    char done[1024] = "";
    if (getenv("NEMESIS_JOB") && framework_up()) {
        log_msg("livepatch: %s changed; reboot to apply", target);
        cmd_reboot(0, NULL);
        return;
    }
    swap_one(target);
    after_swap(target, stops, restart, done, sizeof(done));
    status_all("pending restart", "ok");
}

/* After postinstall (or unregister): the swap lines of the job's commit are queued. */
void lp_swap_flush(void) {
    static char buf[LP_MAX * 900];
    char *p, *nl;
    if (read_file(LP_SWAPJOB, buf, sizeof(buf)) <= 0) return;
    for (p = buf; *p; p = nl ? nl + 1 : p + strlen(p)) {
        char *a, *b;
        nl = strchr(p, '\n');
        if (nl) *nl = 0;
        a = strchr(p, '\t');
        b = a ? strchr(a + 1, '\t') : NULL;
        if (a) *a++ = 0;
        if (b) *b++ = 0;
        if (*p) lp_swap_add(p, a ? a : "", b ? b : "");
        if (!nl) break;
    }
    unlink(LP_SWAPJOB);
}

/* ---- boot-apply ---- */

static void unbind_lines(const char *file) {
    static char buf[LP_MAX * 900];
    char *p, *nl, d[MAX_PATH];
    if (read_file(file, buf, sizeof(buf)) <= 0) return;
    for (p = buf; *p; p = nl ? nl + 1 : p + strlen(p)) {
        nl = strchr(p, '\n');
        if (nl) *nl = 0;
        p[strcspn(p, "\t")] = 0;
        if (*p) {
            single_unbind(p);
            parent_of(p, d, sizeof(d));
            if (dir_farmed(d)) unfarm(d);
        }
        if (!nl) break;
    }
}

/* The file under a row is still the one its output was built from, and the output is intact.
 * Needs lower_on() for /system targets. */
static int row_ok(const lp_row *r) {
    char md5[33], sv[MAX_PATH];
    stock_view(r->target, sv, sizeof(sv));
    if (!strcmp(r->stock, "-") ? path_exists(sv) : (md5_file_hex(sv, md5), strcmp(md5, r->stock) != 0)) {
        log_msg("livepatch: skip %s (target changed)", r->target);
        return 0;
    }
    md5_file_hex(r->output, md5);
    if (strcmp(md5, r->out_md5)) {
        lp_step ch[CHAIN_MAX];
        int j, c = chain_for(r->target, NULL, NULL, 1, ch, CHAIN_MAX);
        log_msg("livepatch: %s damaged", r->output);
        for (j = 0; j < c; j++) status_set(ch[j].dir, r->target, "damaged", NULL);
        if (c) lp_damaged_add(ch[c - 1].id);
        return 0;
    }
    return 1;
}

/* ---- step aside: farms are read-only, so a writer of /system gets the real directories ---- */

static char aside_buf[LP_MAX * (MAX_PATH + 1)];

static int aside_load(void) { return read_file(LP_ASIDE, aside_buf, sizeof(aside_buf)) > 0; }

static int line_in(const char *buf, const char *line) {
    size_t l = strlen(line);
    const char *p;
    for (p = buf; (p = strstr(p, line)); p += l)
        if ((p == buf || p[-1] == '\n') && p[l] == '\n') return 1;
    return 0;
}

static int aside_has(const char *dir) { return aside_load() && line_in(aside_buf, dir); }

/* Takes every farm down and lists its dir in LP_ASIDE; nested calls add to the list. */
void lp_step_aside(const char *why) {
    static lp_row rows[LP_MAX];
    char d[MAX_PATH];
    int i, n = lp_load(rows, LP_MAX), added = 0;
    if (!aside_load()) aside_buf[0] = 0;
    for (i = 0; i < n; i++) {
        size_t o = strlen(aside_buf);
        parent_of(rows[i].target, d, sizeof(d));
        if (!dir_farmed(d)) continue;
        unfarm(d);
        if (!line_in(aside_buf, d) && o + strlen(d) + 2 < sizeof(aside_buf)) {
            snprintf(aside_buf + o, sizeof(aside_buf) - o, "%s\n", d);
            added++;
        }
    }
    if (added) {
        write_atomic(LP_ASIDE, aside_buf);
        log_msg("livepatch: farms stepped aside for %s", why);
    }
}

/* Rebuilds the farms lp_step_aside took down, from the real directories as the writer left them. */
void lp_step_back(const char *why) {
    static lp_row rows[LP_MAX];
    static int ok[LP_MAX];
    char d[MAX_PATH], sp[MAX_PATH];
    int i, n;
    if (!aside_load()) return;
    n = lp_load(rows, LP_MAX);
    lower_on();
    for (i = 0; i < n; i++) {
        parent_of(rows[i].target, d, sizeof(d));
        ok[i] = line_in(aside_buf, d) && row_ok(&rows[i]);
    }
    lower_off();
    for (i = 0; i < n; i++) {
        parent_of(rows[i].target, d, sizeof(d));
        if (line_in(aside_buf, d) && !dir_farmed(d) && farm_dir(d, rows, n, ok) != 0)
            log_msg("livepatch: farm over %s not rebuilt; the next boot tries again", d);
    }
    /* binds below a farmed dir also go on its stock/ path, which the unfarm took with it */
    for (i = 0; i < n; i++)
        if (lp_mounted(rows[i].target) && under_farm(rows[i].target, sp, sizeof(sp)) && !lp_mounted(sp))
            lp_bind(rows[i].output, sp);
    unlink(LP_ASIDE);
    log_msg("livepatch: farms back after %s", why);
}

/* Runs as root from the app_process wrapper, before the zygote. Never fails the boot. */
static int lp_boot_apply(void) {
    static lp_row rows[LP_MAX];
    static int ok[LP_MAX];
    char buf[200], bid[64] = "", old[64] = "", d[MAX_PATH];
    int tries = 0, zy = 0, n, i, safe = 0;
    lp_dirs();
    if (read_file("/proc/sys/kernel/random/boot_id", bid, sizeof(bid)) <= 0) return 0;
    bid[strcspn(bid, "\n")] = 0;
    if (read_file(LP_BOOT, buf, sizeof(buf)) > 0 && sscanf(buf, "%63s %d %d", old, &tries, &zy) != 3) {
        old[0] = 0;
        tries = zy = 0;
    }
    if (!strcmp(old, bid)) zy++;
    else {
        tries++;
        zy = 1;
    }
    lp_boot_write(bid, tries, zy);
    if (path_exists(LP_SAFE)) safe = 1;
    else if (tries > 2) safe = 2;
    else if (zy > 3) safe = 3;
    if (safe) {
        log_msg("livepatch: safe mode (%s), tries=%d zygote=%d",
                safe == 1 ? "safe file" : safe == 2 ? "boot loop" : "framework restarts", tries, zy);
        lp_unmount_all();
        return 0;
    }
    lp_props_boot();
    /* A swap the user postponed, or a zygote restart before it ran: the table has the new outputs. */
    unbind_lines(LP_SWAP);
    unbind_lines(LP_SWAPJOB);
    unlink(LP_SWAP);
    unlink(LP_SWAPJOB);
    status_all("pending restart", "ok");
    /* Farms stepped aside last boot are rebuilt below like the rest; this boot's writer keeps its dirs. */
    if (zy == 1) unlink(LP_ASIDE);
    n = lp_load(rows, LP_MAX);
    lower_on();
    for (i = 0; i < n; i++) {
        ok[i] = 0;
        parent_of(rows[i].target, d, sizeof(d));
        if (lp_mounted(rows[i].target) || dir_farmed(d)) continue; /* zygote restart: already applied */
        if (aside_has(d)) continue;
        ok[i] = row_ok(&rows[i]);
    }
    lower_off();
    /* farms first (a dir with a new file), then single binds */
    for (i = 0; i < n; i++) {
        if (!ok[i] || strcmp(rows[i].stock, "-")) continue;
        parent_of(rows[i].target, d, sizeof(d));
        if (!dir_farmed(d)) farm_dir(d, rows, n, ok);
    }
    for (i = 0; i < n; i++) {
        if (!ok[i]) continue;
        parent_of(rows[i].target, d, sizeof(d));
        if (!dir_farmed(d)) single_bind(&rows[i]);
    }
    return 0;
}

/* ---- wrapper ---- */

/* The 1.17 header too, so an older wrapper text is recognised and rewritten. */
static int is_script(const char *path) {
    char buf[sizeof(WRAPPER_TEXT) + 8];
    int n = read_file(path, buf, sizeof(buf));
    return n > 0 && !strncmp(buf, "#!/system/bin/sh\n# NEMESIS LivePatch", 36);
}

/* The zygote chain: app_process -> (other tools' wrappers, HMG-API, Leafia, ...) -> stock. A link
 * names the next one as an absolute /system/bin/app_process<suffix> path inside its bytes; stock
 * names only /system/bin/app_process (its own re-exec). No tool's names are known here. */
enum { CH_OK, CH_DEAD, CH_LOOP };
#define LINK_REFS 4

static int is_elf(const char *path) {
    char m[5];
    return read_file(path, m, sizeof(m)) == 4 && !memcmp(m, "\177ELF", 4);
}

static int is_link_char(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
}

/* The app_process<suffix> paths a link names (itself left out); usage=1 when it has stock's usage
 * text. -1 when unreadable. */
static int link_refs(const char *path, char (*refs)[MAX_PATH], int cap, int *usage) {
    static const char pre[] = WRAPPER;
    struct stat st;
    FILE *f;
    char *b;
    size_t len, i, j, pl = sizeof(pre) - 1, ul = sizeof(STOCK_USAGE) - 1;
    int n = 0, k, dup;
    *usage = 0;
    if (stat(path, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 || st.st_size > LINK_MAX_SIZE) return -1;
    if (!(b = malloc((size_t)st.st_size))) return -1;
    if (!(f = fopen(path, "rb"))) {
        free(b);
        return -1;
    }
    len = fread(b, 1, (size_t)st.st_size, f);
    fclose(f);
    for (i = 0; i < len; i++) {
        if (b[i] == 'U' && i + ul <= len && !memcmp(b + i, STOCK_USAGE, ul)) *usage = 1;
        if (b[i] != '/' || i + pl > len || memcmp(b + i, pre, pl)) continue;
        for (j = i + pl; j < len && is_link_char(b[j]) && j - i < MAX_PATH - 1; j++)
            ;
        if (j == i + pl) continue; /* the head itself */
        if (n >= cap) continue;
        memcpy(refs[n], b + i, j - i);
        refs[n][j - i] = 0;
        for (dup = 0, k = 0; k < n; k++) dup |= !strcmp(refs[k], refs[n]);
        if (!dup && strcmp(refs[n], path)) n++;
    }
    free(b);
    return n;
}

static int is_stock(const char *path) {
    char refs[LINK_REFS][MAX_PATH];
    int usage;
    return is_elf(path) && link_refs(path, refs, LINK_REFS, &usage) == 0 && usage;
}

/* CH_OK when the chain from path ends in a binary that names no further link (term gets it);
 * CH_DEAD when it ends in a missing file; CH_LOOP when it reaches NEMESIS's wrapper again. */
static int chain_walk(const char *path, int depth, char *term, size_t tcap) {
    char refs[LINK_REFS][MAX_PATH];
    int n, i, r, worst = CH_DEAD, usage, any = 0;
    if (!is_file(path)) return CH_DEAD;
    if ((depth > 0 && is_script(path)) || depth > 8) return CH_LOOP;
    if ((n = link_refs(path, refs, LINK_REFS, &usage)) < 0) return CH_DEAD;
    if (n == 0) {
        /* shortcut: a script naming no app_process path can't be judged and is trusted, walk what it execs if such a wrapper turns up */
        if (!is_elf(path)) return CH_OK;
        snprintf(term, tcap, "%s", path);
        return CH_OK;
    }
    /* A wrapper may name fallbacks it doesn't need: one live next link is enough. */
    for (i = 0; i < n; i++) {
        if (!is_file(refs[i])) continue;
        any = 1;
        if ((r = chain_walk(refs[i], depth + 1, term, tcap)) == CH_OK) return CH_OK;
        if (r == CH_LOOP) worst = CH_LOOP;
    }
    return any ? worst : CH_DEAD;
}

/* Copy, md5 check, rename: dst is never half-written. */
static int put_file(const char *src, const char *dst, mode_t mode, uid_t uid, gid_t gid) {
    char tmp[MAX_PATH], a[33], b[33];
    snprintf(tmp, sizeof(tmp), "%s.nemesis-new", dst);
    if (copy_file(src, tmp, mode) != 0 || chown(tmp, uid, gid) != 0) goto fail;
    md5_file_hex(src, a);
    md5_file_hex(tmp, b);
    if (strcmp(a, b) || rename(tmp, dst) != 0) goto fail;
    return 0;
fail:
    log_msg("livepatch: copy %s -> %s failed errno=%d", src, dst, errno);
    unlink(tmp);
    return -1;
}

/* NEMESIS's own stock copy first (the last one seen live), then HMG-API's backup, then any stock
 * binary left in /system/bin under another app_process name. */
static int stock_find(char *out, size_t cap) {
    static const char *fixed[] = { ZYGOTE_STOCK, HMG_STOCK };
    DIR *d;
    struct dirent *e;
    unsigned i;
    for (i = 0; i < sizeof(fixed) / sizeof(fixed[0]); i++)
        if (is_stock(fixed[i])) {
            snprintf(out, cap, "%s", fixed[i]);
            return 0;
        }
    if (!(d = opendir("/system/bin"))) return -1;
    while ((e = readdir(d))) {
        if (strncmp(e->d_name, "app_process.", 12) || strstr(e->d_name, ".nemesis-new")) continue;
        snprintf(out, cap, "/system/bin/%s", e->d_name);
        if (is_stock(out)) {
            closedir(d);
            return 0;
        }
    }
    closedir(d);
    return -1;
}

static void stock_keep(const char *term) {
    char a[33], b[33];
    if (!term[0] || !is_stock(term)) return;
    md5_file_hex(term, a);
    if (is_file(ZYGOTE_STOCK)) {
        md5_file_hex(ZYGOTE_STOCK, b);
        if (!strcmp(a, b)) return;
    }
    mkdir_p(NEMESIS_ROOT "/stock", 0700);
    if (put_file(term, ZYGOTE_STOCK, 0600, 0, 0) == 0) log_msg("livepatch: kept stock app_process %s (%s)", a, term);
}

static int has_ref(char (*refs)[MAX_PATH], int n, const char *p) {
    while (n-- > 0)
        if (!strcmp(refs[n], p)) return 1;
    return 0;
}

/* Run at every watchdog start and after every package hook, never while one runs. Whatever is at
 * app_process that isn't NEMESIS's wrapper was put there since (stock back after another tool's
 * uninstall, its update's new wrapper) and becomes app_process.real. A dead .real is replaced by a
 * stock copy. The wrapper goes on only over a chain that reaches a binary. */
int lp_wrapper_install(void) {
    char tmp[MAX_PATH], cur[sizeof(WRAPPER_TEXT) + 8], term[MAX_PATH] = "", st[MAX_PATH];
    char refs[LINK_REFS][MAX_PATH];
    int r, n, usage;
    if (!path_exists(WRAPPER)) return -1;
    if (!is_script(WRAPPER)) {
        r = chain_walk(WRAPPER, 0, term, sizeof(term));
        n = link_refs(WRAPPER, refs, LINK_REFS, &usage);
        if (r == CH_LOOP) {
            /* another wrapper put over NEMESIS's runs it as its next link: boot-apply still runs */
            log_msg("livepatch: %s runs NEMESIS's wrapper itself; left as it is", WRAPPER);
            return 0;
        }
        if (n > 0 && has_ref(refs, n, WRAPPER_REAL)) {
            log_msg("livepatch: %s runs %s itself; LivePatch's wrapper left out", WRAPPER, WRAPPER_REAL);
            return -1;
        }
        if (r == CH_OK) {
            if (put_file(WRAPPER, WRAPPER_REAL, 0755, 0, 2000) != 0) return -1;
            log_msg("livepatch: %s kept as %s", WRAPPER, WRAPPER_REAL);
        } else log_msg("livepatch: %s can't start the zygote (it runs a missing file); NEMESIS's wrapper goes over it", WRAPPER);
        term[0] = 0;
    }
    if (chain_walk(WRAPPER_REAL, 1, term, sizeof(term)) != CH_OK) {
        if (stock_find(st, sizeof(st)) != 0) {
            log_msg("livepatch: %s can't start the zygote and no stock app_process copy is left; nothing changed", WRAPPER_REAL);
            return -1;
        }
        if (put_file(st, WRAPPER_REAL, 0755, 0, 2000) != 0) return -1;
        log_msg("livepatch: %s couldn't start the zygote; now the stock copy from %s", WRAPPER_REAL, st);
        snprintf(term, sizeof(term), "%s", WRAPPER_REAL);
    }
    stock_keep(term);
    if (is_script(WRAPPER)) {
        int k = read_file(WRAPPER, cur, sizeof(cur));
        if (k == (int)sizeof(WRAPPER_TEXT) - 1 && !strcmp(cur, WRAPPER_TEXT)) return 0;
    }
    snprintf(tmp, sizeof(tmp), "%s.nemesis-new", WRAPPER);
    if (write_file(tmp, WRAPPER_TEXT, 0755) != 0 || chmod(tmp, 0755) != 0 ||
        chown(tmp, 0, 2000) != 0 || rename(tmp, WRAPPER) != 0) {
        log_msg("livepatch: wrapper write failed errno=%d", errno);
        unlink(tmp);
        return -1;
    }
    log_msg("livepatch: wrapper installed");
    return 0;
}

/* app_process.real goes back only while its chain reaches a binary; otherwise a stock copy does. */
int lp_wrapper_remove(void) {
    char term[MAX_PATH] = "", st[MAX_PATH];
    int live;
    if (!is_script(WRAPPER)) return 0;
    live = chain_walk(WRAPPER_REAL, 1, term, sizeof(term)) == CH_OK;
    if (!live && stock_find(st, sizeof(st)) != 0) {
        log_msg("livepatch: %s can't start the zygote and no stock app_process copy is left; wrapper kept", WRAPPER_REAL);
        return -1;
    }
    lp_unmount_all();
    if (live ? rename(WRAPPER_REAL, WRAPPER) != 0 : put_file(st, WRAPPER, 0755, 0, 2000) != 0) return -1;
    if (!live) {
        unlink(WRAPPER_REAL);
        log_msg("livepatch: %s couldn't start the zygote; app_process is the stock copy from %s", WRAPPER_REAL, st);
    }
    log_msg("livepatch: wrapper removed");
    return 0;
}

/* After a package hook: farms back, then the wrapper adopts what the hook put at app_process. */
static void wrapper_sync(void) {
    char term[MAX_PATH];
    if (is_script(WRAPPER) && chain_walk(WRAPPER_REAL, 1, term, sizeof(term)) == CH_OK) return;
    remount_system(1);
    lp_wrapper_install();
}

/* ---- engine=dex ---- */

/* The SHA-1 signature of the dex inside an odex ("dey\n" header, dex offset at 8), as hex. */
static int odex_sig(const char *path, char out[41]) {
    unsigned char h[40], d[32];
    unsigned off;
    int fd = open(path, O_RDONLY), i, ok = -1;
    if (fd < 0) return -1;
    if (read(fd, h, sizeof(h)) == (ssize_t)sizeof(h) && !memcmp(h, "dey\n", 4)) {
        off = h[8] | h[9] << 8 | h[10] << 16 | (unsigned)h[11] << 24;
        if (lseek(fd, off, SEEK_SET) == (off_t)off && read(fd, d, sizeof(d)) == (ssize_t)sizeof(d) &&
            !memcmp(d, "dex\n", 4)) {
            for (i = 0; i < 20; i++) snprintf(out + 2 * i, 3, "%02x", d[12 + i]);
            ok = 0;
        }
    }
    close(fd);
    return ok;
}

static void put16(unsigned char *p, unsigned v) { p[0] = v; p[1] = v >> 8; }
static void put32(unsigned char *p, unsigned v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }
static unsigned get32(const unsigned char *p) { return p[0] | p[1] << 8 | p[2] << 16 | (unsigned)p[3] << 24; }

/* dz ("NDZ1", crc32, size, csize, raw deflate) -> a zip holding classes.dex, stored, for dexopt. */
static int dz_to_zip(const char *dz, const char *zip) {
    struct stat st;
    unsigned char *in = NULL, *dex = NULL, lh[30], cd[46], eo[22];
    unsigned crc, size, csize;
    z_stream z;
    FILE *f = NULL;
    int fd, r = -1;
    static const char NAME[] = "classes.dex";
    if (stat(dz, &st) != 0 || st.st_size < 16 || !(in = malloc(st.st_size))) goto out;
    if ((fd = open(dz, O_RDONLY)) < 0) goto out;
    if (read(fd, in, st.st_size) != st.st_size) {
        close(fd);
        goto out;
    }
    close(fd);
    crc = get32(in + 4);
    size = get32(in + 8);
    csize = get32(in + 12);
    if (memcmp(in, "NDZ1", 4) || csize != (unsigned)st.st_size - 16 || size > 64u << 20 || !(dex = malloc(size ? size : 1))) goto out;
    memset(&z, 0, sizeof(z));
    if (inflateInit2(&z, -15) != Z_OK) goto out;
    z.next_in = in + 16;
    z.avail_in = csize;
    z.next_out = dex;
    z.avail_out = size;
    if (inflate(&z, Z_FINISH) != Z_STREAM_END || z.total_out != size) {
        inflateEnd(&z);
        goto out;
    }
    inflateEnd(&z);
    if (crc32(0, dex, size) != crc) goto out;
    memset(lh, 0, sizeof(lh));
    put32(lh, 0x04034b50); put16(lh + 4, 10); put16(lh + 12, 0x21);
    put32(lh + 14, crc); put32(lh + 18, size); put32(lh + 22, size); put16(lh + 26, sizeof(NAME) - 1);
    memset(cd, 0, sizeof(cd));
    put32(cd, 0x02014b50); put16(cd + 4, 20); put16(cd + 6, 10); put16(cd + 14, 0x21);
    put32(cd + 16, crc); put32(cd + 20, size); put32(cd + 24, size); put16(cd + 28, sizeof(NAME) - 1);
    memset(eo, 0, sizeof(eo));
    put32(eo, 0x06054b50); put16(eo + 8, 1); put16(eo + 10, 1);
    put32(eo + 12, sizeof(cd) + sizeof(NAME) - 1); put32(eo + 16, sizeof(lh) + sizeof(NAME) - 1 + size);
    if (!(f = fopen(zip, "wb"))) goto out;
    if (fwrite(lh, sizeof(lh), 1, f) == 1 && fwrite(NAME, sizeof(NAME) - 1, 1, f) == 1 && fwrite(dex, size, 1, f) == 1 &&
        fwrite(cd, sizeof(cd), 1, f) == 1 && fwrite(NAME, sizeof(NAME) - 1, 1, f) == 1 && fwrite(eo, sizeof(eo), 1, f) == 1)
        r = 0;
    if (fclose(f) != 0) r = -1;
out:
    free(in);
    free(dex);
    if (r) unlink(zip);
    return r;
}

#define BOOT_CP_DEFAULT                                                                                         \
    "/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/okhttp.jar:"                  \
    "/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:"            \
    "/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/telephony-common.jar:"  \
    "/system/framework/voip-common.jar:/system/framework/mms-common.jar:/system/framework/android.policy.jar:"  \
    "/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/webviewchromium.jar:"    \
    "/system/framework/automotive.jar"

static const char *boot_cp(void) {
    const char *bcp = getenv("BOOTCLASSPATH");
    return bcp && bcp[0] ? bcp : BOOT_CP_DEFAULT;
}

/* One porter JVM per job ("porter.Main serve"), fed one request line per dex target. */
static pid_t por_pid = -1;
static FILE *por_w, *por_r;

static int porter_start(const char *logf) {
    int in[2], out[2], fd;
    if (por_pid > 0) return 0;
    if (!is_file(LP_ENGINE "/porter.jar")) {
        fprintf(stderr, "LivePatch engine missing: reinstall NEMESIS\n");
        return -1;
    }
    mkdir_p(LP_DIR "/porter/dalvik-cache", 0700);
    mkdir_p(LP_NEW, 0700);
    if (pipe(in) != 0) return -1;
    if (pipe(out) != 0) {
        close(in[0]);
        close(in[1]);
        return -1;
    }
    por_pid = fork();
    if (por_pid < 0) return -1;
    if (por_pid == 0) {
        fd = open(logf, O_WRONLY | O_CREAT | O_APPEND, 0644);
        dup2(in[0], 0);
        dup2(out[1], 1);
        if (fd >= 0) dup2(fd, 2);
        for (fd = 3; fd < 256; fd++) close(fd);
        if (chdir(LP_DIR) != 0) _exit(127);
        setenv("ANDROID_DATA", LP_DIR "/porter", 1);
        setenv("ANDROID_ROOT", "/system", 1);
        setenv("BOOTCLASSPATH", boot_cp(), 1);
        setenv("PORTER_CACHE", LP_CACHE "/names", 1);
        setenv("PORTER_DEODEX", LP_CACHE "/deodex", 1);
        setenv("PORTER_NEW", LP_NEW, 1);
        setenv("PORTER_UI", SESSION_DIR, 1);
        setenv("PORTER_UI_STATUS", ui_fixed ? "0" : "1", 1);
        execl("/system/bin/dalvikvm", "dalvikvm", "-Xms512m", "-Xmx512m", "-XX:HeapMinFree=16m", "-XX:HeapMaxFree=128m",
              "-cp", LP_ENGINE "/porter.jar", "porter.Main", "serve", "/system/framework", "/system/app", (char *)NULL);
        _exit(127);
    }
    close(in[0]);
    close(out[1]);
    signal(SIGPIPE, SIG_IGN);
    por_w = fdopen(in[1], "w");
    por_r = fdopen(out[0], "r");
    return por_w && por_r ? 0 : -1;
}

static void porter_stop(void) {
    if (por_pid <= 0) return;
    if (por_w) fclose(por_w);
    if (por_r) fclose(por_r);
    por_w = por_r = NULL;
    waitpid(por_pid, NULL, 0);
    por_pid = -1;
}

/* Sends one request; handles H (cache hit), C (new cache file), N (rename) until R. */
static int porter_port(const char *req, char *res, size_t rcap, char *detail, size_t dcap) {
    char line[2048];
    if (fputs(req, por_w) < 0 || fflush(por_w) != 0) goto dead;
    while (fgets(line, sizeof(line), por_r)) {
        char *a;
        line[strcspn(line, "\n")] = 0;
        a = strchr(line, ' ');
        if (!a) continue;
        *a++ = 0;
        if (!strcmp(line, "H") || !strcmp(line, "C")) {
            char *f = strchr(a, ' ');
            if (!f) continue;
            *f++ = 0;
            if (line[0] == 'H') cache_touch(a, base_of(f));
            else cache_add(a, f, base_of(f), "-", 1);
        } else if (!strcmp(line, "N")) {
            size_t l = strlen(detail);
            snprintf(detail + l, dcap - l, "%s%s", l ? ", " : "renamed ", a);
        } else if (!strcmp(line, "R")) {
            snprintf(res, rcap, "%s", a);
            return strncmp(a, "ok", 2) ? -1 : 0;
        }
    }
dead:
    snprintf(res, rcap, "fail other porter stopped");
    porter_stop();
    return -1;
}

/* One dex step: in (odex) -> out (odex), always ported on the unit (no per-build outputs ship).
 * The apk stays stock. */
static int dex_step(const char *id, const lp_step *ch, int k, int n, const char *target, const char *stockp,
                    const char *in, const char *out, const char *logf, double base, double span, char *detail, size_t dcap) {
    const lp_step *s = &ch[k];
    char sig[41], dz[MAX_PATH], zip[MAX_PATH], apk[MAX_PATH], cmd[4 * MAX_PATH + 600], req[8 * MAX_PATH + 600], res[600];
    size_t tl = strlen(target);
    int rc;
    if (tl < 6 || strcmp(target + tl - 5, ".odex") || odex_sig(in, sig) != 0) {
        fprintf(stderr, "%s: engine=dex needs an odex target\n", s->app);
        return -1;
    }
    (void)n;
    {
        char below[MAX_NAME] = "-", bdir[MAX_PATH] = "-";
        snprintf(apk, sizeof(apk), "%.*s.apk", (int)(tl - 5), target);
        snprintf(dz, sizeof(dz), "%s/%s.dz", LP_TMP, s->app);
        if (k > 0) {
            snprintf(below, sizeof(below), "%s", ch[k - 1].name);
            snprintf(bdir, sizeof(bdir), "%s", ch[k - 1].dir);
        }
        if (porter_start(logf) != 0) return -1;
        ui_app(s->app, "reading stock");
        log_msg("livepatch: %s: porting (dex %s)", s->app, sig);
        snprintf(req, sizeof(req), "port\t%s\t%s\t%s\t%s\t%s\t%s\t%d\t%s\t%s\t%.2f\t%.2f\t%s/porter-%s-%s.json\n", s->app,
                 s->path, stockp, in, apk, dz, k + 1, below, bdir, base, span, NEMESIS_LOGS, id, s->app);
        if (porter_port(req, res, sizeof(res), detail, dcap) != 0 || !is_file(dz)) {
            char *t = strchr(res, ' '), *msg = t ? strchr(t + 1, ' ') : NULL;
            if (msg && strncmp(t + 1, "other", 5)) fprintf(stderr, "%s\n", msg + 1);
            else fprintf(stderr, "%s: porting failed, see livepatch-%s.log\n", s->app, id);
            return -1;
        }
        {
            char *sh = res + 2, tmp[400];
            while (*sh == ' ') sh++;
            snprintf(tmp, sizeof(tmp), "%s%s%s", sh, detail[0] ? "; " : "", detail);
            snprintf(detail, dcap, "%s", tmp);
        }
        ui_pct = ui_pct > (int)(base + span * 0.995) ? ui_pct : (int)(base + span * 0.995);
    }
    ui_app(s->app, "optimizing");
    snprintf(zip, sizeof(zip), "%s/%s.zip", LP_TMP, s->app);
    if (dz_to_zip(dz, zip) != 0) {
        fprintf(stderr, "%s: bad dex payload, see livepatch-%s.log\n", s->app, id);
        return -1;
    }
    unlink(out);
    snprintf(cmd, sizeof(cmd), "BOOTCLASSPATH='%s' /system/bin/dexopt --preopt '%s' '%s' 'v=a o=v m=y u=n' >> '%s' 2>&1",
             boot_cp(), zip, out, logf);
    rc = run_cmd(cmd);
    unlink(zip);
    if (rc != 0 || odex_sig(out, sig) != 0) {
        fprintf(stderr, "%s: optimizing failed, see livepatch-%s.log\n", s->app, id);
        unlink(out);
        return -1;
    }
    return 0;
}

/* Runs the chain on the stock file into out. base/span: this target's share of the bar. */
/* The last non-empty line the tool wrote to the job log after byte offset off: the reason a patch step failed. */
static void log_reason(const char *logf, long off, char *out, size_t cap) {
    char line[300];
    FILE *f = fopen(logf, "r");
    out[0] = 0;
    if (!f) return;
    if (fseek(f, off, SEEK_SET) == 0)
        while (fgets(line, sizeof(line), f)) {
            line[strcspn(line, "\r\n")] = 0;
            if (line[0]) snprintf(out, cap, "%s", line);
        }
    fclose(f);
}

static int build_target(const char *id, const char *target, const char *stockp, int is_new, const lp_step *ch, int n,
                        const char *out, const char *logf, double base, double span, char *detail, size_t dcap) {
    char cur[MAX_PATH], nxt[MAX_PATH], dir[MAX_PATH], cmd[3 * MAX_PATH + 400];
    const char *name = base_of(target);
    struct stat st;
    mode_t mode = 0644;
    int k, rc, own = 0;
    long off;
    snprintf(cur, sizeof(cur), "%s", is_new ? "" : stockp);
    if (!is_new && stat(stockp, &st) == 0) mode = st.st_mode & 0777;
    for (k = 0; k < n; k++) {
        const lp_step *s = &ch[k];
        double b = base + span * k / n, sp = span / n;
        snprintf(nxt, sizeof(nxt), "%s/step%d", LP_TMP, k);
        if (s->kind == 0) {
            if (k) {
                fprintf(stderr, "%s: a replacement file must come first in the chain\n", name);
                return -1;
            }
            ui_text("Adding ", name, "");
            snprintf(cur, sizeof(cur), "%s", s->path);
            if (is_new && stat(s->path, &st) == 0) mode = (st.st_mode & 0777) | 0444;
            own = 0;
        } else if (!cur[0]) {
            fprintf(stderr, "%s: needs an existing file\n", name);
            return -1;
        } else if (s->kind == 1) {
            ui_app(name, "patching");
            chmod(s->path, 0755);
            snprintf(cmd, sizeof(cmd), "cd '%s/tools' && export PATH=/data/nemesis/bin:/sbin:/system/sbin:/system/bin:/system/xbin; '%s' %s '%s' '%s' >> '%s' 2>&1",
                     s->dir, s->path, s->args, cur, nxt, logf);
            if (stat(logf, &st) != 0) st.st_size = 0;
            off = (long)st.st_size;
            rc = run_cmd(cmd);
            if (rc == 10) continue; /* already patched: pass-through */
            if (rc != 0 || !is_file(nxt)) {
                char why[300];
                log_reason(logf, off, why, sizeof(why));
                if (why[0]) fprintf(stderr, "%s: patching failed: %s\n", name, why);
                else fprintf(stderr, "%s: patching failed, see livepatch-%s.log\n", name, id);
                return -1;
            }
            snprintf(cur, sizeof(cur), "%s", nxt);
            own = 1;
        } else {
            if (dex_step(id, ch, k, n, target, stockp, cur, nxt, logf, b, sp, detail, dcap) != 0) return -1;
            snprintf(cur, sizeof(cur), "%s", nxt);
            own = 1;
        }
        ui_bar(b + sp);
    }
    parent_of(out, dir, sizeof(dir));
    mkdir_p(dir, 0700);
    unlink(out);
    if (own ? rename(cur, out) != 0 : copy_file(cur, out, mode) != 0) {
        fprintf(stderr, "%s: cannot write the output (disk full?)\n", name);
        return -1;
    }
    chown(out, 0, 0);
    chmod(out, mode);
    return 0;
}

/* ---- staging pkg/<id> from the package root ---- */

/* Hard link, else copy. A destination that is already the same file is done: several [patch] sections can name
 * one pack dir, and copy_file onto its own source (O_TRUNC) would empty the package's files. */
static int link_or_copy(const char *s, const char *t, mode_t mode) {
    struct stat a, b;
    if (link(s, t) == 0) return 0;
    if (errno == EEXIST && stat(s, &a) == 0 && stat(t, &b) == 0 && a.st_ino == b.st_ino && a.st_dev == b.st_dev)
        return 0;
    return copy_file(s, t, mode);
}

static int link_tree(const char *src, const char *dst, const char *rel, char (*skip)[MAX_PATH], int ns) {
    DIR *d = opendir(src);
    struct dirent *e;
    int r = 0, i;
    if (!d) return -1;
    mkdir_p(dst, 0700);
    while ((e = readdir(d))) {
        char s[MAX_PATH], t[MAX_PATH], rr[MAX_PATH];
        struct stat st;
        int skipit = 0;
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        snprintf(s, sizeof(s), "%s/%s", src, e->d_name);
        snprintf(t, sizeof(t), "%s/%s", dst, e->d_name);
        snprintf(rr, sizeof(rr), "%s%s%s", rel, rel[0] ? "/" : "", e->d_name);
        for (i = 0; i < ns; i++)
            if (!strcmp(rr, skip[i])) skipit = 1;
        if (skipit || lstat(s, &st) != 0) continue;
        if (S_ISDIR(st.st_mode)) r |= link_tree(s, t, rr, skip, ns);
        else if (S_ISREG(st.st_mode)) {
            if (link_or_copy(s, t, st.st_mode & 07777) != 0) r = -1;
        } else if (S_ISLNK(st.st_mode)) {
            char lt[MAX_PATH];
            ssize_t l = readlink(s, lt, sizeof(lt) - 1);
            if (l > 0) {
                lt[l] = 0;
                symlink(lt, t);
            }
        }
    }
    closedir(d);
    return r;
}

static int link_one(const char *src, const char *dst) {
    char dir[MAX_PATH];
    struct stat st;
    parent_of(dst, dir, sizeof(dir));
    mkdir_p(dir, 0700);
    if (stat(src, &st) != 0) return -1;
    if (S_ISDIR(st.st_mode)) return link_tree(src, dst, "", NULL, 0);
    return link_or_copy(src, dst, st.st_mode & 07777) == 0 ? 0 : -1;
}

static int has_lp_sections(const ini_file *ini) {
    char sec[24];
    int k, f;
    for (k = 1; k < 100; k++)
        for (f = 0; f < 3; f++) {
            snprintf(sec, sizeof(sec), "%s.%d", f == 0 ? "file" : f == 1 ? "patch" : "prop", k);
            if (ini_has_section(ini, sec)) return 1;
        }
    return 0;
}

static int max_seq(const char *skip, int *mine) {
    DIR *d = opendir(LP_PKG);
    struct dirent *e;
    int mx = 0;
    *mine = 0;
    while (d && (e = readdir(d))) {
        char p[MAX_PATH];
        ini_file ini;
        const char *v;
        if (e->d_name[0] == '.') continue;
        snprintf(p, sizeof(p), "%s/%s/entries", LP_PKG, e->d_name);
        if (ini_load(&ini, p) != 0) continue;
        v = ini_get(&ini, "livepatch", "seq");
        if (v && atoi(v) > mx) mx = atoi(v);
        if (v && !strcmp(e->d_name, skip)) *mine = atoi(v);
        ini_free(&ini);
    }
    if (d) closedir(d);
    return mx;
}

/* work/<id>/pkg: entries (paths rewritten to tools/, files/, pack/) + hardlinked copies. */
static int stage_pkg(const char *id, const ini_file *ini, const char *name, const char *version, const char *root,
                     const char *pdir) {
    static char text[256 * 1024];
    static char skip[64][MAX_PATH];
    char sec[24], s[MAX_PATH], t[MAX_PATH];
    int k, f, i, ns = 0, mine, seq = max_seq(id, &mine) + 1;
    size_t o;
    if (mine) seq = mine; /* an update keeps its place in the chains */
    rm_rf(pdir);
    mkdir_p(pdir, 0700);
    o = snprintf(text, sizeof(text), "[livepatch]\nid=%s\nseq=%d\nversion=%s\nname=%s\n", id, seq, version ? version : "",
                 name ? name : id);
    snprintf(skip[ns++], MAX_PATH, "scripts");
    snprintf(skip[ns++], MAX_PATH, "NEMESIS.data");
    for (k = 1; k < 100; k++)
        for (f = 0; f < 3; f++) {
            snprintf(sec, sizeof(sec), "%s.%d", f == 0 ? "file" : f == 1 ? "patch" : "prop", k);
            if (!ini_has_section(ini, sec)) continue;
            o += snprintf(text + o, sizeof(text) - o, "\n[%s]\n", sec);
            if (f == 1) {
                const char *eng = ini_get(ini, sec, "engine");
                if (eng && !strcmp(eng, "dex") && !ini_get(ini, sec, "pack"))
                    o += snprintf(text + o, sizeof(text) - o, "pack=pack/files/porter\n");
                if (eng && !strcmp(eng, "dex") && !ini_get(ini, sec, "pack") && ns < 64) {
                    snprintf(skip[ns++], MAX_PATH, "files/porter");
                    snprintf(s, sizeof(s), "%s/files/porter", root);
                    snprintf(t, sizeof(t), "%s/pack/files/porter", pdir);
                    if (is_dir(s)) link_one(s, t);
                }
            }
            for (i = 0; i < ini->n; i++) {
                const ini_kv *kv = &ini->items[i];
                const char *pre = NULL;
                if (strcmp(kv->section, sec)) continue;
                if (f == 0 && !strcmp(kv->key, "source")) pre = "files";
                else if (f == 1 && !strcmp(kv->key, "tool")) pre = "tools";
                else if (f == 1 && !strcmp(kv->key, "pack")) pre = "pack";
                if (f == 0 && !strcmp(kv->key, "create")) continue; /* dropped: new files come from the farm */
                if (!pre) {
                    o += snprintf(text + o, sizeof(text) - o, "%s=%s\n", kv->key, kv->val);
                    continue;
                }
                if (!safe_rel(kv->val)) {
                    fprintf(stderr, "[%s]: %s= must be a path inside the package\n", sec, kv->key);
                    return -1;
                }
                o += snprintf(text + o, sizeof(text) - o, "%s=%s/%s\n", kv->key, pre, kv->val);
                snprintf(s, sizeof(s), "%s/%s", root, kv->val);
                snprintf(t, sizeof(t), "%s/%s/%s", pdir, pre, kv->val);
                if (strcmp(pre, "tools") && link_one(s, t) != 0) {
                    fprintf(stderr, "[%s]: %s=%s is not in the package\n", sec, kv->key, kv->val);
                    return -1;
                }
                if (!strcmp(pre, "pack") && ns < 64) snprintf(skip[ns++], MAX_PATH, "%s", kv->val);
            }
            if (o >= sizeof(text) - 1) {
                fprintf(stderr, "LivePatch entries too large\n");
                return -1;
            }
        }
    snprintf(t, sizeof(t), "%s/tools", pdir);
    if (link_tree(root, t, "", skip, ns) != 0) {
        fprintf(stderr, "cannot copy the package's tools (disk full?)\n");
        return -1;
    }
    snprintf(t, sizeof(t), "%s/entries", pdir);
    if (write_atomic(t, text) != 0) {
        fprintf(stderr, "cannot write the LivePatch entries (disk full?)\n");
        return -1;
    }
    return 0;
}

/* ---- targets of a package ---- */

typedef struct { char t[MAX_PATH]; long long size; } lp_tgt;

static int tgt_add(lp_tgt *v, int n, int cap, const char *t) {
    int i;
    for (i = 0; i < n; i++)
        if (!strcmp(v[i].t, t)) return n;
    if (n >= cap) return n;
    snprintf(v[n].t, sizeof(v[n].t), "%s", t);
    v[n].size = 0;
    return n + 1;
}

/* [file.N]/[patch.N] targets of ini, in the stock view (lower must be on for that). -1 on error. */
static int ini_targets(const ini_file *ini, lp_tgt *v, int n, int cap) {
    char sec[24], sv[MAX_PATH];
    int k, f;
    for (k = 1; k < 100; k++)
        for (f = 0; f < 2; f++) {
            const char *target, *star;
            int opt;
            snprintf(sec, sizeof(sec), "%s.%d", f ? "patch" : "file", k);
            if (!ini_has_section(ini, sec)) continue;
            target = ini_get(ini, sec, "target");
            opt = opt_set(ini, sec, "optional");
            if (!target || strncmp(target, "/system/", 8) || strstr(target, "..")) {
                fprintf(stderr, "[%s]: target= must be a /system/... path\n", sec);
                return -1;
            }
            star = strchr(target, '*');
            if (star) {
                /* One wildcard path component, e.g. /system/onebin/x/featurexml.cfg for each x. */
                char dir[MAX_PATH], path[MAX_PATH], low[MAX_PATH];
                const char *ds = star, *rest = strchr(star, '/');
                DIR *d;
                struct dirent *e;
                int hit = 0;
                while (ds > target && ds[-1] != '/') ds--;
                snprintf(dir, sizeof(dir), "%.*s", (int)(ds - target), target);
                stock_view(dir, low, sizeof(low));
                if (strchr(rest ? rest : "", '*') || !(d = opendir(low))) {
                    if (!opt) {
                        fprintf(stderr, "[%s]: %s matches no file\n", sec, target);
                        return -1;
                    }
                    continue;
                }
                while ((e = readdir(d))) {
                    if (e->d_name[0] == '.') continue;
                    snprintf(path, sizeof(path), "%s%s%s", dir, e->d_name, rest ? rest : "");
                    stock_view(path, sv, sizeof(sv));
                    if (fnmatch(target, path, FNM_PATHNAME) || !is_file(sv)) continue;
                    hit = 1;
                    n = tgt_add(v, n, cap, path);
                }
                closedir(d);
                if (!hit && !opt) {
                    fprintf(stderr, "[%s]: %s matches no file\n", sec, target);
                    return -1;
                }
                continue;
            }
            stock_view(target, sv, sizeof(sv));
            if (!is_file(sv)) {
                if (opt) continue;
                if (f) {
                    fprintf(stderr, "[%s]: %s does not exist\n", sec, target);
                    return -1;
                }
                /* a [file.N] without a stock file: a new file */
            }
            n = tgt_add(v, n, cap, target);
        }
    return n;
}

/* ---- the job: plan, prepare, commit ---- */

typedef struct {
    char target[MAX_PATH], nkey[80], okey[80], state[12], nver[16], over[16], stops[512], restart[128], detail[400];
} lp_plan;

static lp_plan plan[LP_MAX];
static int pn;

static void plan_path(const char *id, char *out, size_t cap) { snprintf(out, cap, "%s/%s/plan", LP_WORK, id); }

static void work_out(const char *id, const char *target, char *out, size_t cap) {
    snprintf(out, cap, "%s/%s%s", LP_WORK, id, target);
}

static void plan_load(const char *id) {
    static char buf[LP_MAX * 2400];
    char path[MAX_PATH], *p, *nl;
    pn = 0;
    plan_path(id, path, sizeof(path));
    if (read_file(path, buf, sizeof(buf)) <= 0) return;
    for (p = buf; *p && pn < LP_MAX; p = nl ? nl + 1 : p + strlen(p)) {
        char *f[9];
        int i;
        nl = strchr(p, '\n');
        if (nl) *nl = 0;
        f[0] = p;
        for (i = 1; i < 9 && f[i - 1]; i++) {
            f[i] = strchr(f[i - 1], '\t');
            if (f[i]) *f[i]++ = 0;
        }
        if (i == 9 && f[8]) {
            lp_plan *l = &plan[pn++];
            snprintf(l->target, sizeof(l->target), "%s", f[0]);
            snprintf(l->nkey, sizeof(l->nkey), "%s", f[1]);
            snprintf(l->okey, sizeof(l->okey), "%s", f[2]);
            snprintf(l->state, sizeof(l->state), "%s", f[3]);
            snprintf(l->nver, sizeof(l->nver), "%s", f[4]);
            snprintf(l->over, sizeof(l->over), "%s", f[5]);
            snprintf(l->stops, sizeof(l->stops), "%s", f[6]);
            snprintf(l->restart, sizeof(l->restart), "%s", f[7]);
            snprintf(l->detail, sizeof(l->detail), "%s", f[8]);
        }
        if (!nl) break;
    }
}

static int plan_save(const char *id) {
    static char buf[LP_MAX * 2400];
    char path[MAX_PATH];
    size_t o = 0;
    int i;
    buf[0] = 0;
    for (i = 0; i < pn; i++)
        o += snprintf(buf + o, sizeof(buf) - o, "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", plan[i].target, plan[i].nkey,
                      plan[i].okey, plan[i].state, plan[i].nver, plan[i].over, plan[i].stops[0] ? plan[i].stops : "",
                      plan[i].restart, plan[i].detail);
    plan_path(id, path, sizeof(path));
    return write_atomic(path, buf);
}

static void union_list(char *dst, size_t cap, const char *add) {
    char list[512], *t, *sv = NULL, key[128], have[600];
    snprintf(list, sizeof(list), "%s", add);
    for (t = strtok_r(list, ", ", &sv); t; t = strtok_r(NULL, ", ", &sv)) {
        snprintf(have, sizeof(have), ",%s,", dst);
        snprintf(key, sizeof(key), ",%s,", t);
        if (strstr(have, key)) continue;
        snprintf(dst + strlen(dst), cap - strlen(dst), "%s%s", dst[0] ? "," : "", t);
    }
}

static void lp_tmp_wipe(void) {
    rm_rf(LP_TMP);
    mkdir_p(LP_TMP, 0700);
}

static void job_log(const char *id, char *out, size_t cap) {
    mkdir_p(NEMESIS_LOGS, 0755);
    snprintf(out, cap, "%s/livepatch-%s.log", NEMESIS_LOGS, id);
}

enum { MODE_INSTALL, MODE_UNINSTALL, MODE_REPAIR };

/* Ports every target the job changes into work/<id>/, nothing mounted, before any hook.
 * Install: ini is the new package. Uninstall: ini NULL. Repair: every output rebuilt, no cache. */
static int prepare(const char *id, const ini_file *ini, const char *name, const char *version, const char *stage, int mode) {
    static lp_row rows[LP_MAX];
    static lp_tgt tg[LP_MAX];
    static lp_step och[CHAIN_MAX], nch[CHAIN_MAX];
    char oldp[MAX_PATH], sdir[MAX_PATH], wdir[MAX_PATH], logf[MAX_PATH], sv[MAX_PATH], smd5[33], wo[MAX_PATH];
    char lbl[MAX_NAME];
    int has_new = ini && has_lp_sections(ini), nr, nt = 0, i, j, k, rc = 0;
    long long total = 0, done = 0;
    lp_step tmp;
    snprintf(oldp, sizeof(oldp), "%s/%s", LP_PKG, id);
    snprintf(wdir, sizeof(wdir), "%s/%s", LP_WORK, id);
    snprintf(sdir, sizeof(sdir), "%s/pkg", wdir);
    if (!has_new && !is_dir(oldp)) {
        if (is_dir(wdir)) rm_rf(wdir);
        return 0;
    }
    lp_dirs();
    lp_tmp_wipe();
    cache_sweep();
    job_log(id, logf, sizeof(logf));
    mkdir_p(wdir, 0700);
    ui_pct = -1;
    pkg_label(id, lbl, sizeof(lbl));
    if (mode == MODE_INSTALL && has_new) {
        if (stage_pkg(id, ini, name, version, stage, sdir) != 0) return 1;
    } else if (mode == MODE_INSTALL) {
        rm_rf(sdir);
        write_file(LP_WORK "/drop", "", 0600);
        snprintf(sv, sizeof(sv), "%s/drop", wdir);
        rename(LP_WORK "/drop", sv); /* the new version has no LivePatch entries: pkg/<id> goes at commit */
    } else if (mode == MODE_UNINSTALL) {
        snprintf(sv, sizeof(sv), "%s/removing", oldp);
        write_file(sv, "", 0600);
    }
    if (lower_on() != 0) {
        fprintf(stderr, "LivePatch cannot read the stock files\n");
        return 1;
    }
    if (has_new && mode == MODE_INSTALL) {
        ini_file e;
        snprintf(sv, sizeof(sv), "%s/entries", sdir);
        if (ini_load(&e, sv) != 0) {
            rc = 1;
            goto out;
        }
        nt = ini_targets(&e, tg, 0, LP_MAX);
        ini_free(&e);
        if (nt < 0) {
            rc = 1;
            goto out;
        }
    }
    nr = lp_load(rows, LP_MAX);
    for (i = 0; i < nr; i++)
        if (is_dir(oldp) && step_in(oldp, rows[i].target, &tmp) == 0) nt = tgt_add(tg, nt, LP_MAX, rows[i].target);
    for (i = 0; i < nt; i++) {
        stock_view(tg[i].t, sv, sizeof(sv));
        tg[i].size = file_size(sv);
        if (!tg[i].size) tg[i].size = 4096;
        total += tg[i].size;
    }
    plan_load(id);
    for (i = 0; i < nt; i++) {
        const char *t = tg[i].t;
        char nkey[80], okey[80], nver[16], over[16], ostock[33] = "", detail[400] = "";
        int on, nn, is_new, pi = -1;
        double base = total ? 100.0 * done / total : 0, span = total ? 100.0 * tg[i].size / total : 0;
        lp_plan *l;
        done += tg[i].size;
        stock_view(t, sv, sizeof(sv));
        is_new = !is_file(sv);
        if (is_new) snprintf(smd5, sizeof(smd5), "-");
        else md5_file_hex(sv, smd5);
        for (j = 0; j < nr; j++)
            if (!strcmp(rows[j].target, t)) snprintf(ostock, sizeof(ostock), "%s", rows[j].stock);
        on = chain_for(t, NULL, NULL, 1, och, CHAIN_MAX);
        if (mode == MODE_INSTALL) {
            nn = chain_for(t, id, has_new ? sdir : NULL, 0, nch, CHAIN_MAX);
        } else if (mode == MODE_UNINSTALL) nn = chain_for(t, id, NULL, 0, nch, CHAIN_MAX);
        else nn = chain_for(t, NULL, NULL, 0, nch, CHAIN_MAX);
        chain_key(och, ostock[0] ? on : 0, ostock, okey, sizeof(okey), over, sizeof(over));
        chain_key(nch, nn, smd5, nkey, sizeof(nkey), nver, sizeof(nver));
        if (mode != MODE_REPAIR && !strcmp(nkey, okey)) {
            ui_bar(base + span);
            continue;
        }
        for (j = 0; j < pn; j++)
            if (!strcmp(plan[j].target, t)) pi = j;
        if (pi < 0) {
            if (pn >= LP_MAX) continue;
            pi = pn++;
            memset(&plan[pi], 0, sizeof(plan[pi]));
        }
        l = &plan[pi];
        work_out(id, t, wo, sizeof(wo));
        if (!strcmp(l->target, t) && !strcmp(l->nkey, nkey) && !strcmp(l->state, "done") &&
            (!strcmp(nkey, "-") || is_file(wo))) {
            ui_bar(base + span); /* resume: ported before the interruption */
            continue;
        }
        memset(l, 0, sizeof(*l));
        snprintf(l->target, sizeof(l->target), "%s", t);
        snprintf(l->nkey, sizeof(l->nkey), "%s", nkey);
        snprintf(l->okey, sizeof(l->okey), "%s", okey);
        snprintf(l->nver, sizeof(l->nver), "%s", nver);
        snprintf(l->over, sizeof(l->over), "%s", over);
        for (k = 0; k < on; k++) {
            union_list(l->stops, sizeof(l->stops), och[k].stop);
            union_list(l->restart, sizeof(l->restart), och[k].restart);
        }
        for (k = 0; k < nn; k++) {
            union_list(l->stops, sizeof(l->stops), nch[k].stop);
            union_list(l->restart, sizeof(l->restart), nch[k].restart);
        }
        snprintf(l->state, sizeof(l->state), "todo");
        plan_save(id);
        if (!strcmp(nkey, "-")) {
            snprintf(l->state, sizeof(l->state), "done");
            plan_save(id);
            ui_bar(base + span);
            continue;
        }
        if (mode != MODE_REPAIR) {
            char hit[MAX_PATH], dir[MAX_PATH];
            cpath("out", nkey, hit, sizeof(hit));
            if (is_file(hit) && cache_touch("out", nkey)) {
                const char *app = nch[nn - 1].kind == 2 ? nch[nn - 1].app : base_of(t);
                parent_of(wo, dir, sizeof(dir));
                mkdir_p(dir, 0700);
                unlink(wo);
                if (link(hit, wo) == 0 || copy_file(hit, wo, 0) == 0) {
                    ui_app(app, "from cache");
                    log_msg("livepatch: %s from cache", t);
                    snprintf(l->detail, sizeof(l->detail), "from cache");
                    snprintf(l->state, sizeof(l->state), "done");
                    plan_save(id);
                    ui_bar(base + span);
                    continue;
                }
            }
        }
        if (mode == MODE_UNINSTALL) {
            char st[300];
            const lp_step *top = &nch[nn - 1];
            ui_text("Re-porting ", top->kind == 2 ? top->app : base_of(t), "");
            ui_fixed = 1;
            for (k = 0; k < nn; k++) {
                snprintf(st, sizeof(st), "removing %s: re-porting %s on %s", lbl, nch[k].name, t);
                status_set(nch[k].dir, t, st, NULL);
            }
        }
        if (build_target(id, t, is_new ? "" : (stock_view(t, sv, sizeof(sv)), sv), is_new, nch, nn, wo, logf, base, span,
                         detail, sizeof(detail)) != 0) {
            ui_fixed = 0;
            if (mode == MODE_UNINSTALL) {
                /* the rest of the chain does not build on stock: stock is mounted, the package is marked */
                for (k = 0; k < nn; k++) status_set(nch[k].dir, t, "failed", "rest of the chain failed on stock");
                fprintf(stderr, "%s stays stock: %s failed on it\n", t, nch[nn - 1].name);
                snprintf(l->nkey, sizeof(l->nkey), "-");
                snprintf(l->state, sizeof(l->state), "done");
                snprintf(l->detail, sizeof(l->detail), "failed on %s", t);
                plan_save(id);
                continue;
            }
            snprintf(l->state, sizeof(l->state), "failed");
            plan_save(id);
            rc = 1;
            goto out;
        }
        ui_fixed = 0;
        for (k = 0; detail[k]; k++)
            if (detail[k] == '\t' || detail[k] == '\n') detail[k] = ' ';
        snprintf(l->detail, sizeof(l->detail), "%s", detail);
        snprintf(l->state, sizeof(l->state), "done");
        plan_save(id);
        ui_bar(base + span);
    }
out:
    lower_off();
    porter_stop();
    return rc;
}

int lp_prepare(const char *id, const ini_file *ini, const char *name, const char *version, const char *stage) {
    return prepare(id, ini, name, version, stage, ini ? MODE_INSTALL : MODE_UNINSTALL);
}

/* Outputs into out/, table, pkg/<id>, props, swap lines (queued by lp_swap_flush). Old and new
 * outputs are kept in cache/out. */
int lp_commit(const char *id) {
    static lp_row rows[LP_MAX];
    static lp_step ch[CHAIN_MAX];
    static char swl[LP_MAX * 900];
    char wdir[MAX_PATH], sdir[MAX_PATH], pdir[MAX_PATH], drop[MAX_PATH], out[MAX_PATH], wo[MAX_PATH], dir[MAX_PATH];
    int nr, i, j, k, n;
    size_t so = 0;
    snprintf(wdir, sizeof(wdir), "%s/%s", LP_WORK, id);
    snprintf(sdir, sizeof(sdir), "%s/pkg", wdir);
    snprintf(drop, sizeof(drop), "%s/drop", wdir);
    snprintf(pdir, sizeof(pdir), "%s/%s", LP_PKG, id);
    if (!is_dir(wdir)) return 0;
    plan_load(id);
    if (is_dir(sdir)) {
        char e[MAX_PATH];
        ini_file ini;
        rm_rf(pdir);
        if (rename(sdir, pdir) != 0) {
            fprintf(stderr, "cannot register the LivePatch entries\n");
            return 1;
        }
        lp_props_remove(id);
        snprintf(e, sizeof(e), "%s/entries", pdir);
        if (ini_load(&ini, e) == 0) {
            int r = lp_props_install(&ini, id);
            ini_free(&ini);
            if (r != 0) return 1;
        }
    } else if (is_file(drop)) {
        rm_rf(pdir);
        lp_props_remove(id);
    }
    nr = lp_load(rows, LP_MAX);
    swl[0] = 0;
    for (i = 0; i < pn; i++) {
        lp_plan *l = &plan[i];
        char md5[33];
        if (strcmp(l->state, "done")) continue;
        snprintf(out, sizeof(out), "%s%s", LP_OUT, l->target);
        work_out(id, l->target, wo, sizeof(wo));
        if (is_file(out) && strcmp(l->okey, "-")) {
            if (!strcmp(l->okey, l->nkey)) cache_forget("out", l->okey); /* repair: that inode is damaged */
            else cache_add("out", out, l->okey, l->over, 0);
        }
        for (j = 0; j < nr; j++)
            if (!strcmp(rows[j].target, l->target)) break;
        if (!strcmp(l->nkey, "-")) {
            unlink(out);
            if (j < nr) rows[j] = rows[--nr];
        } else {
            parent_of(out, dir, sizeof(dir));
            mkdir_x(dir);
            unlink(out);
            if (rename(wo, out) != 0) {
                fprintf(stderr, "%s: cannot write the output\n", base_of(l->target));
                return 1;
            }
            md5_file_hex(out, md5);
            cache_add("out", out, l->nkey, l->nver, 0);
            if (j == nr) {
                if (nr >= LP_MAX) continue;
                nr++;
            }
            snprintf(rows[j].target, MAX_PATH, "%s", l->target);
            snprintf(rows[j].output, MAX_PATH, "%s", out);
            snprintf(rows[j].stock, sizeof(rows[j].stock), "%.32s", l->nkey);
            rows[j].stock[strcspn(rows[j].stock, ".")] = 0;
            snprintf(rows[j].out_md5, sizeof(rows[j].out_md5), "%s", md5);
        }
        so += snprintf(swl + so, sizeof(swl) - so, "%s\t%s\t%s\n", l->target, l->stops, l->restart);
    }
    if (lp_save(rows, nr) != 0) {
        fprintf(stderr, "cannot write the LivePatch table (disk full?)\n");
        return 1;
    }
    for (i = 0; i < pn; i++) {
        if (strcmp(plan[i].state, "done")) continue;
        n = chain_for(plan[i].target, NULL, NULL, 0, ch, CHAIN_MAX);
        for (j = 0, k = n - 1; j < n; j++)
            if (!strcmp(ch[j].id, id)) k = j; /* uninstall: id is gone, the re-ported top gets the new detail (renames dropped) */
        for (j = 0; j < n; j++)
            if (!strcmp(ch[j].id, id) || strncmp(plan[i].detail, "failed", 6))
                status_set(ch[j].dir, plan[i].target, "pending restart", j == k ? plan[i].detail : NULL);
    }
    if (swl[0]) append_file(LP_SWAPJOB, swl);
    rm_rf(wdir);
    return 0;
}

/* Uninstall, after the package is unregistered: its props and pkg/<id> go, the swap is queued. */
void lp_finish_remove(const char *id) {
    char p[MAX_PATH];
    lp_props_remove(id);
    snprintf(p, sizeof(p), "%s/%s", LP_PKG, id);
    rm_rf(p);
    snprintf(p, sizeof(p), "%s/%s", LP_WORK, id);
    rm_rf(p);
    lp_swap_flush();
}

/* queue delete of an interrupted or failed job: ported outputs are kept in cache/out. */
void lp_discard(const char *id) {
    pkg_rec pr;
    char p[MAX_PATH], wo[MAX_PATH];
    int i;
    plan_load(id);
    if (pn) cache_sweep();
    for (i = 0; i < pn; i++) {
        work_out(id, plan[i].target, wo, sizeof(wo));
        if (!strcmp(plan[i].state, "done") && strcmp(plan[i].nkey, "-") && is_file(wo))
            cache_add("out", wo, plan[i].nkey, plan[i].nver, 1);
    }
    snprintf(p, sizeof(p), "%s/%s", LP_WORK, id);
    rm_rf(p);
    lp_tmp_wipe();
    snprintf(p, sizeof(p), "%s/%s", LP_PKG, id);
    if (pkg_find(id, &pr) != 0) rm_rf(p);
    else {
        snprintf(p, sizeof(p), "%s/%s/removing", LP_PKG, id); /* an interrupted uninstall is cancelled */
        unlink(p);
    }
    log_msg("livepatch: discarded the job of %s", id);
}

/* The space check: 100 MB free plus the size of every target, and prop conflicts. */
int lp_check(const ini_file *ini, const char *id) {
    static lp_tgt tg[LP_MAX];
    long long need = DATA_FLOOR;
    int n, i;
    if (!has_lp_sections(ini)) return 0;
    if (lp_props_check(ini, id) != 0) return 1;
    n = ini_targets(ini, tg, 0, LP_MAX);
    if (n < 0) return 1;
    /* A file an HMG-API mod changed: its backup and LivePatch's stock would each hold the other's bytes. */
    {
        static char hj[1 << 20];
        char q[MAX_PATH + 4];
        if (read_file("/data/hmgapi/state/files.json", hj, sizeof(hj)) > 0)
            for (i = 0; i < n; i++) {
                snprintf(q, sizeof(q), "\"%s\"", tg[i].t);
                if (strstr(hj, q)) {
                    fprintf(stderr, "%s is changed by an HMG-API mod: remove that mod in HMG-API first\n", tg[i].t);
                    return 1;
                }
            }
    }
    for (i = 0; i < n; i++) need += file_size(tg[i].t);
    if (data_free() < need) {
        fprintf(stderr, "Not enough space on /data\n");
        return 1;
    }
    return 0;
}

/* Around every package hook: the farms step aside so the hook writes the real directories, a hook
 * touching a managed target is logged (09-LivePatch.md, 3.7), and the wrapper adopts what the
 * hook left at app_process. HMG-API's sync runs (HMGAPI_NEMESIS_SYNC=1) write nothing: no churn. */
void lp_hook_watch(const char *hook, int after) {
    static struct { char t[MAX_PATH]; struct stat st; int ok; } hw[LP_MAX];
    static int hn;
    static lp_row rows[LP_MAX];
    static int owner; /* a hook running `nemesis install` nests: only the outermost steps aside and back */
    int i, n, quiet = getenv("HMGAPI_NEMESIS_SYNC") && !strcmp(getenv("HMGAPI_NEMESIS_SYNC"), "1");
    if (!after) {
        owner = !getenv("NEMESIS_LP_HOOK");
        if (owner) setenv("NEMESIS_LP_HOOK", hook, 1);
        if (owner && !quiet) lp_step_aside(hook);
        n = lp_load(rows, LP_MAX);
        for (hn = 0; hn < n; hn++) {
            snprintf(hw[hn].t, sizeof(hw[hn].t), "%s", rows[hn].target);
            hw[hn].ok = lstat(rows[hn].target, &hw[hn].st) == 0;
        }
        return;
    }
    for (i = 0; i < hn; i++) {
        struct stat st;
        int ok = lstat(hw[i].t, &st) == 0;
        if (ok != hw[i].ok || (ok && (st.st_ino != hw[i].st.st_ino || st.st_size != hw[i].st.st_size ||
                                      st.st_mtime != hw[i].st.st_mtime || st.st_mode != hw[i].st.st_mode)))
            log_msg("livepatch: hook %s changed %s (managed by LivePatch)", hook, hw[i].t);
    }
    hn = 0;
    if (!owner) return;
    unsetenv("NEMESIS_LP_HOOK");
    if (!quiet) lp_step_back(hook);
    wrapper_sync();
}

static int selftest(void) {
    sha1_ctx c;
    char h[41];
    sha1_init(&c);
    sha1_update(&c, "abc", 3);
    sha1_hex(&c, h);
    if (strcmp(h, "a9993e364706816aba3e25717850c26c9cd0d89d")) {
        fprintf(stderr, "sha1 abc: %s\n", h);
        return 1;
    }
    sha1_init(&c);
    sha1_update(&c, "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq", 56);
    sha1_hex(&c, h);
    if (strcmp(h, "84983e441c3bd26ebaae4aa1f95129e5e54670f1")) {
        fprintf(stderr, "sha1 448: %s\n", h);
        return 1;
    }
    printf("livepatch selftest ok\n");
    return 0;
}

int cmd_livepatch(int argc, char **argv) {
    const char *op = argc > 0 ? argv[0] : "status";
    if (!strcmp(op, "status")) {
        static lp_row rows[LP_MAX];
        char d[MAX_PATH];
        int i, n = lp_load(rows, LP_MAX);
        printf("wrapper=%d safe=%d\n", is_script(WRAPPER), path_exists(LP_SAFE) ? 1 : 0);
        for (i = 0; i < n; i++) {
            parent_of(rows[i].target, d, sizeof(d));
            printf("%s %s\n", rows[i].target, lp_mounted(rows[i].target) ? "mounted" : dir_farmed(d) ? "farmed" : "unmounted");
        }
        return 0;
    }
    if (!strcmp(op, "selftest")) return selftest();
    if (!strcmp(op, "safe") && argc > 1) {
        mkdir_p(LP_DIR, 0711);
        if (!strcmp(argv[1], "on")) {
            write_file(LP_SAFE, "1\n", 0600);
            lp_unmount_all();
        } else unlink(LP_SAFE);
        return 0;
    }
    if (!strcmp(op, "repair") && argc > 1) {
        int r = prepare(argv[1], NULL, NULL, NULL, NULL, MODE_REPAIR);
        if (r == 0) r = lp_commit(argv[1]);
        if (r == 0) lp_swap_flush();
        return r ? 1 : 0;
    }
    if (!strcmp(op, "discard") && argc > 1) {
        lp_discard(argv[1]);
        return 0;
    }
    if (!strcmp(op, "wrapper") || !strcmp(op, "unwrap")) remount_system(1);
    if (!strcmp(op, "wrapper")) return lp_wrapper_install() == 0 ? 0 : 1;
    if (!strcmp(op, "unwrap")) return lp_wrapper_remove() == 0 ? 0 : 1;
    fprintf(stderr, "nemesis livepatch status|safe on|off|wrapper|unwrap|repair <id>|discard <id>|selftest\n");
    return 1;
}

int cmd_boot_apply(void) {
    if (geteuid() != 0) return 0;
    return lp_boot_apply();
}
