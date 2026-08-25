#include <stdint.h>
#include <string.h>
#include <jni.h>


#define SHA1_K0 0x5a827999u
#define SHA1_K1 0x6ed9eba1u
#define SHA1_K2 0x8f1bbcdcu
#define SHA1_K3 0xca62c1d6u
#define ROTL32(x, n) (((x) << (n)) | ((x) >> (32 - (n))))

static inline uint32_t load_be32(const uint8_t *p) {
    return ((uint32_t) p[0] << 24) | ((uint32_t) p[1] << 16) |
           ((uint32_t) p[2] << 8) | (uint32_t) p[3];
}

static inline void store_be32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t) (v >> 24);
    p[1] = (uint8_t) (v >> 16);
    p[2] = (uint8_t) (v >> 8);
    p[3] = (uint8_t) (v);
}

static inline uint32_t load_le32(const uint8_t *p) {
    return ((uint32_t) p[0]) | ((uint32_t) p[1] << 8) |
           ((uint32_t) p[2] << 16) | ((uint32_t) p[3] << 24);
}

static inline void store_le32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t) (v);
    p[1] = (uint8_t) (v >> 8);
    p[2] = (uint8_t) (v >> 16);
    p[3] = (uint8_t) (v >> 24);
}

typedef struct {
    uint32_t state[5];
    uint64_t count;
    uint8_t buffer[64];
} sha1_ctx;

static void sha1_init(sha1_ctx *ctx) {
    ctx->state[0] = 0x67452301u;
    ctx->state[1] = 0xefcdab89u;
    ctx->state[2] = 0x98badcfeu;
    ctx->state[3] = 0x10325476u;
    ctx->state[4] = 0xc3d2e1f0u;
    ctx->count = 0;
}


static void sha1_transform_scalar(uint32_t state[5], const uint8_t block[64]) {
    uint32_t W[80];
    for (int i = 0; i < 16; i++) W[i] = load_be32(block + i * 4);
    for (int i = 16; i < 80; i++)
        W[i] = ROTL32(W[i - 3] ^ W[i - 8] ^ W[i - 14] ^ W[i - 16], 1);
    uint32_t a = state[0], b = state[1], c = state[2], d = state[3], e = state[4];
    uint32_t f, k, temp;
#define SHA1_ROUND(t, fn, k_val) do { f = fn; k = k_val; temp = ROTL32(a, 5) + f + e + k + W[t]; e = d; d = c; c = ROTL32(b, 30); b = a; a = temp; } while(0)
    for (int i = 0; i < 80; i++) {
        if (i < 20) SHA1_ROUND(i, (b & c) | (~b & d), SHA1_K0);
        else if (i < 40) SHA1_ROUND(i, b ^ c ^ d, SHA1_K1);
        else if (i < 60) SHA1_ROUND(i, (b & c) | (b & d) | (c & d), SHA1_K2);
        else
            SHA1_ROUND(i, b ^ c ^ d, SHA1_K3);
    }
#undef SHA1_ROUND
    state[0] += a;
    state[1] += b;
    state[2] += c;
    state[3] += d;
    state[4] += e;
}

#if defined(__aarch64__)
#include <arm_neon.h>
#include <sys/auxv.h>
#ifndef HWCAP_ASIMD
#define HWCAP_ASIMD (1UL << 1)
#endif
#ifndef HWCAP_SHA1
#define HWCAP_SHA1 (1UL << 5)
#endif

static int have_sha1_ce(void) {
    static int cached = -1;
    if (cached < 0) {
        unsigned long h = getauxval(AT_HWCAP);
        cached = ((h & HWCAP_ASIMD) && (h & HWCAP_SHA1)) ? 1 : 0;
    }
    return cached;
}


