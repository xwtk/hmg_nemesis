/* libCarPlayConverter.so
 * AudioConverter for the five stubs in libCarPlayUtils.so.
 * AAC-LC uses the unit's libstagefright_soft_aacdec.so.
 * Opus uses the unit's libopus.so.
 * Any other pair returns -6735, the same value the stock stubs return.
 */
#include <dlfcn.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "CarPlayConverter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define kUnsupportedErr (-6735)
#define kParamErr (-6705)
#define kNoMemoryErr (-108)
#define FMT_AAC 0x61616320u
#define FMT_LPCM 0x6c70636du
#define FMT_OPUS 0x6f707573u
#define MAGIC 0x43505631u
#define MAX_FRAMES 5760
#define MAX_CH 2
#define TT_MP4_RAW 0
#define AAC_TPDEC_CLEAR_BUFFER 0x0603
#define OPUS_APPLICATION_VOIP 2048
#define OPUS_RESET_STATE 4028

typedef struct {
    uint32_t sampleRate;
    uint32_t formatID;
    uint32_t flags;
    uint32_t bytesPerPacket;
    uint32_t framesPerPacket;
    uint32_t bytesPerFrame;
    uint32_t channelsPerFrame;
    uint32_t bitsPerChannel;
    uint32_t reserved;
} ASBD;

typedef struct {
    uint32_t mNumberChannels;
    uint32_t mDataByteSize;
    void *mData;
} AudioBuffer;

typedef struct {
    uint32_t mNumberBuffers;
    AudioBuffer mBuffers[1];
} AudioBufferList;

typedef struct {
    int64_t mStartOffset;
    uint32_t mVariableFramesInPacket;
    uint32_t mDataByteSize;
} PacketDesc;

_Static_assert(sizeof(ASBD) == 36, "asbd");
_Static_assert(offsetof(ASBD, formatID) == 4, "formatID");
_Static_assert(offsetof(ASBD, channelsPerFrame) == 24, "channels");
_Static_assert(sizeof(AudioBuffer) == 12, "AudioBuffer");
_Static_assert(offsetof(AudioBufferList, mBuffers[0].mData) == 12, "mData");
_Static_assert(sizeof(PacketDesc) == 16, "PacketDesc");
_Static_assert(offsetof(PacketDesc, mDataByteSize) == 12, "mDataByteSize");

typedef int32_t (*InputProc)(void *, uint32_t *, AudioBufferList *, PacketDesc **, void *);

typedef void *fdk_handle;
typedef struct {
    int32_t sampleRate;
    int32_t frameSize;
    int32_t numChannels;
} StreamInfo;

typedef fdk_handle (*fn_open)(int32_t, uint32_t);
typedef int32_t (*fn_config)(fdk_handle, uint8_t *[], const uint32_t[]);
typedef int32_t (*fn_fill)(fdk_handle, uint8_t *[], const uint32_t[], uint32_t *);
typedef int32_t (*fn_decode)(fdk_handle, int16_t *, int32_t, uint32_t);
typedef StreamInfo *(*fn_info)(fdk_handle);
typedef int32_t (*fn_set)(fdk_handle, int32_t, int32_t);
typedef void (*fn_close)(fdk_handle);

static struct {
    int tried, ok;
    fn_open open;
    fn_config config;
    fn_fill fill;
    fn_decode decode;
    fn_info info;
    fn_set set;
    fn_close close;
} F;

typedef void *(*fn_dcreate)(int32_t, int32_t, int32_t *);
typedef int32_t (*fn_odec)(void *, const uint8_t *, int32_t, int16_t *, int32_t, int32_t);
typedef void *(*fn_ecreate)(int32_t, int32_t, int32_t, int32_t *);
typedef int32_t (*fn_oenc)(void *, const int16_t *, int32_t, uint8_t *, int32_t);
typedef void (*fn_odestroy)(void *);
typedef int32_t (*fn_octl)(void *, int32_t, ...);

static struct {
    int tried, ok;
    fn_dcreate dcreate;
    fn_odec odec;
    fn_odestroy ddestroy;
    fn_octl dctl;
    fn_ecreate ecreate;
    fn_oenc oenc;
    fn_odestroy edestroy;
    fn_octl ectl;
} O;

