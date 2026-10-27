#include "nemesis.h"

static void usage(void) {
    fprintf(stderr,
            "nemesis install [--update] <zip>\n"
            "nemesis uninstall <package-id>\n"
            "nemesis pkg-list\n"
            "nemesis pkg-info <zip>\n"
            "nemesis pkg-launch <package-id>\n"
            "nemesis root [uid] [-c command]\n"
            "nemesis remount [rw|ro]\n"
            "nemesis service start|status|stop|list <package-id>/<name>\n"
            "nemesis log dump|logcat|dmesg|service ...\n"
            "nemesis wipe --confirm\n"
            "nemesis watchdog | svcmgr | usb-handler | ping\n");
}

int main(int argc, char **argv) {
    const char *op;
    if (argc < 2) {
        usage();
        return 1;
    }
    op = argv[1];
    if (!strcmp(op, "ping")) return su_ping_tries(3) == 0 ? 0 : 1;
    if (geteuid() != 0) {
        if (!strcmp(op, "watchdog") || !strcmp(op, "svcmgr") ||
            !strcmp(op, "usb-handler")) {
            if (!strcmp(op, "watchdog") && su_ping() == 0) return 0;
            fprintf(stderr, "nemesis: daemon not running (euid=%d)\n",
                    (int)geteuid());
            return 1;
        }
        return su_client_main(argc, argv);
    }
    if (become_root() != 0) return 1;
    umask(022);
    ensure_dirs();
    if (!strcmp(op, "install")) return cmd_install(argc - 2, argv + 2);
    if (!strcmp(op, "uninstall")) return cmd_uninstall(argc > 2 ? argv[2] : NULL, 0);
    if (!strcmp(op, "pkg-list")) return cmd_pkg_list();
    if (!strcmp(op, "pkg-info")) return cmd_pkg_info(argc > 2 ? argv[2] : NULL);
    if (!strcmp(op, "pkg-launch")) return cmd_pkg_launch(argc > 2 ? argv[2] : NULL);
    if (!strcmp(op, "root")) return cmd_root(argc - 2, argv + 2);
    if (!strcmp(op, "remount")) return cmd_remount(argc - 2, argv + 2);
    if (!strcmp(op, "service")) return cmd_service(argc - 2, argv + 2);
    if (!strcmp(op, "log")) return cmd_log(argc - 2, argv + 2);
    if (!strcmp(op, "wipe")) return cmd_wipe(argc > 2 && !strcmp(argv[2], "--confirm"));
    if (!strcmp(op, "watchdog")) return cmd_watchdog();
    if (!strcmp(op, "svcmgr")) return cmd_svcmgr();
    if (!strcmp(op, "usb-handler")) return cmd_usb_handler();
    usage();
    return 1;
}
