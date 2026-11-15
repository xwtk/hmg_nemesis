#include "nemesis.h"

#include <signal.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>

static int looks_like_sys_remount(const char *cmd, int *rw_out) {
    size_t n;
    int saw_root = 0;
    if (!cmd || !strstr(cmd, "mount") || !strstr(cmd, "remount")) return 0;
    if (strstr(cmd, "/system")) {
        /* ok */
    } else {
        n = strlen(cmd);
        while (n && cmd[n - 1] == ' ') n--;
        if (n >= 2 && cmd[n - 2] == ' ' && cmd[n - 1] == '/') saw_root = 1;
        else if (n == 1 && cmd[0] == '/') saw_root = 1;
        if (!saw_root) return 0;
    }
    if (rw_out) {
        if (strstr(cmd, "rw")) *rw_out = 1;
        else if (strstr(cmd, "ro")) *rw_out = 0;
        else *rw_out = 1;
    }
    return 1;
}

int cmd_remount(int argc, char **argv) {
    int rw = 1;
    if (argc >= 1) {
        if (!strcmp(argv[0], "ro") || !strcmp(argv[0], "readonly")) rw = 0;
        else if (!strcmp(argv[0], "rw") || !strcmp(argv[0], "readwrite")) rw = 1;
        else {
            fprintf(stderr, "usage: nemesis remount [rw|ro]\n");
            return 1;
        }
    }
    if (remount_system(rw) != 0) {
        fprintf(stderr, "remount /system %s failed\n", rw ? "rw" : "ro");
        return 1;
    }
    printf("remount /system %s ok\n", rw ? "rw" : "ro");
    fflush(stdout);
    return 0;
}

int cmd_root(int argc, char **argv) {
    uid_t uid = 0;
    gid_t gid = 0;
    int i, have_c = 0;
    char cmd[MAX_LINE];
    cmd[0] = 0;
    for (i = 0; i < argc; i++) {
        if (!strcmp(argv[i], "-c") && i + 1 < argc) {
            have_c = 1;
            i++;
            snprintf(cmd, sizeof(cmd), "%s", argv[i]);
            i++;
            while (i < argc) {
                size_t L = strlen(cmd);
                snprintf(cmd + L, sizeof(cmd) - L, " %s", argv[i]);
                i++;
            }
            break;
        }
        if (!strcmp(argv[i], "--system")) {
            uid = 1000;
            gid = 1000;
            continue;
        }
        if (argv[i][0] >= '0' && argv[i][0] <= '9' && !strchr(argv[i], '/')) {
            uid = (uid_t)atoi(argv[i]);
            gid = uid;
            continue;
        }
        if (!have_c) {
            have_c = 1;
            snprintf(cmd, sizeof(cmd), "%s", argv[i]);
            i++;
            while (i < argc) {
                size_t L = strlen(cmd);
                snprintf(cmd + L, sizeof(cmd) - L, " %s", argv[i]);
                i++;
            }
            break;
        }
    }
    if (uid == 0 && have_c && cmd[0]) {
        int rw = 1;
        if (looks_like_sys_remount(cmd, &rw))
            return remount_system(rw) == 0 ? 0 : 1;
    }
    setgid(gid);
    setuid(uid);
    setenv("PATH", NEMESIS_PATH, 1);
    if (have_c && cmd[0]) {
        execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        return 127;
    }
    execl("/system/bin/sh", "sh", (char *)NULL);
    return 127;
}

