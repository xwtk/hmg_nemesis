#ifndef NEMESIS_H
#define NEMESIS_H

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define NEMESIS_VERSION "1.17"
#define NEMESIS_ROOT "/data/nemesis"
#define NEMESIS_APPS "/data/nemesis/apps"
#define NEMESIS_TMP "/data/local/tmp/nemesis"
#define NEMESIS_LOGS "/data/nemesis/logs"
#define NEMESIS_LOGFILE "/data/nemesis/nemesis.log"
#define NEMESIS_BINDIR "/data/nemesis/bin"
#define NEMESIS_PATH "/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin"
#define PACKAGES_DB "/data/nemesis/packages.db"
#define SERVICES_DB "/data/nemesis/services.db"
#define PIDS_DIR "/data/nemesis/pids"
#define SESSION_DIR "/data/nemesis/usb-session"
/* Live install/uninstall UI, written by hooks via `nemesis ui`, polled by the
 * Manager popup. In SESSION_DIR because that dir is world-rwx, so the app uid
 * can read them on both the USB and in-app paths. */
#define UI_STATUS SESSION_DIR "/ui.status"
#define UI_PROGRESS SESSION_DIR "/ui.progress"
#define UI_ERROR SESSION_DIR "/ui.error"
#define USB0 "/storage/usb0"
#define USB0_MNT "/mnt/media_rw/usb0"
#define LOCKED_ID "usb-handler"
#define MONITOR_ID "nemesis.update-monitor"
/* Holds the boot_id of the boot that already announced Android up. */
#define ANDROID_UP_MARK NEMESIS_ROOT "/android-up"
#define ANDROID_UP_SEC 5
#define MANAGER_EVENTS "com.nemesis.manager/.EventReceiver"
#define BIN_PATH "/system/xbin/nemesis"
#define INSTALL_RECOVERY "/system/etc/install-recovery.sh"
#define QB_RUNTIME "/system/etc/qb_runtime.sh"
#define QB_MARKER "NEMESIS_QB_HOOK"
#define MANAGER_PKG "com.nemesis.manager"
#define MANAGER_DIALOG "com.nemesis.manager/.DialogActivity"
#define MANAGER_MAIN "com.nemesis.manager/.MainActivity"
#define MANAGER_STORE "com.nemesis.manager/.StoreActivity"
#define MANAGER_PKGMGR "com.nemesis.manager/.PackageManagerActivity"
#define MANAGER_APK "/system/app/NEMESIS.apk"
#define CACERTS_DIR "/system/etc/security/cacerts"
#define OWNED_CACERTS "/data/nemesis/owned-cacerts"
#define UPDATE_DIR "/data/nemesis/update"
#define CORE_ID "nemesis.core"
#define WD_PIDFILE "/data/nemesis/watchdog.pid"
#define SM_PIDFILE "/data/nemesis/svcmgr.pid"
#define SU_SOCK_DEV "/dev/nemesis.sock"
#define SU_SOCK_DATA "/data/nemesis/root.sock"
/* Install queue: <seq>.job/.zip/.out/.hook, read directly by the Manager. */
#define QUEUE_DIR "/data/nemesis/queue"
#define REBOOT_REQ QUEUE_DIR "/reboot.req"

#define MAX_PATH 512
#define MAX_LINE 1024
#define MAX_ID 96
#define MAX_NAME 128
#define MAX_QNAME 192
#define RESTART_LIMIT 5
/* A restarted service that stays up this long is healthy again. */
#define SVC_STABLE_SEC 10
#define USB_PROMPT_SEC 60
#define META_SHORT_MAX 64
#define META_DETAIL_MAX 256
#define META_LIST_MAX 1024
#define META_VERSION "1.2"
#define FAVICON_MAX (256 * 1024)
#define COMPAT_FULL 0
#define COMPAT_PARTIAL 1
#define COMPAT_NONE 2

typedef struct {
    char section[80];
    char key[80];
    char val[2048];
} ini_kv;

typedef struct {
    ini_kv *items;
    int n, cap;
    char err[96];
} ini_file;

