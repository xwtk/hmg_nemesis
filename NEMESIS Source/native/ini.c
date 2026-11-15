#include "nemesis.h"

static void ini_trim(char *s) {
    char *e;
    while (*s == ' ' || *s == '\t' || *s == '\r') memmove(s, s + 1, strlen(s));
    e = s + strlen(s);
    while (e > s && (e[-1] == ' ' || e[-1] == '\t' || e[-1] == '\r' || e[-1] == '\n')) {
        *--e = 0;
    }
}

static int ini_add(ini_file *ini, const char *sec, const char *key, const char *val) {
    if (ini->n >= ini->cap) {
        int ncap = ini->cap ? ini->cap * 2 : 32;
        ini_kv *nitems = realloc(ini->items, (size_t)ncap * sizeof(ini_kv));
        if (!nitems) return -1;
        ini->items = nitems;
        ini->cap = ncap;
    }
    snprintf(ini->items[ini->n].section, sizeof(ini->items[ini->n].section), "%s", sec);
    snprintf(ini->items[ini->n].key, sizeof(ini->items[ini->n].key), "%s", key);
    snprintf(ini->items[ini->n].val, sizeof(ini->items[ini->n].val), "%s", val ? val : "");
    ini->n++;
    return 0;
}

static int ini_fail(ini_file *ini, int line, const char *msg) {
    free(ini->items);
    ini->items = NULL;
    ini->n = ini->cap = 0;
    snprintf(ini->err, sizeof(ini->err), "line %d: %s", line, msg);
    return -1;
}

/*
 * Line based key=value parser. A value that starts with '"' runs to the next
 * unescaped '"' and may span lines; inside it \" and \\ are escapes, CRLF and
 * lone CR become LF. Unquoted values are taken as-is up to the end of line.
 */
int ini_load_mem(ini_file *ini, const char *text) {
    char sec[80];
    const char *p = text ? text : "";
    int lineno = 1;
    memset(ini, 0, sizeof(*ini));
    sec[0] = 0;
    while (*p) {
        char key[MAX_LINE];
        char val[sizeof(((ini_kv *)0)->val)];
        size_t i = 0;
        int start_line = lineno;
        while (*p == ' ' || *p == '\t') p++;
        if (*p == '\r' || *p == '\n') {
            if (*p == '\r' && p[1] == '\n') p++;
            p++;
            lineno++;
            continue;
        }
        if (*p == '#' || *p == ';' || *p == '[') {
            char line[MAX_LINE];
            while (*p && *p != '\n' && *p != '\r') {
                if (i + 1 < sizeof(line)) line[i++] = *p;
                p++;
            }
            line[i] = 0;
            if (line[0] == '[') {
                char *end = strchr(line, ']');
                if (end) {
                    *end = 0;
                    snprintf(sec, sizeof(sec), "%s", line + 1);
                    ini_trim(sec);
                }
            }
            continue;
        }
        while (*p && *p != '=' && *p != '\n' && *p != '\r') {
            if (i + 1 < sizeof(key)) key[i++] = *p;
            p++;
        }
        key[i] = 0;
        if (*p != '=') continue;
        p++;
        ini_trim(key);
        while (*p == ' ' || *p == '\t') p++;
        i = 0;
        if (*p == '"') {
            p++;
            for (;;) {
                char c = *p;
                if (!c) {
                    return ini_fail(ini, start_line, "unterminated quoted value");
                }
                p++;
                if (c == '"') break;
                if (c == '\\' && (*p == '"' || *p == '\\')) c = *p++;
                else if (c == '\r') {
                    if (*p == '\n') p++;
                    c = '\n';
                    lineno++;
                } else if (c == '\n') lineno++;
                if (i + 1 >= sizeof(val)) {
                    return ini_fail(ini, start_line, "value too long");
                }
                val[i++] = c;
            }
            val[i] = 0;
            /* anything after the closing quote up to EOL is ignored */
            while (*p && *p != '\n' && *p != '\r') p++;
        } else {
            while (*p && *p != '\n' && *p != '\r') {
                if (i + 1 >= sizeof(val)) {
                    return ini_fail(ini, start_line, "value too long");
                }
                val[i++] = *p++;
            }
            val[i] = 0;
            ini_trim(val);
        }
        if (!key[0]) continue;
        if (ini_add(ini, sec, key, val) != 0) return -1;
    }
    return 0;
}

int ini_load(ini_file *ini, const char *path) {
    char *buf;
    struct stat st;
    int rc;
    if (!ini) return -1;
    memset(ini, 0, sizeof(*ini));
    if (stat(path, &st) != 0 || st.st_size < 0) {
        return -1;
    }
    buf = malloc((size_t)st.st_size + 1);
    if (!buf) return -1;
    if (read_file(path, buf, (size_t)st.st_size + 1) < 0) {
        free(buf);
        return -1;
    }
    rc = ini_load_mem(ini, buf);
    free(buf);
    return rc;
}

void ini_free(ini_file *ini) {
    if (!ini) return;
    free(ini->items);
    memset(ini, 0, sizeof(*ini));
}

const char *ini_get(const ini_file *ini, const char *sec, const char *key) {
    int i;
    if (!ini) return NULL;
    for (i = 0; i < ini->n; i++) {
        if (!strcmp(ini->items[i].section, sec) && !strcmp(ini->items[i].key, key))
            return ini->items[i].val;
    }
    return NULL;
}

int ini_has_section(const ini_file *ini, const char *sec) {
    int i;
    if (!ini) return 0;
    for (i = 0; i < ini->n; i++) {
        if (!strcmp(ini->items[i].section, sec)) return 1;
    }
    return 0;
}
