#include "nemesis.h"

#include <dirent.h>
#include <signal.h>
#include <sys/file.h>
#include <sys/mount.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <sys/wait.h>
#include <linux/reboot.h>

/*
 * One tunnel for package work. Every install, uninstall, update and wipe holds
 * QUEUE_DIR/job.lock while it runs, with NEMESIS_JOB set and the reboot shim
 * bound over /system/bin/reboot, so a hook's reboot only leaves REBOOT_REQ.
 * The queue runner works the <seq>.job files in order and, once nothing is
 * queued, does the one reboot the jobs asked for.
 *
 * Job files are key=value lines, world-readable for the Manager:
 * action id name zip risk state boot rc unclean.
 */

#define JOB_LOCK QUEUE_DIR "/job.lock"
#define RUN_LOCK QUEUE_DIR "/run.lock"
#define SYS_REBOOT "/system/bin/reboot"
#define REBOOT_SHIM NEMESIS_BINDIR "/reboot"
#define REBOOT_SESSION SESSION_DIR "/reboot"
#define REBOOT_PROMPT_SEC 25

typedef struct {
    int seq;
    char action[16];
    char id[MAX_ID];
    char name[MAX_NAME];
    char zip[MAX_PATH];
    char state[16];
    char boot[40];
    int risk, rc, unclean;
} qjob;

static void job_path(char *out, size_t cap, int seq, const char *ext) {
    snprintf(out, cap, "%s/%08d.%s", QUEUE_DIR, seq, ext);
}

static void kv_copy(char *dst, size_t cap, const char *line, const char *key) {
    size_t n = strlen(key);
    if (!strncmp(line, key, n) && line[n] == '=') snprintf(dst, cap, "%s", line + n + 1);
}

