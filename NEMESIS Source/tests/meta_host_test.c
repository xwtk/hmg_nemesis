/*
 * Host tests for native/ini.c and native/meta.c (descriptor 1.1).
 * Build and run: make test-meta
 */
#include "../native/nemesis.h"

int read_file(const char *path, char *buf, size_t cap) {
    FILE *f = fopen(path, "rb");
    size_t n;
    if (!f) return -1;
    n = fread(buf, 1, cap - 1, f);
    buf[n] = 0;
    fclose(f);
    return (int)n;
}

static int fails, checks;

#define CHECK(cond, ...)                                  \
    do {                                                  \
        checks++;                                         \
        if (!(cond)) {                                    \
            fails++;                                      \
            fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); \
            fprintf(stderr, __VA_ARGS__);                 \
            fputc('\n', stderr);                          \
        }                                                 \
    } while (0)

static int parse(const char *text, ini_file *ini, pkg_meta *m, char *err, size_t ecap) {
    if (ini_load_mem(ini, text) != 0) {
        snprintf(err, ecap, "ini: %s", ini->err);
        return -1;
    }
    return meta_parse(ini, m, err, ecap);
}

static const char *installed[][2] = {
    {"adb.tcp", "1.2.0"}, {"busybox", "1.36"}, {"noversion", ""}, {NULL, NULL}};

static int lookup(const char *id, char *ver, size_t cap) {
    int i;
    for (i = 0; installed[i][0]; i++) {
        if (!strcmp(installed[i][0], id)) {
            snprintf(ver, cap, "%s", installed[i][1]);
            return 1;
        }
    }
    return 0;
}

static void test_ini(void) {
    ini_file ini;
    const char *v;
    char big[2048];
    CHECK(ini_load_mem(&ini, "[nemesis]\r\nid=a.b\r\nname=  Spaced Name  \r\n") == 0, "crlf");
    v = ini_get(&ini, "nemesis", "id");
    CHECK(v && !strcmp(v, "a.b"), "crlf id '%s'", v ? v : "(null)");
    v = ini_get(&ini, "nemesis", "name");
    CHECK(v && !strcmp(v, "Spaced Name"), "trim '%s'", v ? v : "(null)");
    ini_free(&ini);

    CHECK(ini_load_mem(&ini, "[s]\nk=\"say \\\"hi\\\" \\\\ back\"  # tail\n") == 0, "escapes");
    v = ini_get(&ini, "s", "k");
    CHECK(v && !strcmp(v, "say \"hi\" \\ back"), "escapes '%s'", v ? v : "(null)");
    ini_free(&ini);

    CHECK(ini_load_mem(&ini, "[s]\nk=\"line1\r\nline2\rline3\nline4\"\nnext=1\n") == 0, "multiline");
    v = ini_get(&ini, "s", "k");
    CHECK(v && !strcmp(v, "line1\nline2\nline3\nline4"), "multiline '%s'", v ? v : "(null)");
    v = ini_get(&ini, "s", "next");
    CHECK(v && !strcmp(v, "1"), "key after multiline");
    ini_free(&ini);

    CHECK(ini_load_mem(&ini, "[s]\nk=\"=[x]#;\"\n") == 0, "specials in quotes");
    v = ini_get(&ini, "s", "k");
    CHECK(v && !strcmp(v, "=[x]#;"), "specials '%s'", v ? v : "(null)");
    ini_free(&ini);

    CHECK(ini_load_mem(&ini, "[s]\nk=a \"b\" c\n") == 0, "inner quotes unquoted");
    v = ini_get(&ini, "s", "k");
    CHECK(v && !strcmp(v, "a \"b\" c"), "unquoted kept '%s'", v ? v : "(null)");
    ini_free(&ini);

    CHECK(ini_load_mem(&ini, "[s]\nk=\"open\nother=1\n") != 0, "unterminated rejected");
    CHECK(strstr(ini.err, "line 2") && strstr(ini.err, "unterminated"), "err '%s'", ini.err);
    ini_free(&ini);

    memset(big, 'x', sizeof(big) - 1);
    big[sizeof(big) - 1] = 0;
    {
        char text[2200];
        snprintf(text, sizeof(text), "[s]\nk=%s\n", big);
        CHECK(ini_load_mem(&ini, text) != 0, "overflow rejected");
        CHECK(strstr(ini.err, "too long") != NULL, "overflow err '%s'", ini.err);
        ini_free(&ini);
    }
    CHECK(ini_load_mem(&ini, "# c\n; c\n\n[a]\nnoeq\nk=v\n[b]\nk=w\n") == 0, "sections");
    CHECK(!strcmp(ini_get(&ini, "a", "k"), "v") && !strcmp(ini_get(&ini, "b", "k"), "w"), "section keys");
    ini_free(&ini);
}

