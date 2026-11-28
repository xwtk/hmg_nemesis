/* Host-state toggles the user flips from Manager: OTA online-check blocker,
 * SELinux enforce/permissive, ADB over USB/TCP. Each one records what the
 * stock setting was before we changed it, so wipe can put it back. */
#include "nemesis.h"

#include <ctype.h>
#include <strings.h>

/* The alarm receiver is only the periodic check: EventReceiver (boot, ACC on, login) starts
 * OTAConnection, which runs its own server check 45 s after boot. All four keep OTAClient from
 * starting; USB updates go through DMClient. */
#define OTA_PKG "com.hkmc.system.app.OTAClient/com.hkmc.system.app.OTAClient."
static const char *const ota_comps[] = {"OTAClientAlarmReceiver", "mof.EventReceiver", "OTAConnection",
                                        "mof.TAppService"};
#define OTA_FLAG NEMESIS_ROOT "/ota_blocked"
#define SE_FLAG NEMESIS_ROOT "/selinux_prev"
#define ADB_USB_FLAG NEMESIS_ROOT "/adb_usb_prev"
#define ADB_TCP_FLAG NEMESIS_ROOT "/adb_tcp_prev"

static int trimline(char *s) {
    size_t n;
    if (!s) return 0;
    n = strlen(s);
    while (n > 0 && (s[n - 1] == '\n' || s[n - 1] == '\r' || s[n - 1] == ' ')) s[--n] = 0;
    return (int)n;
}

static int getprop(const char *key, char *out, size_t cap) {
    char cmd[128];
    int rc;
    if (!out || !cap) return -1;
    out[0] = 0;
    snprintf(cmd, sizeof(cmd), "/system/bin/getprop %s", key);
    rc = run_cmd_capture(cmd, out, cap);
    trimline(out);
    return rc;
}

static int setprop(const char *key, const char *value) {
    char cmd[256];
    snprintf(cmd, sizeof(cmd), "/system/bin/setprop %s %s", key, value && *value ? value : "\"\"");
    return run_cmd(cmd);
}

static int local_ip(char *out, size_t cap) {
    char buf[256];
    size_t n, i;
    int st, u = 0;
    if (!out || cap < 8) return -1;
    out[0] = 0;
    st = run_cmd_capture("/system/bin/ifconfig wlan0 2>/dev/null", buf, sizeof(buf));
    if (st != 0 || !buf[0]) {
        st = run_cmd_capture("/system/bin/getprop dhcp.wlan0.ipaddress", buf, sizeof(buf));
        if (st == 0 && buf[0]) {
            trimline(buf);
            n = strlen(buf);
            if (n < cap) {
                memcpy(out, buf, n + 1);
                return 0;
            }
        }
        return -1;
    }
    /* ifconfig prints "... ip <a.b.c.d>  mask ..." or "inet addr:<a.b.c.d>". Pick the first dotted quad. */
    n = strlen(buf);
    for (i = 0; i < n; i++) {
        if ((buf[i] >= '0' && buf[i] <= '9')) {
            size_t j = i, dots = 0;
            while (j < n && (buf[j] == '.' || (buf[j] >= '0' && buf[j] <= '9'))) {
                if (buf[j] == '.') dots++;
                j++;
            }
            if (dots == 3 && j - i <= 15) {
                size_t len = j - i;
                if (len < cap) {
                    memcpy(out, buf + i, len);
                    out[len] = 0;
                    u = 1;
                    if (strcmp(out, "0.0.0.0") != 0) break;
                    u = 0;
                }
            }
            i = j;
        }
    }
    return u ? 0 : -1;
}

/* ---- OTA online-check blocker ---- */

static int ota_wipe_downloads(void) {
    /* OTAClient caches online-downloaded packages under /storage/upgrade/. We
     * leave /cache/update.zip (Update DSP > Update OTA) and USB updates alone. */
    run_cmd("/system/bin/rm -rf /storage/upgrade/ota /storage/upgrade/scomo /storage/upgrade/news");
    return 0;
}

/* Every component is tried; non-zero if any pm call failed. */
static int ota_set_component(int enable) {
    char cmd[256];
    size_t i;
    int rc = 0;
    for (i = 0; i < sizeof(ota_comps) / sizeof(ota_comps[0]); i++) {
        snprintf(cmd, sizeof(cmd), "/system/bin/pm %s %s%s", enable ? "enable" : "disable", OTA_PKG, ota_comps[i]);
        if (run_cmd(cmd) != 0) rc = 1;
    }
    return rc;
}

static int ota_block_is_on(void) {
    return path_exists(OTA_FLAG);
}

int cmd_ota_block(int argc, char **argv) {
    const char *sub = argc > 0 ? argv[0] : "status";
    if (!strcmp(sub, "status")) {
        printf("%s\n", ota_block_is_on() ? "on" : "off");
        return 0;
    }
    if (!strcmp(sub, "on")) {
        if (ota_set_component(0) != 0) {
            fprintf(stderr, "ota-block: pm disable failed\n");
            return 1;
        }
        ota_wipe_downloads();
        write_file(OTA_FLAG, "1\n", 0644);
        log_msg("ota-block on: disabled OTAClient check, alarm, connection and TAppService");
        printf("ok\n");
        return 0;
    }
    if (!strcmp(sub, "off")) {
        ota_set_component(1);
        unlink(OTA_FLAG);
        log_msg("ota-block off: enabled OTAClient components");
        printf("ok\n");
        return 0;
    }
    fprintf(stderr, "usage: nemesis ota-block on|off|status\n");
    return 1;
}