int cmd_log(int argc, char **argv) {
    if (argc < 1) {
        fprintf(stderr, "usage: nemesis log dump|logcat|dmesg|service ...\n");
        return 1;
    }
    if (!strcmp(argv[0], "dump")) {
        char path[MAX_PATH], buf[4096];
        FILE *f;
        long sz, skip = 0;
        int n;
        snprintf(path, sizeof(path), "%s", NEMESIS_LOGFILE);
        f = fopen(path, "r");
        if (!f) {
            snprintf(path, sizeof(path), "%s/nemesis.log", NEMESIS_TMP);
            f = fopen(path, "r");
        }
        if (!f) {
            printf("(no log yet)\n");
            fflush(stdout);
            return 0;
        }
        if (fseek(f, 0, SEEK_END) == 0) {
            sz = ftell(f);
            if (sz > 128 * 1024) skip = sz - 128 * 1024;
            fseek(f, skip, SEEK_SET);
        }
        if (skip) printf("… (truncated)\n");
        while ((n = (int)fread(buf, 1, sizeof(buf), f)) > 0)
            fwrite(buf, 1, (size_t)n, stdout);
        fclose(f);
        fflush(stdout);
        return 0;
    }
    if (!strcmp(argv[0], "logcat")) {
        char *nargs[32];
        int i, n = 0;
        nargs[n++] = (char *)"logcat";
        for (i = 1; i < argc && n < 31; i++) nargs[n++] = argv[i];
        nargs[n] = NULL;
        execv("/system/bin/logcat", nargs);
        return 127;
    }
    if (!strcmp(argv[0], "dmesg")) {
        char *nargs[32];
        int i, n = 0;
        nargs[n++] = (char *)"dmesg";
        for (i = 1; i < argc && n < 31; i++) nargs[n++] = argv[i];
        nargs[n] = NULL;
        execv("/system/bin/dmesg", nargs);
        execv("/system/bin/toolbox", nargs);
        return 127;
    }
    if (!strcmp(argv[0], "service")) {
        char path[MAX_PATH], buf[4096];
        FILE *f;
        int n;
        if (argc < 2) {
            fprintf(stderr, "usage: nemesis log service <package-id>/<name>\n");
            return 1;
        }
        {
            char id[MAX_ID], local[MAX_NAME];
            qname_split(argv[1], id, sizeof(id), local, sizeof(local));
            snprintf(path, sizeof(path), "%s/%s/%s.log", NEMESIS_LOGS, id, local);
        }
        f = fopen(path, "r");
        if (!f) return 0;
        while ((n = (int)fread(buf, 1, sizeof(buf), f)) > 0) fwrite(buf, 1, (size_t)n, stdout);
        fclose(f);
        return 0;
    }
    fprintf(stderr, "unknown log source %s\n", argv[0]);
    return 1;
}

static void sanitize_extra(char *s) {
    char *p;
    if (!s) return;
    for (p = s; *p; p++) {
        unsigned char c = (unsigned char)*p;
        if (c < 32 || c == '\'' || c == '"' || c == ';' || c == '&' ||
            c == '|' || c == '`' || c == '$' || c == '\\' || c == '(' || c == ')')
            *p = '_';
    }
}

/* Bound wait: never stall USB Handler on a wedged Activity Manager. */
static int spawn_am(char *const argv[]) {
    pid_t pid = fork();
    int st;
    if (pid < 0) return -1;
    if (pid == 0) {
        alarm(8);
        execv("/system/bin/am", argv);
        _exit(127);
    }
    waitpid(pid, &st, 0);
    return 0;
}

static void add_es(char **argv, int *n, const char *key, const char *val) {
    if (!val || !val[0]) return;
    argv[(*n)++] = (char *)"--es";
    argv[(*n)++] = (char *)key;
    argv[(*n)++] = (char *)val;
}

static int spawn_manager(void) {
    char *argv[8];
    int n = 0;
    argv[n++] = (char *)"am";
    argv[n++] = (char *)"start";
    argv[n++] = (char *)"-n";
    argv[n++] = (char *)MANAGER_MAIN;
    argv[n++] = (char *)"-f";
    argv[n++] = (char *)"0x10000000"; /* FLAG_ACTIVITY_NEW_TASK */
    argv[n++] = NULL;
    return spawn_am(argv);
}

