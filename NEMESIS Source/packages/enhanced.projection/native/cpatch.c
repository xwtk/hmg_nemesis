/* Scan a live CarPlay library and patch the one matching instruction.
 * A file offset from the 260507 image is not an input. Zero matches or
 * more than one match leaves the destination unwritten.
 *
 *   cpatch check  <file> core|audio|utils     0 stock, 10 already, 1 refuse
 *   cpatch apply  <src> <dst> core|audio|utils
 *   cpatch locate <file> core|audio|utils
 *   cpatch test   <core> <audio> <utils>
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define ST_STOCK 0
#define ST_ALREADY 10
#define ST_REFUSE 1

#define ET_DYN 3
#define EM_ARM 40
#define PT_LOAD 1
#define PT_DYNAMIC 2
#define PF_X 1
#define PF_W 2
#define DT_NULL 0
#define DT_HASH 4
#define DT_STRTAB 5
#define DT_SYMTAB 6
#define DT_PLTRELSZ 2
#define DT_JMPREL 23
#define DT_PLTREL 20
#define DT_REL 17
#define R_ARM_JUMP_SLOT 22
#define MARKER 0x43505631u /* CPV1 */

struct image {
    uint8_t *b;
    size_t n;
    uint32_t phoff, phnum, phentsize;
};

struct load {
    uint32_t off, va, filesz, memsz, flags;
    uint32_t phdr_off;
};

static uint16_t r16(const uint8_t *p)
{
    return (uint16_t)(p[0] | (p[1] << 8));
}

static uint32_t r32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void w32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

static int rd_file(const char *path, struct image *im)
{
    FILE *f = fopen(path, "rb");
    long sz;
    if (!f)
        return -1;
    if (fseek(f, 0, SEEK_END) != 0) {
        fclose(f);
        return -1;
    }
    sz = ftell(f);
    if (sz < 64 || sz > 8 * 1024 * 1024) {
        fclose(f);
        return -1;
    }
    rewind(f);
    im->b = (uint8_t *)malloc((size_t)sz);
    if (!im->b) {
        fclose(f);
        return -1;
    }
    if (fread(im->b, 1, (size_t)sz, f) != (size_t)sz) {
        free(im->b);
        fclose(f);
        return -1;
    }
    fclose(f);
    im->n = (size_t)sz;
    return 0;
}

static int wr_file(const char *path, const uint8_t *b, size_t n)
{
    FILE *f = fopen(path, "wb");
    if (!f)
        return -1;
    if (fwrite(b, 1, n, f) != n) {
        fclose(f);
        return -1;
    }
    if (fclose(f) != 0)
        return -1;
    return 0;
}

static int parse_elf(struct image *im)
{
    if (im->n < 52 || memcmp(im->b, "\x7f" "ELF", 4) != 0)
        return -1;
    if (im->b[4] != 1 || im->b[5] != 1)
        return -1;
    if (r16(im->b + 16) != ET_DYN || r16(im->b + 18) != EM_ARM)
        return -1;
    im->phoff = r32(im->b + 28);
    im->phentsize = r16(im->b + 42);
    im->phnum = r16(im->b + 44);
    if (im->phentsize < 32 || im->phoff + (uint32_t)im->phnum * im->phentsize > im->n)
        return -1;
    return 0;
}

static int nth_load(const struct image *im, int which, struct load *o)
{
    uint32_t i, seen = 0;
    for (i = 0; i < im->phnum; i++) {
        const uint8_t *p = im->b + im->phoff + i * im->phentsize;
        if (r32(p) != PT_LOAD)
            continue;
        if ((int)seen == which) {
            o->off = r32(p + 4);
            o->va = r32(p + 8);
            o->filesz = r32(p + 16);
            o->memsz = r32(p + 20);
            o->flags = r32(p + 24);
            o->phdr_off = im->phoff + i * im->phentsize;
            return 0;
        }
        seen++;
    }
    return -1;
}

static int va_ok(const struct image *im, uint32_t va, uint32_t *off)
{
    int i;
    struct load L;
    for (i = 0; nth_load(im, i, &L) == 0; i++) {
        if (va >= L.va && va - L.va < L.filesz) {
            *off = L.off + (va - L.va);
            return *off < im->n ? 0 : -1;
        }
    }
    return -1;
}

static int dyn_find(const struct image *im, int32_t tag, uint32_t *val)
{
    int i;
    for (i = 0; i < (int)im->phnum; i++) {
        const uint8_t *p = im->b + im->phoff + (uint32_t)i * im->phentsize;
        uint32_t off, sz, j;
        if (r32(p) != PT_DYNAMIC)
            continue;
        off = r32(p + 4);
        sz = r32(p + 16);
        if (off + sz > im->n)
            return -1;
        for (j = 0; j + 8 <= sz; j += 8) {
            int32_t t = (int32_t)r32(im->b + off + j);
            if (t == DT_NULL)
                return -1;
            if (t == tag) {
                *val = r32(im->b + off + j + 4);
                return 0;
            }
        }
    }
    return -1;
}

static int sym_by_name(const struct image *im, const char *name, uint32_t *value, uint32_t *size)
{
    uint32_t hash_va, str_va, sym_va, nchain, i, hoff, soff, yoff;
    if (dyn_find(im, DT_HASH, &hash_va) || dyn_find(im, DT_STRTAB, &str_va) ||
        dyn_find(im, DT_SYMTAB, &sym_va))
        return -1;
    if (va_ok(im, hash_va, &hoff) || va_ok(im, str_va, &soff) || va_ok(im, sym_va, &yoff))
        return -1;
    if (hoff + 8 > im->n)
        return -1;
    nchain = r32(im->b + hoff + 4);
    if (nchain > 100000)
        return -1;
    for (i = 0; i < nchain; i++) {
        uint32_t so = yoff + i * 16;
        uint32_t no, noff;
        if (so + 16 > im->n)
            return -1;
        no = r32(im->b + so);
        if (va_ok(im, str_va + no, &noff) != 0)
            continue;
        if (noff >= im->n)
            continue;
        if (strcmp((char *)im->b + noff, name) == 0) {
            *value = r32(im->b + so + 4);
            *size = r32(im->b + so + 8);
            return 0;
        }
    }
    return -1;
}

static int fit25(int32_t off)
{
    return (off & 1) == 0 && off >= -(1 << 24) && off < (1 << 24);
}

static void put16(uint8_t *p, uint16_t hw)
{
    p[0] = (uint8_t)hw;
    p[1] = (uint8_t)(hw >> 8);
}

