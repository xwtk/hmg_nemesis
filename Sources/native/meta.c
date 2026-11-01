#include "nemesis.h"

/*
 * Descriptor metadata (NEMESIS.data [nemesis]) parsing, version specs and
 * compatibility. Pure logic: the caller supplies the device OS/model and a
 * lookup for installed package versions, so this file also builds on the host.
 */

static size_t utf8_chars(const char *s) {
    size_t n = 0;
    for (; *s; s++) {
        if (((unsigned char)*s & 0xC0) != 0x80) n++;
    }
    return n;
}

static void trim_copy(char *out, size_t cap, const char *s, size_t len) {
    while (len && (*s == ' ' || *s == '\t' || *s == '\n')) {
        s++;
        len--;
    }
    while (len && (s[len - 1] == ' ' || s[len - 1] == '\t' || s[len - 1] == '\n')) len--;
    if (len >= cap) len = cap - 1;
    memcpy(out, s, len);
    out[len] = 0;
}

/* Next comma separated item from *p, trimmed. Returns 0 at the end. */
int meta_list_next(const char **p, char *out, size_t cap) {
    const char *s = *p, *e;
    while (*s == ',' || *s == ' ' || *s == '\t' || *s == '\n') s++;
    if (!*s) return 0;
    e = strchr(s, ',');
    if (!e) e = s + strlen(s);
    trim_copy(out, cap, s, (size_t)(e - s));
    *p = e;
    return 1;
}

int ver_valid(const char *v) {
    int digits = 0;
    if (!v || !*v) return 0;
    for (; *v; v++) {
        if (*v >= '0' && *v <= '9') {
            digits++;
        } else if (*v == '.' && digits) {
            digits = 0;
        } else {
            return 0;
        }
    }
    return digits > 0;
}

/* Dotted numeric comparison; missing parts count as 0 (1.2 == 1.2.0). */
int ver_cmp(const char *a, const char *b) {
    while (*a || *b) {
        unsigned long x = 0, y = 0;
        while (*a >= '0' && *a <= '9') x = x * 10 + (unsigned long)(*a++ - '0');
        while (*b >= '0' && *b <= '9') y = y * 10 + (unsigned long)(*b++ - '0');
        if (x != y) return x < y ? -1 : 1;
        if (*a == '.') a++;
        if (*b == '.') b++;
    }
    return 0;
}

/* Checks the part after '@'. Returns 1 valid, 0 not. */
static int spec_valid(const char *spec) {
    char lo[32];
    const char *dash;
    if (!strcmp(spec, "any")) return 1;
    if (!strncmp(spec, ">=", 2) || !strncmp(spec, "<=", 2)) return ver_valid(spec + 2);
    dash = strchr(spec, '-');
    if (dash) {
        size_t n = (size_t)(dash - spec);
        if (n == 0 || n >= sizeof(lo)) return 0;
        memcpy(lo, spec, n);
        lo[n] = 0;
        return ver_valid(lo) && ver_valid(dash + 1);
    }
    return ver_valid(spec);
}

int dep_spec_match(const char *spec, const char *have) {
    char lo[32];
    const char *dash;
    if (!spec || !*spec || !strcmp(spec, "any")) return 1;
    if (!have || !ver_valid(have)) return 0;
    if (!strncmp(spec, ">=", 2)) return ver_cmp(have, spec + 2) >= 0;
    if (!strncmp(spec, "<=", 2)) return ver_cmp(have, spec + 2) <= 0;
    dash = strchr(spec, '-');
    if (dash) {
        size_t n = (size_t)(dash - spec);
        if (n >= sizeof(lo)) return 0;
        memcpy(lo, spec, n);
        lo[n] = 0;
        return ver_cmp(have, lo) >= 0 && ver_cmp(have, dash + 1) <= 0;
    }
    return ver_cmp(have, spec) == 0;
}

/* Splits "id@spec" (or bare "id", meaning any) into its parts. */
int dep_split(const char *item, char *id, size_t idcap, char *spec, size_t scap) {
    const char *at = strchr(item, '@');
    if (!at) {
        trim_copy(id, idcap, item, strlen(item));
        snprintf(spec, scap, "any");
    } else {
        trim_copy(id, idcap, item, (size_t)(at - item));
        trim_copy(spec, scap, at + 1, strlen(at + 1));
    }
    return id[0] ? 0 : -1;
}

