#include <string.h>
#include "rec_stream.h"

// The packed structs are copied as they are: the ESP32-S3 and the host test
// machines are little-endian like the wire.
_Static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__, "the wire format is little-endian");

// The wire sizes the decoders rely on (and no padding anywhere).
_Static_assert(sizeof(rec_hdr_t) == REC_HDR_LEN, "record header is 8 bytes");
_Static_assert(sizeof(rec_axis_t) == 2, "axis entry is 2 bytes");
_Static_assert(sizeof(rec_session_t) == 78, "SESSION fixed part is 78 bytes");
_Static_assert(sizeof(rec_imu_t) == 20, "IMU header is 20 bytes");
_Static_assert(sizeof(rec_audio_t) == 20, "AUDIO header is 20 bytes");
_Static_assert(sizeof(rec_event_t) == 12, "EVENT header is 12 bytes");
_Static_assert(sizeof(rec_level_t) == 26, "LEVEL is 26 bytes");
_Static_assert(sizeof(rec_stats_t) == 56, "STATS is 56 bytes");
_Static_assert(sizeof(rec_end_t) == 37, "END is 37 bytes");
_Static_assert(sizeof(rec_imu_t) + REC_IMU_MAX_FRAMES * REC_IMU_FRAME_LEN <= REC_MAX_PAYLOAD,
               "a full FIFO read fits one IMU record");

// Reflected polynomial: bit 31 is x^0, bit 0 is x^31.
#define CRC_POLY 0xEDB88320u