static int enc_wide(uint32_t addr, uint32_t target, int kind, uint8_t out[4])
{
    uint32_t pc = (kind == 2) ? ((addr + 4) & ~3u) : (addr + 4);
    int32_t off = (int32_t)target - (int32_t)pc;
    uint32_t imm, S, I1, I2, imm10, imm11, J1, J2;
    uint16_t hw1, hw2;
    if (kind == 2) {
        if ((target & 3) || (off & 1))
            return -1;
    } else if (!fit25(off)) {
        return -1;
    }
    if (off < -(1 << 24) || off >= (1 << 24))
        return -1;
    imm = off < 0 ? (uint32_t)off + (1u << 25) : (uint32_t)off;
    S = (imm >> 24) & 1;
    I1 = (imm >> 23) & 1;
    I2 = (imm >> 22) & 1;
    imm10 = (imm >> 12) & 0x3ff;
    J1 = (~(I1 ^ S)) & 1;
    J2 = (~(I2 ^ S)) & 1;
    hw1 = (uint16_t)(0xF000 | (S << 10) | imm10);
    if (kind == 2) {
        imm11 = (imm >> 2) & 0x3ff;
        hw2 = (uint16_t)(0xC000 | (J1 << 13) | (J2 << 11) | (imm11 << 1));
    } else {
        imm11 = (imm >> 1) & 0x7ff;
        hw2 = (uint16_t)((kind == 1 ? 0xD000 : 0x9000) | (J1 << 13) | (J2 << 11) | imm11);
    }
    put16(out, hw1);
    put16(out + 2, hw2);
    return 0;
}

static int dec_bcond(uint32_t addr, uint16_t hw1, uint16_t hw2, int *cond, uint32_t *target)
{
    uint32_t S, J1, J2, imm6, imm11, imm;
    int32_t off;
    if ((hw1 & 0xF800) != 0xF000 || (hw2 & 0xD000) != 0x8000)
        return -1;
    S = (hw1 >> 10) & 1;
    *cond = (int)((hw1 >> 6) & 0xF);
    imm6 = hw1 & 0x3f;
    J1 = (hw2 >> 13) & 1;
    J2 = (hw2 >> 11) & 1;
    imm11 = hw2 & 0x7ff;
    imm = (S << 20) | (J2 << 19) | (J1 << 18) | (imm6 << 12) | (imm11 << 1);
    off = (int32_t)(imm << 11) >> 11;
    *target = addr + 4 + (uint32_t)off;
    return 0;
}

static int dec_bl(uint32_t addr, uint16_t hw1, uint16_t hw2, int kind, uint32_t *target)
{
    uint32_t S, J1, J2, I1, I2, imm10, imm, pc;
    int32_t off;
    if ((hw1 & 0xF800) != 0xF000)
        return -1;
    if (kind == 2) {
        if ((hw2 & 0xD000) != 0xC000)
            return -1;
    } else if (kind == 1) {
        if ((hw2 & 0xD000) != 0xD000)
            return -1;
    } else if ((hw2 & 0xD000) != 0x9000) {
        return -1;
    }
    S = (hw1 >> 10) & 1;
    imm10 = hw1 & 0x3ff;
    J1 = (hw2 >> 13) & 1;
    J2 = (hw2 >> 11) & 1;
    I1 = (~(J1 ^ S)) & 1;
    I2 = (~(J2 ^ S)) & 1;
    if (kind == 2) {
        uint32_t imm10l = (hw2 >> 1) & 0x3ff;
        imm = (S << 24) | (I1 << 23) | (I2 << 22) | (imm10 << 12) | (imm10l << 2);
        pc = (addr + 4) & ~3u;
    } else {
        uint32_t imm11 = hw2 & 0x7ff;
        imm = (S << 24) | (I1 << 23) | (I2 << 22) | (imm10 << 12) | (imm11 << 1);
        pc = addr + 4;
    }
    off = (int32_t)(imm << 7) >> 7;
    *target = pc + (uint32_t)off;
    return 0;
}

static int dec_short_b(uint32_t addr, uint16_t hw, uint32_t *target)
{
    uint32_t imm11, off;
    if ((hw & 0xF800) != 0xE000)
        return -1;
    imm11 = hw & 0x7ff;
    off = imm11 << 1;
    if (off & 0x800)
        off -= 0x1000;
    *target = addr + 4 + off;
    return 0;
}

static int dec_ldr_lit(uint32_t addr, uint16_t hw, int *rd, uint32_t *pool)
{
    uint32_t imm;
    if ((hw & 0xF800) != 0x4800)
        return -1;
    *rd = (hw >> 8) & 7;
    imm = (uint32_t)(hw & 0xff) << 2;
    *pool = ((addr + 4) & ~3u) + imm;
    return 0;
}

static int selftest_enc(void)
{
    uint8_t o[4];
    int cond;
    uint32_t t;
    if (enc_wide(0xfbba, 0xf8d0, 0, o) != 0 || memcmp(o, "\xff\xf7\x89\xbe", 4) != 0)
        return -1;
    if (dec_bcond(0xfbba, 0xF43F, 0xAE89, &cond, &t) != 0 || cond != 0 || t != 0xf8d0)
        return -1;
    if (dec_bl(0x3d00, 0xF7FF, 0xFED4, 1, &t) != 0 || t != 0x3aac)
        return -1;
    if (dec_bl(0x3c2e, 0xF7FE, 0xED5E, 2, &t) != 0 || t != 0x26ec)
        return -1;
    if (enc_wide(0x3d00, 0x3aac, 1, o) != 0 || memcmp(o, "\xff\xf7\xd4\xfe", 4) != 0)
        return -1;
    if (enc_wide(0x3c2e, 0x26ec, 2, o) != 0 || memcmp(o, "\xfe\xf7\x5e\xed", 4) != 0)
        return -1;
    return 0;
}

/* ---------- core ---------- */

/* ProcessFrames passes -0x10000 to the frame reads, which NetSocket_Wait turns
 * into 200 ms. A Wi-Fi stall that long ends the stream, and nothing restarts it.
 * movs r7,#0 / add.w r4,r6,#0x38 / movt r7,#0xffff becomes 10 seconds. */
static const uint8_t READ_WAIT_STOCK[10] = {
    0x00, 0x27, 0x06, 0xf1, 0x38, 0x04, 0xcf, 0xf6, 0xff, 0x77
};
static const uint8_t READ_WAIT_PATCH[10] = {
    0x0a, 0x27, 0x06, 0xf1, 0x38, 0x04, 0xc0, 0xf2, 0x00, 0x07
};

struct core_hit {
    int stock, already, wait_stock, wait_already;
    uint32_t beq_off, target, wait_off;
};

static int scan_core(struct image *im, struct core_hit *h)
{
    size_t i;
    memset(h, 0, sizeof(*h));
    if (parse_elf(im) != 0)
        return -1;
    for (i = 0; i + 6 <= im->n; i += 2) {
        int cond;
        uint32_t target;
        uint16_t hw1, hw2;
        if (i + sizeof READ_WAIT_STOCK <= im->n) {
            if (memcmp(im->b + i, READ_WAIT_STOCK, sizeof READ_WAIT_STOCK) == 0) {
                h->wait_stock++;
                h->wait_off = (uint32_t)i;
            } else if (memcmp(im->b + i, READ_WAIT_PATCH, sizeof READ_WAIT_PATCH) == 0) {
                h->wait_already++;
            }
        }
        if (im->b[i] != 0x10 || im->b[i + 1] != 0x2c)
            continue;
        hw1 = r16(im->b + i + 2);
        hw2 = r16(im->b + i + 4);
        if (dec_bcond((uint32_t)(i + 2), hw1, hw2, &cond, &target) == 0 && cond == 0) {
            h->stock++;
            h->beq_off = (uint32_t)(i + 2);
            h->target = target;
            continue;
        }
        if (dec_bl((uint32_t)(i + 2), hw1, hw2, 0, &target) == 0)
            h->already++;
    }
    if (h->stock == 1 && h->already == 0 && h->wait_stock == 1 && h->wait_already == 0)
        return ST_STOCK;
    if (h->stock == 0 && h->already == 1 && h->wait_stock == 0 && h->wait_already == 1)
        return ST_ALREADY;
    return ST_REFUSE;
}