static int am_dialog(const char *mode, const char *path, const char *id, const char *name) {
    char m[64], pth[MAX_PATH], pidv[MAX_ID], nm[MAX_NAME];
    char *argv[24];
    int n = 0;
    snprintf(m, sizeof(m), "%s", mode ? mode : "");
    snprintf(pth, sizeof(pth), "%s", path ? path : "");
    snprintf(pidv, sizeof(pidv), "%s", id ? id : "");
    snprintf(nm, sizeof(nm), "%s", name ? name : "");
    sanitize_extra(m);
    sanitize_extra(pth);
    sanitize_extra(pidv);
    sanitize_extra(nm);
    log_msg("usb prompt %s", m);
    chmod(SESSION_DIR, 0777);
    argv[n++] = (char *)"am";
    argv[n++] = (char *)"start";
    argv[n++] = (char *)"-n";
    argv[n++] = (char *)MANAGER_DIALOG;
    add_es(argv, &n, "nemesis_mode", m);
    add_es(argv, &n, "nemesis_path", pth);
    add_es(argv, &n, "nemesis_id", pidv);
    add_es(argv, &n, "nemesis_name", nm);
    add_es(argv, &n, "session_dir", SESSION_DIR);
    argv[n++] = NULL;
    return spawn_am(argv);
}

static int rel_ok(const char *rel) {
    const char *p;
    if (!rel || !*rel) return 0;
    if (rel[0] == '/' || rel[0] == '\\') return 0;
    if (strstr(rel, "..")) return 0;
    for (p = rel; *p; p++) {
        if (*p == ';' || *p == '|' || *p == '&' || *p == '`' || *p == '$' ||
            *p == '\'' || *p == '"' || *p == '\n' || *p == '\r' ||
            *p == '(' || *p == ')' || *p == '<' || *p == '>')
            return 0;
    }
    return 1;
}

static int wait_reply(char *out, size_t cap, int seconds) {
    char path[MAX_PATH];
    int i;
    snprintf(path, sizeof(path), "%s/reply", SESSION_DIR);
    unlink(path);
    for (i = 0; i < seconds; i++) {
        if (!usb0_mounted()) {
            snprintf(out, cap, "unmount");
            return -1;
        }
        /* Empty: the writer has created the file but not filled it yet. */
        if (is_file(path) && read_file(path, out, cap) > 0) return 0;
        sleep(1);
    }
    snprintf(out, cap, "timeout");
    return -1;
}

static int reply_yes(const char *reply) {
    return reply && (!strncmp(reply, "yes", 3) || !strncmp(reply, "ok", 2) ||
                     !strncmp(reply, "recovery", 8));
}

static void log_handled(const char *type, const char *rel, const char *reply) {
    char line[MAX_LINE];
    log_msg("usb-handler handled insertion result=%s", reply ? reply : "");
    snprintf(line, sizeof(line), "handled type=%s path=%s reply=%s\n",
             type ? type : "", rel ? rel : "", reply ? reply : "");
    mkdir_p(NEMESIS_LOGS, 0755);
    append_file(NEMESIS_LOGS "/usb-session.log", line);
}

/* Manager Yes/No when Android is up. If the framework is down, execute/install
 * still run so a USB stick can recover the HU without Activity Manager. */
static int usb_confirm(const char *mode, const char *path, const char *id,
                       const char *name, char *reply, size_t cap) {
    if (!framework_up()) {
        snprintf(reply, cap, "recovery");
        log_msg("android down; auto-confirm %s", mode);
        return 1;
    }
    am_dialog(mode, path, id, name);
    if (wait_reply(reply, cap, USB_PROMPT_SEC) != 0) return 0;
    return reply_yes(reply);
}

static void usb_notify(const char *mode, const char *path, const char *id,
                       const char *name, char *reply, size_t cap) {
    if (!framework_up()) {
        snprintf(reply, cap, "skip-ui");
        log_msg("android down; skip UI %s", mode);
        return;
    }
    am_dialog(mode, path, id, name);
    wait_reply(reply, cap, USB_PROMPT_SEC);
}

static const char *usb_roots[] = {
    USB0, USB0_MNT, USB1, USB1_MNT, USB2, USB2_MNT
};

