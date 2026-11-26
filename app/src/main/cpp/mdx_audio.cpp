#include "mdx_audio.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <cstdio>
#include <memory>
#include <stdexcept>
#include <vector>

namespace mdx {
namespace {
constexpr int playback_rate = 48000;
constexpr int model_rate = 44100;
constexpr int fft_size = 6144;
constexpr int radix_size = fft_size / 3;
constexpr int hop = 1024;
constexpr int chunk_size = hop * (time_bins - 1);
constexpr int half_fft = fft_size / 2;
constexpr int wave_header = 44;
constexpr int output_block = int(int64_t(chunk_size - fft_size - 4) * playback_rate / model_rate);
constexpr int resample_radius = 12;
constexpr float pi = 3.14159265358979323846f;
using Complex = std::complex<float>;
using Stereo = std::array<float, 2>;
using Pcm = std::array<int16_t, 2>;
static_assert(sizeof(Pcm) == 4);

// 6144 = 3 x 2048. Reuse FFT roots and the bit-reversal table for each window.
class Fft {
    std::vector<Complex> roots = std::vector<Complex>(fft_size);
    std::vector<Complex> scratch = std::vector<Complex>(fft_size);
    std::array<int, radix_size> reversed{};

public:
    Fft() {
        for (int k = 0; k < fft_size; ++k) roots[k] = std::polar(1.f, -2 * pi * k / fft_size);
        for (int k = 1; k < radix_size; ++k) {
            reversed[k] = (reversed[k / 2] / 2) + (k % 2) * (radix_size / 2);
        }
    }

    void forward(std::vector<Complex>& values) {
        for (int group = 0; group < 3; ++group) {
            auto* part = scratch.data() + group * radix_size;
            for (int k = 0; k < radix_size; ++k) part[reversed[k]] = values[3 * k + group];
            for (int length = 2; length <= radix_size; length *= 2) {
                const int stride = fft_size / length;
                for (int block = 0; block < radix_size; block += length) {
                    for (int i = 0; i < length / 2; ++i) {
                        const auto first = part[block + i];
                        const auto second = part[block + i + length / 2] * roots[i * stride];
                        part[block + i] = first + second;
                        part[block + i + length / 2] = first - second;
                    }
                }
            }
        }
        for (int k = 0; k < fft_size; ++k) {
            const int offset = k % radix_size;
            values[k] = scratch[offset] + scratch[radix_size + offset] * roots[k] +
                        scratch[2 * radix_size + offset] * roots[(2 * k) % fft_size];
        }
    }

    void inverse(std::vector<Complex>& values) {
        for (auto& value : values) value = std::conj(value);
        forward(values);
        for (auto& value : values) value = std::conj(value) / float(fft_size);
    }
};

// Both sample-rate ratios have small rational denominators. Precompute the fractional
// phases of a windowed sinc, using a low-pass cutoff for downsampling.
class Resampler {
    int numerator;
    int denominator;
    std::vector<std::array<float, 2 * resample_radius>> weights;

public:
    Resampler(int from, int to) : numerator(from / 300), denominator(to / 300), weights(denominator) {
        const float cutoff = 0.95f * std::min(1.f, float(to) / from);
        for (int phase = 0; phase < denominator; ++phase) {
            auto& coefficients = weights[phase];
            float sum = 0;
            for (int i = 0; i < int(coefficients.size()); ++i) {
                const float distance = i - resample_radius + 1 - float(phase) / denominator;
                const float x = pi * distance * cutoff;
                const float sinc = std::abs(x) < 1e-7f ? 1.f : std::sin(x) / x;
                coefficients[i] = cutoff * sinc * (0.5f + 0.5f * std::cos(pi * distance / resample_radius));
                sum += coefficients[i];
            }
            for (auto& entry : coefficients) entry /= sum;
        }
    }

    int64_t position(int64_t frame) const {
        return frame * numerator / denominator;
    }

