package com.kennyb1201.kbstream.data.device

/**
 * What this device can be asked to decode, for the stream ranker.
 *
 * This object used to answer one question: `constrainedStreamDevice`, a total
 * RAM probe that let
 * [com.kennyb1201.kbstream.domain.streamengine.StreamRanker] demote 4K below
 * 1080p on any box reporting less than 2.5 GB. RAM was a proxy for "can this
 * decode 4K", and the field says the proxy is wrong - the 2 GB TV boxes play 4K
 * fine, 50 GB files included - so the probe overrode the viewer on hardware
 * that never needed protecting.
 *
 * The ranker no longer takes a device fact at all: it is a pure object with no
 * Android dependency, and 4K scores its full resolution bonus on every box. The
 * viewer-facing control for wanting a smaller stream is Settings -> auto-play
 * quality cap (`max_auto_play_quality`, see
 * [com.kennyb1201.kbstream.domain.streamengine.AutoPlayQuality]), which moves
 * only the head auto-play starts.
 */
internal object DeviceCapability
