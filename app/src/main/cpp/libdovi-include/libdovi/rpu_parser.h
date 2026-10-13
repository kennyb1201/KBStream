/*
 * The libdovi C API, declared for the bridge.
 *
 * libdovi does not ship this header in its source tree -- cargo-c generates it
 * from `dolby_vision/cbindgen.toml` at install time (its `[lib] name = "dovi"`
 * metadata is what the file is named after) -- so the declarations the bridge
 * needs are written out here instead, pinned to the crate version
 * scripts/build_dovi.sh fetches (dolby_vision 3.4.0).
 *
 * This is a deliberate, bounded duplication, and a stale one is meant to be
 * LOUD: a future version whose signatures or struct layout moved fails the
 * bridge compile inside the CI gate that already refuses to ship a release
 * without libdovi, rather than silently mis-reading an RPU at runtime. To
 * regenerate these declarations from the crate source:
 *
 *   cargo install cbindgen
 *   cbindgen --crate dolby_vision \
 *     --config <dolby_vision source>/cbindgen.toml \
 *     --output libdovi/rpu_parser.h
 *
 * Only what dovi_bridge.c touches is documented here as load-bearing:
 * `DoviRpuDataHeader.guessed_profile` (the one field the bridge reads, and the
 * struct's first member) and `DoviData`'s `data`/`len` (the bytes libdovi
 * writes back). The rest of DoviRpuDataHeader's layout is reproduced so the
 * struct's size matches what the library allocates.
 */

#ifndef KBSTREAM_LIBDOVI_RPU_PARSER_H
#define KBSTREAM_LIBDOVI_RPU_PARSER_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct DoviRpuOpaque DoviRpuOpaque;

typedef struct DoviRpuDataHeader {
    /** The Dolby Vision profile the header identifies: 4, 5, 7 or 8; 0 = none. */
    uint8_t guessed_profile;
    /** "FEL"/"MEL" for profile 7, null otherwise. */
    const char *el_type;
    uint8_t rpu_nal_prefix;
    uint8_t rpu_type;
    uint16_t rpu_format;
    uint8_t vdr_rpu_profile;
    uint8_t vdr_rpu_level;
    bool vdr_seq_info_present_flag;
    bool chroma_resampling_explicit_filter_flag;
    uint8_t coefficient_data_type;
    uint64_t coefficient_log2_denom;
    uint8_t vdr_rpu_normalized_idc;
    bool bl_video_full_range_flag;
    uint64_t bl_bit_depth_minus8;
    uint64_t el_bit_depth_minus8;
    uint64_t vdr_bit_depth_minus8;
    bool spatial_resampling_filter_flag;
    uint8_t reserved_zero_3bits;
    bool el_spatial_resampling_filter_flag;
    bool disable_residual_flag;
    bool vdr_dm_metadata_present_flag;
    bool use_prev_vdr_rpu_flag;
    uint64_t prev_vdr_rpu_id;
} DoviRpuDataHeader;

/** A heap buffer libdovi wrote: free it with dovi_data_free(). */
typedef struct DoviData {
    uint8_t *data;
    size_t len;
} DoviData;

/** Parses an UNSPEC62 RPU NAL: bytes INCLUDING its 2-byte NAL header. */
DoviRpuOpaque *dovi_parse_unspec62_nalu(const uint8_t *buf, size_t len);

/** The parsed header, or null when nothing parsed. Free with dovi_rpu_free_header(). */
const DoviRpuDataHeader *dovi_rpu_get_header(const DoviRpuOpaque *ptr);

/** Converts the parsed RPU in place: 0 on success, -1 on failure. */
int32_t dovi_convert_rpu_with_mode(DoviRpuOpaque *ptr, uint8_t mode);

/** The converted RPU NAL (header included), or null. Free with dovi_data_free(). */
const DoviData *dovi_write_unspec62_nalu(DoviRpuOpaque *ptr);

/** The parse/convert error string, or null. Owned by the object - do not free. */
const char *dovi_rpu_get_error(const DoviRpuOpaque *ptr);

void dovi_rpu_free(DoviRpuOpaque *ptr);

void dovi_rpu_free_header(const DoviRpuDataHeader *ptr);

void dovi_data_free(const DoviData *data);

#ifdef __cplusplus
}
#endif

#endif /* KBSTREAM_LIBDOVI_RPU_PARSER_H */
