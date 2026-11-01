#include "nemesis.h"

#include <dirent.h>
#include <signal.h>
#include <strings.h>
#include <sys/system_properties.h>
#include <sys/xattr.h>

static int pkg_id_ok(const char *id) {
    size_t i, n;
    if (!id || !*id) return 0;
    n = strlen(id);
    if (n > 64) return 0;
    if (id[0] == '.') return 0;
    if (strstr(id, "..")) return 0;
    for (i = 0; i < n; i++) {
        char c = id[i];
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-')
            continue;
        return 0;
    }
    return 1;
}

static int parse_pkg_line(const char *line, pkg_rec *p) {
    char buf[MAX_LINE];
    char *a, *b, *c, *d;
    snprintf(buf, sizeof(buf), "%s", line);
    a = buf;
    b = strchr(a, '|');
    if (!b) return -1;
    *b++ = 0;
    c = strchr(b, '|');
    if (!c) return -1;
    *c++ = 0;
    d = strchr(c, '|');
    if (!d) return -1;
    *d++ = 0;
    memset(p, 0, sizeof(*p));
    snprintf(p->id, sizeof(p->id), "%s", a);
    snprintf(p->name, sizeof(p->name), "%s", b);
    p->locked = atoi(c);
    snprintf(p->types, sizeof(p->types), "%s", d);
    return p->id[0] ? 0 : -1;
}

int pkg_list_load(pkg_rec **out, int *n) {
    FILE *f;
    char line[MAX_LINE];
    pkg_rec *list = NULL;
    int cap = 0, cnt = 0;
    *out = NULL;
    *n = 0;
    f = fopen(PACKAGES_DB, "r");
    if (!f) return 0;
    while (fgets(line, sizeof(line), f)) {
        pkg_rec p;
        size_t L = strlen(line);
        while (L && (line[L - 1] == '\n' || line[L - 1] == '\r')) line[--L] = 0;
        if (!line[0] || line[0] == '#') continue;
        if (parse_pkg_line(line, &p) != 0) continue;
        if (cnt >= cap) {
            cap = cap ? cap * 2 : 8;
            list = realloc(list, (size_t)cap * sizeof(pkg_rec));
        }
        list[cnt++] = p;
    }
    fclose(f);
    *out = list;
    *n = cnt;
    return 0;
}

int pkg_find(const char *id, pkg_rec *out) {
    pkg_rec *list = NULL;
    int n = 0, i, found = 0;
    pkg_list_load(&list, &n);
    for (i = 0; i < n; i++) {
        if (!strcmp(list[i].id, id)) {
            if (out) *out = list[i];
            found = 1;
            break;
        }
    }
    free(list);
    return found ? 0 : -1;
}

int pkg_save_all(pkg_rec *list, int n) {
    FILE *f;
    int i;
    mkdir_p(NEMESIS_ROOT, 0755);
    f = fopen(PACKAGES_DB, "w");
    if (!f) return -1;
    for (i = 0; i < n; i++) {
        fprintf(f, "%s|%s|%d|%s\n", list[i].id, list[i].name, list[i].locked, list[i].types);
    }
    fclose(f);
    return 0;
}

int pkg_upsert(const pkg_rec *p) {
    pkg_rec *list = NULL;
    int n = 0, i, found = 0;
    pkg_list_load(&list, &n);
    for (i = 0; i < n; i++) {
        if (!strcmp(list[i].id, p->id)) {
            list[i] = *p;
            found = 1;
            break;
        }
    }
    if (!found) {
        list = realloc(list, (size_t)(n + 1) * sizeof(pkg_rec));
        list[n++] = *p;
    }
    i = pkg_save_all(list, n);
    free(list);
    return i;
}

int pkg_remove(const char *id) {
    pkg_rec *list = NULL;
    int n = 0, i, w = 0;
    pkg_list_load(&list, &n);
    for (i = 0; i < n; i++) {
        if (!strcmp(list[i].id, id)) continue;
        list[w++] = list[i];
    }
    i = pkg_save_all(list, w);
    free(list);
    return i;
}

static int parse_svc_line(const char *line, svc_rec *s) {
    char buf[MAX_LINE];
    char *f[5];
    int i;
    snprintf(buf, sizeof(buf), "%s", line);
    f[0] = buf;
    for (i = 1; i < 5; i++) {
        f[i] = strchr(f[i - 1], '|');
        if (!f[i]) return -1;
        *f[i]++ = 0;
    }
    memset(s, 0, sizeof(*s));
    snprintf(s->qname, sizeof(s->qname), "%s", f[0]);
    snprintf(s->kind, sizeof(s->kind), "%s", f[1]);
    snprintf(s->path, sizeof(s->path), "%s", f[2]);
    snprintf(s->uid, sizeof(s->uid), "%s", f[3]);
    snprintf(s->mode, sizeof(s->mode), "%s", f[4]);
    return s->qname[0] ? 0 : -1;
}

int svc_list_load(svc_rec **out, int *n) {
    FILE *f;
    char line[MAX_LINE];
    svc_rec *list = NULL;
    int cap = 0, cnt = 0;
    *out = NULL;
    *n = 0;
    f = fopen(SERVICES_DB, "r");
    if (!f) return 0;
    while (fgets(line, sizeof(line), f)) {
        svc_rec s;
        size_t L = strlen(line);
        while (L && (line[L - 1] == '\n' || line[L - 1] == '\r')) line[--L] = 0;
        if (!line[0] || line[0] == '#') continue;
        if (parse_svc_line(line, &s) != 0) continue;
        if (cnt >= cap) {
            cap = cap ? cap * 2 : 8;
            list = realloc(list, (size_t)cap * sizeof(svc_rec));
        }
        list[cnt++] = s;
    }
    fclose(f);
    *out = list;
    *n = cnt;
    return 0;
}

int svc_save_all(svc_rec *list, int n) {
    FILE *f;
    int i;
    mkdir_p(NEMESIS_ROOT, 0755);
    f = fopen(SERVICES_DB, "w");
    if (!f) return -1;
    for (i = 0; i < n; i++) {
        fprintf(f, "%s|%s|%s|%s|%s\n", list[i].qname, list[i].kind, list[i].path, list[i].uid, list[i].mode);
    }
    fclose(f);
    return 0;
}