enum { KIND_AAC = 1, KIND_OPUS_DEC = 2, KIND_OPUS_ENC = 3 };

typedef struct {
    uint32_t magic;
    int kind;
    ASBD in, out;
    fdk_handle dec;
    void *op;
    uint32_t encFrames;
    int16_t *pcm;
    uint32_t pcmFrames;
    uint32_t pcmPos;
    uint32_t packets;
    uint32_t errors;
} Conv;

static void load_fdk_once(void)
{
    static const char *libs[] = {
        "libstagefright_soft_aacdec.so",
        "libbt-codec_aac_dec.so"
    };
    unsigned i;
    F.tried = 1;
    for (i = 0; i < 2 && !F.ok; i++) {
        void *h = dlopen(libs[i], RTLD_NOW);
        if (!h)
            continue;
        F.open = (fn_open)dlsym(h, "aacDecoder_Open");
        F.config = (fn_config)dlsym(h, "aacDecoder_ConfigRaw");
        F.fill = (fn_fill)dlsym(h, "aacDecoder_Fill");
        F.decode = (fn_decode)dlsym(h, "aacDecoder_DecodeFrame");
        F.info = (fn_info)dlsym(h, "aacDecoder_GetStreamInfo");
        F.set = (fn_set)dlsym(h, "aacDecoder_SetParam");
        F.close = (fn_close)dlsym(h, "aacDecoder_Close");
        F.ok = F.open && F.config && F.fill && F.decode && F.info && F.close;
        LOGI("aac %s %s", libs[i], F.ok ? "ok" : "missing symbols");
    }
}

static int load_fdk(void)
{
    static pthread_once_t once = PTHREAD_ONCE_INIT;
    pthread_once(&once, load_fdk_once);
    return F.ok;
}

static void load_opus_once(void)
{
    void *h;
    O.tried = 1;
    h = dlopen("libopus.so", RTLD_NOW);
    if (!h) {
        LOGW("libopus.so not found");
        return;
    }
    O.dcreate = (fn_dcreate)dlsym(h, "opus_decoder_create");
    O.odec = (fn_odec)dlsym(h, "opus_decode");
    O.ddestroy = (fn_odestroy)dlsym(h, "opus_decoder_destroy");
    O.dctl = (fn_octl)dlsym(h, "opus_decoder_ctl");
    O.ecreate = (fn_ecreate)dlsym(h, "opus_encoder_create");
    O.oenc = (fn_oenc)dlsym(h, "opus_encode");
    O.edestroy = (fn_odestroy)dlsym(h, "opus_encoder_destroy");
    O.ectl = (fn_octl)dlsym(h, "opus_encoder_ctl");
    O.ok = O.dcreate && O.odec && O.ddestroy && O.ecreate && O.oenc && O.edestroy;
    LOGI("opus %s", O.ok ? "ok" : "missing symbols");
}

static int load_opus(void)
{
    static pthread_once_t once = PTHREAD_ONCE_INIT;
    pthread_once(&once, load_opus_once);
    return O.ok;
}

static int opus_rate_ok(uint32_t sr)
{
    return sr == 8000 || sr == 12000 || sr == 16000 || sr == 24000 || sr == 48000;
}

static uint32_t opus_frames(uint32_t sr, uint32_t fpp)
{
    uint32_t ms25 = sr / 400;
    static const uint32_t m[6] = { 1, 2, 4, 8, 16, 24 };
    int i;
    for (i = 0; i < 6; i++) {
        if (fpp == ms25 * m[i])
            return fpp;
    }
    return ms25 * 8;
}

static int sr_index(uint32_t sr)
{
    static const uint32_t t[] = {
        96000, 88200, 64000, 48000, 44100, 32000, 24000,
        22050, 16000, 12000, 11025, 8000, 7350
    };
    int i;
    for (i = 0; i < 13; i++) {
        if (t[i] == sr)
            return i;
    }
    return -1;
}

static Conv *conv_alloc(const ASBD *in, const ASBD *out, int kind)
{
    Conv *c = (Conv *)calloc(1, sizeof(*c));
    if (!c)
        return 0;
    c->pcm = (int16_t *)malloc(MAX_FRAMES * MAX_CH * sizeof(int16_t));
    if (!c->pcm) {
        free(c);
        return 0;
    }
    c->magic = MAGIC;
    c->kind = kind;
    c->in = *in;
    c->out = *out;
    return c;
}

