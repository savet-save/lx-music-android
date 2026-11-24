#pragma once

#include <cstdint>
#include <functional>

namespace mdx {
constexpr int frequency_bins = 2048;
constexpr int time_bins = 256;
constexpr int tensor_size = 4 * frequency_bins * time_bins;

// Input/output are the app's PCM16 stereo 48 kHz WAV files. The model predicts vocals.
bool separate(const char* input, const char* output, int64_t frames, int strength_percent, int overlap_ms,
              const std::function<void(float*, float*)>& predict,
              const std::function<bool(int)>& progress);
}