static void test_meta(void) {
    ini_file ini;
    pkg_meta m;
    char err[128], text[2048], longs[300];
    int i;

    CHECK(parse("[nemesis]\nversion=1.0\nid=old.pkg\ntypes=application,service\n", &ini, &m, err, sizeof(err)) == 0,
          "1.0: %s", err);
    CHECK(!strcmp(m.types, "application,service"), "1.0 types '%s'", m.types);
    CHECK(!strcmp(m.name, "old.pkg"), "name defaults to id");
    ini_free(&ini);

    CHECK(parse("[nemesis]\nid=nover\ntypes=service\n", &ini, &m, err, sizeof(err)) == 0, "no version: %s", err);
    CHECK(!strcmp(m.meta_ver, "1.0") && !strcmp(m.types, "service"), "implicit 1.0");
    ini_free(&ini);

    CHECK(parse("[nemesis]\nversion=1.1\nid=new.pkg\ntype=application, service\ntypes=ignored\n"
                "package-version=1.2.0\nshort-desc=\"Short \\\"one\\\"\"\n"
                "detailed-desc=\"Line A\nLine B\"\ndepends=\"adb.tcp@>=1.0, busybox@any\"\n"
                "os=\"ECOKMC.USA.*\"\nmodels=\"cvev_us, nq5_us\"\n",
                &ini, &m, err, sizeof(err)) == 0, "1.1: %s", err);
    CHECK(!strcmp(m.types, "application, service"), "1.1 type wins '%s'", m.types);
    CHECK(!strcmp(m.pkg_ver, "1.2.0"), "pkg ver");
    CHECK(!strcmp(m.short_desc, "Short \"one\""), "short '%s'", m.short_desc);
    CHECK(!strcmp(m.detailed_desc, "Line A\nLine B"), "detail '%s'", m.detailed_desc);
    CHECK(meta_depends_on(m.depends, "adb.tcp") && meta_depends_on(m.depends, "busybox") &&
          !meta_depends_on(m.depends, "adb"), "depends_on");
    ini_free(&ini);

    CHECK(parse("[nemesis]\nversion=1.1\nid=fallback\ntypes=service\n", &ini, &m, err, sizeof(err)) == 0 &&
          !strcmp(m.types, "service"), "1.1 falls back to types=");
    ini_free(&ini);

    /* limits are counted in characters after unescaping */
    memset(longs, 'a', 64);
    longs[64] = 0;
    snprintf(text, sizeof(text), "[nemesis]\nid=x\nshort-desc=\"%s\"\n", longs);
    CHECK(parse(text, &ini, &m, err, sizeof(err)) == 0, "64 chars ok: %s", err);
    ini_free(&ini);
    snprintf(text, sizeof(text), "[nemesis]\nid=x\nshort-desc=\"%s\\\"\"\n", longs);
    CHECK(parse(text, &ini, &m, err, sizeof(err)) != 0 && strstr(err, "short-desc"), "65 chars rejected");
    ini_free(&ini);
    {
        char utf[200] = "";
        for (i = 0; i < 64; i++) strcat(utf, "\xc3\xa9");
        snprintf(text, sizeof(text), "[nemesis]\nid=x\nshort-desc=%s\n", utf);
        CHECK(parse(text, &ini, &m, err, sizeof(err)) == 0, "64 utf8 chars ok: %s", err);
        ini_free(&ini);
    }
    memset(longs, 'b', 257);
    longs[257] = 0;
    snprintf(text, sizeof(text), "[nemesis]\nid=x\ndetailed-desc=\"%s\"\n", longs);
    CHECK(parse(text, &ini, &m, err, sizeof(err)) != 0 && strstr(err, "detailed-desc"), "257 rejected");
    ini_free(&ini);
    longs[256] = 0;
    snprintf(text, sizeof(text), "[nemesis]\nid=x\ndetailed-desc=\"%s\"\n", longs);
    CHECK(parse(text, &ini, &m, err, sizeof(err)) == 0, "256 ok: %s", err);
    ini_free(&ini);

    CHECK(parse("[nemesis]\nid=x\npackage-version=1.a\n", &ini, &m, err, sizeof(err)) != 0, "bad pkg ver");
    ini_free(&ini);
    CHECK(parse("[nemesis]\nid=x\ndepends=a@>=1.x\n", &ini, &m, err, sizeof(err)) != 0, "bad dep spec");
    ini_free(&ini);
    CHECK(parse("[nemesis]\nid=x\ndepends=a@2.0-\n", &ini, &m, err, sizeof(err)) != 0, "bad range");
    ini_free(&ini);
    CHECK(parse("[nemesis]\nid=x\ndepends=x\n", &ini, &m, err, sizeof(err)) != 0, "self dep");
    ini_free(&ini);
    CHECK(parse("[nemesis]\nid=x\ndepends=!x\n", &ini, &m, err, sizeof(err)) != 0, "self conflict");
    ini_free(&ini);
    CHECK(parse("[nemesis]\nid=x\ndepends=!a@>=1.x\n", &ini, &m, err, sizeof(err)) != 0, "bad conflict spec");
    ini_free(&ini);
    CHECK(parse("[nemesis]\nid=x\ndepends=a,!a\n", &ini, &m, err, sizeof(err)) != 0, "needs and refuses");
    ini_free(&ini);
    CHECK(parse("[nemesis]\nid=x\ndepends=a@>=1, !b@<=2.0\n", &ini, &m, err, sizeof(err)) == 0, "mixed depends");
    {
        char spec[64], reasons[128] = "";
        CHECK(!meta_depends_on(m.depends, "b"), "!b is not a dependency");
        CHECK(meta_conflict_spec(m.depends, "b", spec, sizeof(spec)) && !strcmp(spec, "<=2.0"), "!b spec");
        CHECK(!meta_conflict_spec(m.depends, "a", spec, sizeof(spec)), "a is not a conflict");
        CHECK(meta_deps_unmet(&m, NULL, reasons, sizeof(reasons)) == 1 && !strstr(reasons, "b"), "only a is unmet");
    }
    ini_free(&ini);
    CHECK(parse("[nemesis]\nname=x\n", &ini, &m, err, sizeof(err)) != 0, "missing id");
    ini_free(&ini);
}