static int patch_core(struct image *im)
{
    struct core_hit h;
    uint8_t op[4];
    int st = scan_core(im, &h);
    if (st != ST_STOCK)
        return st;
    if (enc_wide(h.beq_off, h.target, 0, op) != 0)
        return ST_REFUSE;
    memcpy(im->b + h.beq_off, op, 4);
    memcpy(im->b + h.wait_off, READ_WAIT_PATCH, sizeof READ_WAIT_PATCH);
    if (scan_core(im, &h) != ST_ALREADY)
        return ST_REFUSE;
    return ST_STOCK;
}

/* ---------- audio ---------- */

struct audio_hit {
    uint32_t mask_off, mask_ret;
    uint32_t bl_in, bl_out, in_tgt, out_tgt;
    uint32_t append_off, cont, pool_word, createdict;
    uint32_t media_va;
    uint32_t append_plt, release_plt;
    uint32_t gap_off, gap_va, gap_end;
    uint32_t phdr_off;
    int stock_sites;
    int marker;
};

static int count_pat(const struct image *im, const uint8_t *pat, size_t n, uint32_t *only)
{
    size_t i;
    int c = 0;
    for (i = 0; i + n <= im->n; i++) {
        if (memcmp(im->b + i, pat, n) == 0) {
            *only = (uint32_t)i;
            c++;
        }
    }
    return c;
}

static int scan_audio(struct image *im, struct audio_hit *h)
{
    static const uint8_t movs[] = {0x22, 0x46, 0x40, 0x46, 0x49, 0x46};
    static const uint8_t mid[] = {0x22, 0x46, 0x06, 0x46, 0x0f, 0x46, 0x40, 0x46, 0x49, 0x46};
    static const uint8_t orr[] = {0x40, 0xea, 0x06, 0x02};
    static const uint8_t media_pat[] = {0x79, 0x44, 0x64, 0x20, 0x98, 0x31};
    static const uint8_t app_pat[] = {0x79, 0x44, 0xe8, 0x31, 0x65, 0x20};
    struct load rx, other;
    uint32_t mask = 0, getters = 0, append = 0, media = 0;
    uint32_t plt_a = 0, plt_b = 0;
    int plt_n = 0;
    int i, rd;
    size_t k;
    memset(h, 0, sizeof(*h));
    if (parse_elf(im) != 0)
        return -1;
    if (nth_load(im, 0, &rx) != 0 || (rx.flags & PF_X) == 0 || rx.filesz != rx.memsz)
        return ST_REFUSE;
    h->gap_off = rx.off + rx.filesz;
    h->gap_va = rx.va + rx.filesz;
    h->phdr_off = rx.phdr_off;
    h->gap_end = im->n;
    for (i = 1; nth_load(im, i, &other) == 0; i++) {
        if (other.off > h->gap_off && other.off < h->gap_end)
            h->gap_end = other.off;
    }
    if (h->gap_end < h->gap_off || h->gap_end - h->gap_off < 256)
        return ST_REFUSE;
    for (k = rx.off; k + 4 <= h->gap_end && k + 4 <= im->n; k++) {
        if (r32(im->b + k) == MARKER)
            h->marker = 1;
    }
    for (k = 0; k + 10 <= im->n; k += 2) {
        uint32_t blx_at, tgt;
        if (im->b[k] != 0x98 || im->b[k + 1] != 0x31)
            continue;
        if (k + 10 > im->n)
            continue;
        if (memcmp(im->b + k + 6, "\x00\x22\x00\x21", 4) != 0)
            continue;
        blx_at = (uint32_t)k + 2;
        if (dec_bl(blx_at, r16(im->b + blx_at), r16(im->b + blx_at + 2), 2, &tgt) != 0)
            continue;
        mask = (uint32_t)k + 6;
        h->mask_off = mask;
        if (dec_short_b(mask + 4, r16(im->b + mask + 4), &h->mask_ret) != 0)
            return ST_REFUSE;
        h->stock_sites++;
    }
    if (h->stock_sites > 1)
        return ST_REFUSE;
    for (k = 0; k + 28 <= im->n; k += 2) {
        uint32_t in_at, out_at;
        if (memcmp(im->b + k, movs, 6) != 0)
            continue;
        in_at = (uint32_t)k + 6;
        if (memcmp(im->b + in_at + 4, mid, 10) != 0)
            continue;
        out_at = in_at + 14;
        if (memcmp(im->b + out_at + 4, orr, 4) != 0)
            continue;
        if (dec_bl(in_at, r16(im->b + in_at), r16(im->b + in_at + 2), 1, &h->in_tgt) != 0)
            continue;
        if (dec_bl(out_at, r16(im->b + out_at), r16(im->b + out_at + 2), 1, &h->out_tgt) != 0)
            continue;
        h->bl_in = in_at;
        h->bl_out = out_at;
        getters++;
    }
    if (getters > 1)
        return ST_REFUSE;
    if (count_pat(im, app_pat, 6, &append) == 1) {
        uint32_t ldr_at = append - 4;
        int rdn;
        uint32_t paddr, bl_at;
        if (ldr_at + 12 < im->n && im->b[append - 2] == 0x22 && im->b[append - 1] == 0x46 &&
            dec_ldr_lit(ldr_at, r16(im->b + ldr_at), &rdn, &paddr) == 0 && rdn == 1 &&
            paddr + 4 <= im->n) {
            h->append_off = ldr_at;
            h->cont = append;
            h->pool_word = r32(im->b + paddr);
            bl_at = append + 8;
            if (dec_bl(bl_at, r16(im->b + bl_at), r16(im->b + bl_at + 2), 1, &h->createdict) != 0)
                h->createdict = 0;
            else
                h->stock_sites++;
        }
    } else if (count_pat(im, app_pat, 6, &append) > 1) {
        return ST_REFUSE;
    }
    if (count_pat(im, media_pat, 6, &media) == 1) {
        uint32_t ldr_at = media - 4;
        uint32_t paddr;
        if (dec_ldr_lit(ldr_at, r16(im->b + ldr_at), &rd, &paddr) == 0 && rd == 1 &&
            paddr + 4 <= im->n) {
            h->media_va = r32(im->b + paddr) + (media + 4) + 0x98;
            h->stock_sites++;
        }
    } else if (count_pat(im, media_pat, 6, &media) > 1) {
        return ST_REFUSE;
    }
    for (k = 0; k + 8 <= im->n; k += 2) {
        uint32_t a, b, t1, t2;
        if (im->b[k] != 0x38 || im->b[k + 1] != 0x46)
            continue;
        if (k < 4)
            continue;
        a = (uint32_t)k - 4;
        b = (uint32_t)k + 2;
        if (dec_bl(a, r16(im->b + a), r16(im->b + a + 2), 2, &t1) != 0)
            continue;
        if (dec_bl(b, r16(im->b + b), r16(im->b + b + 2), 2, &t2) != 0)
            continue;
        if (plt_n == 0) {
            plt_a = t1;
            plt_b = t2;
        } else if (t1 != plt_a || t2 != plt_b) {
            return ST_REFUSE;
        }
        plt_n++;
    }
    h->append_plt = plt_a;
    h->release_plt = plt_b;
    if (h->marker) {
        if (h->mask_off || h->append_off)
            return ST_REFUSE;
        return ST_ALREADY;
    }
    if (h->mask_off && h->bl_in && h->append_off && h->media_va && h->createdict && plt_n > 0)
        return ST_STOCK;
    return ST_REFUSE;
}