int svc_remove_prefix(const char *id) {
    svc_rec *list = NULL;
    int n = 0, i, w = 0;
    char prefix[MAX_ID + 2];
    snprintf(prefix, sizeof(prefix), "%s/", id);
    svc_list_load(&list, &n);
    for (i = 0; i < n; i++) {
        if (!strncmp(list[i].qname, prefix, strlen(prefix)) || !strcmp(list[i].qname, id)) {
            svc_forget(list[i].qname);
            continue;
        }
        list[w++] = list[i];
    }
    i = svc_save_all(list, w);
    free(list);
    return i;
}

static int hook_path(char *out, size_t cap, const char *root, const char *rel) {
    if (!rel || !*rel || !strcmp(rel, "N/A")) {
        out[0] = 0;
        return 0;
    }
    if (rel[0] == '/') {
        snprintf(out, cap, "%s", rel);
        return 1;
    }
    snprintf(out, cap, "%s/%s", root, rel);
    return 1;
}

int load_zip_meta(const char *zip, ini_file *ini, pkg_meta *m) {
    char text[16384], err[128];
    memset(ini, 0, sizeof(*ini));
    if (zip_extract_file(zip, "NEMESIS.data", text, sizeof(text)) != 0) {
        fprintf(stderr, "invalid package metadata: no NEMESIS.data\n");
        return -1;
    }
    if (ini_load_mem(ini, text) != 0) {
        fprintf(stderr, "invalid package metadata: %s\n", ini->err[0] ? ini->err : "parse error");
        return -1;
    }
    if (meta_parse(ini, m, err, sizeof(err)) != 0) {
        fprintf(stderr, "invalid package metadata: %s\n", err);
        ini_free(ini);
        return -1;
    }
    if (!pkg_id_ok(m->id)) {
        fprintf(stderr, "invalid package id\n");
        ini_free(ini);
        return -1;
    }
    return 0;
}

/* Metadata of an installed package, from apps/<id>/NEMESIS.data. */
static int installed_meta(const char *id, pkg_meta *m) {
    char path[MAX_PATH], err[128];
    ini_file ini;
    int rc;
    snprintf(path, sizeof(path), "%s/%s/NEMESIS.data", NEMESIS_APPS, id);
    if (ini_load(&ini, path) != 0) {
        memset(m, 0, sizeof(*m));
        return -1;
    }
    rc = meta_parse(&ini, m, err, sizeof(err));
    ini_free(&ini);
    return rc;
}

static int installed_version(const char *id, char *ver, size_t cap) {
    pkg_meta m;
    if (pkg_find(id, NULL) != 0) return 0;
    if (installed_meta(id, &m) == 0) snprintf(ver, cap, "%s", m.pkg_ver);
    else if (cap) ver[0] = 0;
    return 1;
}

static void device_ids(char *os_id, size_t ocap, char *model, size_t mcap) {
    char v[PROP_VALUE_MAX];
    os_id[0] = model[0] = 0;
    if (__system_property_get("ro.build.product", v) > 0) snprintf(os_id, ocap, "%s", v);
    if (__system_property_get("ro.mobis.targetdir", v) > 0 && v[0]) {
        char *slash;
        size_t n = strlen(v);
        while (n > 1 && v[n - 1] == '/') v[--n] = 0;
        slash = strrchr(v, '/');
        snprintf(model, mcap, "%s", slash ? slash + 1 : v);
        return;
    }
    {
        DIR *d = opendir("/system/onebin");
        struct dirent *e;
        int hits = 0;
        char path[MAX_PATH];
        if (!d) return;
        while ((e = readdir(d)) != NULL) {
            if (e->d_name[0] == '.') continue;
            snprintf(path, sizeof(path), "/system/onebin/%s", e->d_name);
            if (!is_dir(path)) continue;
            if (!hits++) snprintf(model, mcap, "%s", e->d_name);
        }
        closedir(d);
        if (hits != 1) model[0] = 0;
    }
}

int pkg_compat(const pkg_meta *m, char *reasons, size_t cap) {
    char os_id[PROP_VALUE_MAX], model[128];
    device_ids(os_id, sizeof(os_id), model, sizeof(model));
    return meta_compat(m, os_id, model, installed_version, reasons, cap);
}

/*
 * Installed packages (other than `id`) whose depends= name `id`. With `newver`
 * set, only those whose version spec `newver` would not satisfy are listed.
 * Prints "dependent <id>|<name>" per hit; returns the count.
 */
static int print_dependents(const char *id, const char *newver) {
    pkg_rec *list = NULL;
    int n = 0, i, hits = 0;
    pkg_list_load(&list, &n);
    for (i = 0; i < n; i++) {
        pkg_meta m;
        char spec[64];
        if (!strcmp(list[i].id, id)) continue;
        if (installed_meta(list[i].id, &m) != 0) continue;
        if (!meta_dep_spec(m.depends, id, spec, sizeof(spec))) continue;
        if (newver && dep_spec_match(spec, newver)) continue;
        printf("dependent %s|%s\n", list[i].id, list[i].name);
        hits++;
    }
    free(list);
    fflush(stdout);
    return hits;
}

static int safe_pkg_name(const char *s, char *out, size_t cap) {
    size_t i;
    if (!s || !*s || !out || cap < 2) return -1;
    for (i = 0; s[i]; i++) {
        char c = s[i];
        int ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                 (c >= '0' && c <= '9') || c == '.' || c == '_';
        if (!ok || i + 1 >= cap) return -1;
    }
    snprintf(out, cap, "%s", s);
    return 0;
}

/* package= wins. Otherwise the package half of component=pkg/class. */
static int android_pkg_id(const ini_file *ini, const char *sec, char *out, size_t cap) {
    const char *pkg = ini_get(ini, sec, "package");
    const char *comp = ini_get(ini, sec, "component");
    char tmp[160];
    const char *slash;
    if (pkg && *pkg) return safe_pkg_name(pkg, out, cap);
    if (!comp) return -1;
    slash = strchr(comp, '/');
    if (!slash || slash == comp || (size_t)(slash - comp) >= sizeof(tmp)) return -1;
    memcpy(tmp, comp, (size_t)(slash - comp));
    tmp[slash - comp] = 0;
    return safe_pkg_name(tmp, out, cap);
}

static int shell_quoted_ok(const char *s) {
    if (!s || !*s) return 0;
    if (strchr(s, '\'') || strchr(s, '\n') || strchr(s, '\r')) return 0;
    return 1;
}

/* Returns the number of apps Package Manager still lists afterwards. pm
 * prints Failure for a package that was never installed, so the check is
 * `pm path`, not the uninstall text. With Android down nothing is checked. */
