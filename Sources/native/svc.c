#include "nemesis.h"

#include <fcntl.h>
#include <signal.h>
#include <sys/wait.h>
#include <time.h>

static void ignore_hup_pipe(void) {
    signal(SIGHUP, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
}

static void reap_children(void) {
    while (waitpid((pid_t)-1, NULL, WNOHANG) > 0) {}
}

static void svc_log_path(char *out, size_t cap, const char *qname) {
    char id[MAX_ID], local[MAX_NAME];
    qname_split(qname, id, sizeof(id), local, sizeof(local));
    mkdir_p(NEMESIS_LOGS, 0755);
    snprintf(out, cap, "%s/%s", NEMESIS_LOGS, id);
    mkdir_p(out, 0755);
    snprintf(out, cap, "%s/%s/%s.log", NEMESIS_LOGS, id, local);
}

static int find_svc(const char *qname, svc_rec *out) {
    svc_rec *list = NULL;
    int n = 0, i, rc = -1;
    svc_list_load(&list, &n);
    for (i = 0; i < n; i++) {
        if (!strcmp(list[i].qname, qname)) {
            *out = list[i];
            rc = 0;
            break;
        }
    }
    free(list);
    return rc;
}

static pid_t svc_pid(const char *qname) {
    char p[MAX_PATH], b[96];
    pid_path_for(p, sizeof(p), qname);
    if (read_file(p, b, sizeof(b)) < 0) return 0;
    return (pid_t)atoi(b);
}

static int svc_fails(const char *qname) {
    char p[MAX_PATH], b[32];
    pid_path_for(p, sizeof(p), qname);
    strcat(p, ".fails");
    if (read_file(p, b, sizeof(b)) < 0) return 0;
    return atoi(b);
}

static void svc_set_fails(const char *qname, int n) {
    char p[MAX_PATH], b[32];
    pid_path_for(p, sizeof(p), qname);
    strcat(p, ".fails");
    snprintf(b, sizeof(b), "%d\n", n);
    write_file(p, b, 0644);
}

/* Install and uninstall start a service with a clean crash count. */
void svc_forget(const char *qname) {
    char p[MAX_PATH];
    pid_path_for(p, sizeof(p), qname);
    strcat(p, ".fails");
    unlink(p);
}

static int svc_locked(const char *qname) {
    return qname && (!strncmp(qname, LOCKED_ID "/", (int)strlen(LOCKED_ID) + 1) ||
                     !strncmp(qname, MONITOR_ID "/", (int)strlen(MONITOR_ID) + 1));
}

static int svc_proc_ok(const svc_rec *s) {
    char p[MAX_PATH], local[MAX_NAME];
    const char *base, *slash;
    if (!s) return 0;
    pid_path_for(p, sizeof(p), s->qname);
    base = s->path;
    slash = s->path[0] ? strrchr(s->path, '/') : NULL;
    if (slash && slash[1]) base = slash + 1;
    if (base && base[0] && pidfile_is(p, base)) return 1;
    local[0] = 0;
    qname_split(s->qname, NULL, 0, local, sizeof(local));
    if (local[0] && (!base || strcmp(local, base) != 0) && pidfile_is(p, local))
        return 1;
    return 0;
}

int svc_stop(const char *qname) {
    svc_rec s;
    pid_t pid;
    char p[MAX_PATH];
    pid_path_for(p, sizeof(p), qname);
    if (find_svc(qname, &s) != 0 || !svc_proc_ok(&s)) {
        unlink(p);
        return 0;
    }
    pid = svc_pid(qname);
    if (pid > 0 && pid_alive(pid)) {
        kill(pid, SIGTERM);
        usleep(200000);
        if (pid_alive(pid)) kill(pid, SIGKILL);
    }
    unlink(p);
    return 0;
}

/* manual: started by the user. A service that hit the restart limit gets
 * one more run; svcmgr clears the count if it stays up for SVC_STABLE_SEC. */
int svc_start_ex(const char *qname, int manual) {
    svc_rec s;
    pid_t pid;
    char logp[MAX_PATH], pidp[MAX_PATH];
    int logfd;
    uid_t uid = 0;
    gid_t gid = 0;
    if (find_svc(qname, &s) != 0) {
        fprintf(stderr, "unknown service %s\n", qname);
        return 1;
    }
    if (svc_proc_ok(&s)) return 0;
    if (!svc_locked(qname) && svc_fails(qname) >= RESTART_LIMIT && !strcmp(s.mode, "restart")) {
        if (!manual) {
            log_msg("service %s disabled after %d restarts", qname, RESTART_LIMIT);
            return 2;
        }
        log_msg("manual start of disabled service %s", qname);
    }
    svc_log_path(logp, sizeof(logp), qname);
    pid_path_for(pidp, sizeof(pidp), qname);
    if (s.uid[0] && !strcmp(s.uid, "system")) {
        uid = 1000;
        gid = 1000;
    }
    if (!strcmp(s.kind, "instance") && !framework_up()) {
        log_msg("defer instance %s (android down)", qname);
        return 0;
    }
    pid = fork();
    if (pid < 0) return 1;
    if (pid == 0) {
        int i;
        setsid();
        ignore_hup_pipe();
        for (i = 0; i < 64; i++) close(i);
        logfd = open(logp, O_WRONLY | O_CREAT | O_APPEND, 0644);
        if (logfd >= 0) {
            dup2(logfd, 1);
            dup2(logfd, 2);
            if (logfd > 2) close(logfd);
        }
        open("/dev/null", O_RDONLY);
        setenv("PATH", NEMESIS_PATH, 1);
        setenv("LD_LIBRARY_PATH", "/vendor/lib:/system/lib", 1);
        if (uid) {
            setgid(gid);
            setuid(uid);
        }
        if (!strcmp(s.kind, "instance")) {
            char cmd[MAX_PATH + 64];
            alarm(8);
            snprintf(cmd, sizeof(cmd), "am start -n '%s' || am startservice -n '%s'", s.path, s.path);
            execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        } else {
            chmod(s.path, 0755);
            execl("/system/bin/sh", "sh", s.path, (char *)NULL);
        }
        _exit(127);
    }
    write_pidfile(pidp, pid);
    if (!strcmp(s.mode, "once")) {
        int st;
        waitpid(pid, &st, 0);
        unlink(pidp);
        return WIFEXITED(st) ? WEXITSTATUS(st) : 1;
    }
    log_msg("started %s pid=%d", qname, (int)pid);
    return 0;
}

int svc_start(const char *qname) {
    return svc_start_ex(qname, 0);
}

int svc_status(const char *qname) {
    svc_rec s;
    pid_t pid;
    const char *st;
    if (find_svc(qname, &s) != 0) {
        fprintf(stderr, "unknown service %s\n", qname);
        return 1;
    }
    pid = svc_proc_ok(&s) ? svc_pid(qname) : 0;
    if (pid > 0) st = "running";
    else if (!svc_locked(qname) && svc_fails(qname) >= RESTART_LIMIT) st = "failed";
    else st = "stopped";
    printf("%s|%s|%s|%d\n", qname, s.mode, st, (int)pid);
    return 0;
}

int cmd_service(int argc, char **argv) {
    const char *op, *name;
    if (argc < 1) {
        fprintf(stderr, "usage: nemesis service start|status|stop|list <name>\n");
        return 1;
    }
    op = argv[0];
    if (!strcmp(op, "list")) {
        svc_rec *list = NULL;
        int n = 0, i;
        seed_core_packages();
        svc_list_load(&list, &n);
        for (i = 0; i < n; i++) {
            pid_t pid = svc_proc_ok(&list[i]) ? svc_pid(list[i].qname) : 0;
            const char *st = (pid > 0) ? "running" : "stopped";
            if (pid <= 0 && !svc_locked(list[i].qname) && svc_fails(list[i].qname) >= RESTART_LIMIT)
                st = "failed";
            printf("%s|%s|%s|%d\n", list[i].qname, list[i].mode, st, (int)pid);
        }
        free(list);
        fflush(stdout);
        return 0;
    }
    if (argc < 2) {
        fprintf(stderr, "usage: nemesis service start|status|stop <package-id>/<name>\n");
        return 1;
    }
    name = argv[1];
    if (!strcmp(op, "start")) return svc_start_ex(name, 1);
    if (!strcmp(op, "stop")) return svc_stop(name);
    if (!strcmp(op, "status")) return svc_status(name);
    fprintf(stderr, "unknown service op %s\n", op);
    return 1;
}

static int spawn_argv(const char *pidfile, char *const argv[]) {
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        execv(argv[0], argv);
        _exit(127);
    }
    if (pidfile) write_pidfile(pidfile, pid);
    return 0;
}

