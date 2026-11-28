#include "nemesis.h"

#include <dirent.h>
#include <signal.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/wait.h>
#include <time.h>

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
    int rw = 1, bypass = 0, i;
    for (i = 0; i < argc; i++) {
        if (!strcmp(argv[i], "ro") || !strcmp(argv[i], "readonly")) rw = 0;
        else if (!strcmp(argv[i], "rw") || !strcmp(argv[i], "readwrite")) rw = 1;
        else if (!strcmp(argv[i], "--bypass-livepatch")) bypass = 1;
        else {
            fprintf(stderr, "usage: nemesis remount [rw|ro] [--bypass-livepatch]\n");
            return 1;
        }
    }
    /* A writer that asks for it gets the real directories under LivePatch's read-only farms. Any `ro`
     * puts them back (nothing to do when none stepped aside), so a plain `ro` can't leave them down. */
    if (rw && bypass) lp_step_aside("nemesis remount rw --bypass-livepatch");
    if (!rw) lp_step_back("nemesis remount ro");
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
int spawn_am(char *const argv[]) {
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

void add_es(char **argv, int *n, const char *key, const char *val) {
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

/* The HU has one USB port. */
static const char *usb_roots[] = {USB0, USB0_MNT};
#define N_USB_ROOTS 2

static int usb_meta_path(char *out, size_t cap) {
    int i;
    for (i = 0; i < N_USB_ROOTS; i++) {
        snprintf(out, cap, "%s/NEMESIS.data", usb_roots[i]);
        if (is_file(out)) return 0;
    }
    return -1;
}

static const char *usb_root_for(const char *meta) {
    int i;
    if (!meta) return USB0;
    for (i = 0; i < N_USB_ROOTS; i++) {
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
    char info_id[MAX_ID] = "", info_name[MAX_NAME] = "", info_ver[32] = "";
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
            v = ini_get(&zini, "nemesis", "package-version");
            snprintf(info_ver, sizeof(info_ver), "%s", v ? v : "");
            ini_free(&zini);
        }
    }
    /* The Manager's install_blocked popup says why; install refuses it anyway (exit 27). */
    if (info_id[0] && framework_up() && meta_blacklisted(info_id, info_ver)) {
        log_msg("usb install %s %s: blacklisted", info_id, info_ver);
        usb_notify("install_blocked", full, info_id, info_name, reply, cap);
        return;
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
    /* Copied into the queue, so the stick may come out; Package Manager shows
     * the progress, the risk prompt and any block reason. */
    {
        char cmd[MAX_PATH + 64], out[256], idv[MAX_ID];
        char *argv[16];
        int n = 0, rc;
        snprintf(cmd, sizeof(cmd), "%s queue add '%s'", BIN_PATH, full);
        rc = run_cmd_capture(cmd, out, sizeof(out));
        log_msg("usb install %s: queue rc=%d %s", info_id, rc, out);
        if (rc != 0) {
            usb_notify("result_fail", full, info_id, info_name, nreply, sizeof(nreply));
            return;
        }
        snprintf(idv, sizeof(idv), "%s", info_id);
        sanitize_extra(idv);
        argv[n++] = (char *)"am";
        argv[n++] = (char *)"start";
        argv[n++] = (char *)"-n";
        argv[n++] = (char *)MANAGER_PKGMGR;
        argv[n++] = (char *)"-f";
        argv[n++] = (char *)"0x10000000";
        add_es(argv, &n, "focus_id", idv);
        argv[n++] = NULL;
        spawn_am(argv);
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

/* ---- Android down: NEMESIS mounts the stick itself ----
 * vold mounts usb0 only when system_server asks, so with the zygote dead an emergency stick was never
 * mounted. Once Android has been down SELF_MOUNT_SEC and system_server is gone, the handler puts the
 * port back in host mode and mounts the stick at USB0 as vold would; a stick without
 * NEMESIS.emergency is unmounted at once.
 * shortcut: if Android comes up while NEMESIS holds the stick, vold may fsck and mount over it;
 * emergency sticks end in a reboot, revisit if one is meant to run while Android boots. */
/* HU 260507 healthy boot: handler at 2.8 s, system_server at 6.2 s; the odexed bootclasspath means
 * zygote never compiles first, so system_server is up within seconds or not at all. ~6x margin. */
#define SELF_MOUNT_SEC 20

static char self_dev[64]; /* block device NEMESIS mounted at USB0, "" = none */
static char tried_dev[64]; /* already probed this insertion */

/* /dev/block/sdX1, or sdX for a stick with no partition table. Internal storage is mmcblk, the
 * CD drive tcr0, so sd* is the USB port. */
static int usb_block_dev(char *out, size_t cap) {
    DIR *d = opendir("/sys/block");
    struct dirent *de;
    int rc = -1;
    if (!d) return -1;
    while (rc && (de = readdir(d))) {
        char part[MAX_PATH];
        if (strncmp(de->d_name, "sd", 2)) continue;
        snprintf(part, sizeof(part), "/sys/block/%s/%s1", de->d_name, de->d_name);
        snprintf(out, cap, "/dev/block/%s%s", de->d_name, is_dir(part) ? "1" : "");
        rc = 0;
    }
    closedir(d);
    return rc;
}

/* A device-mode USB config persisted in /data turns VBUS off until Android's USB service switches
 * back to host, so a dead zygote leaves the stick unpowered. host,adb is default.prop's setting.
 * A PC attached in device mode (USB adb) keeps the port. */
static void usb_host_mode(void) {
    static int done;
    char mode[PROP_VALUE_MAX] = "", state[32] = "";
    if (done) return;
    done = 1;
    __system_property_get("sys.usb.mode", mode);
    if (!strcmp(mode, "usb_host")) return;
    read_file("/sys/class/android_usb/android0/state", state, sizeof(state));
    if (!strncmp(state, "CONFIGURED", 10)) return;
    log_msg("usb: android down, port in %s mode; switching to host", mode[0] ? mode : "unknown");
    __system_property_set("sys.usb.config", "host,adb");
}

static void usb_self_mount(void) {
    static const char *const fs[] = {"vfat", "texfat", "tntfs"}; /* what the HU's vold mounts */
    char dev[64];
    int i;
    if (usb_block_dev(dev, sizeof(dev)) != 0) {
        tried_dev[0] = 0;
        usb_host_mode();
        return;
    }
    if (!strcmp(dev, tried_dev) || !path_exists(dev)) return; /* ueventd may not have made the node yet */
    snprintf(tried_dev, sizeof(tried_dev), "%s", dev);
    mkdir_p(USB0, 0755);
    for (i = 0; i < 3; i++)
        if (mount(dev, USB0, fs[i], MS_NOSUID | MS_NODEV | MS_NOATIME | MS_SYNCHRONOUS, NULL) == 0) break;
    if (i == 3) {
        log_msg("usb: android down, cannot mount %s as vfat, texfat or tntfs (last errno=%d)", dev, errno);
        return;
    }
    if (!usb_emergency(USB0)) {
        umount(USB0);
        log_msg("usb: android down, %s has no NEMESIS.emergency; left unmounted", dev);
        return;
    }
    snprintf(self_dev, sizeof(self_dev), "%s", dev);
    log_msg("usb: android down, mounted %s (%s) at %s", dev, fs[i], USB0);
}

/* The stick NEMESIS mounted was pulled out. */
static void usb_self_release(void) {
    char dev[64];
    if (!self_dev[0] || (usb_block_dev(dev, sizeof(dev)) == 0 && !strcmp(dev, self_dev))) return;
    umount2(USB0, MNT_DETACH);
    log_msg("usb: released %s", self_dev);
    self_dev[0] = 0;
}

static long mono_sec(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long)t.tv_sec;
}

/* MountService lives in system_server. sys.boot_completed alone is no proof: NEMESIS clears it for
 * every APK install (pm_install_apk) while Android is up. */
static int system_server_running(void) {
    DIR *d = opendir("/proc");
    struct dirent *de;
    int found = 0;
    if (!d) return 1;
    while (!found && (de = readdir(d)))
        if (de->d_name[0] >= '1' && de->d_name[0] <= '9')
            found = proc_cmdline_has((pid_t)atoi(de->d_name), "system_server");
    closedir(d);
    return found;
}

int cmd_usb_handler(void) {
    int handled = 0;
    int wait_type = 0;
    int have_ident = 0;
    int waiting = 0;
    long down_since = -1; /* when sys.boot_completed last stopped being 1 */
    usb_ident last_id;
    signal(SIGHUP, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    ensure_dirs();
    memset(&last_id, 0, sizeof(last_id));
    log_msg("usb-handler start (android %s)", framework_up() ? "up" : "down");
    for (;;) {
        char meta[MAX_PATH];
        usb_ident cur;
        int mounted;
        usb_self_release();
        mounted = usb0_mounted();
        if (framework_up()) down_since = -1;
        else if (down_since < 0) down_since = mono_sec();
        if (!mounted && down_since >= 0 && mono_sec() - down_since >= SELF_MOUNT_SEC && !system_server_running()) {
            usb_self_mount();
            mounted = usb0_mounted();
        }
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