static int pm_uninstall_apks(const ini_file *ini, const char *root) {
    int i, left = 0, up = framework_up();
    (void)root;
    for (i = 0; i < 32; i++) {
        char sec[32], apkid[160], cmd[256], out[256];
        const char *kind;
        snprintf(sec, sizeof(sec), "app.%d", i);
        if (!ini_has_section(ini, sec)) continue;
        kind = ini_get(ini, sec, "kind");
        if (!kind || strcmp(kind, "apk")) continue;
        if (android_pkg_id(ini, sec, apkid, sizeof(apkid)) != 0) continue;
        /* The Android package name, not the apk path. */
        snprintf(cmd, sizeof(cmd), "/system/bin/pm uninstall %s >/dev/null 2>&1 || true", apkid);
        run_cmd(cmd);
        if (!up) continue;
        snprintf(cmd, sizeof(cmd), "/system/bin/pm path %s", apkid);
        out[0] = 0;
        run_cmd_capture(cmd, out, sizeof(out));
        if (strstr(out, "package:")) {
            log_msg("pm uninstall left %s installed", apkid);
            fprintf(stderr, "pm uninstall failed: %s\n", apkid);
            left++;
        }
    }
    return left;
}

/*
 * KitKat pm prints Success/Failure and almost always exits 0. Trust the text.
 *
 * HMG PackageManagerService.installNewPackageLI: when sys.boot_completed is 1,
 * only allowlisted OEM signers may install. Anything else returns -104, which
 * pm labels INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES even when that
 * package was never installed. Clear the property for the install only, then
 * put the previous value back. BOOT_COMPLETED is a one-shot broadcast; this
 * does not rebroadcast it.
 */
static int pm_install_apk(const char *apk, const char *apkid) {
    char cmd[MAX_PATH + 80];
    char out[768];
    char prev[96];
    char pathout[256];
    int cleared = 0;
    int ok;

    prev[0] = 0;
    if (__system_property_get("sys.boot_completed", prev) > 0 && prev[0] == '1') {
        if (__system_property_set("sys.boot_completed", "0") == 0) {
            cleared = 1;
            log_msg("pm: cleared sys.boot_completed for install");
        } else {
            log_msg("pm: WARN could not clear sys.boot_completed");
        }
    }

    snprintf(cmd, sizeof(cmd), "/system/bin/pm install -r \"%s\"", apk);
    run_cmd_capture(cmd, out, sizeof(out));
    ok = strstr(out, "Success") != NULL;

    /* A real replace with a different signer also returns -104. Only uninstall
     * when pm path shows the package is already on the unit. */
    if (!ok && apkid && *apkid && strstr(out, "INCONSISTENT_CERTIFICATES")) {
        snprintf(cmd, sizeof(cmd), "/system/bin/pm path %s", apkid);
        run_cmd_capture(cmd, pathout, sizeof(pathout));
        if (strstr(pathout, "package:")) {
            log_msg("pm: %s present with a different signer; uninstalling", apkid);
            snprintf(cmd, sizeof(cmd), "/system/bin/pm uninstall %s", apkid);
            run_cmd_capture(cmd, pathout, sizeof(pathout));
            snprintf(cmd, sizeof(cmd), "/system/bin/pm install -r \"%s\"", apk);
            run_cmd_capture(cmd, out, sizeof(out));
            ok = strstr(out, "Success") != NULL;
        }
    }

    if (cleared) {
        if (__system_property_set("sys.boot_completed", prev) != 0)
            log_msg("pm: WARN could not restore sys.boot_completed=%s", prev);
    }

    if (ok) return 0;
    if (out[0]) {
        char *nl = strchr(out, '\n');
        if (nl) *nl = 0;
        fprintf(stderr, "%s\n", out);
        log_msg("pm install failed %s: %s", apk, out);
    } else {
        log_msg("pm install failed %s", apk);
    }
    return 1;
}

static int install_apks(const ini_file *ini, const char *root) {
    int i, rc = 0;
    for (i = 0; i < 32; i++) {
        char sec[32], apk[MAX_PATH], apkid[160];
        const char *kind, *path;
        snprintf(sec, sizeof(sec), "app.%d", i);
        if (!ini_has_section(ini, sec)) continue;
        kind = ini_get(ini, sec, "kind");
        path = ini_get(ini, sec, "path");
        if (!kind || strcmp(kind, "apk") || !path) continue;
        snprintf(apk, sizeof(apk), "%s/%s", root, path);
        if (!is_file(apk)) {
            log_msg("missing apk %s", apk);
            rc = 1;
            continue;
        }
        apkid[0] = 0;
        android_pkg_id(ini, sec, apkid, sizeof(apkid));
        if (pm_install_apk(apk, apkid[0] ? apkid : NULL) != 0) {
            /* Android is down during recovery installs. A booted unit must not
             * record the package when Package Manager rejected the apk. */
            if (framework_up()) rc = 1;
        }
    }
    return rc;
}

static int register_services(const ini_file *ini, const char *id, const char *root) {
    svc_rec *list = NULL;
    int n = 0, i, w;
    char prefix[MAX_ID + 2];
    snprintf(prefix, sizeof(prefix), "%s/", id);
    svc_list_load(&list, &n);
    w = 0;
    for (i = 0; i < n; i++) {
        if (!strncmp(list[i].qname, prefix, strlen(prefix))) continue;
        list[w++] = list[i];
    }
    n = w;
    for (i = 0; i < 32; i++) {
        char sec[32], qn[MAX_QNAME], resolved[MAX_PATH];
        const char *local, *kind, *path, *uid, *mode;
        svc_rec s;
        snprintf(sec, sizeof(sec), "service.%d", i);
        if (!ini_has_section(ini, sec)) continue;
        local = ini_get(ini, sec, "name");
        kind = ini_get(ini, sec, "kind");
        path = ini_get(ini, sec, "path");
        uid = ini_get(ini, sec, "uid");
        mode = ini_get(ini, sec, "mode");
        if (!local || !*local) continue;
        qname_join(qn, sizeof(qn), id, local);
        svc_forget(qn);
        memset(&s, 0, sizeof(s));
        snprintf(s.qname, sizeof(s.qname), "%s", qn);
        snprintf(s.kind, sizeof(s.kind), "%s", kind ? kind : "sh");
        if (path && path[0] == '/') snprintf(resolved, sizeof(resolved), "%s", path);
        else if (path) snprintf(resolved, sizeof(resolved), "%s/%s", root, path);
        else resolved[0] = 0;
        snprintf(s.path, sizeof(s.path), "%s", resolved);
        snprintf(s.uid, sizeof(s.uid), "%s", uid ? uid : "root");
        snprintf(s.mode, sizeof(s.mode), "%s", mode ? mode : "background");
        list = realloc(list, (size_t)(n + 1) * sizeof(svc_rec));
        list[n++] = s;
    }
    i = svc_save_all(list, n);
    free(list);
    return i;
}

