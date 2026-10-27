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

#define NEMESIS_ROOT "/data/nemesis"
#define NEMESIS_APPS "/data/nemesis/apps"
#define NEMESIS_TMP "/data/nemesis/tmp"
#define NEMESIS_TMP_LEGACY "/data/local/tmp/nemesis"
#define NEMESIS_LOGS "/data/nemesis/logs"
#define NEMESIS_LOGFILE "/data/nemesis/nemesis.log"
#define NEMESIS_BINDIR "/data/nemesis/bin"
#define NEMESIS_PATH "/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin"
#define PACKAGES_DB "/data/nemesis/packages.db"
#define SERVICES_DB "/data/nemesis/services.db"
#define PIDS_DIR "/data/nemesis/pids"
#define SESSION_DIR "/data/nemesis/usb-session"
#define USB0 "/storage/usb0"
#define USB0_MNT "/mnt/media_rw/usb0"
#define USB1 "/storage/usb1"
#define USB1_MNT "/mnt/media_rw/usb1"
#define USB2 "/storage/usb2"
#define USB2_MNT "/mnt/media_rw/usb2"
#define LOCKED_ID "usb-handler"
#define BIN_PATH "/system/xbin/nemesis"
#define INSTALL_RECOVERY "/system/etc/install-recovery.sh"
#define QB_RUNTIME "/system/etc/qb_runtime.sh"
#define QB_MARKER "NEMESIS_QB_HOOK"
#define MANAGER_PKG "com.nemesis.manager"
#define MANAGER_DIALOG "com.nemesis.manager/.DialogActivity"
#define MANAGER_MAIN "com.nemesis.manager/.MainActivity"
#define MANAGER_APK "/system/app/NEMESIS.apk"
#define WD_PIDFILE "/data/nemesis/watchdog.pid"
#define SM_PIDFILE "/data/nemesis/svcmgr.pid"
#define SU_SOCK_DEV "/dev/nemesis.sock"
#define SU_SOCK_DATA "/data/nemesis/root.sock"
#define PACKAGES_XML "/data/system/packages.xml"
#define USB_RECOVERY_FLAG "/data/nemesis/usb-recovery"

#define MAX_PATH 512
#define MAX_LINE 1024
#define MAX_ID 96
#define MAX_NAME 128
#define MAX_QNAME 192
#define RESTART_LIMIT 5
#define USB_PROMPT_SEC 60

typedef struct {
    char section[80];
    char key[80];
    char val[512];
} ini_kv;

typedef struct {
    ini_kv *items;
    int n, cap;
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
int usb0_mounted(void);
int framework_up(void);
int manager_uid(uid_t *out);
void secure_session_dir(void);
int detach_daemon(void);
void daemon_ready(void);
void qname_join(char *out, size_t cap, const char *id, const char *local);
int qname_split(const char *qname, char *id, size_t idcap, char *local, size_t lcap);
void pid_path_for(char *out, size_t cap, const char *qname);

int ini_load(ini_file *ini, const char *path);
int ini_load_mem(ini_file *ini, const char *text);
void ini_free(ini_file *ini);
const char *ini_get(const ini_file *ini, const char *sec, const char *key);
int ini_has_section(const ini_file *ini, const char *sec);

int zip_extract(const char *zip, const char *dest);
int zip_extract_file(const char *zip, const char *member, char *out, size_t cap);

int pkg_list_load(pkg_rec **out, int *n);
int pkg_find(const char *id, pkg_rec *out);
int pkg_save_all(pkg_rec *list, int n);
int pkg_upsert(const pkg_rec *p);
int pkg_remove(const char *id);
int svc_list_load(svc_rec **out, int *n);
int svc_save_all(svc_rec *list, int n);
int svc_remove_prefix(const char *id);
int cmd_install(int argc, char **argv);
int cmd_uninstall(const char *id, int allow_locked);
int cmd_wipe(int confirm);
int cmd_pkg_info(const char *zip);
int cmd_pkg_list(void);
int cmd_pkg_launch(const char *id);
int seed_core_packages(void);

int cmd_service(int argc, char **argv);
int cmd_watchdog(void);
int cmd_svcmgr(void);
int svc_start(const char *qname);
int svc_stop(const char *qname);
int svc_status(const char *qname);

int cmd_root(int argc, char **argv);
int cmd_remount(int argc, char **argv);
int cmd_log(int argc, char **argv);
int cmd_usb_handler(void);

int become_root(void);
int su_listen(int fds[2]);
int su_poll(int fds[2], int nfds, int timeout_ms);
int su_client_main(int argc, char **argv);
int su_ping(void);
int su_ping_tries(int tries);

#endif