static int usb_meta_path(char *out, size_t cap) {
    int i;
    for (i = 0; i < 6; i++) {
        snprintf(out, cap, "%s/NEMESIS.data", usb_roots[i]);
        if (is_file(out)) return 0;
    }
    return -1;
}

static const char *usb_root_for(const char *meta) {
    int i;
    if (!meta) return USB0;
    for (i = 0; i < 6; i++) {
        size_t n = strlen(usb_roots[i]);
        if (!strncmp(meta, usb_roots[i], n) && meta[n] == '/') return usb_roots[i];
    }
    return USB0;
}

/* Flag file on the stick root: lets the handler act while Android is down. */
static int usb_emergency(const char *root) {
    char path[MAX_PATH];
    snprintf(path, sizeof(path), "%s/NEMESIS.emergency", root);
    return is_file(path);
}

static void do_execute(const char *full, const char *root) {
    int rc;
    char nreply[128];
    if (!is_file(full)) {
        rc = 1;
        log_msg("execute missing %s", full);
    } else {
        rc = run_script(full, root, "root");
        log_msg("execute %s rc=%d", full, rc);
    }
    {
        char extra[64];
        snprintf(extra, sizeof(extra), "%d", rc);
        usb_notify(rc == 0 ? "result_ok" : "result_fail", full, extra, extra, nreply, sizeof(nreply));
    }
}

static void do_install(const char *full, char *reply, size_t cap) {
    char info_id[MAX_ID] = "", info_name[MAX_NAME] = "";
    char nreply[128];
    pkg_rec rec;
    int inst, locked = 0;
    ini_file zini;
    memset(&zini, 0, sizeof(zini));
    if (is_file(full)) {
        char text[8192];
        if (zip_extract_file(full, "NEMESIS.data", text, sizeof(text)) == 0 &&
            ini_load_mem(&zini, text) == 0) {
            const char *v = ini_get(&zini, "nemesis", "id");
            const char *n = ini_get(&zini, "nemesis", "name");
            snprintf(info_id, sizeof(info_id), "%s", v ? v : "");
            snprintf(info_name, sizeof(info_name), "%s", n ? n : info_id);
            ini_free(&zini);
        }
    }
    inst = info_id[0] && pkg_find(info_id, &rec) == 0;
    if (inst) locked = rec.locked;
    if (inst && locked) {
        usb_notify("forbidden", full, info_id, info_name, reply, cap);
        return;
    }
    /* Android is down: no dialog, keep the recovery update. */
    if (inst && !framework_up()) {
        char cmd[MAX_PATH + 64];
        int rc;
        snprintf(cmd, sizeof(cmd), "%s install --update --force '%s'", BIN_PATH, full);
        rc = run_cmd(cmd);
        if (reply && cap) snprintf(reply, cap, "recovery");
        usb_notify(rc == 0 ? "result_ok" : "result_fail", full, info_id, info_name,
                   nreply, sizeof(nreply));
        return;
    }
    /* Installed, Android up: replace in place, so packages depending on this one stay valid. */
    if (inst) {
        if (!usb_confirm("confirm_reinstall", full, info_id, info_name, reply, cap)) return;
    } else if (!usb_confirm("confirm_install", full, info_id, info_name, reply, cap)) {
        return;
    }
    {
        char cmd[MAX_PATH + 64];
        const char *upd = inst ? "--update " : "";
        int rc;
        snprintf(cmd, sizeof(cmd), "%s install %s'%s'", BIN_PATH, upd, full);
        rc = run_cmd(cmd);
        /* Not marked compatible: only a user on screen may accept the risk. */
        if (rc == 20 && framework_up()) {
            char rreply[128];
            am_dialog("confirm_risk", full, info_id, info_name);
            if (wait_reply(rreply, sizeof(rreply), USB_PROMPT_SEC) == 0 && reply_yes(rreply)) {
                log_msg("install %s: user accepted compatibility risk", info_id);
                snprintf(cmd, sizeof(cmd), "%s install %s--accept-risk '%s'", BIN_PATH, upd, full);
                rc = run_cmd(cmd);
            } else {
                log_msg("install %s: compatibility risk declined", info_id);
                return;
            }
        }
        /* Dependents, dependencies or conflicts: Manager explains and offers the fix. */
        if (rc >= 21 && rc <= 23) {
            usb_notify("install_blocked", full, info_id, info_name, nreply, sizeof(nreply));
            return;
        }
        usb_notify(rc == 0 ? "result_ok" : "result_fail", full, info_id, info_name,
                   nreply, sizeof(nreply));
    }
}