static int start_pkg_services(const char *id) {
    svc_rec *list = NULL;
    int n = 0, i;
    char prefix[MAX_ID + 2];
    snprintf(prefix, sizeof(prefix), "%s/", id);
    svc_list_load(&list, &n);
    for (i = 0; i < n; i++) {
        if (!strncmp(list[i].qname, prefix, strlen(prefix))) svc_start(list[i].qname);
    }
    free(list);
    return 0;
}

static int stop_pkg_services(const char *id) {
    svc_rec *list = NULL;
    int n = 0, i;
    char prefix[MAX_ID + 2];
    snprintf(prefix, sizeof(prefix), "%s/", id);
    svc_list_load(&list, &n);
    for (i = 0; i < n; i++) {
        if (!strncmp(list[i].qname, prefix, strlen(prefix))) svc_stop(list[i].qname);
    }
    free(list);
    return 0;
}

static int run_named_hook(const ini_file *ini, const char *root, const char *key) {
    char script[MAX_PATH];
    const char *rel = ini_get(ini, "nemesis", key);
    if (!hook_path(script, sizeof(script), root, rel)) return 0;
    log_msg("hook %s -> %s", key, script);
    return run_script(script, root, "root");
}

/* force: carry on when preuninstall or pm uninstall fails. Without it the
 * package stays registered with its files, and its services run again. */
static int uninstall_tree(const char *id, int allow_locked, int force) {
    pkg_rec rec;
    char root[MAX_PATH], meta[MAX_PATH], tmpun[MAX_PATH];
    ini_file ini;
    int have_ini = 0, rc;
    if (pkg_find(id, &rec) != 0) {
        fprintf(stderr, "package not installed: %s\n", id);
        return 1;
    }
    if (rec.locked && !allow_locked) {
        fprintf(stderr, "package is locked: %s\n", id);
        return 2;
    }
    snprintf(root, sizeof(root), "%s/%s", NEMESIS_APPS, id);
    snprintf(meta, sizeof(meta), "%s/NEMESIS.data", root);
    if (is_file(meta) && ini_load(&ini, meta) == 0) have_ini = 1;
    stop_pkg_services(id);
    if (have_ini) {
        rc = run_named_hook(&ini, root, "preuninstall");
        if (rc != 0 && !force) {
            log_msg("uninstall %s: preuninstall exited %d", id, rc);
            fprintf(stderr, "uninstall failed: %s: preuninstall exited %d\n", id, rc);
            ini_free(&ini);
            start_pkg_services(id);
            return 5;
        }
        if (pm_uninstall_apks(&ini, root) && !force) {
            log_msg("uninstall %s: apps still installed", id);
            fprintf(stderr, "uninstall failed: %s: Android app still installed\n", id);
            ini_free(&ini);
            start_pkg_services(id);
            return 5;
        }
    }
    svc_remove_prefix(id);
    if (have_ini) {
        const char *rel = ini_get(&ini, "nemesis", "postuninstall");
        char script[MAX_PATH];
        if (hook_path(script, sizeof(script), root, rel) && is_file(script)) {
            snprintf(tmpun, sizeof(tmpun), "%s/postun-%s.sh", NEMESIS_TMP, id);
            copy_file(script, tmpun, 0755);
            run_script(tmpun, root, "root");
            unlink(tmpun);
        }
        ini_free(&ini);
    }
    rm_rf(root);
    pkg_remove(id);
    log_msg("uninstalled %s", id);
    return 0;
}

static int do_fresh_install(const char *stage, ini_file *ini,
                            const char *id, const char *name, const char *types, int locked) {
    char dest[MAX_PATH];
    pkg_rec rec;
    int rc;
    snprintf(dest, sizeof(dest), "%s/%s", NEMESIS_APPS, id);
    rc = run_named_hook(ini, stage, "preinstall");
    if (rc != 0) {
        log_msg("preinstall failed rc=%d", rc);
        return rc;
    }
    rm_rf(dest);
    mkdir_p(dest, 0755);
    if (copy_tree(stage, dest) != 0) {
        log_msg("copy tree failed %s -> %s", stage, dest);
        return 1;
    }
    if (install_apks(ini, dest) != 0) {
        pm_uninstall_apks(ini, dest);
        rm_rf(dest);
        fprintf(stderr, "pm install failed\n");
        return 1;
    }
    register_services(ini, id, dest);
    memset(&rec, 0, sizeof(rec));
    snprintf(rec.id, sizeof(rec.id), "%s", id);
    snprintf(rec.name, sizeof(rec.name), "%s", name);
    rec.locked = locked;
    snprintf(rec.types, sizeof(rec.types), "%s", types);
    pkg_upsert(&rec);
    rc = run_named_hook(ini, dest, "postinstall");
    start_pkg_services(id);
    return rc;
}

static int dir_has_meta(const char *dir) {
    DIR *d;
    struct dirent *e;
    int found = 0;
    d = opendir(dir);
    if (!d) return 0;
    while ((e = readdir(d))) {
        if (!strcasecmp(e->d_name, "NEMESIS.data")) {
            found = 1;
            break;
        }
    }
    closedir(d);
    return found;
}

/* Paths in NEMESIS.data are relative to the directory that contains it.
 * A zip of the folder, rather than of its contents, still installs. */
int package_root(const char *stage, char *out, size_t cap) {
    DIR *d;
    struct dirent *e;
    char child[MAX_PATH];
    int hits = 0;
    if (dir_has_meta(stage)) {
        snprintf(out, cap, "%s", stage);
        return 0;
    }
    d = opendir(stage);
    if (!d) return -1;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        if (!strcmp(e->d_name, "__MACOSX")) continue;
        snprintf(child, sizeof(child), "%s/%s", stage, e->d_name);
        if (!dir_has_meta(child)) continue;
        hits++;
        snprintf(out, cap, "%s", child);
    }
    closedir(d);
    return hits == 1 ? 0 : -1;
}

