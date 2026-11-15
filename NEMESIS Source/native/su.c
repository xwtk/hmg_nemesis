#include "nemesis.h"

#include <poll.h>
#include <stddef.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>

#define SU_MAGIC "NEM\x01"
#define SU_MAX_ARGC 32
#define SU_MAX_ARG 768
#define SU_MAX_OUT (512 * 1024)

static const char *su_paths[] = { SU_SOCK_DEV, SU_SOCK_DATA, NULL };

static int io_all(int fd, void *buf, size_t n, int wr) {
    char *p = (char *)buf;
    size_t off = 0;
    while (off < n) {
        ssize_t r = wr ? write(fd, p + off, n - off) : read(fd, p + off, n - off);
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (r == 0) return wr ? -1 : (int)off;
        off += (size_t)r;
    }
    return (int)n;
}

static int wr_u32(int fd, uint32_t v) {
    unsigned char b[4];
    b[0] = (unsigned char)(v & 0xff);
    b[1] = (unsigned char)((v >> 8) & 0xff);
    b[2] = (unsigned char)((v >> 16) & 0xff);
    b[3] = (unsigned char)((v >> 24) & 0xff);
    return io_all(fd, b, 4, 1) == 4 ? 0 : -1;
}

static int rd_u32(int fd, uint32_t *v) {
    unsigned char b[4];
    if (io_all(fd, b, 4, 0) != 4) return -1;
    *v = (uint32_t)b[0] | ((uint32_t)b[1] << 8) |
         ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
    return 0;
}

static int wr_str(int fd, const char *s) {
    uint32_t n = (uint32_t)strlen(s ? s : "");
    if (n > SU_MAX_ARG) return -1;
    if (wr_u32(fd, n) != 0) return -1;
    return n ? (io_all(fd, (void *)s, n, 1) == (int)n ? 0 : -1) : 0;
}

static int rd_str(int fd, char *out, size_t cap) {
    uint32_t n;
    if (rd_u32(fd, &n) != 0 || n > SU_MAX_ARG || n >= cap) return -1;
    if (n && io_all(fd, out, n, 0) != (int)n) return -1;
    out[n] = 0;
    return 0;
}

static int bind_one(const char *path) {
    struct sockaddr_un addr;
    int fd, slen;
    unlink(path);
    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", path);
    slen = (int)(offsetof(struct sockaddr_un, sun_path) + strlen(addr.sun_path) + 1);
    if (bind(fd, (struct sockaddr *)&addr, slen) != 0) {
        close(fd);
        return -1;
    }
    chmod(path, 0666);
    if (listen(fd, 8) != 0) {
        close(fd);
        unlink(path);
        return -1;
    }
    fcntl(fd, F_SETFD, FD_CLOEXEC);
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
    return fd;
}

int su_listen(int fds[2]) {
    int n = 0, i;
    mkdir_p(NEMESIS_ROOT, 0755);
    chmod(NEMESIS_ROOT, 0755);
    fds[0] = fds[1] = -1;
    for (i = 0; su_paths[i] && n < 2; i++) {
        int fd = bind_one(su_paths[i]);
        if (fd >= 0) {
            log_msg("su listen %s fd=%d", su_paths[i], fd);
            fds[n++] = fd;
        } else {
            log_msg("su bind %s failed errno=%d", su_paths[i], errno);
        }
    }
    return n;
}

static int peer_uid(int fd, uid_t *uid) {
    struct ucred cr;
    socklen_t len = sizeof(cr);
    memset(&cr, 0, sizeof(cr));
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cr, &len) != 0) return -1;
    *uid = cr.uid;
    return 0;
}

static int daemon_op_ok(const char *op) {
    if (!op || !*op) return 0;
    if (!strcmp(op, "watchdog") || !strcmp(op, "svcmgr") ||
        !strcmp(op, "usb-handler"))
        return 0;
    return 1;
}

static int want_interactive(int argc, char **argv) {
    int i;
    if (argc < 1 || strcmp(argv[0], "root") != 0) return 0;
    for (i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-c")) return 0;
        if (!strcmp(argv[i], "--system")) continue;
        if (argv[i][0] >= '0' && argv[i][0] <= '9' && !strchr(argv[i], '/'))
            continue;
        return 0;
    }
    return 1;
}

static int send_result(int fd, int rc, const char *body, uint32_t len) {
    if (wr_u32(fd, (uint32_t)(rc & 0xff)) != 0) return -1;
    if (wr_u32(fd, len) != 0) return -1;
    if (len && io_all(fd, (void *)body, len, 1) != (int)len) return -1;
    return 0;
}