static void sha1_transform_ce(uint32_t state[5], const uint8_t block[64]) {
    uint32x4_t ABCD, ABCD_SAVED, MSG0, MSG1, MSG2, MSG3, TMP0, TMP1;
    uint32_t E0, E0_SAVED, E1;

    ABCD = vld1q_u32(state);
    E0 = state[4];
    ABCD_SAVED = ABCD;
    E0_SAVED = E0;

    MSG0 = vld1q_u32((const uint32_t *) (block));
    MSG1 = vld1q_u32((const uint32_t *) (block + 16));
    MSG2 = vld1q_u32((const uint32_t *) (block + 32));
    MSG3 = vld1q_u32((const uint32_t *) (block + 48));

    
    MSG0 = vreinterpretq_u32_u8(vrev32q_u8(vreinterpretq_u8_u32(MSG0)));
    MSG1 = vreinterpretq_u32_u8(vrev32q_u8(vreinterpretq_u8_u32(MSG1)));
    MSG2 = vreinterpretq_u32_u8(vrev32q_u8(vreinterpretq_u8_u32(MSG2)));
    MSG3 = vreinterpretq_u32_u8(vrev32q_u8(vreinterpretq_u8_u32(MSG3)));

    TMP0 = vaddq_u32(MSG0, vdupq_n_u32(SHA1_K0));
    TMP1 = vaddq_u32(MSG1, vdupq_n_u32(SHA1_K0));

    
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1cq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG2, vdupq_n_u32(SHA1_K0));
    MSG0 = vsha1su0q_u32(MSG0, MSG1, MSG2);

    
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1cq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG3, vdupq_n_u32(SHA1_K0));
    MSG0 = vsha1su1q_u32(MSG0, MSG3);
    MSG1 = vsha1su0q_u32(MSG1, MSG2, MSG3);

    
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1cq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG0, vdupq_n_u32(SHA1_K0));
    MSG1 = vsha1su1q_u32(MSG1, MSG0);
    MSG2 = vsha1su0q_u32(MSG2, MSG3, MSG0);

    
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1cq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG1, vdupq_n_u32(SHA1_K1));
    MSG2 = vsha1su1q_u32(MSG2, MSG1);
    MSG3 = vsha1su0q_u32(MSG3, MSG0, MSG1);

    
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1cq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG2, vdupq_n_u32(SHA1_K1));
    MSG3 = vsha1su1q_u32(MSG3, MSG2);
    MSG0 = vsha1su0q_u32(MSG0, MSG1, MSG2);

    
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1pq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG3, vdupq_n_u32(SHA1_K1));
    MSG0 = vsha1su1q_u32(MSG0, MSG3);
    MSG1 = vsha1su0q_u32(MSG1, MSG2, MSG3);

    
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1pq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG0, vdupq_n_u32(SHA1_K1));
    MSG1 = vsha1su1q_u32(MSG1, MSG0);
    MSG2 = vsha1su0q_u32(MSG2, MSG3, MSG0);

    
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1pq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG1, vdupq_n_u32(SHA1_K1));
    MSG2 = vsha1su1q_u32(MSG2, MSG1);
    MSG3 = vsha1su0q_u32(MSG3, MSG0, MSG1);

    
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1pq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG2, vdupq_n_u32(SHA1_K2));
    MSG3 = vsha1su1q_u32(MSG3, MSG2);
    MSG0 = vsha1su0q_u32(MSG0, MSG1, MSG2);

    
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1pq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG3, vdupq_n_u32(SHA1_K2));
    MSG0 = vsha1su1q_u32(MSG0, MSG3);
    MSG1 = vsha1su0q_u32(MSG1, MSG2, MSG3);

    
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1mq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG0, vdupq_n_u32(SHA1_K2));
    MSG1 = vsha1su1q_u32(MSG1, MSG0);
    MSG2 = vsha1su0q_u32(MSG2, MSG3, MSG0);

    
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1mq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG1, vdupq_n_u32(SHA1_K2));
    MSG2 = vsha1su1q_u32(MSG2, MSG1);
    MSG3 = vsha1su0q_u32(MSG3, MSG0, MSG1);

    
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1mq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG2, vdupq_n_u32(SHA1_K2));
    MSG3 = vsha1su1q_u32(MSG3, MSG2);
    MSG0 = vsha1su0q_u32(MSG0, MSG1, MSG2);

    
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1mq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG3, vdupq_n_u32(SHA1_K3));
    MSG0 = vsha1su1q_u32(MSG0, MSG3);
    MSG1 = vsha1su0q_u32(MSG1, MSG2, MSG3);

    
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1mq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG0, vdupq_n_u32(SHA1_K3));
    MSG1 = vsha1su1q_u32(MSG1, MSG0);
    MSG2 = vsha1su0q_u32(MSG2, MSG3, MSG0);

    
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1pq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG1, vdupq_n_u32(SHA1_K3));
    MSG2 = vsha1su1q_u32(MSG2, MSG1);
    MSG3 = vsha1su0q_u32(MSG3, MSG0, MSG1);

    
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1pq_u32(ABCD, E0, TMP0);
    TMP0 = vaddq_u32(MSG2, vdupq_n_u32(SHA1_K3));
    MSG3 = vsha1su1q_u32(MSG3, MSG2);
    MSG0 = vsha1su0q_u32(MSG0, MSG1, MSG2);

    
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1pq_u32(ABCD, E1, TMP1);
    TMP1 = vaddq_u32(MSG3, vdupq_n_u32(SHA1_K3));
    MSG0 = vsha1su1q_u32(MSG0, MSG3);

    
    E1 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1pq_u32(ABCD, E0, TMP0);

    
    E0 = vsha1h_u32(vgetq_lane_u32(ABCD, 0));
    ABCD = vsha1pq_u32(ABCD, E1, TMP1);

    
    E0 += E0_SAVED;
    ABCD = vaddq_u32(ABCD_SAVED, ABCD);

    vst1q_u32(state, ABCD);
    state[4] = E0;
}
#endif 


static void sha1_transform(uint32_t state[5], const uint8_t block[64]) {
#if defined(__aarch64__)
    if (have_sha1_ce()) {
        sha1_transform_ce(state, block);
        return;
    }
#endif
    sha1_transform_scalar(state, block);
}

static void sha1_update(sha1_ctx *ctx, const uint8_t *data, size_t len) {
    size_t idx = (size_t) (ctx->count & 0x3F);
    ctx->count += len;
    size_t part = 64 - idx;
    if (len >= part) {
        memcpy(ctx->buffer + idx, data, part);
        sha1_transform(ctx->state, ctx->buffer);
        for (data += part, len -= part; len >= 64; data += 64, len -= 64)
            sha1_transform(ctx->state, data);
        idx = 0;
    }
    memcpy(ctx->buffer + idx, data, len);
}

static void sha1_final(sha1_ctx *ctx, uint8_t digest[20]) {
    uint64_t bits = ctx->count * 8;
    size_t idx = (size_t) (ctx->count & 0x3F);
    ctx->buffer[idx++] = 0x80;
    if (idx > 56) {
        memset(ctx->buffer + idx, 0, 64 - idx);
        sha1_transform(ctx->state, ctx->buffer);
        idx = 0;
    }
    memset(ctx->buffer + idx, 0, 56 - idx);
    store_be32(ctx->buffer + 56, (uint32_t) (bits >> 32));
    store_be32(ctx->buffer + 60, (uint32_t) (bits));
    sha1_transform(ctx->state, ctx->buffer);
    for (int i = 0; i < 5; i++) store_be32(digest + i * 4, ctx->state[i]);
}