int cmd_install(int argc, char **argv) {
    int update = 0, i, locked_exist = 0, accept_risk = 0, compat, core = 0;
    const char *zip = NULL;
    char stage[MAX_PATH], stage_top[MAX_PATH], reasons[1024];
    const char *id;
    ini_file ini;
    pkg_meta meta;
    pkg_rec existing;
    int have_existing;
    for (i = 0; i < argc; i++) {
        if (!strcmp(argv[i], "--update")) update = 1;
        else if (!strcmp(argv[i], "--accept-risk")) accept_risk = 1;
        else if (!strcmp(argv[i], "--core")) core = 1;
        else if (argv[i][0] != '-') zip = argv[i];
    }
    if (!zip) {
        fprintf(stderr, "usage: nemesis install [--update] [--core] [--accept-risk] <zip>\n");
        return 1;
    }
    if (!is_file(zip)) {
        fprintf(stderr, "missing zip: %s\n", zip);
        return 1;
    }
    ensure_dirs();
    if (load_zip_meta(zip, &ini, &meta) != 0) return 1;
    id = meta.id;
    have_existing = pkg_find(id, &existing) == 0;
    if (have_existing && existing.locked) locked_exist = 1;
    /* --core: a built-in package from a NEMESIS update; it may replace a
     * locked package and is locked itself. */
    if (have_existing && locked_exist && !core) {
        fprintf(stderr, "package is locked and cannot be updated: %s\n", id);
        ini_free(&ini);
        return 2;
    }
    if (have_existing && !update) {
        fprintf(stderr, "already installed: %s (pass --update)\n", id);
        ini_free(&ini);
        return 3;
    }
    if (have_existing && print_dependents(id, meta.pkg_ver[0] ? meta.pkg_ver : "0") > 0) {
        fprintf(stderr, "update blocked: %s %s does not satisfy installed dependents\n",
                id, meta.pkg_ver[0] ? meta.pkg_ver : "(no version)");
        ini_free(&ini);
        return 21;
    }
    compat = pkg_compat(&meta, reasons, sizeof(reasons));
    if (compat == COMPAT_NONE && !accept_risk) {
        printf("compat=incompatible\n%s", reasons);
        fflush(stdout);
        fprintf(stderr, "package not marked compatible: %s (pass --accept-risk)\n", id);
        ini_free(&ini);
        return 20;
    }
    if (compat != COMPAT_FULL)
        log_msg("install %s: compat=%s%s", id, compat_name(compat), accept_risk ? " (risk accepted)" : "");
    snprintf(stage, sizeof(stage), "%s/stage/%s", NEMESIS_TMP, id);
    snprintf(stage_top, sizeof(stage_top), "%s", stage);
    rm_rf(stage);
    mkdir_p(stage, 0755);
    if (zip_extract(zip, stage) != 0) {
        fprintf(stderr, "unzip failed\n");
        ini_free(&ini);
        rm_rf(stage);
        return 1;
    }
    {
        char pkgroot[MAX_PATH];
        if (package_root(stage, pkgroot, sizeof(pkgroot)) != 0) {
            fprintf(stderr, "invalid package metadata\n");
            ini_free(&ini);
            rm_rf(stage);
            return 1;
        }
        if (strcmp(pkgroot, stage))
            snprintf(stage, sizeof(stage), "%s", pkgroot);
    }
    if (have_existing && update) {
        char oldroot[MAX_PATH], oldmeta[MAX_PATH];
        ini_file oldini;
        snprintf(oldroot, sizeof(oldroot), "%s/%s", NEMESIS_APPS, id);
        snprintf(oldmeta, sizeof(oldmeta), "%s/NEMESIS.data", oldroot);
        stop_pkg_services(id);
        if (is_file(oldmeta) && ini_load(&oldini, oldmeta) == 0) {
            run_named_hook(&oldini, oldroot, "preuninstall");
            pm_uninstall_apks(&oldini, oldroot);
            svc_remove_prefix(id);
            run_named_hook(&oldini, oldroot, "postuninstall");
            ini_free(&oldini);
        } else {
            svc_remove_prefix(id);
        }
        rm_rf(oldroot);
        log_msg("update replacing %s", id);
    }
    i = do_fresh_install(stage, &ini, id, meta.name, meta.types, core);
    ini_free(&ini);
    rm_rf(stage_top);
    if (i == 0) fprintf(stdout, "ok %s %s\n", have_existing ? "updated" : "installed", id);
    return i;
}

int cmd_uninstall(int argc, char **argv) {
    const char *id = NULL;
    int i, force = 0, rc;
    for (i = 0; i < argc; i++) {
        if (!strcmp(argv[i], "--force")) force = 1;
        else if (argv[i][0] != '-') id = argv[i];
    }
    if (!id || !*id) {
        fprintf(stderr, "usage: nemesis uninstall [--force] <package-id>\n");
        return 1;
    }
    ensure_dirs();
    if (pkg_find(id, NULL) == 0 && print_dependents(id, NULL) > 0) {
        fprintf(stderr, "uninstall blocked: other packages depend on %s\n", id);
        return 4;
    }
    rc = uninstall_tree(id, 0, force);
    if (rc == 0) {
        fprintf(stdout, "ok uninstalled %s\n", id);
        fflush(stdout);
    }
    return rc;
}

static void print_meta(const pkg_meta *m) {
    char esc[META_DETAIL_MAX * 12 + 1];
    printf("id=%s\nname=%s\nversion=%s\ntype=%s\ntypes=%s\npackage-version=%s\n",
           m->id, m->name, m->meta_ver, m->types, m->types, m->pkg_ver);
    meta_escape_field(m->short_desc, esc, sizeof(esc));
    printf("short-desc=%s\n", esc);
    meta_escape_field(m->detailed_desc, esc, sizeof(esc));
    printf("detailed-desc=%s\n", esc);
    printf("depends=%s\nos=%s\nmodels=%s\n", m->depends, m->os, m->models);
}

/* pkg-info <zip|installed-id>: descriptor fields, one key=value per line. */
int cmd_pkg_info(const char *arg) {
    ini_file ini;
    pkg_meta m;
    pkg_rec rec;
    char reasons[1024], have[32];
    int inst, c;
    if (!arg) return 1;
    if (is_file(arg)) {
        if (load_zip_meta(arg, &ini, &m) != 0) return 1;
        ini_free(&ini);
    } else if (pkg_find(arg, NULL) == 0) {
        if (installed_meta(arg, &m) != 0) {
            fprintf(stderr, "invalid installed metadata: %s\n", arg);
            return 1;
        }
    } else {
        fprintf(stderr, "missing zip or package: %s\n", arg);
        return 1;
    }
    inst = pkg_find(m.id, &rec) == 0;
    print_meta(&m);
    have[0] = 0;
    if (inst) installed_version(m.id, have, sizeof(have));
    printf("installed=%d\ninstalled-version=%s\nlocked=%d\n", inst, have, inst ? rec.locked : 0);
    c = pkg_compat(&m, reasons, sizeof(reasons));
    printf("compat=%s\n", compat_name(c));
    fflush(stdout);
    return 0;
}