struct asmbuf {
    uint8_t b[640];
    uint32_t base, len;
    uint32_t lit[8];
    int nlit;
    struct {
        uint32_t at;
        int cond;
        int lab;
    } br[24];
    int nbr;
    uint32_t lab[16];
    int havelab[16];
};

static void ab_init(struct asmbuf *a, uint32_t base)
{
    memset(a, 0, sizeof(*a));
    a->base = base;
}

static uint32_t ab_here(const struct asmbuf *a)
{
    return a->base + a->len;
}

static int ab_byte(struct asmbuf *a, uint8_t v)
{
    if (a->len >= sizeof(a->b))
        return -1;
    a->b[a->len++] = v;
    return 0;
}

static int ab_hw(struct asmbuf *a, uint16_t hw)
{
    if (ab_byte(a, (uint8_t)hw) || ab_byte(a, (uint8_t)(hw >> 8)))
        return -1;
    return 0;
}

static int ab_movw(struct asmbuf *a, int rd, uint16_t imm, int top)
{
    uint32_t imm4 = (imm >> 12) & 0xf;
    uint32_t i = (imm >> 11) & 1;
    uint32_t imm3 = (imm >> 8) & 7;
    uint32_t imm8 = imm & 0xff;
    uint16_t hw1 = (uint16_t)((top ? 0xF2C0 : 0xF240) | (i << 10) | imm4);
    uint16_t hw2 = (uint16_t)((imm3 << 12) | ((uint32_t)rd << 8) | imm8);
    return ab_hw(a, hw1) || ab_hw(a, hw2);
}

static int ab_bw(struct asmbuf *a, uint32_t target, int kind)
{
    uint8_t o[4];
    if (enc_wide(ab_here(a), target, kind, o) != 0)
        return -1;
    memcpy(a->b + a->len, o, 4);
    a->len += 4;
    return 0;
}

static int ab_bcond(struct asmbuf *a, int cond, int lab)
{
    if (a->nbr >= 24)
        return -1;
    a->br[a->nbr].at = a->len;
    a->br[a->nbr].cond = cond;
    a->br[a->nbr].lab = lab;
    a->nbr++;
    return ab_hw(a, 0);
}

static int ab_label(struct asmbuf *a, int lab)
{
    a->lab[lab] = ab_here(a);
    a->havelab[lab] = 1;
    return 0;
}

static int ab_lit(struct asmbuf *a, uint32_t word)
{
    if (a->nlit >= 8)
        return -1;
    a->lit[a->nlit] = word;
    return a->nlit++;
}

static int ab_ldr_lit(struct asmbuf *a, int rd, int lit)
{
    /* placeholder imm8 in the low byte; resolved later. lit index stored in a side channel
     * by using imm8 values 0x80+lit, which cannot be a real forward slot we want. */
    if (lit < 0 || lit > 7)
        return -1;
    return ab_hw(a, (uint16_t)(0x4800 | (rd << 8) | (0x80 + lit)));
}

static int ab_finish(struct asmbuf *a)
{
    uint32_t pool;
    int i;
    while (a->len & 3) {
        if (ab_byte(a, 0))
            return -1;
    }
    pool = ab_here(a);
    for (i = 0; i < a->nlit; i++) {
        if (a->len + 4 > sizeof(a->b))
            return -1;
        w32(a->b + a->len, a->lit[i]);
        a->len += 4;
    }
    for (i = 0; i < (int)a->len; i += 2) {
        uint16_t hw = r16(a->b + i);
        uint32_t addr, pc, slot, imm;
        int lit, rd;
        if ((hw & 0xF800) != 0x4800)
            continue;
        if ((hw & 0x80) == 0)
            continue;
        lit = hw & 0x7f;
        if (lit >= a->nlit)
            return -1;
        rd = (hw >> 8) & 7;
        addr = a->base + (uint32_t)i;
        pc = (addr + 4) & ~3u;
        slot = pool + (uint32_t)lit * 4;
        if (slot < pc || ((slot - pc) & 3) || (slot - pc) > 1020)
            return -1;
        imm = (slot - pc) >> 2;
        put16(a->b + i, (uint16_t)(0x4800 | (rd << 8) | imm));
    }
    for (i = 0; i < a->nbr; i++) {
        uint32_t addr = a->base + a->br[i].at;
        int32_t off;
        int imm8;
        if (!a->havelab[a->br[i].lab])
            return -1;
        off = (int32_t)a->lab[a->br[i].lab] - (int32_t)(addr + 4);
        if ((off & 1) || off < -256 || off > 254)
            return -1;
        imm8 = off >> 1;
        put16(a->b + a->br[i].at, (uint16_t)(0xD000 | (a->br[i].cond << 8) | (imm8 & 0xff)));
    }
    return 0;
}

enum {
    LAB_RET = 1,
    LAB_OR24,
    LAB_OR48,
    LAB_DO,
    LAB_SKIP
};