static void hmac_sha1(const uint8_t *key, size_t key_len,
                      const uint8_t *data, size_t data_len,
                      uint8_t mac[20]) {
    uint8_t k_ipad[64], k_opad[64], inner[20];
    sha1_ctx ctx;
    memset(k_ipad, 0, 64);
    memset(k_opad, 0, 64);
    if (key_len > 64) {
        sha1_init(&ctx);
        sha1_update(&ctx, key, key_len);
        sha1_final(&ctx, k_ipad);
        memcpy(k_opad, k_ipad, 20);
    } else {
        memcpy(k_ipad, key, key_len);
        memcpy(k_opad, key, key_len);
    }
    for (int i = 0; i < 64; i++) {
        k_ipad[i] ^= 0x36;
        k_opad[i] ^= 0x5c;
    }
    sha1_init(&ctx);
    sha1_update(&ctx, k_ipad, 64);
    sha1_update(&ctx, data, data_len);
    sha1_final(&ctx, inner);
    sha1_init(&ctx);
    sha1_update(&ctx, k_opad, 64);
    sha1_update(&ctx, inner, 20);
    sha1_final(&ctx, mac);
}


static void hmac_sha1_padded(const sha1_ctx *ipad_state,
                             const sha1_ctx *opad_state,
                             const uint8_t *data, size_t data_len,
                             uint8_t mac[20]) {
    sha1_ctx c = *ipad_state;
    uint8_t inner[20];
    sha1_update(&c, data, data_len);
    sha1_final(&c, inner);
    c = *opad_state;
    sha1_update(&c, inner, 20);
    sha1_final(&c, mac);
}



typedef struct {
    uint32_t state[4];
    uint64_t count;
    uint8_t buffer[64];
} md5_ctx;

static void md5_init(md5_ctx *ctx) {
    ctx->state[0] = 0x67452301u;
    ctx->state[1] = 0xefcdab89u;
    ctx->state[2] = 0x98badcfeu;
    ctx->state[3] = 0x10325476u;
    ctx->count = 0;
}

static void md5_transform(uint32_t state[4], const uint8_t block[64]) {
    uint32_t a = state[0], b = state[1], c = state[2], d = state[3];
    uint32_t x[16];
    for (int i = 0; i < 16; i++) x[i] = load_le32(block + i * 4);

#define FF(a, b, c, d, x, s, ac) do { \
    (a) += (((b) & (c)) | (~(b) & (d))) + (x) + (uint32_t)(ac); \
    (a) = ROTL32((a), (s)); (a) += (b); \
} while (0)
#define GG(a, b, c, d, x, s, ac) do { \
    (a) += (((b) & (d)) | ((c) & ~(d))) + (x) + (uint32_t)(ac); \
    (a) = ROTL32((a), (s)); (a) += (b); \
} while (0)
#define HH(a, b, c, d, x, s, ac) do { \
    (a) += ((b) ^ (c) ^ (d)) + (x) + (uint32_t)(ac); \
    (a) = ROTL32((a), (s)); (a) += (b); \
} while (0)
#define II(a, b, c, d, x, s, ac) do { \
    (a) += ((c) ^ ((b) | ~(d))) + (x) + (uint32_t)(ac); \
    (a) = ROTL32((a), (s)); (a) += (b); \
} while (0)

    FF(a, b, c, d, x[0], 7, 0xd76aa478u);
    FF(d, a, b, c, x[1], 12, 0xe8c7b756u);
    FF(c, d, a, b, x[2], 17, 0x242070dbu);
    FF(b, c, d, a, x[3], 22, 0xc1bdceeeu);
    FF(a, b, c, d, x[4], 7, 0xf57c0fafu);
    FF(d, a, b, c, x[5], 12, 0x4787c62au);
    FF(c, d, a, b, x[6], 17, 0xa8304613u);
    FF(b, c, d, a, x[7], 22, 0xfd469501u);
    FF(a, b, c, d, x[8], 7, 0x698098d8u);
    FF(d, a, b, c, x[9], 12, 0x8b44f7afu);
    FF(c, d, a, b, x[10], 17, 0xffff5bb1u);
    FF(b, c, d, a, x[11], 22, 0x895cd7beu);
    FF(a, b, c, d, x[12], 7, 0x6b901122u);
    FF(d, a, b, c, x[13], 12, 0xfd987193u);
    FF(c, d, a, b, x[14], 17, 0xa679438eu);
    FF(b, c, d, a, x[15], 22, 0x49b40821u);

    GG(a, b, c, d, x[1], 5, 0xf61e2562u);
    GG(d, a, b, c, x[6], 9, 0xc040b340u);
    GG(c, d, a, b, x[11], 14, 0x265e5a51u);
    GG(b, c, d, a, x[0], 20, 0xe9b6c7aau);
    GG(a, b, c, d, x[5], 5, 0xd62f105du);
    GG(d, a, b, c, x[10], 9, 0x02441453u);
    GG(c, d, a, b, x[15], 14, 0xd8a1e681u);
    GG(b, c, d, a, x[4], 20, 0xe7d3fbc8u);
    GG(a, b, c, d, x[9], 5, 0x21e1cde6u);
    GG(d, a, b, c, x[14], 9, 0xc33707d6u);
    GG(c, d, a, b, x[3], 14, 0xf4d50d87u);
    GG(b, c, d, a, x[8], 20, 0x455a14edu);
    GG(a, b, c, d, x[13], 5, 0xa9e3e905u);
    GG(d, a, b, c, x[2], 9, 0xfcefa3f8u);
    GG(c, d, a, b, x[7], 14, 0x676f02d9u);
    GG(b, c, d, a, x[12], 20, 0x8d2a4c8au);

    HH(a, b, c, d, x[5], 4, 0xfffa3942u);
    HH(d, a, b, c, x[8], 11, 0x8771f681u);
    HH(c, d, a, b, x[11], 16, 0x6d9d6122u);
    HH(b, c, d, a, x[14], 23, 0xfde5380cu);
    HH(a, b, c, d, x[1], 4, 0xa4beea44u);
    HH(d, a, b, c, x[4], 11, 0x4bdecfa9u);
    HH(c, d, a, b, x[7], 16, 0xf6bb4b60u);
    HH(b, c, d, a, x[10], 23, 0xbebfbc70u);
    HH(a, b, c, d, x[13], 4, 0x289b7ec6u);
    HH(d, a, b, c, x[0], 11, 0xeaa127fau);
    HH(c, d, a, b, x[3], 16, 0xd4ef3085u);
    HH(b, c, d, a, x[6], 23, 0x04881d05u);
    HH(a, b, c, d, x[9], 4, 0xd9d4d039u);
    HH(d, a, b, c, x[12], 11, 0xe6db99e5u);
    HH(c, d, a, b, x[15], 16, 0x1fa27cf8u);
    HH(b, c, d, a, x[2], 23, 0xc4ac5665u);

    II(a, b, c, d, x[0], 6, 0xf4292244u);
    II(d, a, b, c, x[7], 10, 0x432aff97u);
    II(c, d, a, b, x[14], 15, 0xab9423a7u);
    II(b, c, d, a, x[5], 21, 0xfc93a039u);
    II(a, b, c, d, x[12], 6, 0x655b59c3u);
    II(d, a, b, c, x[3], 10, 0x8f0ccc92u);
    II(c, d, a, b, x[10], 15, 0xffeff47du);
    II(b, c, d, a, x[1], 21, 0x85845dd1u);
    II(a, b, c, d, x[8], 6, 0x6fa87e4fu);
    II(d, a, b, c, x[15], 10, 0xfe2ce6e0u);
    II(c, d, a, b, x[6], 15, 0xa3014314u);
    II(b, c, d, a, x[13], 21, 0x4e0811a1u);
    II(a, b, c, d, x[4], 6, 0xf7537e82u);
    II(d, a, b, c, x[11], 10, 0xbd3af235u);
    II(c, d, a, b, x[2], 15, 0x2ad7d2bbu);
    II(b, c, d, a, x[9], 21, 0xeb86d391u);