static void test_versions(void) {
    CHECK(ver_cmp("1.2", "1.2.0") == 0, "1.2 == 1.2.0");
    CHECK(ver_cmp("1.10", "1.9") > 0, "1.10 > 1.9");
    CHECK(ver_cmp("2", "10") < 0, "2 < 10");
    CHECK(dep_spec_match("1.2", "1.2.0"), "exact");
    CHECK(!dep_spec_match("1.2", "1.3"), "exact miss");
    CHECK(dep_spec_match(">=1.2", "1.10"), ">=");
    CHECK(!dep_spec_match(">=1.2", "1.1.9"), ">= miss");
    CHECK(dep_spec_match("<=2.0", "2.0"), "<=");
    CHECK(!dep_spec_match("<=2.0", "2.0.1"), "<= miss");
    CHECK(dep_spec_match("1.2-1.5", "1.2") && dep_spec_match("1.2-1.5", "1.5") &&
          dep_spec_match("1.2-1.5", "1.4.9"), "range inclusive");
    CHECK(!dep_spec_match("1.2-1.5", "1.5.1") && !dep_spec_match("1.2-1.5", "1.1"), "range miss");
    CHECK(dep_spec_match("any", "") && dep_spec_match("any", NULL), "any");
    CHECK(!dep_spec_match(">=1.0", ""), "no version fails a range");
    CHECK(glob_match("ECOKMC.USA.*", "ECOKMC.USA.0000.V015.012.260507"), "glob *");
    CHECK(!glob_match("ECOKMC.EUR.*", "ECOKMC.USA.0000"), "glob miss");
    CHECK(glob_match("cvev_??", "cvev_us"), "glob ?");
    CHECK(meta_list_match("", "x") && meta_list_match("any", "x") && meta_list_match(" a , any", "x"), "any lists");
    CHECK(meta_list_match("nq5_us, cvev_us", "cvev_us") && !meta_list_match("nq5_us", "cvev_us"), "list");
    CHECK(!meta_list_match("cvev_us", ""), "unknown model only matches any");
}