// A byte at a time (1 KB of flash): the producers run it on every record.
static const uint32_t CRC_TABLE[256] = {
    0x00000000u, 0x77073096u, 0xEE0E612Cu, 0x990951BAu, 0x076DC419u, 0x706AF48Fu,
    0xE963A535u, 0x9E6495A3u, 0x0EDB8832u, 0x79DCB8A4u, 0xE0D5E91Eu, 0x97D2D988u,
    0x09B64C2Bu, 0x7EB17CBDu, 0xE7B82D07u, 0x90BF1D91u, 0x1DB71064u, 0x6AB020F2u,
    0xF3B97148u, 0x84BE41DEu, 0x1ADAD47Du, 0x6DDDE4EBu, 0xF4D4B551u, 0x83D385C7u,
    0x136C9856u, 0x646BA8C0u, 0xFD62F97Au, 0x8A65C9ECu, 0x14015C4Fu, 0x63066CD9u,
    0xFA0F3D63u, 0x8D080DF5u, 0x3B6E20C8u, 0x4C69105Eu, 0xD56041E4u, 0xA2677172u,
    0x3C03E4D1u, 0x4B04D447u, 0xD20D85FDu, 0xA50AB56Bu, 0x35B5A8FAu, 0x42B2986Cu,
    0xDBBBC9D6u, 0xACBCF940u, 0x32D86CE3u, 0x45DF5C75u, 0xDCD60DCFu, 0xABD13D59u,
    0x26D930ACu, 0x51DE003Au, 0xC8D75180u, 0xBFD06116u, 0x21B4F4B5u, 0x56B3C423u,
    0xCFBA9599u, 0xB8BDA50Fu, 0x2802B89Eu, 0x5F058808u, 0xC60CD9B2u, 0xB10BE924u,
    0x2F6F7C87u, 0x58684C11u, 0xC1611DABu, 0xB6662D3Du, 0x76DC4190u, 0x01DB7106u,
    0x98D220BCu, 0xEFD5102Au, 0x71B18589u, 0x06B6B51Fu, 0x9FBFE4A5u, 0xE8B8D433u,
    0x7807C9A2u, 0x0F00F934u, 0x9609A88Eu, 0xE10E9818u, 0x7F6A0DBBu, 0x086D3D2Du,
    0x91646C97u, 0xE6635C01u, 0x6B6B51F4u, 0x1C6C6162u, 0x856530D8u, 0xF262004Eu,
    0x6C0695EDu, 0x1B01A57Bu, 0x8208F4C1u, 0xF50FC457u, 0x65B0D9C6u, 0x12B7E950u,
    0x8BBEB8EAu, 0xFCB9887Cu, 0x62DD1DDFu, 0x15DA2D49u, 0x8CD37CF3u, 0xFBD44C65u,
    0x4DB26158u, 0x3AB551CEu, 0xA3BC0074u, 0xD4BB30E2u, 0x4ADFA541u, 0x3DD895D7u,
    0xA4D1C46Du, 0xD3D6F4FBu, 0x4369E96Au, 0x346ED9FCu, 0xAD678846u, 0xDA60B8D0u,
    0x44042D73u, 0x33031DE5u, 0xAA0A4C5Fu, 0xDD0D7CC9u, 0x5005713Cu, 0x270241AAu,
    0xBE0B1010u, 0xC90C2086u, 0x5768B525u, 0x206F85B3u, 0xB966D409u, 0xCE61E49Fu,
    0x5EDEF90Eu, 0x29D9C998u, 0xB0D09822u, 0xC7D7A8B4u, 0x59B33D17u, 0x2EB40D81u,
    0xB7BD5C3Bu, 0xC0BA6CADu, 0xEDB88320u, 0x9ABFB3B6u, 0x03B6E20Cu, 0x74B1D29Au,
    0xEAD54739u, 0x9DD277AFu, 0x04DB2615u, 0x73DC1683u, 0xE3630B12u, 0x94643B84u,
    0x0D6D6A3Eu, 0x7A6A5AA8u, 0xE40ECF0Bu, 0x9309FF9Du, 0x0A00AE27u, 0x7D079EB1u,
    0xF00F9344u, 0x8708A3D2u, 0x1E01F268u, 0x6906C2FEu, 0xF762575Du, 0x806567CBu,
    0x196C3671u, 0x6E6B06E7u, 0xFED41B76u, 0x89D32BE0u, 0x10DA7A5Au, 0x67DD4ACCu,
    0xF9B9DF6Fu, 0x8EBEEFF9u, 0x17B7BE43u, 0x60B08ED5u, 0xD6D6A3E8u, 0xA1D1937Eu,
    0x38D8C2C4u, 0x4FDFF252u, 0xD1BB67F1u, 0xA6BC5767u, 0x3FB506DDu, 0x48B2364Bu,
    0xD80D2BDAu, 0xAF0A1B4Cu, 0x36034AF6u, 0x41047A60u, 0xDF60EFC3u, 0xA867DF55u,
    0x316E8EEFu, 0x4669BE79u, 0xCB61B38Cu, 0xBC66831Au, 0x256FD2A0u, 0x5268E236u,
    0xCC0C7795u, 0xBB0B4703u, 0x220216B9u, 0x5505262Fu, 0xC5BA3BBEu, 0xB2BD0B28u,
    0x2BB45A92u, 0x5CB36A04u, 0xC2D7FFA7u, 0xB5D0CF31u, 0x2CD99E8Bu, 0x5BDEAE1Du,
    0x9B64C2B0u, 0xEC63F226u, 0x756AA39Cu, 0x026D930Au, 0x9C0906A9u, 0xEB0E363Fu,
    0x72076785u, 0x05005713u, 0x95BF4A82u, 0xE2B87A14u, 0x7BB12BAEu, 0x0CB61B38u,
    0x92D28E9Bu, 0xE5D5BE0Du, 0x7CDCEFB7u, 0x0BDBDF21u, 0x86D3D2D4u, 0xF1D4E242u,
    0x68DDB3F8u, 0x1FDA836Eu, 0x81BE16CDu, 0xF6B9265Bu, 0x6FB077E1u, 0x18B74777u,
    0x88085AE6u, 0xFF0F6A70u, 0x66063BCAu, 0x11010B5Cu, 0x8F659EFFu, 0xF862AE69u,
    0x616BFFD3u, 0x166CCF45u, 0xA00AE278u, 0xD70DD2EEu, 0x4E048354u, 0x3903B3C2u,
    0xA7672661u, 0xD06016F7u, 0x4969474Du, 0x3E6E77DBu, 0xAED16A4Au, 0xD9D65ADCu,
    0x40DF0B66u, 0x37D83BF0u, 0xA9BCAE53u, 0xDEBB9EC5u, 0x47B2CF7Fu, 0x30B5FFE9u,
    0xBDBDF21Cu, 0xCABAC28Au, 0x53B39330u, 0x24B4A3A6u, 0xBAD03605u, 0xCDD70693u,
    0x54DE5729u, 0x23D967BFu, 0xB3667A2Eu, 0xC4614AB8u, 0x5D681B02u, 0x2A6F2B94u,
    0xB40BBE37u, 0xC30C8EA1u, 0x5A05DF1Bu, 0x2D02EF8Du,
};