static int build_cave(const struct audio_hit *h, struct asmbuf *a, uint32_t *aac, uint32_t *inw,
                      uint32_t *outw, uint32_t *appw)
{
    int lit_media, lit_pool;
    uint32_t add_at;
    ab_init(a, h->gap_va);

    *aac = ab_here(a);
    if (ab_movw(a, 2, 0, 0) || ab_movw(a, 2, 0x0080, 1) || ab_hw(a, 0x2100))
        return -1;
    if (ab_bw(a, h->mask_ret, 0))
        return -1;

    *inw = ab_here(a);
    if (ab_hw(a, 0xb510) || ab_bw(a, h->in_tgt, 1))
        return -1;
    {
        uint32_t hole_in = a->len;
        uint32_t hole_out, adj;
        if (ab_hw(a, 0) || ab_hw(a, 0) || ab_hw(a, 0xbd10))
            return -1;
        *outw = ab_here(a);
        if (ab_hw(a, 0xb510) || ab_bw(a, h->out_tgt, 1))
            return -1;
        hole_out = a->len;
        if (ab_hw(a, 0) || ab_hw(a, 0) || ab_hw(a, 0xbd10))
            return -1;
        adj = ab_here(a);
        if (enc_wide(a->base + hole_in, adj, 1, a->b + hole_in) != 0)
            return -1;
        if (enc_wide(a->base + hole_out, adj, 1, a->b + hole_out) != 0)
            return -1;

        if (ab_hw(a, 0x2c01) || ab_bcond(a, 1, LAB_RET))
            return -1;
        if (ab_hw(a, 0x2840) || ab_bcond(a, 0, LAB_OR24))
            return -1;
        if (ab_hw(a, 0x2850) || ab_bcond(a, 0, LAB_OR24))
            return -1;
        if (ab_hw(a, 0x2854) || ab_bcond(a, 0, LAB_OR48))
            return -1;
        if (ab_movw(a, 3, 0x400, 0) || ab_hw(a, 0x4298) || ab_bcond(a, 1, LAB_RET))
            return -1;
        ab_label(a, LAB_OR48);
        if (ab_movw(a, 3, 0, 0) || ab_movw(a, 3, 0x4000, 1))
            return -1;
        if (ab_hw(a, 0xe000)) /* b.n placeholder, patched to skip or24's movs */
            return -1;
        {
            uint32_t bsite = a->len - 2;
            uint32_t dest;
            ab_label(a, LAB_OR24);
            if (ab_movw(a, 3, 0, 0) || ab_movw(a, 3, 0x2000, 1))
                return -1;
            dest = ab_here(a);
            {
                int32_t off = (int32_t)dest - (int32_t)(a->base + bsite + 4);
                int imm11;
                if ((off & 1) || off < -2048 || off > 2046)
                    return -1;
                imm11 = off >> 1;
                put16(a->b + bsite, (uint16_t)(0xE000 | (imm11 & 0x7ff)));
            }
        }
        ab_label(a, LAB_DO);
        if (ab_hw(a, 0x4318)) /* orrs r0, r3 */
            return -1;
        ab_label(a, LAB_RET);
        if (ab_hw(a, 0x4770))
            return -1;

        *appw = ab_here(a);
        if (ab_hw(a, 0xb580) || ab_hw(a, 0xb082))
            return -1;
        if (ab_hw(a, 0x2066))
            return -1;
        lit_media = ab_lit(a, 0);
        if (lit_media < 0 || ab_ldr_lit(a, 1, lit_media))
            return -1;
        add_at = ab_here(a);
        if (ab_hw(a, 0x4479))
            return -1;
        a->lit[lit_media] = h->media_va - (add_at + 4);
        if (ab_hw(a, 0x4622) || ab_hw(a, 0x466b))
            return -1;
        if (ab_bw(a, h->createdict, 1))
            return -1;
        if (ab_hw(a, 0x2800) || ab_bcond(a, 0, LAB_SKIP))
            return -1;
        if (ab_hw(a, 0x4607) || ab_hw(a, 0x4628) || ab_hw(a, 0x4639))
            return -1;
        if (ab_bw(a, h->append_plt, 2))
            return -1;
        if (ab_hw(a, 0x4638))
            return -1;
        if (ab_bw(a, h->release_plt, 2))
            return -1;
        ab_label(a, LAB_SKIP);
        if (ab_hw(a, 0xb002))
            return -1;
        lit_pool = ab_lit(a, h->pool_word);
        if (lit_pool < 0 || ab_ldr_lit(a, 1, lit_pool))
            return -1;
        if (ab_hw(a, 0x4622) || ab_hw(a, 0xe8bd) || ab_hw(a, 0x4080))
            return -1;
        if (ab_bw(a, h->cont, 0))
            return -1;
        if (ab_finish(a))
            return -1;
        if (a->len + 4 > sizeof(a->b))
            return -1;
        w32(a->b + a->len, MARKER);
        a->len += 4;
        return 0;
    }
}

static int patch_audio(struct image *im)
{
    struct audio_hit h;
    struct asmbuf cave;
    uint32_t aac, inw, outw, appw, end;
    uint8_t op[4];
    int st = scan_audio(im, &h);
    if (st != ST_STOCK)
        return st;
    if (build_cave(&h, &cave, &aac, &inw, &outw, &appw) != 0) {
        fprintf(stderr, "cpatch: audio cave failed\n");
        return ST_REFUSE;
    }
    end = h.gap_off + cave.len;
    if (end > h.gap_end)
        return ST_REFUSE;
    {
        size_t k;
        for (k = h.gap_off; k < end; k++) {
            if (im->b[k] != 0)
                return ST_REFUSE;
        }
    }
    if (enc_wide(h.mask_off, aac, 0, op) != 0)
        return ST_REFUSE;
    memcpy(im->b + h.mask_off, op, 4);
    if (enc_wide(h.bl_in, inw, 1, op) != 0)
        return ST_REFUSE;
    memcpy(im->b + h.bl_in, op, 4);
    if (enc_wide(h.bl_out, outw, 1, op) != 0)
        return ST_REFUSE;
    memcpy(im->b + h.bl_out, op, 4);
    if (enc_wide(h.append_off, appw, 0, op) != 0)
        return ST_REFUSE;
    memcpy(im->b + h.append_off, op, 4);
    memcpy(im->b + h.gap_off, cave.b, cave.len);
    w32(im->b + h.phdr_off + 16, (h.gap_off - (r32(im->b + h.phdr_off + 4))) + cave.len);
    /* filesz = gap_off - p_offset + cave.len, and gap_off was p_offset+old filesz,
     * so new filesz = old filesz + cave.len. Same for memsz. */
    {
        uint32_t filesz = r32(im->b + h.phdr_off + 16);
        uint32_t off = r32(im->b + h.phdr_off + 4);
        if (off + filesz != end)
            return ST_REFUSE;
        w32(im->b + h.phdr_off + 20, filesz);
    }
    st = scan_audio(im, &h);
    if (st != ST_ALREADY)
        return ST_REFUSE;
    return ST_STOCK;
}

/* ---------- utils ---------- */

static const char *UTIL_NAMES[5] = {
    "AudioConverterNew",
    "AudioConverterSetProperty",
    "AudioConverterFillComplexBuffer",
    "AudioConverterReset",
    "AudioConverterDispose"
};

static const char *CONV_NAMES[5] = {
    "CarPlayConverterNew",
    "CarPlayConverterSetProperty",
    "CarPlayConverterFillComplexBuffer",
    "CarPlayConverterReset",
    "CarPlayConverterDispose"
};

struct util_fn {
    uint32_t addr, size;
};

struct util_hit {
    struct util_fn fn[5];
    uint32_t dlopen_plt, dlsym_plt;
    uint32_t bss, phdr_off, old_memsz;
    int stubs;
};

static int arm_add_imm(uint32_t insn, uint32_t *rd, uint32_t *rn, uint32_t *imm)
{
    uint32_t imm8, rot;
    if ((insn & 0x0fe00000u) != 0x02800000u)
        return -1;
    *rd = (insn >> 12) & 0xf;
    *rn = (insn >> 16) & 0xf;
    imm8 = insn & 0xff;
    rot = ((insn >> 8) & 0xf) * 2;
    if (rot == 0)
        *imm = imm8;
    else
        *imm = (imm8 >> rot) | (imm8 << (32 - rot));
    return 0;
}