typedef struct {
    char id[MAX_ID];
    char name[MAX_NAME];
    int locked;
    char types[80];
} pkg_rec;

typedef struct {
    char qname[MAX_QNAME];
    char kind[16];
    char path[MAX_PATH];
    char uid[16];
    char mode[16];
} svc_rec;

int log_msg(const char *fmt, ...);
int ensure_dirs(void);
int mkdir_p(const char *path, mode_t mode);
int is_dir(const char *path);
int is_file(const char *path);
int path_exists(const char *path);
int copy_file(const char *src, const char *dst, mode_t mode);
int copy_tree(const char *src, const char *dst);
int rm_rf(const char *path);
int read_file(const char *path, char *buf, size_t cap);
int write_file(const char *path, const char *data, mode_t mode);
int append_file(const char *path, const char *data);
int remount_system(int rw);
int run_cmd(const char *cmd);
/* Combined stdout+stderr in out. Returns the process exit status. */
int run_cmd_capture(const char *cmd, char *out, size_t cap);
void purge_manager_dalvik(void);
int run_script(const char *script, const char *cwd, const char *uid_name);
int pid_alive(pid_t pid);
int proc_cmdline_has(pid_t pid, const char *needle);
int write_pidfile(const char *path, pid_t pid);
pid_t read_pidfile(const char *path);
/* 1 if pidfile still refers to this process (start time, or cmdline for old files). */
int pidfile_is(const char *path, const char *needle);
int pidfile_lock(const char *path);
/* Seconds since pid started, or -1. */
long proc_age_sec(pid_t pid);
int usb0_mounted(void);
/* Per-boot random token, 32 hex chars. */
int boot_token(char *out, size_t cap);
int framework_up(void);
int detach_daemon(void);
void daemon_ready(void);
void qname_join(char *out, size_t cap, const char *id, const char *local);
int qname_split(const char *qname, char *id, size_t idcap, char *local, size_t lcap);
void pid_path_for(char *out, size_t cap, const char *qname);

typedef struct {
    char meta_ver[16];
    char id[MAX_ID];
    char name[MAX_NAME];
    char types[80];
    char pkg_ver[32];
    char short_desc[META_SHORT_MAX * 4 + 1];
    char detailed_desc[META_DETAIL_MAX * 4 + 1];
    char depends[512];
    char os[META_LIST_MAX + 1];
    char models[META_LIST_MAX + 1];
    /* 1.2: image path under the package root. */
    char favicon[160];
} pkg_meta;

/* Fills `ver` with the installed version of `id`; returns 1 when installed. */
typedef int (*meta_lookup_fn)(const char *id, char *ver, size_t cap);

int meta_parse(const ini_file *ini, pkg_meta *m, char *err, size_t ecap);
int meta_list_next(const char **p, char *out, size_t cap);
int meta_list_match(const char *list, const char *value);
int meta_depends_on(const char *deps, const char *id);
const char *meta_dep_spec(const char *deps, const char *id, char *spec, size_t cap);
const char *meta_conflict_spec(const char *deps, const char *id, char *spec, size_t cap);
int meta_deps_unmet(const pkg_meta *m, meta_lookup_fn lookup, char *reasons, size_t cap);
int meta_too_new(const pkg_meta *m);
int meta_compat(const pkg_meta *m, const char *os_id, const char *model,
                meta_lookup_fn lookup, char *reasons, size_t cap);
const char *compat_name(int c);
void meta_escape_field(const char *in, char *out, size_t cap);
int ver_valid(const char *v);
int ver_cmp(const char *a, const char *b);
int dep_spec_match(const char *spec, const char *have);
int dep_split(const char *item, char *id, size_t idcap, char *spec, size_t scap);
int glob_match(const char *pat, const char *s);

int ini_load(ini_file *ini, const char *path);
int ini_load_mem(ini_file *ini, const char *text);
void ini_free(ini_file *ini);
const char *ini_get(const ini_file *ini, const char *sec, const char *key);
int ini_has_section(const ini_file *ini, const char *sec);