/* type=update: a NEMESIS core update zip. Runs before any other type.
 * Same or older than the running core: report, do not prompt. */
static void do_update(const char *full, char *reply, size_t cap) {
    ini_file zi;
    pkg_meta m;
    char cmd[MAX_PATH + 64], nreply[128];
    int rc;
    if (!is_file(full) || load_zip_meta(full, &zi, &m) != 0) {
        usb_notify("invalid", full, "", "", reply, cap);
        return;
    }
    ini_free(&zi);
    if (strcmp(m.id, CORE_ID) || !m.pkg_ver[0]) {
        log_msg("usb update: %s is not a NEMESIS update", full);
        usb_notify("invalid", full, "", "", reply, cap);
        return;
    }
    if (ver_cmp(m.pkg_ver, NEMESIS_VERSION) <= 0) {
        log_msg("usb update: %s is not newer than %s", m.pkg_ver, NEMESIS_VERSION);
        usb_notify("up_to_date", full, CORE_ID, NEMESIS_VERSION, reply, cap);
        if (!framework_up()) snprintf(reply, cap, "up-to-date");
        return;
    }
    if (!usb_confirm("confirm_update", full, CORE_ID, m.pkg_ver, reply, cap)) return;
    log_msg("usb update: installing %s", m.pkg_ver);
    snprintf(cmd, sizeof(cmd), "%s update '%s'", BIN_PATH, full);
    rc = run_cmd(cmd);
    if (rc == 20 && framework_up()) {
        char rreply[128];
        am_dialog("confirm_risk", full, CORE_ID, m.pkg_ver);
        if (wait_reply(rreply, sizeof(rreply), USB_PROMPT_SEC) != 0 || !reply_yes(rreply)) {
            log_msg("usb update: compatibility risk declined");
            return;
        }
        snprintf(cmd, sizeof(cmd), "%s update --accept-risk '%s'", BIN_PATH, full);
        rc = run_cmd(cmd);
    }
    /* On success the unit is rebooting by now. */
    log_msg("usb update: rc=%d", rc);
    usb_notify(rc == 0 ? "result_ok" : "result_fail", full, CORE_ID, m.pkg_ver, nreply, sizeof(nreply));
}

/* type=list: the Store on the stick's package list. Needs the Manager. */
static void do_list(const char *meta, char *reply, size_t cap) {
    char m[MAX_PATH];
    char *argv[16];
    int n = 0;
    snprintf(m, sizeof(m), "%s", meta);
    sanitize_extra(m);
    argv[n++] = (char *)"am";
    argv[n++] = (char *)"start";
    argv[n++] = (char *)"-n";
    argv[n++] = (char *)MANAGER_STORE;
    argv[n++] = (char *)"-f";
    argv[n++] = (char *)"0x10000000";
    add_es(argv, &n, "store_mode", "usb");
    add_es(argv, &n, "store_list", m);
    argv[n++] = NULL;
    spawn_am(argv);
    snprintf(reply, cap, "store");
    log_msg("usb list: store opened on %s", meta);
}

static int type_has(const char *types, const char *want) {
    const char *p = types;
    char item[64];
    if (!types) return 0;
    while (meta_list_next(&p, item, sizeof(item)))
        if (!strcmp(item, want)) return 1;
    return 0;
}

typedef struct {
    unsigned long long dev;
    unsigned long long ino;
    unsigned long long size;
    unsigned long mtime;
} usb_ident;