static int compat_of(const char *desc, const char *os, const char *model, char *reasons, size_t cap) {
    ini_file ini;
    pkg_meta m;
    char err[128];
    int c;
    if (parse(desc, &ini, &m, err, sizeof(err)) != 0) {
        fprintf(stderr, "parse: %s\n", err);
        return -1;
    }
    ini_free(&ini);
    c = meta_compat(&m, os, model, lookup, reasons, cap);
    return c;
}

static void test_compat(void) {
    char r[512];
    const char *os = "ECOKMC.USA.0000.V015.012.260507";
    CHECK(compat_of("[nemesis]\nid=a\n", os, "cvev_us", r, sizeof(r)) == COMPAT_FULL, "1.0 no fields = full");
    CHECK(compat_of("[nemesis]\nid=a\nos=ECOKMC.USA.*\nmodels=nq5_us\n", os, "cvev_us", r, sizeof(r)) == COMPAT_FULL,
          "deps+os = full even if model differs");
    CHECK(compat_of("[nemesis]\nid=a\nos=ECOKMC.EUR.*\nmodels=cvev_us\n", os, "cvev_us", r, sizeof(r)) == COMPAT_PARTIAL,
          "deps+model = partial");
    CHECK(strstr(r, "os ") != NULL, "partial reason '%s'", r);
    CHECK(compat_of("[nemesis]\nid=a\nos=ECOKMC.EUR.*\nmodels=nq5_us\n", os, "cvev_us", r, sizeof(r)) == COMPAT_NONE,
          "neither = incompatible");
    CHECK(compat_of("[nemesis]\nid=a\ndepends=missing.pkg\n", os, "cvev_us", r, sizeof(r)) == COMPAT_FULL &&
          strstr(r, "dep-missing missing.pkg"), "missing dep is only a reason '%s'", r);
    CHECK(compat_of("[nemesis]\nid=a\ndepends=adb.tcp@>=2.0\n", os, "cvev_us", r, sizeof(r)) == COMPAT_FULL &&
          strstr(r, "dep-version adb.tcp have 1.2.0 need >=2.0"), "dep version is only a reason '%s'", r);
    CHECK(compat_of("[nemesis]\nid=a\ndepends=\"adb.tcp@1.0-1.5, busybox, noversion@any\"\n", os, "x", r, sizeof(r)) ==
          COMPAT_FULL, "deps satisfied '%s'", r);
    CHECK(compat_of("[nemesis]\nid=a\ndepends=noversion@1.0\n", os, "x", r, sizeof(r)) == COMPAT_FULL &&
          strstr(r, "have unknown"), "unversioned dep '%s'", r);
    CHECK(compat_of("[nemesis]\nid=a\nos=\"ECOKMC.USA.*.260507\"\nmodels=cvev_us\n", os, "cvev_us", r, sizeof(r)) == COMPAT_FULL,
          "wildcard in the middle '%s'", r);
    CHECK(compat_of("[nemesis]\nid=a\nos=\"x, ecokmc.usa.????.*.260507\"\nmodels=none\n", os, "cvev_us", r, sizeof(r)) == COMPAT_FULL,
          "list, any case, ? '%s'", r);
    CHECK(compat_of("[nemesis]\nid=a\nos=ECOKMC.USA.*.260903\nmodels=CVEV_*\n", os, "cvev_us", r, sizeof(r)) == COMPAT_PARTIAL,
          "other firmware, same car '%s'", r);
}

static void test_escape(void) {
    char out[64];
    meta_escape_field("a|b%c\nd\re", out, sizeof(out));
    CHECK(!strcmp(out, "a%7Cb%25c%0Ad%0De"), "escape '%s'", out);
    meta_escape_field("abcdef|", out, 8);
    CHECK(!strcmp(out, "abcdef"), "escape truncates whole '%s'", out);
}

int main(void) {
    test_ini();
    test_meta();
    test_versions();
    test_compat();
    test_escape();
    printf("%d/%d meta host checks passed\n", checks - fails, checks);
    return fails ? 1 : 0;
}