#undef FF
#undef GG
#undef HH
#undef II

    state[0] += a;
    state[1] += b;
    state[2] += c;
    state[3] += d;
}

static void md5_update(md5_ctx *ctx, const uint8_t *data, size_t len) {
    size_t idx = (size_t) (ctx->count & 0x3F);
    ctx->count += len;
    size_t part = 64 - idx;
    if (len >= part) {
        memcpy(ctx->buffer + idx, data, part);
        md5_transform(ctx->state, ctx->buffer);
        for (data += part, len -= part; len >= 64; data += 64, len -= 64)
            md5_transform(ctx->state, data);
        idx = 0;
    }
    memcpy(ctx->buffer + idx, data, len);
}

static void md5_final(md5_ctx *ctx, uint8_t digest[16]) {
    uint64_t bits = ctx->count * 8;
    size_t idx = (size_t) (ctx->count & 0x3F);
    ctx->buffer[idx++] = 0x80;
    if (idx > 56) {
        memset(ctx->buffer + idx, 0, 64 - idx);
        md5_transform(ctx->state, ctx->buffer);
        idx = 0;
    }
    memset(ctx->buffer + idx, 0, 56 - idx);
    store_le32(ctx->buffer + 56, (uint32_t) (bits));
    store_le32(ctx->buffer + 60, (uint32_t) (bits >> 32));
    md5_transform(ctx->state, ctx->buffer);
    for (int i = 0; i < 4; i++) store_le32(digest + i * 4, ctx->state[i]);
}

static void hmac_md5(const uint8_t *key, size_t key_len,
                     const uint8_t *data, size_t data_len,
                     uint8_t mac[16]) {
    uint8_t k_ipad[64], k_opad[64], inner[16];
    md5_ctx ctx;
    memset(k_ipad, 0, 64);
    memset(k_opad, 0, 64);
    if (key_len > 64) {
        md5_init(&ctx);
        md5_update(&ctx, key, key_len);
        md5_final(&ctx, k_ipad);
        memcpy(k_opad, k_ipad, 16);
    } else {
        memcpy(k_ipad, key, key_len);
        memcpy(k_opad, key, key_len);
    }
    for (int i = 0; i < 64; i++) {
        k_ipad[i] ^= 0x36;
        k_opad[i] ^= 0x5c;
    }
    md5_init(&ctx);
    md5_update(&ctx, k_ipad, 64);
    md5_update(&ctx, data, data_len);
    md5_final(&ctx, inner);
    md5_init(&ctx);
    md5_update(&ctx, k_opad, 64);
    md5_update(&ctx, inner, 16);
    md5_final(&ctx, mac);
}


