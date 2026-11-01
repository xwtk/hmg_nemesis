#include "nemesis.h"

static void usage(void) {
    fprintf(stderr,
            "nemesis install [--update] [--core] [--accept-risk] <zip>\n"
            "nemesis uninstall [--force] <package-id>\n"
            "nemesis pkg-list\n"
            "nemesis pkg-info <zip|package-id>\n"
            "nemesis compat <zip|package-id>\n"
            "nemesis pkg-launch <package-id>\n"
            "nemesis root [uid] [-c command]\n"
            "nemesis remount [rw|ro]\n"
            "nemesis service start|status|stop|list <package-id>/<name>\n"
            "nemesis log dump|logcat|dmesg|service ...\n"
            "nemesis update [--force] [--accept-risk] [--no-reboot] <zip>\n"
            "nemesis cacerts-install <dir>\n"
            "nemesis usb-info [--list]\n"
            "nemesis compat-check <os> <models> [depends]\n"
            "nemesis version\n"
            "nemesis wipe --confirm [--no-reboot]\n"
            "nemesis watchdog | svcmgr | usb-handler | ping\n");
}

int main(int argc, char **argv) {
    const char *op;
    if (argc < 2) {
        usage();
        return 1;
    }
    op = argv[1];
    if (!strcmp(op, "version")) {
        printf("%s\n", NEMESIS_VERSION);
        return 0;
    }
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
    ensure_dirs();
    if (!strcmp(op, "install")) return cmd_install(argc - 2, argv + 2);
    if (!strcmp(op, "uninstall")) return cmd_uninstall(argc - 2, argv + 2);
    if (!strcmp(op, "pkg-list")) return cmd_pkg_list();
    if (!strcmp(op, "pkg-info")) return cmd_pkg_info(argc > 2 ? argv[2] : NULL);
    if (!strcmp(op, "compat")) return cmd_compat(argc > 2 ? argv[2] : NULL);
    if (!strcmp(op, "pkg-launch")) return cmd_pkg_launch(argc > 2 ? argv[2] : NULL);
    if (!strcmp(op, "root")) return cmd_root(argc - 2, argv + 2);
    if (!strcmp(op, "remount")) return cmd_remount(argc - 2, argv + 2);
    if (!strcmp(op, "service")) return cmd_service(argc - 2, argv + 2);
    if (!strcmp(op, "log")) return cmd_log(argc - 2, argv + 2);
    if (!strcmp(op, "update")) return cmd_update(argc - 2, argv + 2);
    if (!strcmp(op, "wipe")) return cmd_wipe(argc - 2, argv + 2);
    if (!strcmp(op, "usb-info")) return cmd_usb_info(argc > 2 && !strcmp(argv[2], "--list"));
    if (!strcmp(op, "compat-check")) return cmd_compat_check(argc - 2, argv + 2);
    if (!strcmp(op, "cacerts-install")) return cmd_cacerts_install(argc > 2 ? argv[2] : NULL);
    if (!strcmp(op, "watchdog")) return cmd_watchdog();
    if (!strcmp(op, "svcmgr")) return cmd_svcmgr();
    if (!strcmp(op, "usb-handler")) return cmd_usb_handler();
    usage();
    return 1;
}