/* compat-check <os> <models> [depends]: the same verdict for a descriptor the
 * Store got from the portal, before any zip is on the unit. */
int cmd_compat_check(int argc, char **argv) {
    pkg_meta m;
    char reasons[1024], os_id[PROP_VALUE_MAX], model[128];
    int c;
    if (argc < 2) {
        fprintf(stderr, "usage: nemesis compat-check <os> <models> [depends]\n");
        return 1;
    }
    memset(&m, 0, sizeof(m));
    snprintf(m.os, sizeof(m.os), "%s", argv[0]);
    snprintf(m.models, sizeof(m.models), "%s", argv[1]);
    if (argc > 2) snprintf(m.depends, sizeof(m.depends), "%s", argv[2]);
    device_ids(os_id, sizeof(os_id), model, sizeof(model));
    c = meta_compat(&m, os_id, model, installed_version, reasons, sizeof(reasons));
    printf("compat=%s\nos=%s\nmodel=%s\n%s", compat_name(c), os_id, model, reasons);
    fflush(stdout);
    return 0;
}

/* compat <zip|installed-id>: full / partial / incompatible plus reason lines. */
int cmd_compat(const char *arg) {
    ini_file ini;
    pkg_meta m;
    char reasons[1024], os_id[PROP_VALUE_MAX], model[128];
    int c;
    if (!arg) {
        fprintf(stderr, "usage: nemesis compat <zip|package-id>\n");
        return 1;
    }
    if (is_file(arg)) {
        if (load_zip_meta(arg, &ini, &m) != 0) return 1;
        ini_free(&ini);
    } else if (pkg_find(arg, NULL) != 0 || installed_meta(arg, &m) != 0) {
        fprintf(stderr, "missing zip or package: %s\n", arg);
        return 1;
    }
    device_ids(os_id, sizeof(os_id), model, sizeof(model));
    c = meta_compat(&m, os_id, model, installed_version, reasons, sizeof(reasons));
    printf("compat=%s\nos=%s\nmodel=%s\n%s", compat_name(c), os_id, model, reasons);
    fflush(stdout);
    return 0;
}

/*
 * id|name|locked|types|package-version|short-desc. The first four fields are
 * what 1.15 Managers read; short-desc is percent-encoded (% | CR LF).
 */
int cmd_pkg_list(void) {
    pkg_rec *list = NULL;
    int n = 0, i;
    seed_core_packages();
    pkg_list_load(&list, &n);
    for (i = 0; i < n; i++) {
        pkg_meta m;
        char esc[META_SHORT_MAX * 12 + 1];
        if (installed_meta(list[i].id, &m) != 0) memset(&m, 0, sizeof(m));
        meta_escape_field(m.short_desc, esc, sizeof(esc));
        printf("%s|%s|%d|%s|%s|%s\n", list[i].id, list[i].name, list[i].locked, list[i].types,
               m.pkg_ver, esc);
    }
    free(list);
    fflush(stdout);
    return 0;
}

int cmd_pkg_launch(const char *id) {
    pkg_rec rec;
    char root[MAX_PATH], meta[MAX_PATH];
    ini_file ini;
    int i, launched = 0;
    if (!id || !*id) {
        fprintf(stderr, "usage: nemesis pkg-launch <package-id>\n");
        return 1;
    }
    if (pkg_find(id, &rec) != 0) {
        fprintf(stderr, "package not installed: %s\n", id);
        return 1;
    }
    snprintf(root, sizeof(root), "%s/%s", NEMESIS_APPS, id);
    snprintf(meta, sizeof(meta), "%s/NEMESIS.data", root);
    if (!is_file(meta) || ini_load(&ini, meta) != 0) {
        fprintf(stderr, "missing package metadata: %s\n", id);
        return 1;
    }
    for (i = 0; i < 32; i++) {
        char sec[32], script[MAX_PATH], cmd[MAX_PATH + 80];
        const char *kind, *path, *comp, *pkg;
        snprintf(sec, sizeof(sec), "app.%d", i);
        if (!ini_has_section(&ini, sec)) continue;
        kind = ini_get(&ini, sec, "kind");
        path = ini_get(&ini, sec, "path");
        comp = ini_get(&ini, sec, "component");
        pkg = ini_get(&ini, sec, "package");
        if ((comp && *comp) || (pkg && *pkg)) {
            char apkid[160], apk[MAX_PATH];
            const char *shown = (comp && *comp) ? comp : pkg;
            if (!shell_quoted_ok(shown)) {
                fprintf(stderr, "activity launch failed: %s\n", id);
                ini_free(&ini);
                return 1;
            }
            /* A NEMESIS install while Android is down leaves the apk on disk
             * and never registers it. Launch is the next chance to install. */
            if (framework_up() && kind && !strcmp(kind, "apk") && path && *path &&
                android_pkg_id(&ini, sec, apkid, sizeof(apkid)) == 0) {
                snprintf(cmd, sizeof(cmd), "/system/bin/pm path %s >/dev/null 2>&1", apkid);
                if (run_cmd(cmd) != 0) {
                    snprintf(apk, sizeof(apk), "%s/%s", root, path);
                    if (pm_install_apk(apk, apkid) != 0) {
                        fprintf(stderr, "pm install failed: %s\n", apk);
                        ini_free(&ini);
                        return 1;
                    }
                }
            }
            /* The Manager toasts pkg-launch output: only a script's own text is meant for it. */
            if (comp && *comp) {
                snprintf(cmd, sizeof(cmd),
                         "/system/bin/am start -n '%s' -f 0x10000000 >/dev/null", comp);
            } else {
                snprintf(cmd, sizeof(cmd),
                         "/system/bin/monkey -p '%s' -c android.intent.category.LAUNCHER 1 >/dev/null",
                         pkg);
            }
            if (run_cmd(cmd) != 0) {
                fprintf(stderr, "activity launch failed: %s\n", shown);
                ini_free(&ini);
                return 1;
            }
            launched = 1;
        } else if (kind && !strcmp(kind, "sh") && path && *path) {
            if (path[0] == '/') snprintf(script, sizeof(script), "%s", path);
            else snprintf(script, sizeof(script), "%s/%s", root, path);
            run_script(script, root, "root");
            launched = 1;
        }
    }
    {
        svc_rec *list = NULL;
        int n = 0, j;
        char prefix[MAX_ID + 2];
        snprintf(prefix, sizeof(prefix), "%s/", id);
        svc_list_load(&list, &n);
        for (j = 0; j < n; j++) {
            if (strncmp(list[j].qname, prefix, strlen(prefix))) continue;
            if (!strcmp(list[j].kind, "instance")) {
                svc_start_ex(list[j].qname, 1);
                launched = 1;
            }
        }
        free(list);
    }
    ini_free(&ini);
    if (!launched) {
        fprintf(stderr, "package has nothing to launch: %s\n", id);
        return 1;
    }
    printf("ok launched %s\n", id);
    fflush(stdout);
    return 0;
}