static void pbkdf2_sha1(const uint8_t *password, size_t pw_len,
                        const uint8_t *ssid, size_t ssid_len,
                        uint8_t pmk[32]) {
    
    uint8_t k_ipad[64], k_opad[64], key_hash[20];
    if (pw_len > 64) {
        sha1_ctx c;
        sha1_init(&c);
        sha1_update(&c, password, pw_len);
        sha1_final(&c, key_hash);
        memcpy(k_ipad, key_hash, 20);
        memcpy(k_opad, key_hash, 20);
        memset(k_ipad + 20, 0, 44);
        memset(k_opad + 20, 0, 44);
    } else {
        memcpy(k_ipad, password, pw_len);
        memset(k_ipad + pw_len, 0, 64 - pw_len);
        memcpy(k_opad, password, pw_len);
        memset(k_opad + pw_len, 0, 64 - pw_len);
    }
    for (int i = 0; i < 64; i++) {
        k_ipad[i] ^= 0x36;
        k_opad[i] ^= 0x5c;
    }

    sha1_ctx ipad_state, opad_state;
    sha1_init(&ipad_state);
    sha1_update(&ipad_state, k_ipad, 64);
    sha1_init(&opad_state);
    sha1_update(&opad_state, k_opad, 64);

    uint8_t combined[68], u[20], t[20];
    for (int block = 1; block <= 2; block++) {
        combined[ssid_len]     = (uint8_t) (block >> 24);
        combined[ssid_len + 1] = (uint8_t) (block >> 16);
        combined[ssid_len + 2] = (uint8_t) (block >> 8);
        combined[ssid_len + 3] = (uint8_t) (block);
        memcpy(combined, ssid, ssid_len);
        hmac_sha1_padded(&ipad_state, &opad_state,
                         combined, ssid_len + 4, u);
        memcpy(t, u, 20);
        for (int iter = 2; iter <= 4096; iter++) {
            hmac_sha1_padded(&ipad_state, &opad_state, u, 20, u);
            for (int j = 0; j < 20; j++) t[j] ^= u[j];
        }
        memcpy(pmk + (block - 1) * 20, t, (block == 1) ? 20 : 12);
    }
}


static uint8_t from_hex(char c) {
    if (c >= '0' && c <= '9') return (uint8_t) (c - '0');
    if (c >= 'a' && c <= 'f') return (uint8_t) (c - 'a' + 10);
    if (c >= 'A' && c <= 'F') return (uint8_t) (c - 'A' + 10);
    return 0;
}

static void hex_to_bytes(const char *hex, size_t hex_len, uint8_t *out) {
    for (size_t i = 0; i < hex_len / 2; i++)
        out[i] = (uint8_t) ((from_hex(hex[i * 2]) << 4) | from_hex(hex[i * 2 + 1]));
}


static void hex_to_bytes_tolerant(const char *hex, uint8_t *out, size_t out_len) {
    size_t written = 0;
    int high = -1;
    for (size_t i = 0; written < out_len && hex[i]; i++) {
        if ((hex[i] >= '0' && hex[i] <= '9') ||
            (hex[i] >= 'a' && hex[i] <= 'f') ||
            (hex[i] >= 'A' && hex[i] <= 'F')) {
            if (high < 0) {
                high = from_hex(hex[i]);
            } else {
                out[written++] = (uint8_t) ((high << 4) | from_hex(hex[i]));
                high = -1;
            }
        }
    }
    while (written < out_len) out[written++] = 0;
}

static int memcmp_b(const uint8_t *a, const uint8_t *b, size_t len) {
    for (size_t i = 0; i < len; i++)
        if (a[i] != b[i]) return (int) a[i] - (int) b[i];
    return 0;
}

static int memcmp_or(const uint8_t *a, const uint8_t *b, size_t len) {

    for (size_t i = 0; i < len; i++) {
        if (a[i] < b[i]) return 1;
        if (a[i] > b[i]) return 0;
    }
    return 0;
}


static void build_pke(uint8_t pke[100], const uint8_t apMac[6],
                      const uint8_t staMac[6], const uint8_t aNonce[32],
                      const uint8_t sNonce[32]) {
    memcpy(pke, "Pairwise key expansion", 22);
    pke[22] = 0;

    if (memcmp_or(apMac, staMac, 6)) {
        memcpy(pke + 23, apMac, 6);
        memcpy(pke + 29, staMac, 6);
    } else {
        memcpy(pke + 23, staMac, 6);
        memcpy(pke + 29, apMac, 6);
    }
    if (memcmp_or(aNonce, sNonce, 32)) {
        memcpy(pke + 35, aNonce, 32);
        memcpy(pke + 67, sNonce, 32);
    } else {
        memcpy(pke + 35, sNonce, 32);
        memcpy(pke + 67, aNonce, 32);
    }
}

static void derive_ptk(const uint8_t pmk[32], const uint8_t pke[100],
                       uint8_t ptk[80]) {
    uint8_t msg[100];
    for (int i = 0; i < 4; i++) {
        memcpy(msg, pke, 99);
        msg[99] = (uint8_t) i;
        hmac_sha1(pmk, 32, msg, 100, ptk + i * 20);
    }
}

static int verify_mic_kv2(const uint8_t ptk[80], const uint8_t *eapol,
                          size_t eapol_len, const uint8_t captured_mic[16]) {
    if (eapol_len < 97) return 0;
    uint8_t eapol_copy[512];
    size_t len = eapol_len < 512 ? eapol_len : 512;
    memcpy(eapol_copy, eapol, len);
    size_t mic_end = (81 + 16) < len ? (81 + 16) : len;
    for (size_t i = 81; i < mic_end; i++) eapol_copy[i] = 0;
    uint8_t computed_mic[20];
    hmac_sha1(ptk, 16, eapol_copy, len, computed_mic);
    return memcmp_b(computed_mic, captured_mic, 16) == 0;
}

