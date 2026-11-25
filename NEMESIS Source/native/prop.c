/* Property writer (resetprop-style) and the [prop.N] store of LivePatch.
 * Edits the 4.4 trie property area in place. Package syntax: 09-LivePatch.md, 3.3. */
#include "nemesis.h"
#include <stdint.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/system_properties.h>

#define PROPS NEMESIS_ROOT "/livepatch/props"
#define PROPS_STOCK NEMESIS_ROOT "/livepatch/props.stock"
#define PROP_AREA "/dev/__properties__"
#define VMAX 92
#define PMAX 64
#define LINEMAX 700

struct area { uint32_t used, serial, magic, version, rsv[28]; char data[0]; };
struct bt { uint8_t namelen, r[3]; uint32_t prop, left, right, children; char name[0]; };
struct info { uint32_t serial; char value[VMAX]; char name[0]; };

static struct area *A;
static size_t SZ;
#define BT(o) ((struct bt *)(A->data + (o)))
#define AL(n) (((n) + 3u) & ~3u)

static int area_open(void) {
    struct stat st;
    int fd;
    if (A) return 0;
    fd = open(PROP_AREA, O_RDWR);
    if (fd < 0) return -1;
    if (fstat(fd, &st) != 0) {
        close(fd);
        return -1;
    }
    SZ = st.st_size;
    A = mmap(0, SZ, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (A == MAP_FAILED || A->magic != 0x504f5250 || A->version != 0xfc6ed0ab) {
        A = NULL;
        return -1;
    }
    return 0;
}

static uint32_t alloc_node(uint32_t n) {
    uint32_t o;
    n = AL(n);
    if (A->used + n > SZ - sizeof *A) return 0;
    o = A->used;
    A->used += n;
    return o;
}

static int tok_cmp(const char *a, uint32_t al, const struct bt *b) {
    if (al != b->namelen) return al < b->namelen ? -1 : 1;
    return strncmp(a, b->name, al);
}

static uint32_t child(uint32_t par, const char *t, uint32_t tl, int create) {
    uint32_t *slot = &BT(par)->children, o;
    struct bt *n;
    while (*slot) {
        int c;
        n = BT(*slot);
        c = tok_cmp(t, tl, n);
        if (!c) return *slot;
        slot = c < 0 ? &n->left : &n->right;
    }
    if (!create) return 0;
    o = alloc_node(sizeof(struct bt) + tl + 1);
    if (!o) return 0;
    n = BT(o);
    n->namelen = tl;
    memcpy(n->name, t, tl);
    n->name[tl] = 0;
    *slot = o;
    return o;
}

static uint32_t walk(const char *key, int create) {
    uint32_t cur = 0;
    const char *p = key;
    for (;;) {
        const char *e = strchr(p, '.');
        uint32_t tl = e ? (uint32_t)(e - p) : strlen(p);
        cur = child(cur, p, tl, create);
        if (!cur || !e) return cur;
        p = e + 1;
    }
}

int prop_area_get(const char *key, char *out, size_t cap) {
    uint32_t n;
    struct bt *b;
    if (area_open() != 0 || !*key) return -1;
    n = walk(key, 0);
    if (!n) return -1;
    b = BT(n);
    if (!b->prop) return -1;
    snprintf(out, cap, "%s", ((struct info *)(A->data + b->prop))->value);
    return 0;
}

int prop_area_set(const char *key, const char *val) {
    uint32_t n, vl = strlen(val);
    struct bt *b;
    struct info *pi;
    if (area_open() != 0 || !*key || vl >= VMAX) return -1;
    n = walk(key, 1);
    if (!n) return -1;
    b = BT(n);
    if (!b->prop) {
        uint32_t o = alloc_node(sizeof *pi + strlen(key) + 1);
        if (!o) return -1;
        pi = (struct info *)(A->data + o);
        memset(pi, 0, sizeof *pi);
        strcpy(pi->name, key);
        strcpy(pi->value, val);
        pi->serial = vl << 24;
        __sync_synchronize();
        b = BT(n);
        b->prop = o;
    } else {
        pi = (struct info *)(A->data + b->prop);
        pi->serial |= 1;
        __sync_synchronize();
        strcpy(pi->value, val);
        __sync_synchronize();
        pi->serial = (vl << 24) | (((pi->serial & 0xffffff) + 1) & 0xffffff);
    }
    A->serial++;
    return 0;
}

int prop_area_del(const char *key) {
    uint32_t n;
    if (area_open() != 0 || !*key) return -1;
    n = walk(key, 0);
    if (!n) return 0;
    BT(n)->prop = 0;
    A->serial++;
    return 0;
}

/* ---- store ---- */

typedef struct { char key[96], val[VMAX], owners[400]; } pown;
typedef struct { char key[96], live[VMAX], file[VMAX]; int has_live, has_file; } pstock;

static int field(char **p, char *out, size_t cap) {
    char *t = strchr(*p, '\t');
    if (t) *t = 0;
    snprintf(out, cap, "%s", *p);
    *p = t ? t + 1 : *p + strlen(*p);
    return t != NULL;
}

static int props_load(pown *o, int cap) {
    char buf[PMAX * LINEMAX], *p, *nl;
    int n = 0;
    if (read_file(PROPS, buf, sizeof(buf)) <= 0) return 0;
    for (p = buf; *p && n < cap; p = nl ? nl + 1 : p + strlen(p)) {
        char *q;
        nl = strchr(p, '\n');
        if (nl) *nl = 0;
        q = p;
        if (field(&q, o[n].key, sizeof(o[n].key)) && field(&q, o[n].val, sizeof(o[n].val))) {
            field(&q, o[n].owners, sizeof(o[n].owners));
            n++;
        }
        if (!nl) break;
    }
    return n;
}

static int props_save(const pown *o, int n) {
    char tmp[MAX_PATH];
    FILE *f;
    int i;
    snprintf(tmp, sizeof(tmp), "%s.tmp", PROPS);
    f = fopen(tmp, "w");
    if (!f) return -1;
    for (i = 0; i < n; i++) fprintf(f, "%s\t%s\t%s\n", o[i].key, o[i].val, o[i].owners);
    fflush(f);
    fsync(fileno(f));
    fclose(f);
    return rename(tmp, PROPS);
}

static int stock_load(pstock *s, int cap) {
    char buf[PMAX * LINEMAX], *p, *nl;
    int n = 0;
    if (read_file(PROPS_STOCK, buf, sizeof(buf)) <= 0) return 0;
    for (p = buf; *p && n < cap; p = nl ? nl + 1 : p + strlen(p)) {
        char *q, live[VMAX + 2], file[VMAX + 2];
        nl = strchr(p, '\n');
        if (nl) *nl = 0;
        q = p;
        if (field(&q, s[n].key, sizeof(s[n].key)) && field(&q, live, sizeof(live))) {
            field(&q, file, sizeof(file));
            s[n].has_live = live[0] == '=';
            snprintf(s[n].live, sizeof(s[n].live), "%s", live[0] == '=' ? live + 1 : "");
            s[n].has_file = file[0] == '=';
            snprintf(s[n].file, sizeof(s[n].file), "%s", file[0] == '=' ? file + 1 : "");
            n++;
        }
        if (!nl) break;
    }
    return n;
}

static int stock_save(const pstock *s, int n) {
    char tmp[MAX_PATH];
    FILE *f;
    int i;
    snprintf(tmp, sizeof(tmp), "%s.tmp", PROPS_STOCK);
    f = fopen(tmp, "w");
    if (!f) return -1;
    for (i = 0; i < n; i++)
        fprintf(f, "%s\t%s%s\t%s%s\n", s[i].key, s[i].has_live ? "=" : "-", s[i].has_live ? s[i].live : "",
                s[i].has_file ? "=" : "-", s[i].has_file ? s[i].file : "");
    fflush(f);
    fsync(fileno(f));
    fclose(f);
    return rename(tmp, PROPS_STOCK);
}

static void persist_path(char *out, size_t cap, const char *key) {
    snprintf(out, cap, "/data/property/%s", key);
}

static int is_persist(const char *key) {
    return !strncmp(key, "persist.", 8);
}

static void prop_apply_one(const char *key, const char *val) {
    if (is_persist(key)) {
        if (__system_property_set(key, val) == 0) return;
    }
    prop_area_set(key, val);
}

static int has_owner(const char *owners, const char *id) {
    char tmp[400], *t, *sv = NULL;
    snprintf(tmp, sizeof(tmp), "%s", owners);
    for (t = strtok_r(tmp, ",", &sv); t; t = strtok_r(NULL, ",", &sv))
        if (!strcmp(t, id)) return 1;
    return 0;
}

static void drop_owner(char *owners, const char *id) {
    char tmp[400], out[400] = "", *t, *sv = NULL;
    snprintf(tmp, sizeof(tmp), "%s", owners);
    for (t = strtok_r(tmp, ",", &sv); t; t = strtok_r(NULL, ",", &sv)) {
        if (!strcmp(t, id)) continue;
        if (out[0]) strncat(out, ",", sizeof(out) - strlen(out) - 1);
        strncat(out, t, sizeof(out) - strlen(out) - 1);
    }
    strcpy(owners, out);
}

static void prop_restart(const char *restart) {
    char cmd[160];
    if (!restart || !*restart || strpbrk(restart, " ;|&$`'\"")) return;
    snprintf(cmd, sizeof(cmd), "stop %s; start %s", restart, restart);
    run_cmd(cmd);
}

/* restart= of the [prop.N] in owner's NEMESIS.data that sets key. */
static void prop_restart_owner(const char *owners, const char *key) {
    char id[MAX_ID], meta[MAX_PATH], sec[24];
    const char *c = strchr(owners, ',');
    ini_file ini;
    int k;
    snprintf(id, sizeof(id), "%.*s", c ? (int)(c - owners) : (int)strlen(owners), owners);
    snprintf(meta, sizeof(meta), "%s/livepatch/pkg/%s/entries", NEMESIS_ROOT, id);
    if (ini_load(&ini, meta) != 0) return;
    for (k = 1; k < 100; k++) {
        const char *kk;
        snprintf(sec, sizeof(sec), "prop.%d", k);
        kk = ini_get(&ini, sec, "key");
        if (kk && !strcmp(kk, key)) {
            prop_restart(ini_get(&ini, sec, "restart"));
            break;
        }
    }
    ini_free(&ini);
}

void lp_props_remove(const char *id) {
    pown own[PMAX];
    pstock st[PMAX];
    int n = props_load(own, PMAX), ns = stock_load(st, PMAX), i, j = 0, changed = 0;
    for (i = 0; i < n; i++) {
        int k;
        if (!has_owner(own[i].owners, id)) {
            own[j++] = own[i];
            continue;
        }
        changed = 1;
        drop_owner(own[i].owners, id);
        if (own[i].owners[0]) {
            own[j++] = own[i];
            continue;
        }
        for (k = 0; k < ns; k++)
            if (!strcmp(st[k].key, own[i].key)) break;
        if (k < ns) {
            char pp[MAX_PATH];
            persist_path(pp, sizeof(pp), own[i].key);
            if (st[k].has_live) prop_apply_one(own[i].key, st[k].live);
            else prop_area_del(own[i].key);
            if (is_persist(own[i].key)) {
                if (st[k].has_file) write_file(pp, st[k].file, 0600);
                else unlink(pp);
            }
            memmove(&st[k], &st[k + 1], (ns - k - 1) * sizeof(st[0]));
            ns--;
            prop_restart_owner(id, own[i].key);
        }
    }
    if (!changed) return;
    props_save(own, j);
    stock_save(st, ns);
}

/* Before anything is extracted: a [prop.N] another package sets to a different value. */
int lp_props_check(const ini_file *ini, const char *id) {
    pown own[PMAX];
    char sec[24];
    int k, i, n = props_load(own, PMAX);
    for (k = 1; k < 100; k++) {
        const char *key, *val;
        snprintf(sec, sizeof(sec), "prop.%d", k);
        if (!ini_has_section(ini, sec)) continue;
        key = ini_get(ini, sec, "key");
        val = ini_get(ini, sec, "value");
        if (!key || !val) continue;
        for (i = 0; i < n; i++) {
            char other[MAX_ID], name[MAX_NAME];
            pkg_rec p;
            const char *c;
            if (strcmp(own[i].key, key) || !strcmp(own[i].val, val)) continue;
            snprintf(other, sizeof(other), "%s", own[i].owners);
            if (has_owner(own[i].owners, id)) {
                if (!strchr(own[i].owners, ',')) continue; /* an update of the only owner */
                drop_owner(other, id);
            }
            c = strchr(other, ',');
            if (c) other[c - other] = 0;
            snprintf(name, sizeof(name), "%s", pkg_find(other, &p) == 0 && p.name[0] ? p.name : other);
            fprintf(stderr, "Conflicts with %s: %s=%s\n", name, key, own[i].val);
            return 1;
        }
    }
    return 0;
}

/* [prop.N] key= value= restart=. 0 or an error with a message. */
int lp_props_install(const ini_file *ini, const char *id) {
    char sec[24];
    int k;
    for (k = 1; k < 100; k++) {
        const char *key, *val, *restart;
        pown own[PMAX];
        pstock st[PMAX];
        int n, ns, i;
        snprintf(sec, sizeof(sec), "prop.%d", k);
        if (!ini_has_section(ini, sec)) continue;
        key = ini_get(ini, sec, "key");
        val = ini_get(ini, sec, "value");
        restart = ini_get(ini, sec, "restart");
        if (!key || !val || !*key || strlen(key) >= 96 || strlen(val) >= VMAX ||
            strpbrk(key, "\t\n /") || strpbrk(val, "\t\n")) {
            fprintf(stderr, "[%s]: need key= and value= (at most 91 bytes)\n", sec);
            return 1;
        }
        n = props_load(own, PMAX);
        ns = stock_load(st, PMAX);
        for (i = 0; i < n; i++)
            if (!strcmp(own[i].key, key)) break;
        if (i < n) {
            if (strcmp(own[i].val, val)) {
                fprintf(stderr, "[%s]: %s is already set to \"%s\" by %s\n", sec, key, own[i].val, own[i].owners);
                return 1;
            }
            if (!has_owner(own[i].owners, id) && strlen(own[i].owners) + strlen(id) + 2 < sizeof(own[i].owners)) {
                if (own[i].owners[0]) strcat(own[i].owners, ",");
                strcat(own[i].owners, id);
            }
        } else {
            char cur[VMAX], pp[MAX_PATH], fb[VMAX + 2];
            if (n >= PMAX || ns >= PMAX) return 1;
            memset(&st[ns], 0, sizeof(st[ns]));
            snprintf(st[ns].key, sizeof(st[ns].key), "%s", key);
            if (prop_area_get(key, cur, sizeof(cur)) == 0) {
                st[ns].has_live = 1;
                snprintf(st[ns].live, sizeof(st[ns].live), "%s", cur);
            }
            persist_path(pp, sizeof(pp), key);
            if (is_persist(key) && read_file(pp, fb, sizeof(fb)) >= 0) {
                st[ns].has_file = 1;
                snprintf(st[ns].file, sizeof(st[ns].file), "%s", fb);
            }
            ns++;
            snprintf(own[n].key, sizeof(own[n].key), "%s", key);
            snprintf(own[n].val, sizeof(own[n].val), "%s", val);
            snprintf(own[n].owners, sizeof(own[n].owners), "%s", id);
            n++;
        }
        mkdir_p(NEMESIS_ROOT "/livepatch", 0711);
        stock_save(st, ns);
        if (props_save(own, n) != 0) return 1;
        prop_apply_one(key, val);
        prop_restart(restart);
        log_msg("livepatch: %s sets %s", id, key);
    }
    return 0;
}

/* Boot: ro.* and normal props are RAM only, so they are written every boot. A daemon that
 * read the stock value before zygote (adbd and ro.adb.secure) gets its restart= again. */
void lp_props_boot(void) {
    pown own[PMAX];
    int n = props_load(own, PMAX), i;
    for (i = 0; i < n; i++) {
        char cur[VMAX];
        if (is_persist(own[i].key)) continue;
        if (prop_area_get(own[i].key, cur, sizeof(cur)) == 0 && !strcmp(cur, own[i].val)) continue;
        prop_area_set(own[i].key, own[i].val);
        prop_restart_owner(own[i].owners, own[i].key);
    }
}

int cmd_prop(int argc, char **argv) {
    char v[VMAX];
    if (argc >= 2 && !strcmp(argv[0], "get")) {
        if (prop_area_get(argv[1], v, sizeof(v)) != 0) return 1;
        printf("%s\n", v);
        return 0;
    }
    if (argc >= 3 && !strcmp(argv[0], "set")) return prop_area_set(argv[1], argv[2]) == 0 ? 0 : 1;
    if (argc >= 2 && !strcmp(argv[0], "del")) return prop_area_del(argv[1]) == 0 ? 0 : 1;
    fprintf(stderr, "nemesis prop get <key> | set <key> <value> | del <key>\n");
    return 1;
}