/* Returns 1 when the comma list `deps` names `id`. */
int meta_depends_on(const char *deps, const char *id) {
    const char *p = deps;
    char item[160], did[MAX_ID], spec[64];
    while (meta_list_next(&p, item, sizeof(item))) {
        if (dep_split(item, did, sizeof(did), spec, sizeof(spec)) == 0 && !strcmp(did, id))
            return 1;
    }
    return 0;
}

/* The spec `deps` gives for `id`, or NULL. */
const char *meta_dep_spec(const char *deps, const char *id, char *spec, size_t cap) {
    const char *p = deps;
    char item[160], did[MAX_ID];
    while (meta_list_next(&p, item, sizeof(item))) {
        if (dep_split(item, did, sizeof(did), spec, cap) == 0 && !strcmp(did, id)) return spec;
    }
    return NULL;
}

int glob_match(const char *pat, const char *s) {
    if (!*pat) return !*s;
    if (*pat == '*') {
        while (pat[1] == '*') pat++;
        for (;; s++) {
            if (glob_match(pat + 1, s)) return 1;
            if (!*s) return 0;
        }
    }
    if (!*s) return 0;
    if (*pat == '?' || *pat == *s) return glob_match(pat + 1, s + 1);
    return 0;
}

/* An empty list or "any" matches everything; otherwise any glob item must match. */
int meta_list_match(const char *list, const char *value) {
    const char *p = list;
    char item[160];
    int seen = 0;
    if (!list) return 1;
    while (meta_list_next(&p, item, sizeof(item))) {
        seen = 1;
        if (!strcmp(item, "any")) return 1;
        if (value && glob_match(item, value)) return 1;
    }
    return !seen;
}

static int set_field(char *dst, size_t cap, const char *v, const char *key, char *err, size_t ecap) {
    if (!v) {
        dst[0] = 0;
        return 0;
    }
    if (strlen(v) >= cap) {
        snprintf(err, ecap, "%s is too long", key);
        return -1;
    }
    snprintf(dst, cap, "%s", v);
    return 0;
}

int meta_parse(const ini_file *ini, pkg_meta *m, char *err, size_t ecap) {
    const char *v, *t, *p;
    char item[160], id[MAX_ID], spec[64];
    memset(m, 0, sizeof(*m));
    if (err && ecap) err[0] = 0;
    v = ini_get(ini, "nemesis", "version");
    snprintf(m->meta_ver, sizeof(m->meta_ver), "%s", v && *v ? v : "1.0");
    if (!ver_valid(m->meta_ver)) {
        snprintf(err, ecap, "bad version=%s", m->meta_ver);
        return -1;
    }
    v = ini_get(ini, "nemesis", "id");
    if (!v || !*v) {
        snprintf(err, ecap, "missing id");
        return -1;
    }
    if (set_field(m->id, sizeof(m->id), v, "id", err, ecap)) return -1;
    v = ini_get(ini, "nemesis", "name");
    if (set_field(m->name, sizeof(m->name), v && *v ? v : m->id, "name", err, ecap)) return -1;
    /* 1.1 names the key type=; 1.0 used types=. Either is read as a fallback. */
    t = ini_get(ini, "nemesis", "type");
    v = ini_get(ini, "nemesis", "types");
    if (ver_cmp(m->meta_ver, "1.1") >= 0) {
        if (!t || !*t) t = v;
    } else if (v && *v) {
        t = v;
    }
    if (set_field(m->types, sizeof(m->types), t, "type", err, ecap)) return -1;
    v = ini_get(ini, "nemesis", "package-version");
    if (v && *v && !ver_valid(v)) {
        snprintf(err, ecap, "package-version must be dotted numbers: %s", v);
        return -1;
    }
    if (set_field(m->pkg_ver, sizeof(m->pkg_ver), v, "package-version", err, ecap)) return -1;
    v = ini_get(ini, "nemesis", "short-desc");
    if (v && utf8_chars(v) > META_SHORT_MAX) {
        snprintf(err, ecap, "short-desc is over %d characters", META_SHORT_MAX);
        return -1;
    }
    if (set_field(m->short_desc, sizeof(m->short_desc), v, "short-desc", err, ecap)) return -1;
    v = ini_get(ini, "nemesis", "detailed-desc");
    if (v && utf8_chars(v) > META_DETAIL_MAX) {
        snprintf(err, ecap, "detailed-desc is over %d characters", META_DETAIL_MAX);
        return -1;
    }
    if (set_field(m->detailed_desc, sizeof(m->detailed_desc), v, "detailed-desc", err, ecap)) return -1;
    v = ini_get(ini, "nemesis", "depends");
    if (set_field(m->depends, sizeof(m->depends), v, "depends", err, ecap)) return -1;
    p = m->depends;
    while (meta_list_next(&p, item, sizeof(item))) {
        if (dep_split(item, id, sizeof(id), spec, sizeof(spec)) != 0 || !spec_valid(spec)) {
            snprintf(err, ecap, "bad depends entry: %s", item);
            return -1;
        }
        if (!strcmp(id, m->id)) {
            snprintf(err, ecap, "package depends on itself");
            return -1;
        }
    }
    if (set_field(m->os, sizeof(m->os), ini_get(ini, "nemesis", "os"), "os", err, ecap)) return -1;
    if (set_field(m->models, sizeof(m->models), ini_get(ini, "nemesis", "models"), "models", err, ecap))
        return -1;
    return 0;
}

