#ifndef POCKETGB_AUDIO_RING_H
#define POCKETGB_AUDIO_RING_H

#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>

#define AUDIO_RING_CAPACITY 8192u

typedef struct audio_ring {
    int16_t samples[AUDIO_RING_CAPACITY * 2u];
    atomic_uint_fast64_t read_index;
    atomic_uint_fast64_t write_index;
    atomic_uint_fast64_t total_written;
    atomic_uint_fast64_t total_read;
    atomic_uint_fast64_t underruns;
} audio_ring;

void audio_ring_init(audio_ring *ring);
void audio_ring_clear(audio_ring *ring);
size_t audio_ring_available(const audio_ring *ring);
size_t audio_ring_write(audio_ring *ring, const int16_t *samples, size_t frames);
size_t audio_ring_read(audio_ring *ring, int16_t *samples, size_t frames);

#endif
