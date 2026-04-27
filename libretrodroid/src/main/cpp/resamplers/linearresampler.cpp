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

#include <cmath>
#include <algorithm>
#include <arm_neon.h>
#include "linearresampler.h"

namespace libretrodroid {

// NEON-accelerated stereo linear interpolation resampler.
// Processes 4 output frames per NEON iteration using float32x4 lanes.
void LinearResampler::resample(const int16_t *__restrict__ source,
                               int32_t inputFrames,
                               int16_t *__restrict__ sink,
                               int32_t sinkFrames) {
    if (__builtin_expect(sinkFrames <= 0 || inputFrames <= 0, 0)) return;

    const float step   = 1.0f / static_cast<float>(sinkFrames);
    const float inF    = static_cast<float>(inputFrames);
    const int32_t maxI = inputFrames - 1;

    // NEON bulk path: 4 output frames per loop
    int32_t n = sinkFrames;
    float   t = 0.0f;

    // Precompute 4-wide base times: t, t+step, t+2*step, t+3*step
    const float32x4_t stepV  = vdupq_n_f32(step);
    const float32x4_t inFV   = vdupq_n_f32(inF);
    const float32x4_t lane01 = {0.0f, step, 2.0f * step, 3.0f * step};
    const float32x4_t fourSt = vdupq_n_f32(4.0f * step);

    float32x4_t tV = vaddq_f32(vdupq_n_f32(t), lane01);

    while (n >= 4) {
        // pos = t * inputFrames  for each of 4 lanes
        float32x4_t posV  = vmulq_f32(tV, inFV);
        // floor via truncation
        int32x4_t   floorV = vcvtq_s32_f32(posV);
        float32x4_t floorF = vcvtq_f32_s32(floorV);
        float32x4_t fracV  = vsubq_f32(posV, floorF);
        float32x4_t invF   = vsubq_f32(vdupq_n_f32(1.0f), fracV);

        // Extract floor indices
        int32_t fi[4];
        vst1q_s32(fi, floorV);

        // Clamp ceil
        int32_t ci[4] = {
            fi[0] < maxI ? fi[0] + 1 : maxI,
            fi[1] < maxI ? fi[1] + 1 : maxI,
            fi[2] < maxI ? fi[2] + 1 : maxI,
            fi[3] < maxI ? fi[3] + 1 : maxI
        };

        // Extract frac scalars
        float frac[4], inv[4];
        vst1q_f32(frac, fracV);
        vst1q_f32(inv,  invF);

        // Interpolate and write 4 stereo pairs
        for (int k = 0; k < 4; k++) {
            const int16_t l0 = source[fi[k] * 2];
            const int16_t r0 = source[fi[k] * 2 + 1];
            const int16_t l1 = source[ci[k] * 2];
            const int16_t r1 = source[ci[k] * 2 + 1];
            *sink++ = static_cast<int16_t>(static_cast<float>(l0) * inv[k] +
                                           static_cast<float>(l1) * frac[k]);
            *sink++ = static_cast<int16_t>(static_cast<float>(r0) * inv[k] +
                                           static_cast<float>(r1) * frac[k]);
        }

        tV = vaddq_f32(tV, fourSt);
        t += 4.0f * step;
        n -= 4;
    }

    // Scalar tail for remaining frames
    while (n > 0) {
        const float   pos    = t * inF;
        const int32_t floor_i = static_cast<int32_t>(pos);
        const float   frac    = pos - static_cast<float>(floor_i);
        const int32_t ceil_i  = (floor_i < maxI) ? floor_i + 1 : maxI;
        const float   invFrac = 1.0f - frac;

        const int16_t l0 = source[floor_i * 2];
        const int16_t r0 = source[floor_i * 2 + 1];
        const int16_t l1 = source[ceil_i  * 2];
        const int16_t r1 = source[ceil_i  * 2 + 1];

        *sink++ = static_cast<int16_t>(static_cast<float>(l0) * invFrac +
                                       static_cast<float>(l1) * frac);
        *sink++ = static_cast<int16_t>(static_cast<float>(r0) * invFrac +
                                       static_cast<float>(r1) * frac);

        t += step;
        --n;
    }
}

} //namespace libretrodroid