static void run_captured(int fd, int argc, char **argv) {
    int outp[2];
    pid_t pid;
    char *nargv[SU_MAX_ARGC + 3];
    int i, n = 0;
    char *body;
    size_t cap = 4096, used = 0;
    int st, rc;

    nargv[n++] = (char *)BIN_PATH;
    for (i = 0; i < argc && n < SU_MAX_ARGC + 1; i++) nargv[n++] = argv[i];
    nargv[n] = NULL;

    if (pipe(outp) != 0) {
        send_result(fd, 1, "pipe failed\n", 12);
        return;
    }
    pid = fork();
    if (pid < 0) {
        close(outp[0]);
        close(outp[1]);
        send_result(fd, 1, "fork failed\n", 12);
        return;
    }
    if (pid == 0) {
        close(fd);
        close(outp[0]);
        dup2(outp[1], 1);
        dup2(outp[1], 2);
        if (outp[1] > 2) close(outp[1]);
        execv(BIN_PATH, nargv);
        _exit(127);
    }
    close(outp[1]);
    body = (char *)malloc(cap);
    if (!body) {
        close(outp[0]);
        waitpid(pid, NULL, 0);
        send_result(fd, 1, "oom\n", 4);
        return;
    }
    for (;;) {
        char buf[4096];
        ssize_t r = read(outp[0], buf, sizeof(buf));
        if (r < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (r == 0) break;
        if (used + (size_t)r + 1 > cap) {
            size_t ncap = cap * 2;
            char *nb;
            if (ncap > SU_MAX_OUT) ncap = SU_MAX_OUT;
            if (used + (size_t)r > ncap) {
                used = ncap;
                continue;
            }
            nb = (char *)realloc(body, ncap);
            if (!nb) break;
            body = nb;
            cap = ncap;
        }
        memcpy(body + used, buf, (size_t)r);
        used += (size_t)r;
    }
    close(outp[0]);
    rc = 1;
    if (waitpid(pid, &st, 0) >= 0 && WIFEXITED(st)) rc = WEXITSTATUS(st);
    send_result(fd, rc, body, (uint32_t)used);
    free(body);
}

static void run_interactive(int fd, int argc, char **argv) {
    char *nargv[SU_MAX_ARGC + 3];
    int i, n = 0;
    nargv[n++] = (char *)BIN_PATH;
    for (i = 0; i < argc && n < SU_MAX_ARGC + 1; i++) nargv[n++] = argv[i];
    nargv[n] = NULL;
    dup2(fd, 0);
    dup2(fd, 1);
    dup2(fd, 2);
    if (fd > 2) close(fd);
    execv(BIN_PATH, nargv);
    _exit(127);
}

static void serve_client(int fd) {
    char magic[4];
    uint32_t argc, i, flags;
    char args[SU_MAX_ARGC][SU_MAX_ARG];
    char *argv[SU_MAX_ARGC];
    uid_t uid = (uid_t)-1;

    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) & ~O_NONBLOCK);
    if (io_all(fd, magic, 4, 0) != 4 || memcmp(magic, SU_MAGIC, 4) != 0) return;
    if (rd_u32(fd, &flags) != 0 || rd_u32(fd, &argc) != 0) return;
    if (argc == 0 || argc > SU_MAX_ARGC) return;
    for (i = 0; i < argc; i++) {
        if (rd_str(fd, args[i], sizeof(args[i])) != 0) return;
        argv[i] = args[i];
    }
    peer_uid(fd, &uid);
    if (argc == 1 && !strcmp(argv[0], "ping")) {
        send_result(fd, 0, "", 0);
        return;
    }
    if (!daemon_op_ok(argv[0])) {
        const char *msg = "nemesis: op not allowed via daemon\n";
        send_result(fd, 1, msg, (uint32_t)strlen(msg));
        return;
    }
    log_msg("su uid=%d op=%s", (int)uid, argv[0]);
    if ((flags & 1u) && want_interactive((int)argc, argv))
        run_interactive(fd, (int)argc, argv);
    else
        run_captured(fd, (int)argc, argv);
}

static void fork_worker(int fd) {
    pid_t pid = fork();
    if (pid < 0) {
        close(fd);
        return;
    }
    if (pid == 0) {
        serve_client(fd);
        close(fd);
        _exit(0);
    }
    close(fd);
}

int su_poll(int fds[2], int nfds, int timeout_ms) {
    struct pollfd p[2];
    int i, n = 0, pr;
    if (nfds <= 0) {
        sleep(timeout_ms > 0 ? (timeout_ms + 999) / 1000 : 2);
        return 0;
    }
    for (i = 0; i < nfds && i < 2; i++) {
        if (fds[i] < 0) continue;
        p[n].fd = fds[i];
        p[n].events = POLLIN;
        p[n].revents = 0;
        n++;
    }
    if (!n) {
        sleep(2);
        return 0;
    }
    pr = poll(p, (nfds_t)n, timeout_ms);
    if (pr <= 0) return pr;
    for (i = 0; i < n; i++) {
        if (!(p[i].revents & POLLIN)) continue;
        for (;;) {
            int fd = accept(p[i].fd, NULL, NULL);
            if (fd < 0) {
                if (errno == EINTR) continue;
                break;
            }
            fcntl(fd, F_SETFD, FD_CLOEXEC);
            fork_worker(fd);
        }
    }
    return pr;
}