int cmd_svcmgr(void) {
    ignore_hup_pipe();
    ensure_dirs();
    seed_core_packages();
    write_pidfile(SM_PIDFILE, getpid());
    log_msg("svcmgr start pid=%d", (int)getpid());
    /* launch all registered services */
    {
        svc_rec *list = NULL;
        int n = 0, i;
        svc_list_load(&list, &n);
        for (i = 0; i < n; i++) {
            if (!strcmp(list[i].mode, "once")) continue;
            svc_start(list[i].qname);
        }
        free(list);
    }
    for (;;) {
        svc_rec *list = NULL;
        int n = 0, i;
        reap_children();
        if (!pidfile_is(WD_PIDFILE, "watchdog")) {
            char *args[] = { (char *)BIN_PATH, (char *)"watchdog", NULL };
            /* Watchdog writes its own pidfile after detaching. */
            spawn_argv(NULL, args);
        }
        svc_list_load(&list, &n);
        for (i = 0; i < n; i++) {
            if (!strcmp(list[i].mode, "once")) continue;
            if (svc_proc_ok(&list[i])) {
                /* A crash loop can look alive between two polls. Only a
                 * process that stayed up clears the count. */
                int f = svc_fails(list[i].qname);
                if (f && proc_age_sec(svc_pid(list[i].qname)) >= SVC_STABLE_SEC) {
                    if (f > RESTART_LIMIT) log_msg("service %s recovered", list[i].qname);
                    svc_set_fails(list[i].qname, 0);
                }
                continue;
            }
            if (!strcmp(list[i].kind, "instance") && !framework_up()) continue;
            if (!strcmp(list[i].mode, "background")) continue;
            if (!strcmp(list[i].mode, "restart")) {
                if (svc_locked(list[i].qname)) {
                    svc_set_fails(list[i].qname, 0);
                    log_msg("restart %s (locked)", list[i].qname);
                    svc_start(list[i].qname);
                    continue;
                }
                int f = svc_fails(list[i].qname) + 1;
                if (f > RESTART_LIMIT + 1) continue;
                svc_set_fails(list[i].qname, f);
                if (f <= RESTART_LIMIT) {
                    log_msg("restart %s attempt %d", list[i].qname, f);
                    svc_start(list[i].qname);
                } else {
                    log_msg("giving up on %s", list[i].qname);
                }
            }
        }
        free(list);
        sleep(2);
    }
}