static int verify_mic_md5(const uint8_t ptk[80], const uint8_t *eapol,
                          size_t eapol_len, const uint8_t captured_mic[16]) {
    if (eapol_len < 97) return 0;
    uint8_t eapol_copy[512];
    size_t len = eapol_len < 512 ? eapol_len : 512;
    memcpy(eapol_copy, eapol, len);
    size_t mic_end = (81 + 16) < len ? (81 + 16) : len;
    for (size_t i = 81; i < mic_end; i++) eapol_copy[i] = 0;
    uint8_t computed_mic[16];
    hmac_md5(ptk, 16, eapol_copy, len, computed_mic);
    return memcmp_b(computed_mic, captured_mic, 16) == 0;
}

static int verify_pmkid(const uint8_t pmk[32], const uint8_t apMac[6],
                        const uint8_t staMac[6],
                        const uint8_t captured_pmkid[16]) {
    uint8_t input[20];
    memcpy(input, "PMK Name", 8);
    memcpy(input + 8, apMac, 6);
    memcpy(input + 14, staMac, 6);
    uint8_t computed[20];
    hmac_sha1(pmk, 32, input, 20, computed);
    return memcmp_b(computed, captured_pmkid, 16) == 0;
}


#include <time.h>

JNIEXPORT jlong JNICALL
Java_com_lsd_wififrankenstein_util_NativeCracker_benchmarkPbkdf2(
        JNIEnv *env, jclass cls, jint iterations) {

    const uint8_t *password = (const uint8_t *) "TestPassword12345678";
    const uint8_t *ssid = (const uint8_t *) "TestNetwork";
    uint8_t pmk[32];
    struct timespec start, end;

    clock_gettime(CLOCK_MONOTONIC, &start);
    for (int i = 0; i < iterations; i++) {
        pbkdf2_sha1(password, 22, ssid, 11, pmk);
    }
    clock_gettime(CLOCK_MONOTONIC, &end);

    jlong elapsed = (end.tv_sec - start.tv_sec) * 1000000000L +
                    (end.tv_nsec - start.tv_nsec);
    return elapsed;
}


#define JNI_CLASS "com/lsd/wififrankenstein/util/NativeCracker"


JNIEXPORT jstring JNICALL
Java_com_lsd_wififrankenstein_util_NativeCracker_debugPbkdf2Hex(
        JNIEnv *env, jclass cls,
        jstring jPassword, jstring jSsid) {

    const char *password = (*env)->GetStringUTFChars(env, jPassword, NULL);
    const char *ssid = (*env)->GetStringUTFChars(env, jSsid, NULL);
    jsize pw_len = (*env)->GetStringUTFLength(env, jPassword);
    jsize ssid_len = (*env)->GetStringUTFLength(env, jSsid);

    uint8_t pmk[32];
    pbkdf2_sha1((const uint8_t *) password, pw_len,
                (const uint8_t *) ssid, ssid_len, pmk);

    (*env)->ReleaseStringUTFChars(env, jPassword, password);
    (*env)->ReleaseStringUTFChars(env, jSsid, ssid);

    char out[65];
    static const char hx[] = "0123456789abcdef";
    for (int i = 0; i < 32; i++) {
        out[i * 2] = hx[pmk[i] >> 4];
        out[i * 2 + 1] = hx[pmk[i] & 0xF];
    }
    out[64] = 0;
    return (*env)->NewStringUTF(env, out);
}


JNIEXPORT jboolean JNICALL
Java_com_lsd_wififrankenstein_util_NativeCracker_tryPasswordHex(
        JNIEnv *env, jclass cls,
        jstring jPassword, jstring jSsid,
        jstring jMacApHex, jstring jMacStaHex,
        jstring jAnonceHex, jstring jEapolHex,
        jstring jMicHex, jint jKeyver, jint jType) {

    const char *password = (*env)->GetStringUTFChars(env, jPassword, NULL);
    const char *ssid = (*env)->GetStringUTFChars(env, jSsid, NULL);
    const char *macApHex = (*env)->GetStringUTFChars(env, jMacApHex, NULL);
    const char *macStaHex = (*env)->GetStringUTFChars(env, jMacStaHex, NULL);
    const char *anonceHex = (*env)->GetStringUTFChars(env, jAnonceHex, NULL);
    const char *eapolHex = (*env)->GetStringUTFChars(env, jEapolHex, NULL);
    const char *micHex = (*env)->GetStringUTFChars(env, jMicHex, NULL);

    jsize pw_len = (*env)->GetStringUTFLength(env, jPassword);
    jsize ssid_len = (*env)->GetStringUTFLength(env, jSsid);

    uint8_t pmk[32];
    pbkdf2_sha1((const uint8_t *) password, pw_len,
                (const uint8_t *) ssid, ssid_len, pmk);

    uint8_t apMac[6], staMac[6];
    hex_to_bytes_tolerant(macApHex, apMac, 6);
    hex_to_bytes_tolerant(macStaHex, staMac, 6);

    int result = 0;
    int type = jType;
    int keyver = jKeyver;
    int micOk = (micHex && strlen(micHex) >= 32) ? 1 : 0;

    if (micOk && (type == 1 || type == 3)) {
        uint8_t mic[16];
        hex_to_bytes(micHex, 32, mic);
        result = verify_pmkid(pmk, apMac, staMac, mic);
    }
    if (!result && (type == 2 || type == 3)) {
        size_t eapolHexLen = strlen(eapolHex);
        uint8_t eapol[512];
        size_t eapolLen = eapolHexLen / 2;
        if (eapolLen > 512) eapolLen = 512;
        if (eapolLen < 97) eapolLen = 0;
        else hex_to_bytes(eapolHex, eapolLen * 2, eapol);

        uint8_t aNonce[32], sNonce[32];
        size_t anonceHexLen = strlen(anonceHex);
        if (anonceHexLen >= 64)
            hex_to_bytes(anonceHex, 64, aNonce);
        else
            memset(aNonce, 0, 32);

        if (eapolLen >= 49)
            memcpy(sNonce, eapol + 17, 32);
        else
            memset(sNonce, 0, 32);

        if (micOk && eapolLen >= 97 && keyver > 0 && keyver < 3) {
            uint8_t pke[100], ptk[80];
            build_pke(pke, apMac, staMac, aNonce, sNonce);
            derive_ptk(pmk, pke, ptk);
            uint8_t mic[16];
            hex_to_bytes(micHex, 32, mic);
            if (keyver == 1)
                result = verify_mic_md5(ptk, eapol, eapolLen, mic);
            else
                result = verify_mic_kv2(ptk, eapol, eapolLen, mic);
        }
    }

    (*env)->ReleaseStringUTFChars(env, jPassword, password);
    (*env)->ReleaseStringUTFChars(env, jSsid, ssid);
    (*env)->ReleaseStringUTFChars(env, jMacApHex, macApHex);
    (*env)->ReleaseStringUTFChars(env, jMacStaHex, macStaHex);
    (*env)->ReleaseStringUTFChars(env, jAnonceHex, anonceHex);
    (*env)->ReleaseStringUTFChars(env, jEapolHex, eapolHex);
    (*env)->ReleaseStringUTFChars(env, jMicHex, micHex);

    return result ? JNI_TRUE : JNI_FALSE;
}