static int find_plt(const struct image *im, const char *name, uint32_t *plt)
{
    uint32_t jmprel, relsz, pltrel, str_va, sym_va, i, roff, yoff, soff;
    uint32_t got = 0;
    int found = 0;
    struct load rx;
    if (dyn_find(im, DT_JMPREL, &jmprel) || dyn_find(im, DT_PLTRELSZ, &relsz) ||
        dyn_find(im, DT_PLTREL, &pltrel) || dyn_find(im, DT_STRTAB, &str_va) ||
        dyn_find(im, DT_SYMTAB, &sym_va))
        return -1;
    if (pltrel != DT_REL)
        return -1;
    if (va_ok(im, jmprel, &roff) || va_ok(im, sym_va, &yoff) || va_ok(im, str_va, &soff))
        return -1;
    for (i = 0; i + 8 <= relsz; i += 8) {
        uint32_t info, symi, no, noff;
        if (roff + i + 8 > im->n)
            return -1;
        info = r32(im->b + roff + i + 4);
        if ((info & 0xff) != R_ARM_JUMP_SLOT)
            continue;
        symi = info >> 8;
        if (yoff + symi * 16 + 16 > im->n)
            return -1;
        no = r32(im->b + yoff + symi * 16);
        if (va_ok(im, str_va + no, &noff) != 0)
            continue;
        if (strcmp((char *)im->b + noff, name) != 0)
            continue;
        if (found)
            return -1;
        got = r32(im->b + roff + i);
        found = 1;
    }
    if (!found)
        return -1;
    if (nth_load(im, 0, &rx) != 0)
        return -1;
    found = 0;
    for (i = rx.off; i + 12 <= rx.off + rx.filesz && i + 12 <= im->n; i += 4) {
        uint32_t w0 = r32(im->b + i);
        uint32_t w1 = r32(im->b + i + 4);
        uint32_t w2 = r32(im->b + i + 8);
        uint32_t rd, rn, imm0, imm1, imm12, addr;
        if (arm_add_imm(w0, &rd, &rn, &imm0) || rd != 12 || rn != 15)
            continue;
        if (arm_add_imm(w1, &rd, &rn, &imm1) || rd != 12 || rn != 12)
            continue;
        /* ldr pc, [ip, #imm]! : 010 P=1 U B=0 W=1 L=1, Rn=ip, Rd=pc */
        if ((w2 & 0x0fff0000u) != 0x05bc0000u)
            continue;
        if (((w2 >> 12) & 0xf) != 15)
            continue;
        imm12 = w2 & 0xfff;
        if (((w2 >> 23) & 1) == 0)
            addr = (i + 8) + imm0 + imm1 - imm12;
        else
            addr = (i + 8) + imm0 + imm1 + imm12;
        if (addr == got) {
            if (found)
                return -1;
            *plt = i;
            found = 1;
        }
    }
    return found ? 0 : -1;
}

static int stub_is_ours(const uint8_t *p, int index)
{
    return p[0] == 0x10 && p[1] == 0xb5 && p[2] == (uint8_t)index && p[3] == 0x24 &&
           p[8] == 0x10 && p[9] == 0xbd;
}

static int scan_utils(struct image *im, struct util_hit *h)
{
    int i;
    struct load rw;
    int have_rw = 0, n;
    memset(h, 0, sizeof(*h));
    if (parse_elf(im) != 0)
        return -1;
    for (i = 0; i < 5; i++) {
        uint32_t v, sz;
        if (sym_by_name(im, UTIL_NAMES[i], &v, &sz) != 0)
            return ST_REFUSE;
        h->fn[i].addr = v & ~1u;
        h->fn[i].size = sz;
        if (sz < 10 || h->fn[i].addr + sz > im->n)
            return ST_REFUSE;
    }
    for (n = 0; nth_load(im, n, &rw) == 0; n++) {
        if ((rw.flags & PF_W) && (rw.flags & PF_X) == 0) {
            have_rw = 1;
            break;
        }
    }
    if (!have_rw)
        return ST_REFUSE;
    h->bss = rw.va + rw.memsz;
    h->phdr_off = rw.phdr_off;
    h->old_memsz = rw.memsz;
    if (find_plt(im, "dlopen", &h->dlopen_plt) || find_plt(im, "dlsym", &h->dlsym_plt))
        return ST_REFUSE;
    h->stubs = 0;
    for (i = 0; i < 5; i++) {
        if (stub_is_ours(im->b + h->fn[i].addr, i))
            h->stubs++;
    }
    {
        int has_name = memmem(im->b, im->n, "libCarPlayConverter.so", 22) != NULL;
        if (h->stubs == 0 && !has_name)
            return ST_STOCK;
        if (h->stubs == 5 && has_name)
            return ST_ALREADY;
    }
    return ST_REFUSE;
}

/* adr helper lives here so emit can call it. */
static int ab_adr(struct asmbuf *a, int rd, uint32_t target)
{
    uint32_t addr = ab_here(a);
    uint32_t pc = (addr + 4) & ~3u;
    uint32_t off, imm8;
    if (target < pc || ((target - pc) & 3))
        return -1;
    off = target - pc;
    if (off > 1020)
        return -1;
    imm8 = off >> 2;
    return ab_hw(a, (uint16_t)(0xA000 | (rd << 8) | imm8));
}

static uint32_t layout_strings(uint32_t str_base, uint32_t *soname, uint32_t name_at[5])
{
    const char *son = "libCarPlayConverter.so";
    uint32_t cursor = (str_base + 3) & ~3u;
    int i;
    *soname = cursor;
    cursor += (uint32_t)strlen(son) + 1;
    for (i = 0; i < 5; i++) {
        cursor = (cursor + 3) & ~3u;
        name_at[i] = cursor;
        cursor += (uint32_t)strlen(CONV_NAMES[i]) + 1;
    }
    return cursor;
}