static void ota_restore(void) {
    if (!ota_block_is_on()) return;
    ota_set_component(1);
    unlink(OTA_FLAG);
}

/* ---- SELinux switch ---- */

static int selinux_mode(char *out, size_t cap) {
    char buf[32];
    int rc = run_cmd_capture("/system/bin/getenforce", buf, sizeof(buf));
    trimline(buf);
    if (rc != 0 || !buf[0]) {
        if (out && cap) snprintf(out, cap, "disabled");
        return -1;
    }
    if (out && cap) snprintf(out, cap, "%s", buf);
    return 0;
}

/* What the kernel cmdline asked for: "enforcing" (androidboot.selinux absent or
 * =enforcing), else the requested value (disabled/permissive). The Manager
 * hides the SELinux toggle unless this is "enforcing" — on a unit that never
 * boots enforcing, setenforce is pointless. */
static void selinux_cmdline(char *out, size_t cap) {
    char buf[4096], *p;
    const char *key = "androidboot.selinux=";
    size_t i;
    if (!out || !cap) return;
    snprintf(out, cap, "enforcing");
    if (read_file("/proc/cmdline", buf, sizeof(buf)) <= 0) return;
    p = strstr(buf, key);
    if (!p) return;
    p += strlen(key);
    for (i = 0; p[i] && p[i] != ' ' && p[i] != '\n' && p[i] != '\r' && i + 1 < cap; i++)
        out[i] = p[i];
    out[i] = 0;
    if (!out[0]) snprintf(out, cap, "enforcing");
}

int cmd_selinux(int argc, char **argv) {
    const char *sub = argc > 0 ? argv[0] : "status";
    char mode[32];
    selinux_mode(mode, sizeof(mode));
    if (!strcmp(sub, "status")) {
        /* Lowercase for the Manager's parser. */
        char *p;
        for (p = mode; *p; p++) *p = (char)tolower((unsigned char)*p);
        printf("%s\n", mode);
        return 0;
    }
    if (!strcmp(sub, "cmdline")) {
        char cm[32];
        selinux_cmdline(cm, sizeof(cm));
        printf("%s\n", cm);
        return 0;
    }
    if (!strcmp(sub, "off")) {
        /* Record the stock mode exactly once. */
        if (!path_exists(SE_FLAG)) {
            char keep[40];
            snprintf(keep, sizeof(keep), "%s\n", mode);
            write_file(SE_FLAG, keep, 0644);
        }
        if (run_cmd("/system/bin/setenforce 0") != 0) {
            fprintf(stderr, "selinux: setenforce 0 failed (kernel has no SELinux?)\n");
            return 1;
        }
        log_msg("selinux off (was %s)", mode);
        printf("ok\n");
        return 0;
    }
    if (!strcmp(sub, "on")) {
        if (run_cmd("/system/bin/setenforce 1") != 0) {
            fprintf(stderr, "selinux: setenforce 1 failed\n");
            return 1;
        }
        unlink(SE_FLAG);
        log_msg("selinux on");
        printf("ok\n");
        return 0;
    }
    fprintf(stderr, "usage: nemesis selinux on|off|status|cmdline\n");
    return 1;
}

static void selinux_restore(void) {
    char prev[32];
    int n;
    if (!path_exists(SE_FLAG)) return;
    n = read_file(SE_FLAG, prev, sizeof(prev));
    if (n < 0) {
        unlink(SE_FLAG);
        return;
    }
    trimline(prev);
    /* Only re-enforce if stock was Enforcing; Permissive and Disabled need no action. */
    if (!strcasecmp(prev, "Enforcing")) run_cmd("/system/bin/setenforce 1");
    unlink(SE_FLAG);
}

/* ---- ADB over USB and TCP ---- */

/* State is read from the live properties, so Engineering Mode or a script that
 * flips ADB shows up too. The flag files only hold what to restore. */
static int usb_adb_is_on(void) {
    char v[128], *t, *save = NULL;
    if (getprop("persist.sys.usb.config", v, sizeof(v)) < 0) return 0;
    for (t = strtok_r(v, ",", &save); t; t = strtok_r(NULL, ",", &save))
        if (!strcmp(t, "adb")) return 1;
    return 0;
}

static int tcp_adb_is_on(void) {
    char en[16], port[16];
    getprop("persist.service.adb.tcp.enable", en, sizeof(en));
    if (!strcmp(en, "1")) return 1;
    if (!strcmp(en, "0")) return 0;
    getprop("service.adb.tcp.port", port, sizeof(port));
    return atoi(port) > 0;
}

