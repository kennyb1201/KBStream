package com.kennyb1201.kbstream.ui.player

// This file intentionally left minimal.
// Tunneled playback is now configured at the ExoPlayer level
// via AudioAttributes with FLAG_HW_AV_SYNC, which works with
// the Jellyfin media3-ffmpeg fork without requiring API access
// to DefaultAudioSink or buildAudioRenderers.