JNIEXPORT jint JNICALL
Java_com_lsd_wififrankenstein_util_NativeCracker_crackBatchHex(
        JNIEnv *env, jclass cls,
        jobjectArray jPasswords, jstring jSsid,
        jstring jMacApHex, jstring jMacStaHex,
        jstring jAnonceHex, jstring jEapolHex,
        jstring jMicHex, jint jKeyver, jint jType) {

    const char *ssid = (*env)->GetStringUTFChars(env, jSsid, NULL);
    const char *macApHex = (*env)->GetStringUTFChars(env, jMacApHex, NULL);
    const char *macStaHex = (*env)->GetStringUTFChars(env, jMacStaHex, NULL);
    const char *anonceHex = (*env)->GetStringUTFChars(env, jAnonceHex, NULL);
    const char *eapolHex = (*env)->GetStringUTFChars(env, jEapolHex, NULL);
    const char *micHex = (*env)->GetStringUTFChars(env, jMicHex, NULL);

    jsize ssid_len = (*env)->GetStringUTFLength(env, jSsid);
    jsize count = (*env)->GetArrayLength(env, jPasswords);
    int type = jType, keyver = jKeyver;


    uint8_t apMac[6], staMac[6];
    hex_to_bytes_tolerant(macApHex, apMac, 6);
    hex_to_bytes_tolerant(macStaHex, staMac, 6);


    uint8_t aNonce[32], sNonce[32], eapol[512];
    size_t anonceHexLen = strlen(anonceHex);
    size_t eapolHexLen = strlen(eapolHex);
    size_t eapolLen = eapolHexLen / 2;
    uint8_t mic[16];

    if (anonceHexLen >= 64) hex_to_bytes(anonceHex, 64, aNonce);
    else memset(aNonce, 0, 32);

    if (eapolLen > 512) eapolLen = 512;
    if (eapolLen >= 97) hex_to_bytes(eapolHex, eapolLen * 2, eapol);
    else eapolLen = 0;

    if (eapolLen >= 49) memcpy(sNonce, eapol + 17, 32);
    else memset(sNonce, 0, 32);

    int micOk = (micHex && strlen(micHex) >= 32) ? 1 : 0;
    if (micOk) hex_to_bytes(micHex, 32, mic);


    uint8_t pke[100];
    if (eapolLen >= 97 && (type == 2 || type == 3))
        build_pke(pke, apMac, staMac, aNonce, sNonce);

    int result = -1;

    for (jsize i = 0; i < count; i++) {
        jstring jpw = (jstring) (*env)->GetObjectArrayElement(env, jPasswords, i);
        if (!jpw) continue;
        const char *password = (*env)->GetStringUTFChars(env, jpw, NULL);
        jsize pw_len = (*env)->GetStringUTFLength(env, jpw);

        uint8_t pmk[32];
        pbkdf2_sha1((const uint8_t *) password, pw_len,
                    (const uint8_t *) ssid, ssid_len, pmk);

        int found = 0;
        if (micOk && (type == 1 || type == 3))
            found = verify_pmkid(pmk, apMac, staMac, mic);
        if (!found && micOk && eapolLen >= 97 && keyver > 0 && keyver < 3) {
            uint8_t ptk[80];
            derive_ptk(pmk, pke, ptk);
            found = (keyver == 1) ? verify_mic_md5(ptk, eapol, eapolLen, mic)
                                  : verify_mic_kv2(ptk, eapol, eapolLen, mic);
        }

        (*env)->ReleaseStringUTFChars(env, jpw, password);
        (*env)->DeleteLocalRef(env, jpw);

        if (found) {
            result = i;
            break;
        }
    }

    (*env)->ReleaseStringUTFChars(env, jSsid, ssid);
    (*env)->ReleaseStringUTFChars(env, jMacApHex, macApHex);
    (*env)->ReleaseStringUTFChars(env, jMacStaHex, macStaHex);
    (*env)->ReleaseStringUTFChars(env, jAnonceHex, anonceHex);
    (*env)->ReleaseStringUTFChars(env, jEapolHex, eapolHex);
    (*env)->ReleaseStringUTFChars(env, jMicHex, micHex);

    return result;
}