static int emit_disp(struct asmbuf *a, const struct util_hit *h, uint32_t str_base, uint32_t str_len)
{
    uint32_t add_at, delta, soname, name_at[5], cursor;
    int i;
    ab_init(a, h->fn[0].addr + 10);
    cursor = layout_strings(str_base, &soname, name_at);
    if (cursor > str_base + str_len)
        return -1;

    if (ab_hw(a, 0xb081) || ab_hw(a, 0xb40f) || ab_hw(a, 0xb560))
        return -1;
    add_at = ab_here(a) + 8;
    delta = h->bss - (add_at + 4);
    if (ab_movw(a, 5, (uint16_t)delta, 0) || ab_movw(a, 5, (uint16_t)(delta >> 16), 1))
        return -1;
    if (ab_here(a) != add_at || ab_hw(a, 0x447d))
        return -1;
    if (ab_hw(a, 0x682e) || ab_hw(a, 0x2e00) || ab_bcond(a, 1, 2))
        return -1;
    if (ab_adr(a, 0, soname) || ab_hw(a, 0x2102) || ab_bw(a, h->dlopen_plt, 2))
        return -1;
    if (ab_hw(a, 0x2800) || ab_bcond(a, 0, 3))
        return -1;
    if (ab_hw(a, 0x4606))
        return -1;
    for (i = 0; i < 5; i++) {
        uint16_t st;
        if (ab_hw(a, 0x4630) || ab_adr(a, 1, name_at[i]) || ab_bw(a, h->dlsym_plt, 2))
            return -1;
        if (ab_hw(a, 0x2800) || ab_bcond(a, 0, 3))
            return -1;
        /* str r0, [r5, #(4+4*i)] */
        st = (uint16_t)(0x6000 | ((((4 + 4 * i) / 4) & 0x1f) << 6) | (5 << 3) | 0);
        if (ab_hw(a, st))
            return -1;
    }
    if (ab_hw(a, 0x2001) || ab_hw(a, 0x6028))
        return -1;
    ab_label(a, 2);
    if (ab_hw(a, 0x462e) || ab_hw(a, 0x3604) || ab_hw(a, 0x00a0) || ab_hw(a, 0x5830))
        return -1;
    if (ab_hw(a, 0x2800) || ab_bcond(a, 0, 3))
        return -1;
    if (ab_hw(a, 0x9007))
        return -1;
    /* Tail-call. blx would set lr to the following bx and spin.
     * pop the stub frame first so FillComplexBuffer's stack args line up. */
    if (ab_hw(a, 0xe8bd) || ab_hw(a, 0x4060) || ab_hw(a, 0xbc0f))
        return -1;
    if (ab_hw(a, 0xf85d) || ab_hw(a, 0xcb04))
        return -1;
    if (ab_hw(a, 0xe8bd) || ab_hw(a, 0x4010) || ab_hw(a, 0x4760))
        return -1;
    ab_label(a, 3);
    if (ab_hw(a, 0xe8bd) || ab_hw(a, 0x4060) || ab_hw(a, 0xb004))
        return -1;
    if (ab_movw(a, 0, 0xe5b1, 0) || ab_movw(a, 0, 0xffff, 1))
        return -1;
    if (ab_hw(a, 0xb001) || ab_hw(a, 0x4770))
        return -1;
    if (ab_finish(a))
        return -1;
    if (a->len > h->fn[0].size - 10)
        return -1;
    return 0;
}

static int patch_utils(struct image *im)
{
    struct util_hit h;
    struct asmbuf disp;
    struct load rw;
    uint32_t str_base, need, next_va, i, n;
    int st = scan_utils(im, &h);
    uint8_t stub[10];
    if (st != ST_STOCK)
        return st;
    /* strings sit just after SetProperty's 10-byte stub */
    str_base = h.fn[1].addr + 10;
    need = 4 + 23 + 20 + 27 + 32 + 23 + 24;
    if (h.fn[1].size < 10 + need) {
        fprintf(stderr, "cpatch: setproperty body %u < %u\n", h.fn[1].size, 10 + need);
        return ST_REFUSE;
    }
    if (emit_disp(&disp, &h, str_base, h.fn[1].size - 10) != 0) {
        fprintf(stderr, "cpatch: dispatcher did not fit\n");
        return ST_REFUSE;
    }
    /* VA room after the RW segment */
    next_va = 0xffffffffu;
    for (n = 0; nth_load(im, (int)n, &rw) == 0; n++) {
        if (rw.va > h.bss - h.old_memsz && rw.va < next_va && rw.phdr_off != h.phdr_off)
            next_va = rw.va;
    }
    if (next_va != 0xffffffffu && h.bss + 24 > next_va)
        return ST_REFUSE;
    for (i = 0; i < 5; i++) {
        uint32_t bl_at = h.fn[i].addr + 4;
        uint8_t op[4];
        stub[0] = 0x10;
        stub[1] = 0xb5;
        stub[2] = (uint8_t)i;
        stub[3] = 0x24;
        if (enc_wide(bl_at, h.fn[0].addr + 10, 1, op) != 0)
            return ST_REFUSE;
        memcpy(stub + 4, op, 4);
        stub[8] = 0x10;
        stub[9] = 0xbd;
        memcpy(im->b + h.fn[i].addr, stub, 10);
    }
    memcpy(im->b + h.fn[0].addr + 10, disp.b, disp.len);
    /* clear the rest of New so a second match cannot form inside the old body */
    if (h.fn[0].size > 10 + disp.len)
        memset(im->b + h.fn[0].addr + 10 + disp.len, 0, h.fn[0].size - 10 - disp.len);
    {
        uint32_t soname, name_at[5];
        const char *son = "libCarPlayConverter.so";
        layout_strings(str_base, &soname, name_at);
        memset(im->b + str_base, 0, h.fn[1].size - 10);
        memcpy(im->b + soname, son, strlen(son) + 1);
        for (i = 0; i < 5; i++)
            memcpy(im->b + name_at[i], CONV_NAMES[i], strlen(CONV_NAMES[i]) + 1);
    }
    w32(im->b + h.phdr_off + 20, h.old_memsz + 24);
    st = scan_utils(im, &h);
    if (st != ST_ALREADY)
        return ST_REFUSE;
    return ST_STOCK;
}

static int load_image(const char *path, struct image *im)
{
    memset(im, 0, sizeof(*im));
    if (rd_file(path, im) != 0) {
        fprintf(stderr, "cpatch: cannot read %s\n", path);
        return -1;
    }
    return 0;
}

static int do_check(const char *path, const char *kind, int locate)
{
    struct image im;
    int st;
    if (load_image(path, &im) != 0)
        return ST_REFUSE;
    if (strcmp(kind, "core") == 0) {
        struct core_hit h;
        st = scan_core(&im, &h);
        if (locate && st == ST_STOCK)
            printf("core beq 0x%x target 0x%x\n", h.beq_off, h.target);
    } else if (strcmp(kind, "audio") == 0) {
        struct audio_hit h;
        st = scan_audio(&im, &h);
        if (locate && st == ST_STOCK) {
            printf("audio mask 0x%x ret 0x%x\n", h.mask_off, h.mask_ret);
            printf("audio in 0x%x -> 0x%x\n", h.bl_in, h.in_tgt);
            printf("audio out 0x%x -> 0x%x\n", h.bl_out, h.out_tgt);
            printf("audio append 0x%x cont 0x%x\n", h.append_off, h.cont);
            printf("audio gap 0x%x va 0x%x end 0x%x\n", h.gap_off, h.gap_va, h.gap_end);
            printf("audio media 0x%x createdict 0x%x\n", h.media_va, h.createdict);
        }
    } else if (strcmp(kind, "utils") == 0) {
        struct util_hit h;
        st = scan_utils(&im, &h);
        if (locate && st == ST_STOCK) {
            int i;
            for (i = 0; i < 5; i++)
                printf("utils %s 0x%x size 0x%x\n", UTIL_NAMES[i], h.fn[i].addr, h.fn[i].size);
            printf("utils dlopen 0x%x dlsym 0x%x bss 0x%x\n", h.dlopen_plt, h.dlsym_plt, h.bss);
        }
    } else {
        fprintf(stderr, "cpatch: kind %s\n", kind);
        st = ST_REFUSE;
    }
    free(im.b);
    return st;
}

