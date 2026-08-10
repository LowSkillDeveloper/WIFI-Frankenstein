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

#if defined(__aarch64__)
#include <arm_neon.h>
static void sha1_transform(uint32_t state[5], const uint8_t block[64]) {

    uint32_t W[80];
    for (int i = 0; i < 16; i++) W[i] = load_be32(block + i * 4);
    for (int i = 16; i < 80; i++)
        W[i] = ROTL32(W[i-3] ^ W[i-8] ^ W[i-14] ^ W[i-16], 1);

    uint32x4_t abcd = vld1q_u32(state);
    uint32_t e = state[4];
    for (int i = 0; i < 80; i++) {
        uint32x4_t wk = vdupq_n_u32(W[i]);
        e = vsha1h_u32(e);
        if (i < 20)      abcd = vsha1cq_u32(abcd, e, wk);
        else if (i < 40) abcd = vsha1pq_u32(abcd, e, wk);
        else if (i < 60) abcd = vsha1mq_u32(abcd, e, wk);
        else             abcd = vsha1pq_u32(abcd, e, wk);
        e = vgetq_lane_u32(abcd, 2);
    }
    uint32_t sa[4]; vst1q_u32(sa, abcd);
    state[0] += sa[0]; state[1] += sa[1]; state[2] += sa[2];
    state[3] += sa[3]; state[4] += e;
}
#else

static void sha1_transform(uint32_t state[5], const uint8_t block[64]) {
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
    state[0] += a;
    state[1] += b;
    state[2] += c;
    state[3] += d;
    state[4] += e;
}

#endif

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




static void pbkdf2_sha1(const uint8_t *password, size_t pw_len,
                        const uint8_t *ssid, size_t ssid_len,
                        uint8_t pmk[32]) {
    uint8_t buf[4], u[20], t[20];
    uint8_t combined[64 + 4];
    for (int block = 1; block <= 2; block++) {
        buf[0] = (uint8_t) (block >> 24);
        buf[1] = (uint8_t) (block >> 16);
        buf[2] = (uint8_t) (block >> 8);
        buf[3] = (uint8_t) (block);
        memcpy(combined, ssid, ssid_len);
        memcpy(combined + ssid_len, buf, 4);
        hmac_sha1(password, pw_len, combined, ssid_len + 4, u);
        memcpy(t, u, 20);
        for (int iter = 2; iter <= 4096; iter++) {
            hmac_sha1(password, pw_len, u, 20, u);
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
    for (int i = 0; i < 4; i++) {
        uint8_t msg[101];
        memcpy(msg, pke, 100);
        msg[100] = (uint8_t) i;
        hmac_sha1(pmk, 32, msg, 101, ptk + i * 20);
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


    return verify_mic_kv2(ptk, eapol, eapol_len, captured_mic);
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

    size_t macHexLen = 12;
    uint8_t apMac[6], staMac[6];
    hex_to_bytes(macApHex, macHexLen, apMac);
    hex_to_bytes(macStaHex, macHexLen, staMac);

    int result = 0;
    int type = jType;
    int keyver = jKeyver;

    if (type == 1 || type == 3) {
        uint8_t mic[16];
        hex_to_bytes(micHex, 32, mic);
        result = verify_pmkid(pmk, apMac, staMac, mic);
    }
    if (!result && (type == 2 || type == 3)) {
        size_t eapolHexLen = strlen(eapolHex);
        uint8_t eapol[512];
        size_t eapolLen = eapolHexLen / 2;
        if (eapolLen < 100) eapolLen = 0;
        else hex_to_bytes(eapolHex, eapolHexLen, eapol);

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

        if (eapolLen >= 100 && keyver > 0 && keyver < 4) {
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
    hex_to_bytes(macApHex, 12, apMac);
    hex_to_bytes(macStaHex, 12, staMac);


    uint8_t aNonce[32], sNonce[32], eapol[512];
    size_t anonceHexLen = strlen(anonceHex);
    size_t eapolHexLen = strlen(eapolHex);
    size_t eapolLen = eapolHexLen / 2;
    uint8_t mic[16];

    if (anonceHexLen >= 64) hex_to_bytes(anonceHex, 64, aNonce);
    else memset(aNonce, 0, 32);

    if (eapolLen >= 100) hex_to_bytes(eapolHex, eapolHexLen, eapol);
    else eapolLen = 0;

    if (eapolLen >= 49) memcpy(sNonce, eapol + 17, 32);
    else memset(sNonce, 0, 32);

    if (micHex) hex_to_bytes(micHex, 32, mic);


    uint8_t pke[100];
    if (eapolLen >= 100 && (type == 2 || type == 3))
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
        if (type == 1 || type == 3)
            found = verify_pmkid(pmk, apMac, staMac, mic);
        if (!found && eapolLen >= 100 && keyver > 0 && keyver < 4) {
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