static void conv_free(Conv *c)
{
    if (!c)
        return;
    if (c->kind == KIND_AAC && c->dec && F.close)
        F.close(c->dec);
    if (c->kind == KIND_OPUS_DEC && c->op && O.ddestroy)
        O.ddestroy(c->op);
    if (c->kind == KIND_OPUS_ENC && c->op && O.edestroy)
        O.edestroy(c->op);
    c->magic = 0;
    free(c->pcm);
    free(c);
}

static int32_t new_aac(const ASBD *in, const ASBD *out, void **outConv)
{
    Conv *c;
    uint8_t asc[2];
    uint8_t *ascp[1];
    uint32_t ascl[1];
    int idx;
    if (in->channelsPerFrame < 1 || in->channelsPerFrame > MAX_CH)
        return kUnsupportedErr;
    idx = sr_index(in->sampleRate);
    if (idx < 0 || !load_fdk())
        return kUnsupportedErr;
    c = conv_alloc(in, out, KIND_AAC);
    if (!c)
        return kNoMemoryErr;
    c->dec = F.open(TT_MP4_RAW, 1);
    if (!c->dec) {
        conv_free(c);
        return kNoMemoryErr;
    }
    asc[0] = (uint8_t)((2 << 3) | (idx >> 1));
    asc[1] = (uint8_t)(((idx & 1) << 7) | (in->channelsPerFrame << 3));
    ascp[0] = asc;
    ascl[0] = 2;
    if (F.config(c->dec, ascp, ascl) != 0) {
        LOGW("aac config failed");
        conv_free(c);
        return kUnsupportedErr;
    }
    *outConv = c;
    return 0;
}

static int32_t new_opus_dec(const ASBD *in, const ASBD *out, void **outConv)
{
    Conv *c;
    int32_t err = 0;
    if (in->channelsPerFrame < 1 || in->channelsPerFrame > MAX_CH)
        return kUnsupportedErr;
    if (!opus_rate_ok(in->sampleRate) || !load_opus())
        return kUnsupportedErr;
    c = conv_alloc(in, out, KIND_OPUS_DEC);
    if (!c)
        return kNoMemoryErr;
    c->op = O.dcreate((int32_t)in->sampleRate, (int32_t)in->channelsPerFrame, &err);
    if (!c->op || err) {
        LOGW("opus decoder %d", err);
        conv_free(c);
        return kUnsupportedErr;
    }
    *outConv = c;
    return 0;
}

static int32_t new_opus_enc(const ASBD *in, const ASBD *out, void **outConv)
{
    Conv *c;
    int32_t err = 0;
    if (out->channelsPerFrame < 1 || out->channelsPerFrame > MAX_CH)
        return kUnsupportedErr;
    if (in->channelsPerFrame != out->channelsPerFrame || in->sampleRate != out->sampleRate)
        return kUnsupportedErr;
    if (in->bitsPerChannel != 0 && in->bitsPerChannel != 16)
        return kUnsupportedErr;
    if (in->bytesPerFrame != 0 && in->bytesPerFrame != in->channelsPerFrame * 2)
        return kUnsupportedErr;
    if (!opus_rate_ok(out->sampleRate) || !load_opus())
        return kUnsupportedErr;
    c = conv_alloc(in, out, KIND_OPUS_ENC);
    if (!c)
        return kNoMemoryErr;
    c->op = O.ecreate((int32_t)out->sampleRate, (int32_t)out->channelsPerFrame,
                      OPUS_APPLICATION_VOIP, &err);
    if (!c->op || err) {
        LOGW("opus encoder %d", err);
        conv_free(c);
        return kUnsupportedErr;
    }
    c->encFrames = opus_frames(out->sampleRate, out->framesPerPacket);
    *outConv = c;
    return 0;
}

