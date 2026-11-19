// The LibVLC calls that let Shasha process VLC's sound itself ("Clearer sound").
// They are part of VLCKit's LibVLC, which doesn't publish these headers.
#ifndef LibVLCAudio_h
#define LibVLCAudio_h

#include <stdint.h>
#include <stdbool.h>

struct libvlc_media_player_t;

typedef void (*shasha_audio_play_cb)(void *data, const void *samples, unsigned count, int64_t pts);
typedef void (*shasha_audio_pause_cb)(void *data, int64_t pts);
typedef void (*shasha_audio_resume_cb)(void *data, int64_t pts);
typedef void (*shasha_audio_flush_cb)(void *data);
typedef void (*shasha_audio_drain_cb)(void *data);
typedef void (*shasha_audio_volume_cb)(void *data, float volume, bool mute);
typedef int (*shasha_audio_setup_cb)(void **opaque, char *format, unsigned *rate, unsigned *channels);
typedef void (*shasha_audio_cleanup_cb)(void *opaque);

void libvlc_audio_set_callbacks(struct libvlc_media_player_t *mp,
                                shasha_audio_play_cb play, shasha_audio_pause_cb pause,
                                shasha_audio_resume_cb resume, shasha_audio_flush_cb flush,
                                shasha_audio_drain_cb drain, void *opaque);
void libvlc_audio_set_volume_callback(struct libvlc_media_player_t *mp, shasha_audio_volume_cb set_volume);
void libvlc_audio_set_format_callbacks(struct libvlc_media_player_t *mp,
                                       shasha_audio_setup_cb setup, shasha_audio_cleanup_cb cleanup);
int64_t libvlc_clock(void);

#endif