static void read_boot_id(char *out, size_t cap) {
    FILE *f = fopen("/proc/sys/kernel/random/boot_id", "r");
    out[0] = 0;
    if (!f) return;
    if (!fgets(out, (int)cap, f)) out[0] = 0;
    fclose(f);
    out[strcspn(out, "\n")] = 0;
}

/*
 * One "Android up" event per boot. HMG's package manager clears
 * sys.boot_completed for a moment during an install, so the value has to hold
 * and the mark outlives both that dip and a watchdog restart.
 */
static void android_up_tick(void) {
    static time_t since;
    static int done;
    struct timespec ts;
    char id[64], seen[64];
    FILE *f;
    pid_t pid;
    if (done) return;
    if (!framework_up()) {
        since = 0;
        return;
    }
    clock_gettime(CLOCK_MONOTONIC, &ts);
    if (!since) since = ts.tv_sec;
    if (ts.tv_sec - since < ANDROID_UP_SEC) return;
    done = 1;
    read_boot_id(id, sizeof(id));
    seen[0] = 0;
    f = fopen(ANDROID_UP_MARK, "r");
    if (f) {
        if (!fgets(seen, sizeof(seen), f)) seen[0] = 0;
        fclose(f);
        seen[strcspn(seen, "\n")] = 0;
    }
    if (id[0] && !strcmp(id, seen)) return;
    f = fopen(ANDROID_UP_MARK, "w");
    if (f) {
        fprintf(f, "%s\n", id);
        fclose(f);
    }
    log_msg("android up");
    pid = fork();
    if (pid == 0) {
        alarm(30);
        execl("/system/bin/am", "am", "broadcast", "-f", "32",
              "-a", "com.nemesis.manager.ANDROID_UP",
              "-n", MANAGER_EVENTS, (char *)NULL);
        _exit(127);
    }
}

int cmd_watchdog(void) {
    int lk;
    ignore_hup_pipe();
    if (pidfile_is(WD_PIDFILE, "watchdog")) {
        log_msg("watchdog already running pid=%d", (int)read_pidfile(WD_PIDFILE));
        return 0;
    }
    if (read_pidfile(WD_PIDFILE) > 0)
        log_msg("watchdog stale pidfile pid=%d, starting", (int)read_pidfile(WD_PIDFILE));
    switch (detach_daemon()) {
    case 1:
        return 0;
    case -1:
        return 1;
    }
    ensure_dirs();
    lk = pidfile_lock(WD_PIDFILE);
    if (pidfile_is(WD_PIDFILE, "watchdog")) {
        if (lk >= 0) close(lk);
        daemon_ready();
        _exit(0);
    }
    write_pidfile(WD_PIDFILE, getpid());
    if (lk >= 0) close(lk);
    seed_core_packages();
    {
        int su_fds[2];
        int su_n = su_listen(su_fds);
        log_msg("watchdog start pid=%d su_socks=%d", (int)getpid(), su_n);
        daemon_ready();
        remount_system(1);
        for (;;) {
            reap_children();
            if (!pidfile_is(SM_PIDFILE, "svcmgr")) {
                pid_t pid = fork();
                if (pid == 0) {
                    char *args[] = { (char *)BIN_PATH, (char *)"svcmgr", NULL };
                    execv(BIN_PATH, args);
                    _exit(127);
                }
                if (pid > 0) write_pidfile(SM_PIDFILE, pid);
            }
            android_up_tick();
            su_poll(su_fds, su_n, 2000);
        }
    }
}