__attribute__((visibility("default")))
int32_t CarPlayConverterNew(const void *inDesc, const void *outDesc, void **outConv)
{
    const ASBD *in = (const ASBD *)inDesc;
    const ASBD *out = (const ASBD *)outDesc;
    if (!in || !out || !outConv)
        return kParamErr;
    *outConv = 0;
    if (in->formatID == FMT_AAC && out->formatID == FMT_LPCM)
        return new_aac(in, out, outConv);
    if (in->formatID == FMT_OPUS && out->formatID == FMT_LPCM)
        return new_opus_dec(in, out, outConv);
    if (in->formatID == FMT_LPCM && out->formatID == FMT_OPUS)
        return new_opus_enc(in, out, outConv);
    return kUnsupportedErr;
}

__attribute__((visibility("default")))
int32_t CarPlayConverterDispose(void *conv)
{
    Conv *c = (Conv *)conv;
    if (!c || c->magic != MAGIC)
        return kParamErr;
    conv_free(c);
    return 0;
}

__attribute__((visibility("default")))
int32_t CarPlayConverterReset(void *conv)
{
    Conv *c = (Conv *)conv;
    if (!c || c->magic != MAGIC)
        return kParamErr;
    c->pcmFrames = 0;
    c->pcmPos = 0;
    if (c->kind == KIND_AAC && F.set)
        F.set(c->dec, AAC_TPDEC_CLEAR_BUFFER, 1);
    if (c->kind == KIND_OPUS_DEC && O.dctl)
        O.dctl(c->op, OPUS_RESET_STATE);
    if (c->kind == KIND_OPUS_ENC && O.ectl)
        O.ectl(c->op, OPUS_RESET_STATE);
    return 0;
}

__attribute__((visibility("default")))
int32_t CarPlayConverterSetProperty(void *conv, uint32_t prop, uint32_t size, const void *data)
{
    Conv *c = (Conv *)conv;
    (void)prop;
    (void)size;
    (void)data;
    if (!c || c->magic != MAGIC)
        return kParamErr;
    return 0;
}

static void decode_packet(Conv *c, uint8_t *data, uint32_t len)
{
    uint8_t *bufs[1];
    uint32_t sizes[1], valid;
    int32_t err = kUnsupportedErr;
    uint32_t ch = c->in.channelsPerFrame;
    uint32_t frames = c->in.framesPerPacket ? c->in.framesPerPacket : 1024;
    StreamInfo *si;

    c->pcmPos = 0;
    c->pcmFrames = 0;
    if (c->kind == KIND_OPUS_DEC) {
        int32_t n = O.odec(c->op, data, (int32_t)len, c->pcm, MAX_FRAMES, 0);
        c->packets++;
        if (n > 0) {
            c->pcmFrames = (uint32_t)n;
            return;
        }
        err = n;
        frames = c->in.framesPerPacket ? c->in.framesPerPacket : c->in.sampleRate / 50;
        goto silence;
    }
    bufs[0] = data;
    sizes[0] = len;
    valid = len;
    err = F.fill(c->dec, bufs, sizes, &valid);
    if (err == 0)
        err = F.decode(c->dec, c->pcm, MAX_FRAMES * MAX_CH, 0);
    c->packets++;
    if (err == 0 && (si = F.info(c->dec)) != 0 && si->frameSize > 0 &&
        (uint32_t)si->frameSize <= MAX_FRAMES && (uint32_t)si->numChannels == ch) {
        c->pcmFrames = (uint32_t)si->frameSize;
        return;
    }
silence:
    c->errors++;
    if (c->errors <= 5 || (c->errors & 255) == 0)
        LOGW("decode 0x%x packet %u len %u", err, c->packets, len);
    if (frames > MAX_FRAMES)
        frames = MAX_FRAMES;
    if (ch < 1 || ch > MAX_CH)
        ch = 1;
    memset(c->pcm, 0, frames * ch * sizeof(int16_t));
    c->pcmFrames = frames;
}