static int usb_ident_of(const char *path, usb_ident *id) {
    struct stat st;
    if (!id) return -1;
    memset(id, 0, sizeof(*id));
    if (stat(path, &st) != 0) return -1;
    id->dev = (unsigned long long)st.st_dev;
    id->ino = (unsigned long long)st.st_ino;
    id->size = (unsigned long long)st.st_size;
    id->mtime = (unsigned long)st.st_mtime;
    return 0;
}

static int usb_ident_eq(const usb_ident *a, const usb_ident *b) {
    return a && b && a->dev == b->dev && a->ino == b->ino &&
           a->size == b->size && a->mtime == b->mtime;
}

/* usb-info: what the Manager needs to know about the stick, key=value.
 * --list prints the descriptor itself, for the Store. */
int cmd_usb_info(int list) {
    char meta[MAX_PATH];
    ini_file ini;
    const char *type, *rel, *root;
    if (!usb0_mounted() || usb_meta_path(meta, sizeof(meta)) != 0) {
        printf("mounted=%d\n", usb0_mounted() ? 1 : 0);
        fflush(stdout);
        return 1;
    }
    root = usb_root_for(meta);
    if (list) {
        char buf[4096];
        size_t n;
        FILE *f = fopen(meta, "r");
        if (!f) return 1;
        while ((n = fread(buf, 1, sizeof(buf), f)) > 0) fwrite(buf, 1, n, stdout);
        fclose(f);
        fflush(stdout);
        return 0;
    }
    printf("mounted=1\nmeta=%s\nroot=%s\n", meta, root);
    if (ini_load(&ini, meta) != 0) {
        fflush(stdout);
        return 1;
    }
    type = ini_get(&ini, "nemesis", "type");
    rel = ini_get(&ini, "nemesis", "path");
    printf("type=%s\n", type ? type : "");
    if (rel && rel_ok(rel)) printf("path=%s/%s\n", root, rel);
    ini_free(&ini);
    fflush(stdout);
    return 0;
}