static int adb_usb_on(void) {
    char prev[128];
    if (!usb_adb_is_on()) {
        if (getprop("persist.sys.usb.config", prev, sizeof(prev)) < 0) prev[0] = 0;
        /* Keep an empty line if unset — presence of the file is what we key off. */
        {
            char keep[160];
            snprintf(keep, sizeof(keep), "%s\n", prev);
            write_file(ADB_USB_FLAG, keep, 0644);
        }
    }
    setprop("persist.sys.usb.config", "adb");
    setprop("sys.usb.config", "adb");
    log_msg("adb usb on");
    return 0;
}

static int adb_usb_off(void) {
    char prev[128];
    int n;
    prev[0] = 0;
    if (path_exists(ADB_USB_FLAG)) {
        n = read_file(ADB_USB_FLAG, prev, sizeof(prev));
        if (n > 0) trimline(prev);
    }
    /* Restore what we saved; with no flag (ADB was enabled elsewhere) use Engineering Mode's "off" value. */
    if (!prev[0]) snprintf(prev, sizeof(prev), "host");
    setprop("persist.sys.usb.config", prev);
    setprop("sys.usb.config", prev);
    unlink(ADB_USB_FLAG);
    log_msg("adb usb off (restored %s)", prev);
    return 0;
}

static int adb_tcp_on(void) {
    char prev[32];
    if (!tcp_adb_is_on()) {
        if (getprop("service.adb.tcp.port", prev, sizeof(prev)) < 0) prev[0] = 0;
        {
            char keep[40];
            snprintf(keep, sizeof(keep), "%s\n", prev);
            write_file(ADB_TCP_FLAG, keep, 0644);
        }
    }
    setprop("service.adb.tcp.port", "5555");
    /* Engineering Mode keeps its own on/off here; "0" would keep the menu showing off. */
    getprop("persist.service.adb.tcp.enable", prev, sizeof(prev));
    if (!strcmp(prev, "0")) setprop("persist.service.adb.tcp.enable", "1");
    run_cmd("/system/bin/stop adbd");
    run_cmd("/system/bin/start adbd");
    log_msg("adb tcp on :5555");
    return 0;
}

static int adb_tcp_off(void) {
    char prev[32];
    int n;
    prev[0] = 0;
    if (path_exists(ADB_TCP_FLAG)) {
        n = read_file(ADB_TCP_FLAG, prev, sizeof(prev));
        if (n > 0) trimline(prev);
    }
    if (!prev[0]) snprintf(prev, sizeof(prev), "-1");
    setprop("service.adb.tcp.port", prev);
    {
        char en[16];
        getprop("persist.service.adb.tcp.enable", en, sizeof(en));
        if (!strcmp(en, "1")) setprop("persist.service.adb.tcp.enable", "0");
    }
    run_cmd("/system/bin/stop adbd");
    run_cmd("/system/bin/start adbd");
    unlink(ADB_TCP_FLAG);
    log_msg("adb tcp off (restored port=%s)", prev);
    return 0;
}

int cmd_adb(int argc, char **argv) {
    const char *sub = argc > 0 ? argv[0] : "status";
    if (!strcmp(sub, "status")) {
        char ip[64];
        if (local_ip(ip, sizeof(ip)) != 0) ip[0] = 0;
        printf("usb=%s\n", usb_adb_is_on() ? "on" : "off");
        printf("tcp=%s\n", tcp_adb_is_on() ? "on" : "off");
        printf("ip=%s\n", ip);
        printf("port=5555\n");
        return 0;
    }
    if (!strcmp(sub, "usb-on")) return adb_usb_on();
    if (!strcmp(sub, "usb-off")) return adb_usb_off();
    if (!strcmp(sub, "tcp-on")) return adb_tcp_on();
    if (!strcmp(sub, "tcp-off")) return adb_tcp_off();
    fprintf(stderr, "usage: nemesis adb usb-on|usb-off|tcp-on|tcp-off|status\n");
    return 1;
}

static void adb_restore(void) {
    if (path_exists(ADB_USB_FLAG)) adb_usb_off();
    if (path_exists(ADB_TCP_FLAG)) adb_tcp_off();
}

/* Called from cmd_wipe just before NEMESIS files are removed. */
void host_state_restore(void) {
    ota_restore();
    selinux_restore();
    adb_restore();
}

/* Once per boot with Android up (pm needs it): a block set by NEMESIS 1.17.1 or earlier covered
 * only the alarm receiver. Forked so the watchdog loop does not wait on four pm runs. */
void host_state_android_up(void) {
    if (!ota_block_is_on() || fork() != 0) return;
    alarm(60);
    _exit(ota_set_component(0));
}

/* Re-applies toggles the kernel resets each boot. Called from the watchdog so
 * the user's choice survives a reboot. Only SELinux needs it: ota-block (pm
 * disable) and adb (persist. props) already persist. The SE_FLAG file is
 * written whenever the user turns SELinux off (Settings or the popup). */
void host_state_boot_apply(void) {
    if (!path_exists(SE_FLAG)) return;
    if (run_cmd("/system/bin/setenforce 0") == 0)
        log_msg("boot: re-applied selinux off");
    else
        log_msg("boot: setenforce 0 failed");
}