static int32_t fill_encode(Conv *c, InputProc proc, void *user, uint32_t *ioPackets,
                           AudioBufferList *out, PacketDesc *desc)
{
    uint32_t ch = c->in.channelsPerFrame;
    uint32_t bpf = ch * (uint32_t)sizeof(int16_t);
    uint32_t want = *ioPackets, made = 0, used = 0;
    uint32_t cap = out->mBuffers[0].mDataByteSize;
    uint8_t *dst = (uint8_t *)out->mBuffers[0].mData;
    int32_t status = 0;

    while (made < want) {
        while (c->pcmFrames < c->encFrames) {
            uint32_t n = c->encFrames - c->pcmFrames;
            uint32_t got;
            AudioBufferList abl;
            PacketDesc *pd = 0;
            memset(&abl, 0, sizeof(abl));
            abl.mNumberBuffers = 1;
            status = proc(c, &n, &abl, &pd, user);
            if (status != 0 || n == 0 || !abl.mBuffers[0].mData)
                goto out;
            got = abl.mBuffers[0].mDataByteSize >> (ch == 2 ? 2 : 1);
            if (got > n)
                got = n;
            if (got > c->encFrames - c->pcmFrames)
                got = c->encFrames - c->pcmFrames;
            memcpy((uint8_t *)c->pcm + c->pcmFrames * bpf, abl.mBuffers[0].mData, got * bpf);
            c->pcmFrames += got;
        }
        {
            int32_t nb;
            if (cap - used < 64)
                break;
            nb = O.oenc(c->op, c->pcm, (int32_t)c->encFrames, dst + used, (int32_t)(cap - used));
            c->pcmFrames = 0;
            c->packets++;
            if (nb <= 0) {
                c->errors++;
                if (c->errors <= 5)
                    LOGW("opus encode %d", nb);
                continue;
            }
            if (desc) {
                desc[made].mStartOffset = used;
                desc[made].mVariableFramesInPacket = 0;
                desc[made].mDataByteSize = (uint32_t)nb;
            }
            used += (uint32_t)nb;
            made++;
        }
    }
out:
    *ioPackets = made;
    out->mBuffers[0].mDataByteSize = used;
    return made == want ? 0 : status;
}

__attribute__((visibility("default")))
int32_t CarPlayConverterFillComplexBuffer(void *conv, InputProc proc, void *user,
                                          uint32_t *ioPackets, void *outData, void *outDesc)
{
    Conv *c = (Conv *)conv;
    AudioBufferList *out = (AudioBufferList *)outData;
    uint32_t want, done = 0, ch, bpf, cap;
    int32_t status = 0;
    uint8_t *dst;

    if (!c || c->magic != MAGIC || !proc || !ioPackets || !out ||
        out->mNumberBuffers < 1 || !out->mBuffers[0].mData)
        return kParamErr;
    if (c->kind == KIND_OPUS_ENC)
        return fill_encode(c, proc, user, ioPackets, out, (PacketDesc *)outDesc);
    ch = c->out.channelsPerFrame;
    if (ch < 1 || ch > MAX_CH)
        return kParamErr;
    bpf = ch * (uint32_t)sizeof(int16_t);
    want = *ioPackets;
    cap = out->mBuffers[0].mDataByteSize / bpf;
    if (want > cap)
        want = cap;
    dst = (uint8_t *)out->mBuffers[0].mData;
    while (done < want) {
        if (c->pcmFrames == 0) {
            uint32_t n = 1;
            AudioBufferList abl;
            PacketDesc *pd = 0;
            memset(&abl, 0, sizeof(abl));
            abl.mNumberBuffers = 1;
            status = proc(conv, &n, &abl, &pd, user);
            if (status != 0 || n == 0 || !abl.mBuffers[0].mData ||
                abl.mBuffers[0].mDataByteSize == 0)
                break;
            if (pd && pd->mDataByteSize &&
                pd->mDataByteSize <= abl.mBuffers[0].mDataByteSize)
                decode_packet(c, (uint8_t *)abl.mBuffers[0].mData + pd->mStartOffset,
                              pd->mDataByteSize);
            else
                decode_packet(c, (uint8_t *)abl.mBuffers[0].mData,
                              abl.mBuffers[0].mDataByteSize);
            continue;
        }
        {
            uint32_t take = c->pcmFrames;
            if (take > want - done)
                take = want - done;
            memcpy(dst + done * bpf, (uint8_t *)c->pcm + c->pcmPos * bpf, take * bpf);
            done += take;
            c->pcmPos += take;
            c->pcmFrames -= take;
        }
    }
    *ioPackets = done;
    out->mBuffers[0].mDataByteSize = done * bpf;
    return done == want ? 0 : status;
}