static void reason_add(char *out, size_t cap, const char *fmt, const char *a, const char *b, const char *c) {
    size_t n = strlen(out);
    if (n + 1 >= cap) return;
    snprintf(out + n, cap - n, fmt, a, b ? b : "", c ? c : "");
}

int meta_compat(const pkg_meta *m, const char *os_id, const char *model,
                meta_lookup_fn lookup, char *reasons, size_t cap) {
    const char *p = m->depends;
    char item[160], id[MAX_ID], spec[64], have[32];
    int deps_ok = 1, os_ok, model_ok;
    reasons[0] = 0;
    while (meta_list_next(&p, item, sizeof(item))) {
        if (dep_split(item, id, sizeof(id), spec, sizeof(spec)) != 0) continue;
        have[0] = 0;
        if (!lookup || !lookup(id, have, sizeof(have))) {
            deps_ok = 0;
            reason_add(reasons, cap, "dep-missing %s%s%s\n", id, NULL, NULL);
        } else if (!dep_spec_match(spec, have)) {
            deps_ok = 0;
            reason_add(reasons, cap, "dep-version %s have %s need %s\n", id,
                       have[0] ? have : "unknown", spec);
        }
    }
    os_ok = meta_list_match(m->os, os_id);
    if (!os_ok) reason_add(reasons, cap, "os %s not in %s%s\n", os_id && *os_id ? os_id : "unknown", m->os, NULL);
    model_ok = meta_list_match(m->models, model);
    if (!model_ok)
        reason_add(reasons, cap, "model %s not in %s%s\n", model && *model ? model : "unknown", m->models, NULL);
    if (deps_ok && os_ok) return COMPAT_FULL;
    if (deps_ok && model_ok) return COMPAT_PARTIAL;
    return COMPAT_NONE;
}

const char *compat_name(int c) {
    return c == COMPAT_FULL ? "full" : c == COMPAT_PARTIAL ? "partial" : "incompatible";
}

/* One-line form for pipe separated output: % | CR LF are percent-encoded. */
void meta_escape_field(const char *in, char *out, size_t cap) {
    size_t o = 0;
    if (!cap) return;
    for (; *in && o + 1 < cap; in++) {
        unsigned char c = (unsigned char)*in;
        if (c == '%' || c == '|' || c == '\n' || c == '\r') {
            if (o + 4 > cap) break;
            snprintf(out + o, cap - o, "%%%02X", c);
            o += 3;
        } else {
            out[o++] = (char)c;
        }
    }
    out[o] = 0;
}
