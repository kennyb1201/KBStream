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

/*
 * The RPU's own HDR10 metadata — the part of a Profile 8 RPU that survives as
 * the "static HDR10" record once the RPU itself is stripped.
 *
 * Level 6 is the block Dolby's spec defines as the ST 2086/HDR10 metadata
 * fallback: max/min display mastering luminance and MaxCLL/MaxFALL. It does NOT
 * carry the display primaries or the white point (Light Level 9/10/11 carry
 * primaries as *indices*, not as ST 2086 chromaticities), because a Profile 8
 * base layer is BT.2020 / D65 by definition — so a complete ST 2086 record can
 * be built from this block plus those fixed primaries. That is what
 * [DvHdr10StaticInfo] does on the Kotlin side.
 *
 * Only the path down to `dm_data.level6` is reproduced: the block lists above
 * it (level8/9/10/11/254/255) are never read, and their absence from this
 * struct only makes it shorter than the object libdovi allocated — the fields
 * below keep their real offsets, which is all a read needs.
 *
 * Copied from the header a real build generates (cargo-c/cbindgen), the same
 * one checked against the 3.3.2 tree the reference player ships: this part of
 * the C ABI is identical across 3.3.2 and the 3.4.0 this repo pins (the
 * `DoviRpuDataHeader` reproduced above matches that header field-for-field).
 */
typedef struct {
    uint16_t max_display_mastering_luminance;
    uint16_t min_display_mastering_luminance;
    uint16_t max_content_light_level;
    uint16_t max_frame_average_light_level;
} DoviExtMetadataBlockLevel6;

/**
 * Level 2's block list, whose only role here is its size in [DoviDmData].
 * The element type is a pointer list (`const DoviExtMetadataBlockLevel2 *const
 * *`) plus a length, which is layout-identical to this on every ABI.
 */
typedef struct {
    const void *const *list;
    size_t len;
} DoviLevel2BlockList;

/** The DM metadata blocks, up to the one this app reads. */
typedef struct {
    uint64_t num_ext_blocks;
    const void *level1;
    DoviLevel2BlockList level2;
    const void *level3;
    const void *level4;
    const void *level5;
    const DoviExtMetadataBlockLevel6 *level6;
} DoviDmData;

typedef struct {
    bool compressed;
    uint64_t affected_dm_metadata_id;
    uint64_t current_dm_metadata_id;
    uint64_t scene_refresh_flag;
    int16_t ycc_to_rgb_coef0;
    int16_t ycc_to_rgb_coef1;
    int16_t ycc_to_rgb_coef2;
    int16_t ycc_to_rgb_coef3;
    int16_t ycc_to_rgb_coef4;
    int16_t ycc_to_rgb_coef5;
    int16_t ycc_to_rgb_coef6;
    int16_t ycc_to_rgb_coef7;
    int16_t ycc_to_rgb_coef8;
    uint32_t ycc_to_rgb_offset0;
    uint32_t ycc_to_rgb_offset1;
    uint32_t ycc_to_rgb_offset2;
    int16_t rgb_to_lms_coef0;
    int16_t rgb_to_lms_coef1;
    int16_t rgb_to_lms_coef2;
    int16_t rgb_to_lms_coef3;
    int16_t rgb_to_lms_coef4;
    int16_t rgb_to_lms_coef5;
    int16_t rgb_to_lms_coef6;
    int16_t rgb_to_lms_coef7;
    int16_t rgb_to_lms_coef8;
    uint16_t signal_eotf;
    uint16_t signal_eotf_param0;
    uint16_t signal_eotf_param1;
    uint32_t signal_eotf_param2;
    uint8_t signal_bit_depth;
    uint8_t signal_color_space;
    uint8_t signal_chroma_format;
    uint8_t signal_full_range_flag;
    uint16_t source_min_pq;
    uint16_t source_max_pq;
    uint16_t source_diagonal;
    DoviDmData dm_data;
} DoviVdrDmData;

/** Parses an UNSPEC62 RPU NAL: bytes INCLUDING its 2-byte NAL header. */
DoviRpuOpaque *dovi_parse_unspec62_nalu(const uint8_t *buf, size_t len);

/** The parsed header, or null when nothing parsed. Free with dovi_rpu_free_header(). */
const DoviRpuDataHeader *dovi_rpu_get_header(const DoviRpuOpaque *ptr);

/** Converts the parsed RPU in place: 0 on success, -1 on failure. */
int32_t dovi_convert_rpu_with_mode(DoviRpuOpaque *ptr, uint8_t mode);

/** The converted RPU NAL (header included), or null. Free with dovi_data_free(). */
const DoviData *dovi_write_unspec62_nalu(DoviRpuOpaque *ptr);

/**
 * The parsed RPU's DM metadata, or null. Free with dovi_rpu_free_vdr_dm_data().
 *
 * `dm_data.level6` is null too when the RPU carries no Level 6 block, which is
 * the common answer for a stream whose HDR10 static metadata lives in the base
 * layer's SEI instead.
 */
const DoviVdrDmData *dovi_rpu_get_vdr_dm_data(const DoviRpuOpaque *ptr);

void dovi_rpu_free_vdr_dm_data(const DoviVdrDmData *ptr);

/** The parse/convert error string, or null. Owned by the object - do not free. */
const char *dovi_rpu_get_error(const DoviRpuOpaque *ptr);

void dovi_rpu_free(DoviRpuOpaque *ptr);

void dovi_rpu_free_header(const DoviRpuDataHeader *ptr);

void dovi_data_free(const DoviData *data);

#ifdef __cplusplus
}
#endif

#endif /* KBSTREAM_LIBDOVI_RPU_PARSER_H */