uint32_t rec_crc32(uint32_t crc, const void *data, size_t len)
{
    const uint8_t *p = data;
    crc = ~crc;
    for (size_t i = 0; i < len; i++) {
        crc = (crc >> 8) ^ CRC_TABLE[(crc ^ p[i]) & 0xFFu];
    }
    return ~crc;
}

// a * b modulo the polynomial, both reflected.
static uint32_t mult_mod_p(uint32_t a, uint32_t b)
{
    uint32_t p = 0;
    for (uint32_t m = 1u << 31; m != 0; m >>= 1) {
        if (a & m) {
            p ^= b;
        }
        b = (b & 1u) ? (b >> 1) ^ CRC_POLY : b >> 1;
    }
    return p;
}

uint32_t rec_crc32_combine_op(size_t len_b)
{
    // x^(8 len_b) by squaring: sq runs through x^8, x^16, x^32, ...
    uint32_t op = 1u << 31;
    uint32_t sq = 1u << (31 - 8);
    for (size_t n = len_b; n != 0; n >>= 1) {
        if (n & 1u) {
            op = mult_mod_p(sq, op);
        }
        sq = mult_mod_p(sq, sq);
    }
    return op;
}

uint32_t rec_crc32_combine(uint32_t crc_a, uint32_t crc_b, uint32_t op)
{
    return mult_mod_p(op, crc_a) ^ crc_b;
}

size_t rec_stream_encode(uint8_t type, uint8_t ver, uint32_t seq, const rec_part_t *parts,
                         size_t nparts, uint8_t *out, size_t cap)
{
    size_t len = rec_parts_len(parts, nparts);
    if (len > REC_MAX_PAYLOAD || rec_record_size(len) > cap) {
        return 0;
    }
    const rec_hdr_t h = {.type = type, .ver = ver, .len = (uint16_t)len, .seq = seq};
    memcpy(out, &h, sizeof(h));
    size_t o = sizeof(h);
    for (size_t i = 0; i < nparts; i++) {
        if (parts[i].len > 0) {
            memcpy(&out[o], parts[i].data, parts[i].len);
            o += parts[i].len;
        }
    }
    rec_put_le32(&out[o], rec_crc32(0, out, o));
    return o + REC_CRC_LEN;
}

// Bytes of s to keep within max without cutting a UTF-8 sequence.
static size_t utf8_cut(const char *s, size_t max)
{
    size_t n = strnlen(s, max + 1);
    if (n <= max) {
        return n;
    }
    n = max;
    // Back off continuation bytes (10xxxxxx) to the start of the sequence,
    // which then does not fit either.
    while (n > 0 && ((uint8_t)s[n] & 0xC0u) == 0x80u) {
        n--;
    }
    return n;
}

void rec_fw_text(char out[REC_FW_LEN], const char *version, const char *git)
{
    memset(out, 0, REC_FW_LEN);
    size_t n = strnlen(version, REC_FW_LEN - 1);
    memcpy(out, version, n);
    if (git && git[0] && n + 2 < REC_FW_LEN - 1) {
        memcpy(&out[n], "-g", 2);
        n += 2;
        memcpy(&out[n], git, strnlen(git, REC_FW_LEN - 1 - n));
    }
}

size_t rec_event_payload(uint8_t *out, uint64_t host_us, uint16_t code, uint16_t arg,
                         const char *text)
{
    const rec_event_t e = {.host_us = host_us, .code = code, .arg = arg};
    memcpy(out, &e, sizeof(e));
    size_t n = text ? utf8_cut(text, REC_EVENT_TEXT_MAX) : 0;
    memcpy(&out[sizeof(e)], text ? text : "", n);
    return sizeof(e) + n;
}

int rec_stream_check(const uint8_t *buf, size_t len, rec_hdr_t *hdr)
{
    if (len < REC_HDR_LEN) {
        return 0;
    }
    rec_hdr_t h;
    memcpy(&h, buf, sizeof(h));
    if (h.ver == 0 || h.len > REC_MAX_PAYLOAD) {
        return -1;
    }
    size_t size = rec_record_size(h.len);
    if (len < size) {
        return 0;
    }
    if (rec_get_le32(&buf[REC_HDR_LEN + h.len]) != rec_crc32(0, buf, REC_HDR_LEN + h.len)) {
        return -1;
    }
    *hdr = h;
    return (int)size;
}