static int job_load(int seq, qjob *j) {
    char p[MAX_PATH], buf[4096], *line, *save = NULL;
    memset(j, 0, sizeof(*j));
    j->seq = seq;
    job_path(p, sizeof(p), seq, "job");
    if (read_file(p, buf, sizeof(buf)) <= 0) return -1;
    for (line = strtok_r(buf, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        char num[16] = "";
        kv_copy(j->action, sizeof(j->action), line, "action");
        kv_copy(j->id, sizeof(j->id), line, "id");
        kv_copy(j->name, sizeof(j->name), line, "name");
        kv_copy(j->zip, sizeof(j->zip), line, "zip");
        kv_copy(j->state, sizeof(j->state), line, "state");
        kv_copy(j->boot, sizeof(j->boot), line, "boot");
        kv_copy(num, sizeof(num), line, "risk");
        if (num[0]) j->risk = atoi(num);
        num[0] = 0;
        kv_copy(num, sizeof(num), line, "rc");
        if (num[0]) j->rc = atoi(num);
        num[0] = 0;
        kv_copy(num, sizeof(num), line, "unclean");
        if (num[0]) j->unclean = atoi(num);
    }
    return j->action[0] ? 0 : -1;
}

/* tmp + rename: the Manager never reads half a file. */
static int job_save(const qjob *j) {
    char p[MAX_PATH], tmp[MAX_PATH], buf[2048];
    job_path(p, sizeof(p), j->seq, "job");
    snprintf(tmp, sizeof(tmp), "%s.tmp", p);
    snprintf(buf, sizeof(buf),
             "action=%s\nid=%s\nname=%s\nzip=%s\nrisk=%d\nstate=%s\nboot=%s\nrc=%d\nunclean=%d\n",
             j->action, j->id, j->name, j->zip, j->risk, j->state, j->boot, j->rc, j->unclean);
    if (write_file(tmp, buf, 0644) != 0) return -1;
    chmod(tmp, 0644);
    return rename(tmp, p);
}

static void job_remove(int seq) {
    static const char *ext[] = {"job", "zip", "out", "hook", "job.tmp"};
    char p[MAX_PATH];
    unsigned i;
    for (i = 0; i < sizeof(ext) / sizeof(ext[0]); i++) {
        job_path(p, sizeof(p), seq, ext[i]);
        unlink(p);
    }
}

/* Job seqs in ascending order; returns the count. */
static int job_list(int *out, int cap) {
    DIR *d = opendir(QUEUE_DIR);
    struct dirent *e;
    int n = 0, i, k;
    if (!d) return 0;
    while ((e = readdir(d)) && n < cap) {
        size_t len = strlen(e->d_name);
        if (len != 12 || strcmp(e->d_name + 8, ".job")) continue;
        out[n++] = atoi(e->d_name);
    }
    closedir(d);
    for (i = 1; i < n; i++)
        for (k = i; k > 0 && out[k - 1] > out[k]; k--) {
            int t = out[k];
            out[k] = out[k - 1];
            out[k - 1] = t;
        }
    return n;
}

static int job_alloc(void) {
    int seqs[512], n = job_list(seqs, 512), seq = n ? seqs[n - 1] + 1 : 1, tries;
    for (tries = 0; tries < 64; tries++, seq++) {
        char p[MAX_PATH];
        int fd;
        job_path(p, sizeof(p), seq, "job");
        fd = open(p, O_CREAT | O_EXCL | O_WRONLY, 0644);
        if (fd >= 0) {
            close(fd);
            return seq;
        }
    }
    return -1;
}

static int count_state(const char *s1, const char *s2) {
    int seqs[512], n = job_list(seqs, 512), i, c = 0;
    qjob j;
    for (i = 0; i < n; i++)
        if (job_load(seqs[i], &j) == 0 && (!strcmp(j.state, s1) || (s2 && !strcmp(j.state, s2))))
            c++;
    return c;
}

/* ---- the tunnel ---- */

static int tunnel_fd = -1;
static int tunnel_bound;

/* The shim goes over the real file only: binding a symlink would cover its target. */
static void shim_bind(void) {
    struct stat a, b;
    if (lstat(SYS_REBOOT, &a) != 0 || !S_ISREG(a.st_mode)) return;
    if (stat("/system/bin", &b) == 0 && a.st_dev != b.st_dev) return; /* bound already */
    if (mount(REBOOT_SHIM, SYS_REBOOT, NULL, MS_BIND, NULL) == 0) tunnel_bound = 1;
    else log_msg("queue: reboot shim bind failed errno=%d", errno);
}

static void shim_unbind(void) {
    if (!tunnel_bound) return;
    if (umount2(SYS_REBOOT, MNT_DETACH) != 0)
        log_msg("queue: reboot shim unbind failed errno=%d", errno);
    tunnel_bound = 0;
}

static void tunnel_enter(const char *tag) {
    mkdir_p(QUEUE_DIR, 0755);
    tunnel_fd = open(JOB_LOCK, O_CREAT | O_RDWR, 0644);
    if (tunnel_fd >= 0) {
        while (flock(tunnel_fd, LOCK_EX) != 0 && errno == EINTR) {
        }
    }
    shim_bind();
    setenv("NEMESIS_JOB", tag, 1);
}

static void tunnel_leave(void) {
    shim_unbind();
    unsetenv("NEMESIS_JOB");
    if (tunnel_fd >= 0) close(tunnel_fd);
    tunnel_fd = -1;
}

static void guard_exit(void) {
    tunnel_leave();
    if (!drain_reboot()) drain_swap();
}

void job_guard(const char *tag) {
    if (getenv("NEMESIS_JOB")) return;
    tunnel_enter(tag);
    atexit(guard_exit);
}

/* ---- reboot ---- */

void reboot_req_clear(void) {
    unlink(REBOOT_REQ);
}

void real_reboot(const char *val) {
    const char *arg;
    shim_unbind();
    sync();
    log_msg("reboot %s", val);
    __system_property_set("sys.powerctl", val);
    sleep(10);
    /* init did not act (wedged or gone): go to the kernel. */
    arg = strchr(val, ',');
    if (!strcmp(val, "shutdown"))
        syscall(__NR_reboot, LINUX_REBOOT_MAGIC1, LINUX_REBOOT_MAGIC2, LINUX_REBOOT_CMD_POWER_OFF, NULL);
    else if (arg)
        syscall(__NR_reboot, LINUX_REBOOT_MAGIC1, LINUX_REBOOT_MAGIC2, LINUX_REBOOT_CMD_RESTART2, arg + 1);
    syscall(__NR_reboot, LINUX_REBOOT_MAGIC1, LINUX_REBOOT_MAGIC2, LINUX_REBOOT_CMD_RESTART, NULL);
}

/* reboot [-n] [-p] [reason], the toolbox syntax. Inside a job it only asks. */
int cmd_reboot(int argc, char **argv) {
    char val[96], tok[40] = "", line[160], old[160];
    const char *reason = NULL;
    int i, off = 0;
    for (i = 0; i < argc; i++) {
        if (!strcmp(argv[i], "-p")) off = 1;
        else if (argv[i][0] != '-') reason = argv[i];
    }
    if (off) snprintf(val, sizeof(val), "shutdown");
    else if (reason && *reason) snprintf(val, sizeof(val), "reboot,%s", reason);
    else snprintf(val, sizeof(val), "reboot");
    if (!getenv("NEMESIS_JOB")) {
        real_reboot(val);
        return 1;
    }
    /* A plain reboot does not replace an earlier recovery or shutdown request. */
    if (!strcmp(val, "reboot") && read_file(REBOOT_REQ, old, sizeof(old)) > 0 && strncmp(old, "reboot ", 7))
        return 0;
    boot_token(tok, sizeof(tok));
    snprintf(line, sizeof(line), "%s %s\n", val, tok);
    mkdir_p(QUEUE_DIR, 0755);
    write_file(REBOOT_REQ, line, 0644);
    log_msg("queue: job %s asked for %s; deferred", getenv("NEMESIS_JOB"), val);
    fprintf(stderr, "reboot deferred until the install queue is done\n");
    return 0;
}

static int read_reply(const char *path, char *out, size_t cap, int seconds) {
    int i;
    for (i = 0; i < seconds; i++) {
        if (read_file(path, out, cap) > 0) return 0;
        sleep(1);
    }
    return -1;
}

/* The Manager's 25 s countdown; reply gets "no", "yes" or "" (silence). swap=1 is the patched-apps text. */
static void countdown(const char *name, int swap, char *reply, size_t cap) {
    char rp[MAX_PATH], *argv[20]; /* 6 fixed + 4 --es triples + NULL = 19 */
    int n = 0;
    mkdir_p(REBOOT_SESSION, 0777);
    chmod(REBOOT_SESSION, 0777);
    snprintf(rp, sizeof(rp), "%s/reply", REBOOT_SESSION);
    unlink(rp);
    argv[n++] = (char *)"am";
    argv[n++] = (char *)"start";
    argv[n++] = (char *)"-n";
    argv[n++] = (char *)MANAGER_DIALOG;
    argv[n++] = (char *)"-f";
    argv[n++] = (char *)"0x10000000";
    add_es(argv, &n, "nemesis_mode", "reboot_countdown");
    add_es(argv, &n, "nemesis_name", name);
    add_es(argv, &n, "session_dir", REBOOT_SESSION);
    if (swap) add_es(argv, &n, "nemesis_swap", "1");
    argv[n++] = NULL;
    log_msg("queue: %s countdown (%s)", swap ? "swap" : "reboot", name);
    spawn_am(argv);
    reply[0] = 0;
    read_reply(rp, reply, cap, REBOOT_PROMPT_SEC);
    log_msg("queue: %s countdown reply '%.8s'", swap ? "swap" : "reboot", reply[0] ? reply : "(none)");
}

/* A "no" holds the swap until the next boot (boot-apply mounts it then). Without the mark, the
 * next guarded command to exit would see an empty reboot.req and swap anyway. */
#define SWAP_HELD QUEUE_DIR "/swap.held"
static int swap_held(void) {
    char tok[40], held[64];
    return boot_token(tok, sizeof(tok)) == 0 && read_file(SWAP_HELD, held, sizeof(held)) >= 32 && !strncmp(held, tok, 32);
}

static void swap_hold(void) {
    char tok[40];
    if (boot_token(tok, sizeof(tok)) == 0) write_file(SWAP_HELD, tok, 0644);
}

/* Reboots when a job asked for it and nothing else is queued. The Manager
 * counts down; "no" drops the request, "yes" or silence reboots. 1 = postponed. */
int drain_reboot(void) {
    char line[160], val[96], reply[32] = "";
    char *sp;
    if (read_file(REBOOT_REQ, line, sizeof(line)) <= 0) return 0;
    if (count_state("queued", "running") > 0) return 0;
    snprintf(val, sizeof(val), "%s", line);
    if ((sp = strchr(val, ' '))) *sp = 0;
    if ((sp = strchr(val, '\n'))) *sp = 0;
    if (!val[0]) snprintf(val, sizeof(val), "reboot");
    if (framework_up()) {
        countdown(val, 0, reply, sizeof(reply));
        if (!strncmp(reply, "no", 2)) {
            log_msg("queue: user postponed %s", val);
            reboot_req_clear();
            swap_hold();
            return 1;
        }
        /* Work arrived during the countdown: finish it, then ask again. */
        if (count_state("queued", "running") > 0) return 0;
    }
    reboot_req_clear();
    real_reboot(val);
    return 0;
}

/* Binds the outputs the jobs left in livepatch/swap. Apps that have the old file loaded are
 * force-stopped, so the user is asked first; "no" leaves the swap to the next boot. */
void drain_swap(void) {
    char reply[32] = "";
    if (!lp_swap_pending() || is_file(REBOOT_REQ) || swap_held() || count_state("queued", "running") > 0) return;
    if (framework_up() && lp_swap_running()) {
        char app[160];
        lp_swap_app(app, sizeof(app));
        countdown(app, 1, reply, sizeof(reply));
        if (!strncmp(reply, "no", 2)) {
            log_msg("queue: user postponed the swap; it applies at the next boot");
            swap_hold();
            return;
        }
        if (count_state("queued", "running") > 0) return;
    }
    lp_swap_now();
}

/* ---- boot scan ---- */

/* boot-apply found outputs that no longer match the table: one interrupted repair job per package. */
static void damaged_jobs(const int *seqs, int n) {
    char buf[2048], *p, *nl;
    int i, lost = 0;
    if (lp_damaged_take(buf, sizeof(buf)) <= 0) return;
    for (p = buf; *p; p = nl ? nl + 1 : p + strlen(p)) {
        pkg_rec rec;
        qjob j;
        int seq = -1, dup = 0;
        nl = strchr(p, '\n');
        if (nl) *nl = 0;
        for (i = 0; i < n && *p; i++)
            if (job_load(seqs[i], &j) == 0 && !strcmp(j.action, "repair") && !strcmp(j.id, p)) dup = 1;
        if (*p && !dup && (seq = job_alloc()) >= 0) {
            memset(&j, 0, sizeof(j));
            j.seq = seq;
            snprintf(j.action, sizeof(j.action), "repair");
            snprintf(j.id, sizeof(j.id), "%s", p);
            snprintf(j.name, sizeof(j.name), "%s", pkg_find(p, &rec) == 0 && rec.name[0] ? rec.name : p);
            snprintf(j.state, sizeof(j.state), "interrupted");
            boot_token(j.boot, sizeof(j.boot));
            if (job_save(&j) != 0) {
                job_remove(seq);
                lost = 1; /* keep the note: the next scan tries again */
                log_msg("queue: LivePatch output of %s damaged; could not save the repair job", p);
            } else log_msg("queue: LivePatch output of %s damaged; repair job %d", p, seq);
        }
        if (*p && !dup && seq < 0) lost = 1;
        if (!nl) break;
    }
    if (!lost) lp_damaged_clear();
}

/* Jobs left queued or running by an earlier boot are interrupted; one that died
 * inside a hook may have half-applied its changes. */
void queue_scan_boot(void) {
    int seqs[512], n, i, cut = 0;
    char tok[40] = "", line[160];
    qjob j;
    mkdir_p(QUEUE_DIR, 0755);
    chmod(QUEUE_DIR, 0755);
    if (boot_token(tok, sizeof(tok)) != 0) {
        log_msg("queue: no boot token; boot scan skipped");
        return;
    }
    if (read_file(REBOOT_REQ, line, sizeof(line)) > 0 && !strstr(line, tok)) reboot_req_clear();
    n = job_list(seqs, 512);
    for (i = 0; i < n; i++) {
        char hook[MAX_PATH];
        if (job_load(seqs[i], &j) != 0) continue;
        if (strcmp(j.state, "queued") && strcmp(j.state, "running")) continue;
        if (!strcmp(j.boot, tok)) continue;
        job_path(hook, sizeof(hook), j.seq, "hook");
        if (is_file(hook)) j.unclean = 1;
        unlink(hook);
        snprintf(j.state, sizeof(j.state), "interrupted");
        job_save(&j);
        log_msg("queue: job %d (%s %s) interrupted by a reboot%s", j.seq, j.action, j.id,
                j.unclean ? " inside a hook" : "");
        cut++;
    }
    /* The cut-off job's live row is still in the session dir. This boot's runner scans before it writes one. */
    if (cut) {
        unlink(SESSION_DIR "/ui.job");
        ui_reset();
    }
    damaged_jobs(seqs, n);
}

/* ---- runner ---- */

static void spawn_runner(void) {
    pid_t pid = fork();
    if (pid < 0) return;
    if (pid == 0) {
        int fd;
        setsid();
        if (fork() != 0) _exit(0);
        fd = open("/dev/null", O_RDWR);
        if (fd >= 0) {
            dup2(fd, 0);
            dup2(fd, 1);
            dup2(fd, 2);
            if (fd > 2) close(fd);
        }
        unsetenv("NEMESIS_JOB");
        execl(BIN_PATH, "nemesis", "queue", "run", (char *)NULL);
        _exit(127);
    }
    waitpid(pid, NULL, 0);
}

static int run_job(qjob *j) {
    char seqs[16], out[MAX_PATH], ui[160];
    pid_t pid;
    int st, rc = 1;
    snprintf(seqs, sizeof(seqs), "%d", j->seq);
    unlink(SWAP_HELD); /* new work: the next drain asks again */
    snprintf(j->state, sizeof(j->state), "running");
    boot_token(j->boot, sizeof(j->boot));
    job_save(j);
    snprintf(ui, sizeof(ui), "%d %s\n", j->seq, j->id);
    write_file(SESSION_DIR "/ui.job", ui, 0644);
    job_path(out, sizeof(out), j->seq, "out");
    tunnel_enter(seqs);
    pid = fork();
    if (pid == 0) {
        int fd = open(out, O_CREAT | O_TRUNC | O_WRONLY, 0644);
        char *argv[8];
        int n = 0;
        if (fd >= 0) {
            dup2(fd, 1);
            dup2(fd, 2);
            if (fd > 2) close(fd);
        }
        argv[n++] = (char *)"nemesis";
        if (!strcmp(j->action, "uninstall")) {
            argv[n++] = (char *)"uninstall";
            argv[n++] = j->id;
        } else if (!strcmp(j->action, "repair")) {
            argv[n++] = (char *)"livepatch";
            argv[n++] = (char *)"repair";
            argv[n++] = j->id;
        } else {
            argv[n++] = (char *)"install";
            /* Installed by now (an update, or a resumed install): replace in place. */
            if (pkg_find(j->id, NULL) == 0) argv[n++] = (char *)"--update";
            if (j->risk) argv[n++] = (char *)"--accept-risk";
            argv[n++] = j->zip;
        }
        argv[n++] = NULL;
        execv(BIN_PATH, argv);
        _exit(127);
    }
    if (pid > 0) {
        while (waitpid(pid, &st, 0) < 0 && errno == EINTR) {
        }
        rc = WIFEXITED(st) ? WEXITSTATUS(st) : 1;
    }
    tunnel_leave();
    job_path(out, sizeof(out), j->seq, "hook");
    unlink(out);
    log_msg("queue: job %d %s %s rc=%d", j->seq, j->action, j->id, rc);
    if (rc == 0) {
        job_remove(j->seq);
    } else {
        snprintf(j->state, sizeof(j->state), "failed");
        j->rc = rc;
        job_save(j);
    }
    return rc;
}

static int next_queued(qjob *j) {
    int seqs[512], n = job_list(seqs, 512), i;
    for (i = 0; i < n; i++)
        if (job_load(seqs[i], j) == 0 && !strcmp(j->state, "queued")) return 0;
    return -1;
}

static int queue_run(void) {
    /* CLOEXEC: a daemon a job starts must not inherit run.lock and block every later runner. */
    int fd = open(RUN_LOCK, O_CREAT | O_RDWR | O_CLOEXEC, 0644), seqs[512], n, i;
    qjob j;
    if (fd < 0) return 1;
    /* Another runner owns the queue and will pick our job up. */
    if (flock(fd, LOCK_EX | LOCK_NB) != 0) return 0;
    signal(SIGHUP, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    unsetenv("NEMESIS_JOB");
    queue_scan_boot();
    /* Holding run.lock, any "running" job is left over from a runner that died. */
    n = job_list(seqs, 512);
    for (i = 0; i < n; i++)
        if (job_load(seqs[i], &j) == 0 && !strcmp(j.state, "running")) {
            snprintf(j.state, sizeof(j.state), "interrupted");
            job_save(&j);
        }
    for (;;) {
        while (next_queued(&j) == 0) run_job(&j);
        if (!drain_reboot()) drain_swap();
        if (next_queued(&j) != 0) break;
    }
    unlink(SESSION_DIR "/ui.job");
    close(fd);
    return 0;
}

/* ---- commands ---- */

static int queue_add(int argc, char **argv) {
    const char *zip = NULL;
    int i, move = 0, risk = 0, seq;
    ini_file ini;
    pkg_meta m;
    struct stat st;
    struct statfs fs;
    char dst[MAX_PATH];
    qjob j;
    for (i = 0; i < argc; i++) {
        if (!strcmp(argv[i], "--move")) move = 1;
        else if (!strcmp(argv[i], "--accept-risk")) risk = 1;
        else if (argv[i][0] != '-') zip = argv[i];
    }
    if (!zip || stat(zip, &st) != 0 || !S_ISREG(st.st_mode)) {
        fprintf(stderr, "usage: nemesis queue add [--accept-risk] [--move] <zip>\n");
        return 1;
    }
    if (load_zip_meta(zip, &ini, &m) != 0) return 1;
    ini_free(&ini);
    /* The zip, its extracted copy, and room for the install itself. */
    if (statfs(NEMESIS_ROOT, &fs) == 0 &&
        (unsigned long long)fs.f_bavail * fs.f_bsize < (unsigned long long)st.st_size * 3) {
        fprintf(stderr, "not enough free space in /data for %s\n", m.id);
        return 6;
    }
    seq = job_alloc();
    if (seq < 0) {
        fprintf(stderr, "queue: no free job slot\n");
        return 1;
    }
    job_path(dst, sizeof(dst), seq, "zip");
    if (!(move && rename(zip, dst) == 0)) {
        if (copy_file(zip, dst, 0644) != 0) {
            fprintf(stderr, "queue: could not copy %s\n", zip);
            job_remove(seq);
            return 1;
        }
        if (move) unlink(zip);
    }
    chmod(dst, 0644);
    memset(&j, 0, sizeof(j));
    j.seq = seq;
    snprintf(j.action, sizeof(j.action), "install");
    snprintf(j.id, sizeof(j.id), "%s", m.id);
    snprintf(j.name, sizeof(j.name), "%s", m.name[0] ? m.name : m.id);
    snprintf(j.zip, sizeof(j.zip), "%s", dst);
    snprintf(j.state, sizeof(j.state), "queued");
    boot_token(j.boot, sizeof(j.boot));
    j.risk = risk;
    if (job_save(&j) != 0) {
        job_remove(seq);
        return 1;
    }
    printf("queued %d %s\n", seq, m.id);
    fflush(stdout);
    spawn_runner();
    return 0;
}

static int queue_uninstall(const char *id) {
    pkg_rec rec;
    qjob j;
    int seq;
    if (!id || pkg_find(id, &rec) != 0) {
        fprintf(stderr, "not installed: %s\n", id ? id : "");
        return 1;
    }
    seq = job_alloc();
    if (seq < 0) return 1;
    memset(&j, 0, sizeof(j));
    j.seq = seq;
    snprintf(j.action, sizeof(j.action), "uninstall");
    snprintf(j.id, sizeof(j.id), "%s", id);
    snprintf(j.name, sizeof(j.name), "%s", rec.name[0] ? rec.name : id);
    snprintf(j.state, sizeof(j.state), "queued");
    boot_token(j.boot, sizeof(j.boot));
    if (job_save(&j) != 0) {
        job_remove(seq);
        return 1;
    }
    printf("queued %d %s\n", seq, id);
    fflush(stdout);
    spawn_runner();
    return 0;
}

/* seq, or "all" for every interrupted job. Failed jobs may be retried one by one. */
static int queue_resume(int argc, char **argv) {
    const char *which = NULL;
    int i, risk = 0, seqs[512], n, hit = 0;
    qjob j;
    for (i = 0; i < argc; i++) {
        if (!strcmp(argv[i], "--accept-risk")) risk = 1;
        else which = argv[i];
    }
    if (!which) return 1;
    n = job_list(seqs, 512);
    for (i = 0; i < n; i++) {
        int all = !strcmp(which, "all");
        if (!all && seqs[i] != atoi(which)) continue;
        if (job_load(seqs[i], &j) != 0) continue;
        if (strcmp(j.state, "interrupted") && (all || strcmp(j.state, "failed"))) continue;
        snprintf(j.state, sizeof(j.state), "queued");
        boot_token(j.boot, sizeof(j.boot));
        j.rc = 0;
        j.unclean = 0;
        if (risk) j.risk = 1;
        job_save(&j);
        hit++;
    }
    if (!hit) {
        fprintf(stderr, "queue: nothing to resume\n");
        return 1;
    }
    spawn_runner();
    return 0;
}

static int queue_delete(const char *which) {
    int seqs[512], n = job_list(seqs, 512), i, hit = 0;
    qjob j;
    if (!which) return 1;
    for (i = 0; i < n; i++) {
        int all = !strcmp(which, "all");
        if (!all && seqs[i] != atoi(which)) continue;
        if (job_load(seqs[i], &j) != 0) j.action[0] = j.state[0] = 0;
        if (!strcmp(j.state, "running")) {
            if (!all) {
                fprintf(stderr, "queue: job %d is running\n", seqs[i]);
                return 2;
            }
            continue;
        }
        if (all && strcmp(j.state, "interrupted") && strcmp(j.state, "failed")) continue;
        job_remove(seqs[i]);
        hit++;
        /* Delete on a damaged output uninstalls the package; a stopped job's ports go to the cache. */
        if (!strcmp(j.action, "repair")) {
            if (pkg_find(j.id, NULL) == 0) queue_uninstall(j.id);
        } else if (!strcmp(j.state, "interrupted") || !strcmp(j.state, "failed"))
            lp_discard(j.id);
    }
    return hit ? 0 : 1;
}

static int queue_list(void) {
    int seqs[512], n = job_list(seqs, 512), i;
    qjob j;
    for (i = 0; i < n; i++)
        if (job_load(seqs[i], &j) == 0)
            printf("%d %s %s %s rc=%d unclean=%d\n", j.seq, j.state, j.action, j.id, j.rc, j.unclean);
    if (is_file(REBOOT_REQ)) printf("reboot requested\n");
    fflush(stdout);
    return 0;
}

int cmd_queue(int argc, char **argv) {
    const char *op = argc > 0 ? argv[0] : "";
    mkdir_p(QUEUE_DIR, 0755);
    chmod(QUEUE_DIR, 0755);
    if (!strcmp(op, "add")) return queue_add(argc - 1, argv + 1);
    if (!strcmp(op, "uninstall")) return queue_uninstall(argc > 1 ? argv[1] : NULL);
    if (!strcmp(op, "resume")) return queue_resume(argc - 1, argv + 1);
    if (!strcmp(op, "delete")) return queue_delete(argc > 1 ? argv[1] : NULL);
    if (!strcmp(op, "list")) return queue_list();
    if (!strcmp(op, "run")) return queue_run();
    fprintf(stderr, "usage: nemesis queue add|uninstall|resume|delete|list|run ...\n");
    return 1;
}
