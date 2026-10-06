#include "audio_output.h"

#include <math.h>
#include <stdint.h>

static aaudio_data_callback_result_t render_audio(
    AAudioStream *stream,
    void *user_data,
    void *audio_data,
    int32_t frame_count
) {
    (void)stream;
    audio_output *output = user_data;
    int16_t *samples = audio_data;
    const size_t frames = audio_ring_read(output->ring, samples, (size_t)frame_count);
    const float gain = atomic_load_explicit(&output->volume, memory_order_relaxed);
    if (gain < 1.0f) {
        for (size_t i = 0; i < frames * 2u; ++i) {
            samples[i] = (int16_t)((float)samples[i] * gain);
        }
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void audio_error(AAudioStream *stream, void *user_data, aaudio_result_t error) {
    (void)stream;
    (void)error;
    audio_output *output = user_data;
    atomic_store_explicit(&output->failed, true, memory_order_release);
}

void audio_output_init(audio_output *output, audio_ring *ring) {
    if (output == NULL) return;
    output->stream = NULL;
    output->ring = ring;
    atomic_init(&output->failed, false);
    atomic_init(&output->volume, 1.0f);
}

void audio_output_set_volume(audio_output *output, float gain) {
    if (output == NULL || !isfinite(gain)) return;
    atomic_store_explicit(&output->volume, gain < 0.0f ? 0.0f : (gain > 1.0f ? 1.0f : gain), memory_order_relaxed);
}

float audio_output_volume(const audio_output *output) {
    if (output == NULL) return 1.0f;
    return atomic_load_explicit(&output->volume, memory_order_relaxed);
}

bool audio_output_start(audio_output *output) {
    if (output == NULL || output->ring == NULL) return false;
    audio_output_stop(output);
    atomic_store_explicit(&output->failed, false, memory_order_release);
    AAudioStreamBuilder *builder = NULL;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return false;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, 48000);
    AAudioStreamBuilder_setDataCallback(builder, render_audio, output);
    AAudioStreamBuilder_setErrorCallback(builder, audio_error, output);
    const aaudio_result_t opened = AAudioStreamBuilder_openStream(builder, &output->stream);
    AAudioStreamBuilder_delete(builder);
    if (opened != AAUDIO_OK || output->stream == NULL) {
        output->stream = NULL;
        return false;
    }
    if (AAudioStream_requestStart(output->stream) != AAUDIO_OK) {
        audio_output_stop(output);
        return false;
    }
    return true;
}

void audio_output_stop(audio_output *output) {
    if (output == NULL || output->stream == NULL) return;
    (void)AAudioStream_requestStop(output->stream);
    (void)AAudioStream_close(output->stream);
    output->stream = NULL;
}