    template<class ReadFrame>
    Stereo sample(int64_t frame, ReadFrame read) const {
        auto scaled = frame * numerator;
        auto phase = scaled % denominator;
        if (phase < 0) phase += denominator;
        const auto first = (scaled - phase) / denominator - resample_radius + 1;
        Stereo output{0, 0};
        const auto& coefficients = weights[phase];
        for (int i = 0; i < int(coefficients.size()); ++i) {
            const auto input = read(first + i);
            output[0] += input[0] * coefficients[i];
            output[1] += input[1] * coefficients[i];
        }
        return output;
    }
};

using File = std::unique_ptr<FILE, decltype(&fclose)>;

void write_wave_header(FILE* file, int64_t frames) {
    const auto bytes = uint32_t(frames * sizeof(Pcm));
    auto little_endian = [](uint32_t value) {
        return std::array<uint8_t, 4>{uint8_t(value), uint8_t(value >> 8), uint8_t(value >> 16), uint8_t(value >> 24)};
    };
    auto write_word = [&](uint32_t value) {
        const auto encoded = little_endian(value);
        if (fwrite(encoded.data(), 1, encoded.size(), file) != encoded.size()) throw std::runtime_error("Cannot write WAV");
    };
    write_word(0x46464952);  // RIFF
    write_word(36 + bytes);
    write_word(0x45564157);  // WAVE
    write_word(0x20746d66);  // fmt
    write_word(16);
    write_word(0x00020001);  // stereo PCM
    write_word(playback_rate);
    write_word(playback_rate * sizeof(Pcm));
    write_word(0x00100004);  // 16-bit, 4 bytes per frame
    write_word(0x61746164);  // data
    write_word(bytes);
}

class Spectrogram {
    Fft fft;
    std::vector<float> window = std::vector<float>(fft_size);
    std::vector<Complex> spectrum = std::vector<Complex>(fft_size);
    std::vector<float> normalization = std::vector<float>(chunk_size);

    static int reflect(int index) {
        if (index < 0) return -index;
        return std::min(index, 2 * chunk_size - 2 - index);
    }

    static int offset(int channel, int component, int frequency, int time) {
        return ((channel * 2 + component) * frequency_bins + frequency) * time_bins + time;
    }

public:
    Spectrogram() {
        for (int i = 0; i < fft_size; ++i) window[i] = 0.5f - 0.5f * std::cos(2 * pi * i / fft_size);
        for (int t = 0; t < time_bins; ++t) {
            const int start = t * hop - half_fft;
            for (int i = std::max(0, -start); i < std::min(fft_size, chunk_size - start); ++i) {
                normalization[start + i] += window[i] * window[i];
            }
        }
    }

    void encode(const std::vector<Stereo>& mixture, std::vector<float>& tensor) {
        for (int channel = 0; channel < 2; ++channel) {
            for (int t = 0; t < time_bins; ++t) {
                const int start = t * hop - half_fft;
                for (int i = 0; i < fft_size; ++i) spectrum[i] = mixture[reflect(start + i)][channel] * window[i];
                fft.forward(spectrum);
                for (int f = 0; f < frequency_bins; ++f) {
                    tensor[offset(channel, 0, f, t)] = spectrum[f].real();
                    tensor[offset(channel, 1, f, t)] = spectrum[f].imag();
                }
            }
        }
    }

