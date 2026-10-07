#include "audio_ring.h"

#include <string.h>

void audio_ring_init(audio_ring *ring) {
    if (ring == NULL) return;
    memset(ring->samples, 0, sizeof(ring->samples));
    atomic_init(&ring->read_index, 0u);
    atomic_init(&ring->write_index, 0u);
    atomic_init(&ring->total_written, 0u);
    atomic_init(&ring->total_read, 0u);
    atomic_init(&ring->underruns, 0u);
}

void audio_ring_clear(audio_ring *ring) {
    if (ring == NULL) return;
    const uint64_t write = atomic_load_explicit(&ring->write_index, memory_order_acquire);
    atomic_store_explicit(&ring->read_index, write, memory_order_release);
}

size_t audio_ring_available(const audio_ring *ring) {
    if (ring == NULL) return 0u;
    const uint64_t write = atomic_load_explicit(&ring->write_index, memory_order_acquire);
    const uint64_t read = atomic_load_explicit(&ring->read_index, memory_order_acquire);
    return (size_t)(write - read);
}

size_t audio_ring_write(audio_ring *ring, const int16_t *samples, size_t frames) {
    if (ring == NULL || samples == NULL) return 0u;
    const uint64_t write = atomic_load_explicit(&ring->write_index, memory_order_relaxed);
    const uint64_t read = atomic_load_explicit(&ring->read_index, memory_order_acquire);
    const size_t free_frames = AUDIO_RING_CAPACITY - (size_t)(write - read);
    const size_t count = frames < free_frames ? frames : free_frames;
    for (size_t frame = 0; frame < count; ++frame) {
        const size_t destination = (size_t)((write + frame) % AUDIO_RING_CAPACITY) * 2u;
        ring->samples[destination] = samples[frame * 2u];
        ring->samples[destination + 1u] = samples[frame * 2u + 1u];
    }
    atomic_store_explicit(&ring->write_index, write + count, memory_order_release);
    (void)atomic_fetch_add_explicit(&ring->total_written, count, memory_order_relaxed);
    return count;
}

size_t audio_ring_read(audio_ring *ring, int16_t *samples, size_t frames) {
    if (ring == NULL || samples == NULL) return 0u;
    const uint64_t read = atomic_load_explicit(&ring->read_index, memory_order_relaxed);
    const uint64_t write = atomic_load_explicit(&ring->write_index, memory_order_acquire);
    const size_t available = (size_t)(write - read);
    const size_t count = frames < available ? frames : available;
    for (size_t frame = 0; frame < count; ++frame) {
        const size_t source = (size_t)((read + frame) % AUDIO_RING_CAPACITY) * 2u;
        samples[frame * 2u] = ring->samples[source];
        samples[frame * 2u + 1u] = ring->samples[source + 1u];
    }
    if (count < frames) {
        memset(samples + count * 2u, 0, (frames - count) * 2u * sizeof(*samples));
        (void)atomic_fetch_add_explicit(&ring->underruns, 1u, memory_order_relaxed);
    }
    atomic_store_explicit(&ring->read_index, read + count, memory_order_release);
    (void)atomic_fetch_add_explicit(&ring->total_read, count, memory_order_relaxed);
    return count;
}