int zip_extract(const char *zip, const char *dest);
int zip_extract_file(const char *zip, const char *member, char *out, size_t cap);
int zip_extract_bin(const char *zip, const char *member, unsigned char *out, size_t cap, size_t *len);

int pkg_list_load(pkg_rec **out, int *n);
int pkg_find(const char *id, pkg_rec *out);
int pkg_save_all(pkg_rec *list, int n);
int pkg_upsert(const pkg_rec *p);
int pkg_remove(const char *id);
int svc_list_load(svc_rec **out, int *n);
int svc_save_all(svc_rec *list, int n);
int svc_remove_prefix(const char *id);
int cmd_install(int argc, char **argv);
int cmd_uninstall(int argc, char **argv);
int cmd_ui(int argc, char **argv);
void ui_reset(void);
int cmd_wipe(int argc, char **argv);
int cmd_pkg_info(const char *arg);
int cmd_compat(const char *arg);
int cmd_pkg_list(void);
int cmd_pkg_launch(const char *id);
int seed_core_packages(void);
int load_zip_meta(const char *zip, ini_file *ini, pkg_meta *m);
int pkg_compat(const pkg_meta *m, char *reasons, size_t cap);
int package_root(const char *stage, char *out, size_t cap);
int replace_keep_attrs(const char *src, const char *dst);
int qb_rewrite(const char *hook);
void stop_daemons(void);
int cmd_update(int argc, char **argv);
int cacerts_install(const char *dir, char *added, size_t cap);
int cmd_cacerts_install(const char *dir);
int cmd_usb_info(int list);
int cmd_compat_check(int argc, char **argv);
int cacerts_remove_owned(const char *only);
void md5_file_hex(const char *path, char out[33]);
void md5_str_hex(const char *s, char out[33]);

int cmd_service(int argc, char **argv);
int cmd_watchdog(void);
int cmd_livepatch(int argc, char **argv);
int cmd_boot_apply(void);
void lp_boot_ok(void);
int lp_wrapper_install(void);
int lp_wrapper_remove(void);
int lp_check(const ini_file *ini, const char *id);
int lp_prepare(const char *id, const ini_file *ini, const char *name, const char *version, const char *stage);
int lp_commit(const char *id);
void lp_swap_flush(void);
void lp_finish_remove(const char *id);
void lp_discard(const char *id);
int lp_damaged_take(char *buf, size_t cap);
void lp_damaged_clear(void);
void lp_hook_watch(const char *hook, int after);
int lp_props_check(const ini_file *ini, const char *id);
int cmd_prop(int argc, char **argv);
void lp_props_remove(const char *id);
int lp_props_install(const ini_file *ini, const char *id);
void lp_props_boot(void);
int cmd_svcmgr(void);
int svc_start(const char *qname);
int svc_start_ex(const char *qname, int manual);
void svc_forget(const char *qname);
int svc_stop(const char *qname);
int svc_status(const char *qname);

int cmd_root(int argc, char **argv);
int cmd_remount(int argc, char **argv);
int cmd_log(int argc, char **argv);
int cmd_usb_handler(void);
int spawn_am(char *const argv[]);
void add_es(char **argv, int *n, const char *key, const char *val);

int cmd_queue(int argc, char **argv);
int cmd_reboot(int argc, char **argv);
/* Takes the job tunnel for this process unless it already runs inside a job. */
void job_guard(const char *tag);
void real_reboot(const char *val);
void reboot_req_clear(void);
int drain_reboot(void);
void queue_scan_boot(void);

int cmd_ota_block(int argc, char **argv);
int cmd_selinux(int argc, char **argv);
int cmd_adb(int argc, char **argv);
void host_state_restore(void);
void host_state_boot_apply(void);

int become_root(void);
int su_listen(int fds[2]);
int su_poll(int fds[2], int nfds, int timeout_ms);
int su_client_main(int argc, char **argv);
int su_ping(void);
int su_ping_tries(int tries);

#endif