static const char *USB_HANDLER_META =
    "[nemesis]\n"
    "version=1.1\n"
    "id=usb-handler\n"
    "name=USB Handler\n"
    "type=service\n"
    "package-version=" NEMESIS_VERSION "\n"
    "short-desc=\"Runs NEMESIS packages and scripts from a USB stick.\"\n"
    "os=any\n"
    "models=any\n"
    "\n"
    "[service.0]\n"
    "name=usb-handler\n"
    "kind=sh\n"
    "path=svc/usb-handler.sh\n"
    "uid=root\n"
    "mode=restart\n";

static const char *USB_HANDLER_SH =
    "#!/system/bin/sh\n"
    "export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin\n"
    "exec /system/xbin/nemesis usb-handler\n";

static const char *MONITOR_META =
    "[nemesis]\n"
    "version=1.1\n"
    "id=" MONITOR_ID "\n"
    "name=Update Monitor\n"
    "type=service\n"
    "package-version=" NEMESIS_VERSION "\n"
    "short-desc=\"Asks the Manager to check for NEMESIS updates.\"\n"
    "os=any\n"
    "models=any\n"
    "\n"
    "[service.0]\n"
    "name=update-monitor\n"
    "kind=sh\n"
    "path=svc/update-monitor.sh\n"
    "uid=root\n"
    "mode=restart\n";

/* The Manager decides whether a check is due. Short sleeps, so a killed
 * instance leaves no long-lived sleep behind. */
static const char *MONITOR_SH =
    "#!/system/bin/sh\n"
    "export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin\n"
    "while true; do\n"
    "    if [ \"$(getprop sys.boot_completed)\" = \"1\" ]; then\n"
    "        am broadcast -f 32 -a com.nemesis.manager.UPDATE_TICK -n " MANAGER_EVENTS " >/dev/null 2>&1\n"
    "    fi\n"
    "    n=0\n"
    "    while [ $n -lt 60 ]; do\n"
    "        sleep 60\n"
    "        n=$((n + 1))\n"
    "    done\n"
    "done\n";

static void seed_core(const char *id, const char *name, const char *svc,
                      const char *meta_text, const char *script) {
    char root[MAX_PATH], sh[MAX_PATH], meta[MAX_PATH], svcdir[MAX_PATH], q[MAX_PATH];
    char fp[MAX_PATH], failp[MAX_PATH];
    pkg_rec rec;
    ini_file ini;
    snprintf(root, sizeof(root), "%s/%s", NEMESIS_APPS, id);
    mkdir_p(root, 0755);
    snprintf(svcdir, sizeof(svcdir), "%s/svc", root);
    mkdir_p(svcdir, 0755);
    snprintf(meta, sizeof(meta), "%s/NEMESIS.data", root);
    snprintf(sh, sizeof(sh), "%s/%s.sh", svcdir, svc);
    write_file(meta, meta_text, 0644);
    write_file(sh, script, 0755);
    memset(&ini, 0, sizeof(ini));
    ini_load_mem(&ini, meta_text);
    register_services(&ini, id, root);
    ini_free(&ini);
    memset(&rec, 0, sizeof(rec));
    snprintf(rec.id, sizeof(rec.id), "%s", id);
    snprintf(rec.name, sizeof(rec.name), "%s", name);
    rec.locked = 1;
    snprintf(rec.types, sizeof(rec.types), "%s", "service");
    pkg_upsert(&rec);
    snprintf(q, sizeof(q), "%s/%s", id, svc);
    pid_path_for(fp, sizeof(fp), q);
    snprintf(failp, sizeof(failp), "%s.fails", fp);
    unlink(failp);
}

int seed_core_packages(void) {
    ensure_dirs();
    seed_core(LOCKED_ID, "USB Handler", "usb-handler", USB_HANDLER_META, USB_HANDLER_SH);
    seed_core(MONITOR_ID, "Update Monitor", "update-monitor", MONITOR_META, MONITOR_SH);
    return 0;
}

/* Replaces dst with a copy of src, keeping dst's owner, mode and SELinux label.
 * The copy is synced before the rename, so power loss leaves the old file or
 * the new one, never a truncated boot script. */
int replace_keep_attrs(const char *src, const char *dst) {
    char tmp[MAX_PATH], label[256];
    struct stat st, want, got;
    ssize_t n;
    int fd;
    if (stat(dst, &st) != 0 || stat(src, &want) != 0) return -1;
    n = lgetxattr(dst, "security.selinux", label, sizeof(label));
    snprintf(tmp, sizeof(tmp), "%s.nemesis-new", dst);
    unlink(tmp);
    if (copy_file(src, tmp, st.st_mode & 0777) != 0) goto fail;
    if (chown(tmp, st.st_uid, st.st_gid) != 0 || chmod(tmp, st.st_mode & 07777) != 0) goto fail;
    if (n > 0 && lsetxattr(tmp, "security.selinux", label, (size_t)n, 0) != 0) goto fail;
    fd = open(tmp, O_RDONLY);
    if (fd < 0) goto fail;
    if (fsync(fd) != 0) {
        close(fd);
        goto fail;
    }
    close(fd);
    if (stat(tmp, &got) != 0 || got.st_size != want.st_size) goto fail;
    if (rename(tmp, dst) != 0) goto fail;
    return 0;
fail:
    log_msg("replace %s failed errno=%d", dst, errno);
    unlink(tmp);
    return -1;
}

/* Drops the marked block from qb_runtime.sh and, if hook is given, appends that
 * file. One replacement: there is no moment with the hook half gone. */