static int do_apply(const char *src, const char *dst, const char *kind)
{
    struct image im;
    int st;
    if (load_image(src, &im) != 0)
        return ST_REFUSE;
    if (strcmp(kind, "core") == 0)
        st = patch_core(&im);
    else if (strcmp(kind, "audio") == 0)
        st = patch_audio(&im);
    else if (strcmp(kind, "utils") == 0)
        st = patch_utils(&im);
    else
        st = ST_REFUSE;
    if (st == ST_STOCK) {
        if (wr_file(dst, im.b, im.n) != 0) {
            fprintf(stderr, "cpatch: cannot write %s\n", dst);
            st = ST_REFUSE;
        }
    }
    free(im.b);
    return st;
}

static int fresh(char *dst, size_t n)
{
    int fd;
    snprintf(dst, n, "/tmp/cpatch-outXXXXXX");
    fd = mkstemp(dst);
    if (fd < 0)
        return -1;
    close(fd);
    return 0;
}

static int do_test(const char *core, const char *audio, const char *utils)
{
    char dst[64];
    int st;
    struct image im;
    struct core_hit ch;
    uint32_t target = 0, site = 0;
    if (selftest_enc() != 0) {
        fprintf(stderr, "cpatch: encoder selftest failed\n");
        return ST_REFUSE;
    }
    st = do_check(core, "core", 1);
    if (st != ST_STOCK)
        return ST_REFUSE;
    if (load_image(core, &im) != 0)
        return ST_REFUSE;
    if (scan_core(&im, &ch) != ST_STOCK) {
        free(im.b);
        return ST_REFUSE;
    }
    target = ch.target;
    site = ch.beq_off;
    free(im.b);
    if (fresh(dst, sizeof dst) != 0)
        return ST_REFUSE;
    st = do_apply(core, dst, "core");
    if (st != ST_STOCK) {
        fprintf(stderr, "cpatch: core apply %d\n", st);
        return ST_REFUSE;
    }
    if (load_image(dst, &im) != 0)
        return ST_REFUSE;
    if (scan_core(&im, &ch) != ST_ALREADY) {
        free(im.b);
        return ST_REFUSE;
    }
    {
        uint32_t t = 0;
        if (site + 4 > im.n ||
            dec_bl(site, r16(im.b + site), r16(im.b + site + 2), 0, &t) != 0 || t != target) {
            fprintf(stderr, "cpatch: core target mismatch\n");
            free(im.b);
            return ST_REFUSE;
        }
    }
    free(im.b);
    if (do_apply(dst, dst, "core") != ST_ALREADY)
        return ST_REFUSE;
    remove(dst);

    st = do_check(audio, "audio", 1);
    if (st != ST_STOCK)
        return ST_REFUSE;
    if (fresh(dst, sizeof dst) != 0)
        return ST_REFUSE;
    if (do_apply(audio, dst, "audio") != ST_STOCK)
        return ST_REFUSE;
    if (do_check(dst, "audio", 0) != ST_ALREADY)
        return ST_REFUSE;
    if (do_apply(dst, dst, "audio") != ST_ALREADY)
        return ST_REFUSE;
    remove(dst);

    st = do_check(utils, "utils", 1);
    if (st != ST_STOCK)
        return ST_REFUSE;
    if (fresh(dst, sizeof dst) != 0)
        return ST_REFUSE;
    if (do_apply(utils, dst, "utils") != ST_STOCK)
        return ST_REFUSE;
    if (do_check(dst, "utils", 0) != ST_ALREADY)
        return ST_REFUSE;
    {
        FILE *f = fopen(dst, "rb");
        char *buf;
        long sz;
        int i;
        if (!f)
            return ST_REFUSE;
        fseek(f, 0, SEEK_END);
        sz = ftell(f);
        rewind(f);
        buf = (char *)malloc((size_t)sz);
        if (!buf || fread(buf, 1, (size_t)sz, f) != (size_t)sz) {
            fclose(f);
            return ST_REFUSE;
        }
        fclose(f);
        if (!memmem(buf, (size_t)sz, "libCarPlayConverter.so", 22)) {
            fprintf(stderr, "cpatch: soname missing\n");
            free(buf);
            return ST_REFUSE;
        }
        for (i = 0; i < 5; i++) {
            if (!memmem(buf, (size_t)sz, CONV_NAMES[i], strlen(CONV_NAMES[i]))) {
                fprintf(stderr, "cpatch: missing %s\n", CONV_NAMES[i]);
                free(buf);
                return ST_REFUSE;
            }
        }
        free(buf);
    }
    if (do_apply(dst, dst, "utils") != ST_ALREADY)
        return ST_REFUSE;
    remove(dst);

    /* two copies of the compare, and a file with the compare removed */
    if (load_image(core, &im) != 0)
        return ST_REFUSE;
    if (im.n > 0x2006 && site + 6 <= im.n) {
        memcpy(im.b + 0x2000, im.b + site - 2, 6);
        if (wr_file(dst, im.b, im.n) != 0) {
            free(im.b);
            return ST_REFUSE;
        }
        if (do_check(dst, "core", 0) != ST_REFUSE) {
            fprintf(stderr, "cpatch: double match was accepted\n");
            free(im.b);
            return ST_REFUSE;
        }
        memset(im.b + site - 2, 0, 6);
        memset(im.b + 0x2000, 0, 6);
        if (wr_file(dst, im.b, im.n) != 0) {
            free(im.b);
            return ST_REFUSE;
        }
        if (do_check(dst, "core", 0) != ST_REFUSE) {
            fprintf(stderr, "cpatch: missing match was accepted\n");
            free(im.b);
            return ST_REFUSE;
        }
    }
    free(im.b);
    remove(dst);
    printf("cpatch test ok\n");
    return ST_STOCK;
}

int main(int argc, char **argv)
{
    int st;
    if (argc >= 2 && strcmp(argv[1], "selftest") == 0)
        return selftest_enc() == 0 ? 0 : 1;
    if (argc == 5 && strcmp(argv[1], "test") == 0)
        return do_test(argv[2], argv[3], argv[4]) == ST_STOCK ? 0 : 1;
    if (argc == 4 && strcmp(argv[1], "check") == 0) {
        st = do_check(argv[2], argv[3], 0);
        return st;
    }
    if (argc == 4 && strcmp(argv[1], "locate") == 0) {
        st = do_check(argv[2], argv[3], 1);
        return st;
    }
    if (argc == 5 && strcmp(argv[1], "apply") == 0) {
        st = do_apply(argv[2], argv[3], argv[4]);
        return st;
    }
    fprintf(stderr, "usage: cpatch check|locate <file> core|audio|utils\n"
                    "       cpatch apply <src> <dst> core|audio|utils\n"
                    "       cpatch test <core> <audio> <utils>\n");
    return ST_REFUSE;
}