int cmd_usb_handler(void) {
    int handled = 0;
    int wait_type = 0;
    int have_ident = 0;
    int waiting = 0;
    usb_ident last_id;
    signal(SIGHUP, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    ensure_dirs();
    memset(&last_id, 0, sizeof(last_id));
    log_msg("usb-handler start (android %s)", framework_up() ? "up" : "down");
    for (;;) {
        char meta[MAX_PATH];
        usb_ident cur;
        int mounted = usb0_mounted();
        chmod(SESSION_DIR, 0777);
        if (!mounted) {
            if (handled) log_msg("usb-handler rearm (unmounted)");
            handled = 0;
            have_ident = 0;
            wait_type = 0;
            waiting = 0;
            sleep(1);
            continue;
        }
        if (usb_meta_path(meta, sizeof(meta)) != 0) {
            if (handled) log_msg("usb-handler rearm (descriptor gone)");
            handled = 0;
            have_ident = 0;
            wait_type = 0;
            waiting = 0;
            sleep(1);
            continue;
        }
        if (usb_ident_of(meta, &cur) == 0) {
            if (handled && have_ident && usb_ident_eq(&cur, &last_id)) {
                sleep(1);
                continue;
            }
            if (handled && have_ident)
                log_msg("usb-handler rearm (descriptor changed)");
            handled = 0;
        } else if (handled) {
            sleep(1);
            continue;
        }
        /*
         * Android down: act only for a stick that carries NEMESIS.emergency.
         * Anything else waits for Android and is then prompted as usual, so a
         * stick left plugged in does not run again on the next boot.
         */
        if (!framework_up() && !usb_emergency(usb_root_for(meta))) {
            if (!waiting) log_msg("usb: android down, no NEMESIS.emergency; waiting for Android");
            waiting = 1;
            sleep(1);
            continue;
        }
        if (waiting) log_msg("usb: %s; handling stick", framework_up() ? "android up" : "NEMESIS.emergency found");
        waiting = 0;
        {
            char reply[128];
            ini_file ini;
            const char *type, *rel, *root;
            int is_exec, is_inst, is_open, is_upd, is_list;
            reply[0] = 0;
            mkdir_p(SESSION_DIR, 0755);
            chmod(SESSION_DIR, 0777);
            if (ini_load(&ini, meta) != 0) {
                usb_notify("invalid", meta, "", "", reply, sizeof(reply));
                handled = 1;
                have_ident = usb_ident_of(meta, &last_id) == 0;
                log_handled("", "", reply);
                continue;
            }
            type = ini_get(&ini, "nemesis", "type");
            rel = ini_get(&ini, "nemesis", "path");
            root = usb_root_for(meta);
            /* Incomplete copy: wait rather than locking this insertion as invalid. */
            if (!type || !*type) {
                ini_free(&ini);
                wait_type++;
                if (wait_type < 3) {
                    sleep(1);
                    continue;
                }
                wait_type = 0;
                usb_notify("invalid", meta, "", "", reply, sizeof(reply));
                handled = 1;
                have_ident = usb_ident_of(meta, &last_id) == 0;
                log_handled("", "", reply);
                continue;
            }
            wait_type = 0;
            is_upd = type_has(type, "update");
            is_list = !is_upd && type_has(type, "list");
            /* The Store needs the Manager, even on an emergency stick. */
            if (is_list && !framework_up()) {
                ini_free(&ini);
                if (!waiting) log_msg("usb: type=list waits for Android");
                waiting = 1;
                sleep(1);
                continue;
            }
            log_msg("usb descriptor %s type=%s", meta, type);
            is_exec = !is_upd && !strcmp(type, "execute");
            is_inst = !is_upd && !strcmp(type, "install");
            is_open = !is_upd && !strcmp(type, "open");
            if (is_upd || is_list) {
                if (is_upd && !rel_ok(rel)) {
                    usb_notify("invalid", meta, "", "", reply, sizeof(reply));
                } else if (is_upd) {
                    char full[MAX_PATH];
                    snprintf(full, sizeof(full), "%s/%s", root, rel);
                    do_update(full, reply, sizeof(reply));
                } else {
                    do_list(meta, reply, sizeof(reply));
                }
                handled = 1;
                have_ident = usb_ident_of(meta, &last_id) == 0;
                log_handled(is_upd ? "update" : "list", rel, reply);
                ini_free(&ini);
                sleep(1);
                continue;
            }
            if (!is_exec && !is_inst && !is_open) {
                usb_notify("invalid", meta, "", "", reply, sizeof(reply));
                handled = 1;
                have_ident = usb_ident_of(meta, &last_id) == 0;
                log_handled(type, rel, reply);
                ini_free(&ini);
                continue;
            }
            if ((is_exec || is_inst) && !rel_ok(rel)) {
                usb_notify("invalid", meta, "", "", reply, sizeof(reply));
                handled = 1;
                have_ident = usb_ident_of(meta, &last_id) == 0;
                log_handled(type, rel, reply);
                ini_free(&ini);
                continue;
            }
            if (is_open) {
                if (!framework_up()) {
                    snprintf(reply, sizeof(reply), "skip-ui");
                    log_msg("android down; cannot open Manager");
                } else {
                    spawn_manager();
                    snprintf(reply, sizeof(reply), "opened");
                    log_msg("open manager spawned");
                }
                handled = 1;
                have_ident = usb_ident_of(meta, &last_id) == 0;
                log_handled(type, rel, reply);
                ini_free(&ini);
                sleep(1);
                continue;
            }
            {
                char full[MAX_PATH];
                snprintf(full, sizeof(full), "%s/%s", root, rel);
                if (is_exec) {
                    if (usb_confirm("confirm_execute", full, "", "", reply, sizeof(reply)))
                        do_execute(full, root);
                } else {
                    do_install(full, reply, sizeof(reply));
                }
            }
            handled = 1;
            have_ident = usb_ident_of(meta, &last_id) == 0;
            /* type and rel point into ini: free it after they are logged. */
            log_handled(type, rel, reply);
            ini_free(&ini);
        }
        sleep(1);
    }
}