    void decode(const std::vector<float>& tensor, std::vector<Stereo>& vocals) {
        std::fill(vocals.begin(), vocals.end(), Stereo{0, 0});
        for (int channel = 0; channel < 2; ++channel) {
            for (int t = 0; t < time_bins; ++t) {
                const int start = t * hop - half_fft;
                std::fill(spectrum.begin(), spectrum.end(), Complex{});
                for (int f = 0; f < frequency_bins; ++f) {
                    const Complex value{tensor[offset(channel, 0, f, t)], tensor[offset(channel, 1, f, t)]};
                    spectrum[f] = value;
                    if (f != 0) spectrum[fft_size - f] = std::conj(value);
                }
                fft.inverse(spectrum);
                for (int i = std::max(0, -start); i < std::min(fft_size, chunk_size - start); ++i) {
                    vocals[start + i][channel] += spectrum[i].real() * window[i];
                }
            }
        }
        for (int i = 0; i < chunk_size; ++i) {
            vocals[i][0] /= normalization[i];
            vocals[i][1] /= normalization[i];
        }
    }
};

int16_t pcm16(float value) {
    if (!std::isfinite(value)) throw std::runtime_error("Model returned non-finite audio");
    return int16_t(std::lrintf(std::clamp(value * 32768.f, -32768.f, 32767.f)));
}
}

bool separate(const char* input, const char* output, int64_t frames, int strength_percent, int overlap_ms,
              const std::function<void(float*, float*)>& predict,
              const std::function<bool(int)>& progress) {
    if (frames <= 0 || frames > int64_t(playback_rate) * 1800) throw std::runtime_error("Invalid WAV length");
    File source(fopen(input, "rb"), fclose);
    File target(fopen(output, "wb"), fclose);
    if (!source || !target) throw std::runtime_error("Cannot open separation audio files");
    write_wave_header(target.get(), frames);

    Resampler to_model(playback_rate, model_rate);
    Resampler to_playback(model_rate, playback_rate);
    Spectrogram spectrum;
    std::vector<float> model_input(tensor_size);
    std::vector<float> model_output(tensor_size);
    std::vector<Stereo> mixture(chunk_size);
    std::vector<Stereo> vocals(chunk_size);
    const int output_overlap = playback_rate * overlap_ms / 1000;
    const float compensation = strength_percent / 100.0f;
    std::vector<Stereo> previous_tail(output_overlap);
    std::vector<Pcm> pcm(output_block);
    std::vector<Pcm> input_frames;
    const int step = output_block - output_overlap;

    for (int64_t first = 0; first < frames; first += step) {
        const int percent = int(first * 100 / frames);
        if (!progress(percent)) return false;
        const auto model_start = to_playback.position(first) - half_fft;
        const auto read_begin = std::max<int64_t>(0, to_model.position(model_start) - resample_radius);
        const auto read_end = std::min(frames, to_model.position(model_start + chunk_size - 1) + resample_radius + 1);
        input_frames.resize(read_end - read_begin);
        if (fseek(source.get(), wave_header + read_begin * sizeof(Pcm), SEEK_SET) != 0 ||
            fread(input_frames.data(), sizeof(Pcm), input_frames.size(), source.get()) != input_frames.size()) {
            throw std::runtime_error("Cannot read source WAV");
        }
        auto read_source = [&](int64_t frame) -> Stereo {
            if (frame < read_begin || frame >= read_end) return {0, 0};
            const auto& value = input_frames[frame - read_begin];
            return {value[0] / 32768.f, value[1] / 32768.f};
        };
        for (int i = 0; i < chunk_size; ++i) mixture[i] = to_model.sample(model_start + i, read_source);
        spectrum.encode(mixture, model_input);
        if (!progress(percent)) return false;
        predict(model_input.data(), model_output.data());
        if (!progress(percent)) return false;
        spectrum.decode(model_output, vocals);

        const bool last = first + output_block >= frames;
        const int write_count = last ? int(frames - first) : step;
        for (int i = 0; i < write_count + (last ? 0 : output_overlap); ++i) {
            auto predicted = to_playback.sample(first + i, [&](int64_t frame) -> Stereo {
                const auto index = frame - model_start;
                if (index < 0 || index >= chunk_size) return {0, 0};
                return vocals[index];
            });
            if (i >= write_count) {
                previous_tail[i - write_count] = predicted;
                continue;
            }
            const auto dry = read_source(first + i);
            if (first > 0 && i < output_overlap) {
                const float fade = (i + 0.5f) / output_overlap;
                for (int c = 0; c < 2; ++c) predicted[c] = previous_tail[i][c] * (1 - fade) + predicted[c] * fade;
            }
            for (int c = 0; c < 2; ++c) pcm[i][c] = pcm16(dry[c] - compensation * predicted[c]);
        }
        if (fwrite(pcm.data(), sizeof(Pcm), write_count, target.get()) != size_t(write_count)) {
            throw std::runtime_error("Cannot write accompaniment WAV");
        }
        if (last) break;
    }
    if (fflush(target.get()) != 0) throw std::runtime_error("Cannot finish accompaniment WAV");
    return progress(100);
}
}