static int connect_path(const char *path) {
    struct sockaddr_un addr;
    int fd, slen;
    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", path);
    slen = (int)(offsetof(struct sockaddr_un, sun_path) + strlen(addr.sun_path) + 1);
    if (connect(fd, (struct sockaddr *)&addr, slen) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int connect_retry_n(int tries) {
    int i, j, fd;
    if (tries < 1) tries = 1;
    for (i = 0; i < tries; i++) {
        for (j = 0; su_paths[j]; j++) {
            fd = connect_path(su_paths[j]);
            if (fd >= 0) return fd;
        }
        if (i + 1 < tries) usleep(200000);
    }
    return -1;
}

static int connect_retry(void) {
    return connect_retry_n(50);
}

static int ping_fd(int fd) {
    uint32_t rc, len;
    if (fd < 0) return -1;
    if (io_all(fd, (void *)SU_MAGIC, 4, 1) != 4 || wr_u32(fd, 0) != 0 ||
        wr_u32(fd, 1) != 0 || wr_str(fd, "ping") != 0) {
        close(fd);
        return -1;
    }
    if (rd_u32(fd, &rc) != 0 || rd_u32(fd, &len) != 0) {
        close(fd);
        return -1;
    }
    close(fd);
    return rc == 0 ? 0 : -1;
}

int su_ping_tries(int tries) {
    return ping_fd(connect_retry_n(tries));
}

int su_ping(void) {
    return su_ping_tries(50);
}

static int relay(int sock) {
    struct pollfd p[2];
    char buf[4096];
    int stdin_open = 1;
    p[0].fd = 0;
    p[0].events = POLLIN;
    p[1].fd = sock;
    p[1].events = POLLIN;
    for (;;) {
        p[0].revents = p[1].revents = 0;
        if (!stdin_open) p[0].fd = -1;
        if (poll(p, 2, -1) < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (stdin_open && (p[0].revents & POLLIN)) {
            ssize_t r = read(0, buf, sizeof(buf));
            if (r <= 0) {
                shutdown(sock, SHUT_WR);
                stdin_open = 0;
            } else if (io_all(sock, buf, (size_t)r, 1) != (int)r) {
                break;
            }
        }
        if (p[1].revents & POLLIN) {
            ssize_t r = read(sock, buf, sizeof(buf));
            if (r <= 0) break;
            if (io_all(1, buf, (size_t)r, 1) != (int)r) break;
        }
        if (p[1].revents & (POLLHUP | POLLERR | POLLNVAL) &&
            !(p[1].revents & POLLIN))
            break;
        if (!stdin_open && (p[0].revents & (POLLHUP | POLLERR))) {
            /* keep reading sock */
        }
    }
    return 0;
}

int su_client_main(int argc, char **argv) {
    int fd, i, interactive;
    uint32_t rc = 1, len = 0;
    char *body;
    char **rest = argv + 1;
    int restc = argc - 1;

    if (restc < 1) return 1;
    fd = connect_retry();
    if (fd < 0) {
        fprintf(stderr, "nemesis: daemon not running (euid=%d)\n", (int)geteuid());
        return 1;
    }
    interactive = want_interactive(restc, rest);
    if (io_all(fd, (void *)SU_MAGIC, 4, 1) != 4 ||
        wr_u32(fd, interactive ? 1u : 0u) != 0 ||
        wr_u32(fd, (uint32_t)restc) != 0) {
        close(fd);
        fprintf(stderr, "nemesis: daemon write failed\n");
        return 1;
    }
    for (i = 0; i < restc; i++) {
        if (wr_str(fd, rest[i]) != 0) {
            close(fd);
            fprintf(stderr, "nemesis: daemon write failed\n");
            return 1;
        }
    }
    if (interactive) return relay(fd);

    if (rd_u32(fd, &rc) != 0 || rd_u32(fd, &len) != 0) {
        close(fd);
        fprintf(stderr, "nemesis: daemon read failed\n");
        return 1;
    }
    if (len > SU_MAX_OUT) len = SU_MAX_OUT;
    body = (char *)malloc(len + 1);
    if (!body) {
        close(fd);
        return 1;
    }
    if (len && io_all(fd, body, len, 0) != (int)len) {
        free(body);
        close(fd);
        return 1;
    }
    close(fd);
    if (len) fwrite(body, 1, len, stdout);
    fflush(stdout);
    free(body);
    return (int)rc;
}