int qb_rewrite(const char *hook) {
    FILE *in, *out;
    char tmp[MAX_PATH], line[MAX_LINE], text[8192];
    int skip = 0, rc, found = 0, depth = 0, n = 0, bad = 0;
    char last = '\n';
    if (!is_file(QB_RUNTIME)) return 0;
    if (hook) {
        n = read_file(hook, text, sizeof(text));
        if (n <= 0 || n >= (int)sizeof(text) - 1) return -1;
    }
    ensure_dirs();
    snprintf(tmp, sizeof(tmp), "%s/qb_runtime.new", NEMESIS_TMP);
    in = fopen(QB_RUNTIME, "r");
    out = fopen(tmp, "w");
    if (!in || !out) {
        if (in) fclose(in);
        if (out) fclose(out);
        return -1;
    }
    while (fgets(line, sizeof(line), in)) {
        char *t = line;
        if (strstr(line, QB_MARKER)) {
            found = 1;
            skip = 1;
            depth = 0;
            continue;
        }
        if (skip) {
            while (*t == ' ' || *t == '\t') t++;
            if (t[0] == 'i' && t[1] == 'f' &&
                (t[2] == ' ' || t[2] == '\t' || t[2] == '['))
                depth++;
            if (t[0] == 'f' && t[1] == 'i' &&
                (t[2] == 0 || t[2] == '\n' || t[2] == '\r' || t[2] == ' ' ||
                 t[2] == '\t' || t[2] == ';' || t[2] == '#')) {
                depth--;
                if (depth <= 0) skip = 0;
            }
            continue;
        }
        if (fputs(line, out) == EOF) bad = 1;
        if (line[0]) last = line[strlen(line) - 1];
    }
    if (ferror(in)) bad = 1;
    fclose(in);
    if (hook) {
        /* No blank line: stripping must give back the file byte for byte. */
        if (last != '\n' && fputc('\n', out) == EOF) bad = 1;
        if (fputs(text, out) == EOF) bad = 1;
        if (text[n - 1] != '\n' && fputc('\n', out) == EOF) bad = 1;
    }
    if (fflush(out) != 0 || ferror(out)) bad = 1;
    if (fclose(out) != 0) bad = 1;
    if (bad) {
        unlink(tmp);
        return -1;
    }
    rc = (found || hook) ? replace_keep_attrs(tmp, QB_RUNTIME) : 0;
    unlink(tmp);
    return rc;
}

/* The watchdog and svcmgr respawn each other and write into /data/nemesis.
 * A wipe asked for over the root socket runs in a worker forked from the
 * watchdog, so killing the watchdog does not end the wipe. */
static int daemon_role(pid_t pid) {
    char path[64], buf[256];
    int fd, n;
    snprintf(path, sizeof(path), "/proc/%d/cmdline", (int)pid);
    fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    n = (int)read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = 0;
    /* argv[0] then argv[1], NUL separated. */
    if (!strstr(buf, "nemesis")) return 0;
    n = (int)strlen(buf) + 1;
    if (!strcmp(buf + n, "watchdog") || !strcmp(buf + n, "svcmgr")) return 1;
    return 0;
}

/* Every watchdog and svcmgr, not only the ones in the pidfiles: an older
 * copy left by a restart would rewrite /data/nemesis after it is removed. */
void stop_daemons(void) {
    int round;
    for (round = 0; round < 3; round++) {
        DIR *d = opendir("/proc");
        struct dirent *e;
        int hit = 0;
        if (!d) return;
        while ((e = readdir(d))) {
            pid_t pid = (pid_t)atoi(e->d_name);
            if (pid <= 1 || pid == getpid() || pid == getppid()) continue;
            if (!pid_alive(pid) || !daemon_role(pid)) continue;
            kill(pid, SIGKILL);
            log_msg("stopped daemon pid=%d", (int)pid);
            hit++;
        }
        closedir(d);
        if (!hit) return;
        usleep(300000);
    }
}

/* Exit codes: 0 wiped (and rebooting), 1 core files left, 3 a package
 * uninstall failed and nothing past it was touched. */
int cmd_wipe(int argc, char **argv) {
    pkg_rec *list = NULL;
    int n = 0, i, confirm = 0, reboot = 1;
    for (i = 0; i < argc; i++) {
        if (!strcmp(argv[i], "--confirm")) confirm = 1;
        else if (!strcmp(argv[i], "--no-reboot")) reboot = 0;
    }
    if (!confirm) {
        fprintf(stderr, "usage: nemesis wipe --confirm [--no-reboot]\n");
        return 1;
    }
    ensure_dirs();
    log_msg("wipe begin");
    /* packages.db is in install order. */
    pkg_list_load(&list, &n);
    /* "wipe step ..." lines are the Manager's progress text. */
    for (i = 0; i < n; i++) {
        fprintf(stdout, "wipe step uninstall %s|%s\n", list[i].id, list[i].name);
        fflush(stdout);
        if (uninstall_tree(list[i].id, 1, 0) != 0) {
            log_msg("wipe blocked by %s", list[i].id);
            fprintf(stdout, "wipe blocked %s|%s\n", list[i].id, list[i].name);
            fflush(stdout);
            free(list);
            return 3;
        }
    }
    free(list);
    fprintf(stdout, "wipe step restore\n");
    fflush(stdout);
    stop_daemons();
    purge_manager_dalvik();
    if (remount_system(1) != 0)
        log_msg("wipe: remount /system rw failed errno=%d", errno);
    unlink(MANAGER_APK);
    unlink(INSTALL_RECOVERY);
    cacerts_remove_owned(NULL);
    qb_rewrite(NULL);
    unlink(BIN_PATH);
    log_msg("wipe: removing %s", NEMESIS_ROOT);
    fprintf(stdout, "wipe step remove\n");
    fflush(stdout);
    /* No log_msg past this point: it would recreate /data/nemesis. */
    rm_rf(NEMESIS_ROOT);
    rm_rf(NEMESIS_TMP);
    purge_manager_dalvik();
    if (path_exists(BIN_PATH) || path_exists(MANAGER_APK) || path_exists(INSTALL_RECOVERY)) {
        fprintf(stderr, "wipe incomplete: /system stayed read-only\n");
        fprintf(stdout, "wipe incomplete\n");
        fflush(stdout);
        return 1;
    }
    fprintf(stdout, "ok wiped\n");
    fflush(stdout);
    if (reboot) {
        sync();
        sleep(1);
        run_cmd("/system/bin/reboot");
    }
    return 0;
}
