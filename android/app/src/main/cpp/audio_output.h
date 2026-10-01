#ifndef POCKETGB_AUDIO_OUTPUT_H
#define POCKETGB_AUDIO_OUTPUT_H

#include <stdatomic.h>
#include <stdbool.h>

#include <aaudio/AAudio.h>

#include "audio_ring.h"

typedef struct audio_output {
    AAudioStream *stream;
    audio_ring *ring;
    atomic_bool failed;
} audio_output;

void audio_output_init(audio_output *output, audio_ring *ring);
bool audio_output_start(audio_output *output);
void audio_output_stop(audio_output *output);

#endif
