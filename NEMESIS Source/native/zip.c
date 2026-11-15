#include "nemesis.h"

#include <strings.h>
#include <zlib.h>

static uint16_t u16(const unsigned char *p) {
    return (uint16_t)(p[0] | (p[1] << 8));
}
static uint32_t u32(const unsigned char *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static int zip_safe_name(const char *name) {
    if (!name || !*name) return 0;
    if (name[0] == '/' || name[0] == '\\') return 0;
    if (strstr(name, "..")) return 0;
    return 1;
}

static int inflate_buf(const unsigned char *in, size_t inlen, unsigned char *out, size_t outlen) {
    z_stream s;
    int rc;
    memset(&s, 0, sizeof(s));
    if (inflateInit2(&s, -MAX_WBITS) != Z_OK) return -1;
    s.next_in = (Bytef *)in;
    s.avail_in = (uInt)inlen;
    s.next_out = out;
    s.avail_out = (uInt)outlen;
    rc = inflate(&s, Z_FINISH);
    inflateEnd(&s);
    return rc == Z_STREAM_END ? 0 : -1;
}

static int write_all(const char *path, const void *data, size_t n, mode_t mode) {
    int fd;
    char dir[MAX_PATH];
    char *slash;
    snprintf(dir, sizeof(dir), "%s", path);
    slash = strrchr(dir, '/');
    if (slash) {
        *slash = 0;
        mkdir_p(dir, 0755);
    }
    fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, mode);
    if (fd < 0) return -1;
    if (n && write(fd, data, n) != (ssize_t)n) {
        close(fd);
        return -1;
    }
    close(fd);
    return 0;
}

/* Sizes live in the central directory. Bit 3 (data descriptor) leaves the
 * local header sizes at zero; macOS zip sets that bit. */
static int zip_find_eocd(FILE *f, uint32_t *cd_off, uint16_t *nument) {
    unsigned char win[65557];
    long sz, start, i;
    size_t n;
    if (fseek(f, 0, SEEK_END) != 0) return -1;
    sz = ftell(f);
    if (sz < 22) return -1;
    start = sz > (long)sizeof(win) ? sz - (long)sizeof(win) : 0;
    if (fseek(f, start, SEEK_SET) != 0) return -1;
    n = fread(win, 1, sizeof(win), f);
    if (n < 22) return -1;
    for (i = (long)n - 22; i >= 0; i--) {
        if (u32(win + i) != 0x06054b50) continue;
        *nument = u16(win + i + 10);
        *cd_off = u32(win + i + 16);
        if (*cd_off == 0xffffffff) return -1;
        return 0;
    }
    return -1;
}

static int zip_member_skip(const char *name) {
    if (!strncmp(name, "__MACOSX/", 9) || strstr(name, "/__MACOSX/")) return 1;
    if (!strcmp(name, ".DS_Store") || strstr(name, "/.DS_Store")) return 1;
    return 0;
}

