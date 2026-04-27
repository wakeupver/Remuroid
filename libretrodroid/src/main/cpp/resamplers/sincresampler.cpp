/*
 *     Copyright (C) 2020  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <algorithm>
#include <cmath>
#include <arm_neon.h>
#include "sincresampler.h"

namespace libretrodroid {

// ─── precomputed sinc LUT ─────────────────────────────────────────────────
// We quantise the fractional part of (outputTime*inputFrames - i) into
// SINC_LUT_SIZE bins per integer distance.  Eliminates all sinf() calls
// in the hot loop.  Memory cost: taps * SINC_LUT_SIZE * 4 bytes ≈ 4 KB.

static constexpr int SINC_LUT_SIZE = 128;

SincResampler::SincResampler(const int taps)
    : halfTaps(taps / 2)
{
    lut.resize(taps * SINC_LUT_SIZE);
    for (int tap = 0; tap < taps; ++tap) {
        for (int q = 0; q < SINC_LUT_SIZE; ++q) {
            float x = static_cast<float>(tap - halfTaps + 1) +
                      static_cast<float>(q) / static_cast<float>(SINC_LUT_SIZE);
            lut[tap * SINC_LUT_SIZE + q] = sinc(x);
        }
    }
}

void SincResampler::resample(const int16_t *__restrict__ source,
                              int32_t inputFrames,
                              int16_t *__restrict__ sink,
                              int32_t sinkFrames) {
    if (__builtin_expect(sinkFrames <= 0 || inputFrames <= 0, 0)) return;

    const float outputTimeStep = 1.0f / static_cast<float>(sinkFrames);
    const float inputFramesF   = static_cast<float>(inputFrames);
    float       outputTime     = 0.0f;

    const int taps = halfTaps * 2;

    while (sinkFrames > 0) {
        const float   pos            = outputTime * inputFramesF;
        const int32_t prevInputIndex = static_cast<int32_t>(pos);

        const int32_t startFrame = std::max(prevInputIndex - halfTaps + 1, 0);
        const int32_t endFrame   = std::min(prevInputIndex + halfTaps, inputFrames - 1);
        const int32_t numTaps    = endFrame - startFrame + 1;

        float32x4_t leftV  = vdupq_n_f32(0.0f);
        float32x4_t rightV = vdupq_n_f32(0.0f);
        float32x4_t gainV  = vdupq_n_f32(0.1f);   // same as scalar initialiser

        // NEON dot-product loop over tap window in batches of 4
        int32_t i = startFrame;
        for (; i <= endFrame - 3; i += 4) {
            // LUT lookup for 4 consecutive taps
            auto lutIdx = [&](int32_t ii) -> int32_t {
                float dist = pos - static_cast<float>(ii);
                // dist ∈ [0, halfTaps).  Tap index = (int)dist, quant = frac * LUT_SIZE
                int   tapIdx = static_cast<int>(dist);
                float frac   = dist - static_cast<float>(tapIdx);
                int   q      = static_cast<int>(frac * SINC_LUT_SIZE);
                q = std::max(0, std::min(q, SINC_LUT_SIZE - 1));
                return tapIdx * SINC_LUT_SIZE + q;
            };

            float32x4_t coeffs = {
                lut[lutIdx(i)],
                lut[lutIdx(i+1)],
                lut[lutIdx(i+2)],
                lut[lutIdx(i+3)]
            };

            int16x4x2_t smp0 = vld2_s16(&source[i * 2]);
            float32x4_t l4   = vcvtq_f32_s32(vmovl_s16(smp0.val[0]));
            float32x4_t r4   = vcvtq_f32_s32(vmovl_s16(smp0.val[1]));

            leftV  = vmlaq_f32(leftV,  l4, coeffs);
            rightV = vmlaq_f32(rightV, r4, coeffs);
            gainV  = vaddq_f32(gainV,  coeffs);
        }
        // Horizontal reduce
        float leftResult  = vaddvq_f32(leftV);
        float rightResult = vaddvq_f32(rightV);
        float gain        = vaddvq_f32(gainV);

        // Scalar tail
        for (; i <= endFrame; ++i) {
            auto lutIdx2 = [&](int32_t ii) -> int32_t {
                float dist = pos - static_cast<float>(ii);
                int   ti   = static_cast<int>(dist);
                float frac = dist - static_cast<float>(ti);
                int   q    = static_cast<int>(frac * SINC_LUT_SIZE);
                q = std::max(0, std::min(q, SINC_LUT_SIZE - 1));
                return ti * SINC_LUT_SIZE + q;
            };
            float c    = lut[lutIdx2(i)];
            gain       += c;
            leftResult  += static_cast<float>(source[i * 2])     * c;
            rightResult += static_cast<float>(source[i * 2 + 1]) * c;
        }

        const float invGain = (gain != 0.0f) ? 1.0f / gain : 0.0f;
        *sink++ = static_cast<int16_t>(leftResult  * invGain);
        *sink++ = static_cast<int16_t>(rightResult * invGain);

        outputTime += outputTimeStep;
        --sinkFrames;
    }
}

float SincResampler::sinc(float x) {
    if (__builtin_expect(std::abs(x) < 1.0e-6f, 0)) return 1.0f;
    const float px = x * PI_F;
    return sinf(px) / px;
}

} //namespace libretrodroid