typedef struct {
    int type;
    int keyver;
    int micOk;
    uint8_t apMac[6];
    uint8_t staMac[6];
    uint8_t anonce[32];
    uint8_t sNonce[32];
    uint8_t eapol[512];
    size_t eapolLen;
    uint8_t pke[100];
    uint8_t mic[16];
} nh_target;

JNIEXPORT jint JNICALL
Java_com_lsd_wififrankenstein_util_NativeCracker_crackBatchMultiHex(
        JNIEnv *env, jclass cls,
        jobjectArray jPasswords, jstring jSsid,
        jobjectArray jApArr, jobjectArray jStaArr,
        jobjectArray jAnonceArr, jobjectArray jEapolArr,
        jobjectArray jMicArr, jintArray jKeyvers, jintArray jTypes) {

    const char *ssid = (*env)->GetStringUTFChars(env, jSsid, NULL);
    jsize ssid_len = (*env)->GetStringUTFLength(env, jSsid);
    jsize count = (*env)->GetArrayLength(env, jPasswords);
    jsize hn = (*env)->GetArrayLength(env, jApArr);
    if (hn > 32) hn = 32;

    jint *kvs = (*env)->GetIntArrayElements(env, jKeyvers, NULL);
    jint *tps = (*env)->GetIntArrayElements(env, jTypes, NULL);

    nh_target tg[32];
    for (int h = 0; h < hn; h++) {
        memset(&tg[h], 0, sizeof(nh_target));
        tg[h].type = tps[h];
        tg[h].keyver = kvs[h];

        jstring s;
        const char *p;

        s = (jstring) (*env)->GetObjectArrayElement(env, jApArr, h);
        p = (*env)->GetStringUTFChars(env, s, NULL);
        hex_to_bytes_tolerant(p, tg[h].apMac, 6);
        (*env)->ReleaseStringUTFChars(env, s, p);
        (*env)->DeleteLocalRef(env, s);

        s = (jstring) (*env)->GetObjectArrayElement(env, jStaArr, h);
        p = (*env)->GetStringUTFChars(env, s, NULL);
        hex_to_bytes_tolerant(p, tg[h].staMac, 6);
        (*env)->ReleaseStringUTFChars(env, s, p);
        (*env)->DeleteLocalRef(env, s);

        s = (jstring) (*env)->GetObjectArrayElement(env, jMicArr, h);
        p = (*env)->GetStringUTFChars(env, s, NULL);
        tg[h].micOk = (strlen(p) >= 32) ? 1 : 0;
        if (tg[h].micOk) hex_to_bytes(p, 32, tg[h].mic);
        (*env)->ReleaseStringUTFChars(env, s, p);
        (*env)->DeleteLocalRef(env, s);

        s = (jstring) (*env)->GetObjectArrayElement(env, jAnonceArr, h);
        p = (*env)->GetStringUTFChars(env, s, NULL);
        if (strlen(p) >= 64) hex_to_bytes(p, 64, tg[h].anonce);
        (*env)->ReleaseStringUTFChars(env, s, p);
        (*env)->DeleteLocalRef(env, s);

        s = (jstring) (*env)->GetObjectArrayElement(env, jEapolArr, h);
        p = (*env)->GetStringUTFChars(env, s, NULL);
        size_t elen = strlen(p) / 2;
        if (elen > 512) elen = 512;
        if (elen >= 97) {
            hex_to_bytes(p, elen * 2, tg[h].eapol);
            tg[h].eapolLen = elen;
            memcpy(tg[h].sNonce, tg[h].eapol + 17, 32);
            build_pke(tg[h].pke, tg[h].apMac, tg[h].staMac,
                      tg[h].anonce, tg[h].sNonce);
        }
        (*env)->ReleaseStringUTFChars(env, s, p);
        (*env)->DeleteLocalRef(env, s);
    }

    (*env)->ReleaseIntArrayElements(env, jKeyvers, kvs, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jTypes, tps, JNI_ABORT);

    int result = -1;
    uint8_t pmk[32], ptk[80];

    for (jsize i = 0; i < count && result < 0; i++) {
        jstring jpw = (jstring) (*env)->GetObjectArrayElement(env, jPasswords, i);
        if (!jpw) continue;
        const char *password = (*env)->GetStringUTFChars(env, jpw, NULL);
        jsize pw_len = (*env)->GetStringUTFLength(env, jpw);

        pbkdf2_sha1((const uint8_t *) password, pw_len,
                    (const uint8_t *) ssid, ssid_len, pmk);

        for (int h = 0; h < hn && result < 0; h++) {
            int ok = 0;
            if (tg[h].micOk && (tg[h].type == 1 || tg[h].type == 3))
                ok = verify_pmkid(pmk, tg[h].apMac, tg[h].staMac, tg[h].mic);
            if (!ok && tg[h].micOk && tg[h].eapolLen >= 97 &&
                tg[h].keyver > 0 && tg[h].keyver < 3) {
                derive_ptk(pmk, tg[h].pke, ptk);
                ok = (tg[h].keyver == 1)
                     ? verify_mic_md5(ptk, tg[h].eapol, tg[h].eapolLen, tg[h].mic)
                     : verify_mic_kv2(ptk, tg[h].eapol, tg[h].eapolLen, tg[h].mic);
            }
            if (ok) result = i;
        }

        (*env)->ReleaseStringUTFChars(env, jpw, password);
        (*env)->DeleteLocalRef(env, jpw);
    }

    (*env)->ReleaseStringUTFChars(env, jSsid, ssid);
    return result;
}