static int zip_iter(const char *zip,
                    int (*cb)(const char *name, const unsigned char *data, size_t n, void *ctx),
                    void *ctx) {
    FILE *f;
    unsigned char hdr[46];
    uint32_t cd_off;
    uint16_t nument, i;
    f = fopen(zip, "rb");
    if (!f) return -1;
    if (zip_find_eocd(f, &cd_off, &nument) != 0) {
        fclose(f);
        return -1;
    }
    if (fseek(f, (long)cd_off, SEEK_SET) != 0) {
        fclose(f);
        return -1;
    }
    for (i = 0; i < nument; i++) {
        uint16_t method, namelen, extralen, commentlen, flags;
        uint32_t csize, usize, local_off;
        char name[MAX_PATH];
        unsigned char local[30];
        unsigned char *cdata = NULL, *udata = NULL;
        if (fread(hdr, 1, 46, f) != 46 || u32(hdr) != 0x02014b50) {
            fclose(f);
            return -1;
        }
        flags = u16(hdr + 8);
        method = u16(hdr + 10);
        csize = u32(hdr + 20);
        usize = u32(hdr + 24);
        namelen = u16(hdr + 28);
        extralen = u16(hdr + 30);
        commentlen = u16(hdr + 32);
        local_off = u32(hdr + 42);
        if (namelen >= sizeof(name) || csize == 0xffffffff || usize == 0xffffffff ||
            local_off == 0xffffffff || (flags & 0x1)) {
            fclose(f);
            return -1;
        }
        if (fread(name, 1, namelen, f) != namelen) {
            fclose(f);
            return -1;
        }
        name[namelen] = 0;
        if (extralen && fseek(f, extralen, SEEK_CUR) != 0) {
            fclose(f);
            return -1;
        }
        if (commentlen && fseek(f, commentlen, SEEK_CUR) != 0) {
            fclose(f);
            return -1;
        }
        if (name[0] && name[strlen(name) - 1] == '/') continue;
        if (zip_member_skip(name)) continue;
        if (!zip_safe_name(name)) {
            fclose(f);
            return -1;
        }
        {
            long cd_pos = ftell(f);
            if (cd_pos < 0) {
                fclose(f);
                return -1;
            }
            if (fseek(f, (long)local_off, SEEK_SET) != 0 ||
                fread(local, 1, 30, f) != 30 || u32(local) != 0x04034b50) {
                fclose(f);
                return -1;
            }
            if (fseek(f, (long)u16(local + 26) + (long)u16(local + 28), SEEK_CUR) != 0) {
                fclose(f);
                return -1;
            }
            cdata = malloc(csize ? csize : 1);
            if (!cdata || (csize && fread(cdata, 1, csize, f) != csize)) {
                free(cdata);
                fclose(f);
                return -1;
            }
            if (method == 0) {
                if (cb(name, cdata, csize, ctx) != 0) {
                    free(cdata);
                    fclose(f);
                    return -1;
                }
            } else if (method == 8) {
                udata = malloc(usize ? usize : 1);
                if (!udata || inflate_buf(cdata, csize, udata, usize) != 0) {
                    free(udata);
                    free(cdata);
                    fclose(f);
                    return -1;
                }
                if (cb(name, udata, usize, ctx) != 0) {
                    free(udata);
                    free(cdata);
                    fclose(f);
                    return -1;
                }
                free(udata);
            } else {
                free(cdata);
                fclose(f);
                return -1;
            }
            free(cdata);
            if (fseek(f, cd_pos, SEEK_SET) != 0) {
                fclose(f);
                return -1;
            }
        }
    }
    fclose(f);
    return 0;
}

struct extract_ctx {
    const char *dest;
};

static int extract_cb(const char *name, const unsigned char *data, size_t n, void *v) {
    struct extract_ctx *c = v;
    char path[MAX_PATH];
    snprintf(path, sizeof(path), "%s/%s", c->dest, name);
    return write_all(path, data, n, 0644);
}

int zip_extract(const char *zip, const char *dest) {
    struct extract_ctx c;
    mkdir_p(dest, 0755);
    c.dest = dest;
    return zip_iter(zip, extract_cb, &c);
}

struct file_ctx {
    const char *member;
    char *out;
    size_t cap;
    int found;
    int depth;
};

static int file_cb(const char *name, const unsigned char *data, size_t n, void *v) {
    struct file_ctx *c = v;
    const char *base = name;
    const char *sl;
    int depth = 0;
    for (sl = name; *sl; sl++)
        if (*sl == '/') depth++;
    sl = strrchr(name, '/');
    if (sl) base = sl + 1;
    if (strcasecmp(name, c->member) && strcasecmp(base, c->member)) return 0;
    /* The shallowest match wins, so a nested copy cannot shadow the root one. */
    if (c->found && depth >= c->depth) return 0;
    c->depth = depth;
    if (n >= c->cap) n = c->cap - 1;
    memcpy(c->out, data, n);
    c->out[n] = 0;
    c->found = 1;
    return 0;
}

int zip_extract_file(const char *zip, const char *member, char *out, size_t cap) {
    struct file_ctx c;
    c.member = member;
    c.out = out;
    c.cap = cap;
    c.found = 0;
    out[0] = 0;
    if (zip_iter(zip, file_cb, &c) != 0) return -1;
    return c.found ? 0 : -1;
}
