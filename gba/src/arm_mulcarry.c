/*
 * arm_mulcarry.c — bandera C tras MUL/MLA/UMULL/UMLAL/SMULL/SMLAL del ARM7TDMI.
 *
 * Versión modificada (portada a C11 sin estado global y reducida al cálculo
 * del acarreo) de https://github.com/zaydlang/multiplication-algorithm
 * (impl.h, commit de 2024), explicada en "Solving the Mystery of ARM7TDMI
 * Multiply Carry Flag" (zayd, 2024). Aviso original, que se conserva:
 *
 *   Copyright (c) 2024 zaydlang
 *
 *   This software is provided 'as-is', without any express or implied
 *   warranty. In no event will the authors be held liable for any damages
 *   arising from the use of this software.
 *
 *   Permission is granted to anyone to use this software for any purpose,
 *   including commercial applications, and to alter it and redistribute it
 *   freely, subject to the following restrictions:
 *
 *   1. The origin of this software must not be misrepresented; you must not
 *      claim that you wrote the original software. If you use this software
 *      in a product, an acknowledgment in the product documentation would
 *      be appreciated but is not required.
 *   2. Altered source versions must be plainly marked as such, and must not
 *      be misrepresented as being the original software.
 *   3. This notice may not be removed or altered from any source
 *      distribution.
 *
 * Cambios respecto del original (PocketGB): el registro de desplazamiento del
 * acumulador pasa a ser local; se devuelve solo la bandera C (el producto lo
 * calcula arm7.c directamente); nombres con prefijo gba_.
 */
#include "internal.h"

typedef struct { uint64_t lo, hi; } mc_u128;
typedef struct { uint64_t output, carry; } mc_csa;

static inline bool mc_bit(uint64_t x, int n) { return (x >> n) & 1u; }

static inline uint64_t mc_mask(int lo, int hi) { return ((1ull << (hi - lo)) - 1u) << lo; }

static inline uint64_t mc_sign_extend(uint64_t v, int from, int to)
{
    if (mc_bit(v, from - 1)) v |= mc_mask(from, to);
    return v;
}

static inline uint64_t mc_asr(uint64_t v, int shift, int size)
{
    int64_t s = (int64_t)mc_sign_extend(v, size, 64);
    s >>= shift;
    return (uint64_t)s & mc_mask(0, size);
}

static inline mc_u128 mc_ror(mc_u128 x, int s)
{
    mc_u128 r = {(x.lo >> s) | (x.hi << (64 - s)), (x.hi >> s) | (x.lo << (64 - s))};
    return r;
}

static void mc_booth(uint64_t in, unsigned chunk, uint64_t *out, uint64_t *carry)
{
    switch (chunk & 7u) {
    case 1: case 2: *out = in; *carry = 0; break;
    case 3: *out = 2 * in; *carry = 0; break;
    case 4: *out = ~(2 * in); *carry = 1; break;
    case 5: case 6: *out = ~in; *carry = 1; break;
    default: *out = 0; *carry = 0; break;
    }
    *out &= 0x3FFFFFFFFull;
}

static mc_csa mc_csa_array(mc_csa in, uint64_t multiplicand, uint64_t multiplier, uint64_t *acc_sr)
{
    mc_csa cur = in, fin = {0, 0};
    for (int i = 0; i < 4; i++) {
        uint64_t add, add_carry;
        mc_booth(multiplicand, (unsigned)(multiplier >> (2 * i)), &add, &add_carry);
        cur.output &= 0x1FFFFFFFFull;
        cur.carry &= 0x1FFFFFFFFull;
        uint64_t b = add & 0x1FFFFFFFFull;
        mc_csa r = {cur.output ^ b ^ cur.carry,
                    (cur.output & b) | (b & cur.carry) | (cur.carry & cur.output)};
        r.carry = (r.carry << 1) | add_carry;
        fin.output |= (r.output & 3u) << (2 * i);
        fin.carry |= (r.carry & 3u) << (2 * i);
        r.output >>= 2;
        r.carry >>= 2;
        uint64_t magic = (uint64_t)mc_bit(*acc_sr, 0) + !mc_bit(cur.carry, 32) + !mc_bit(add, 33);
        r.output |= magic << 31;
        r.carry |= (uint64_t)!mc_bit(*acc_sr, 1) << 32;
        *acc_sr >>= 2;
        cur = r;
    }
    fin.output |= cur.output << 8;
    fin.carry |= cur.carry << 8;
    return fin;
}

bool gba_arm_mul_carry(int flavor, uint32_t rm, uint32_t rs, uint64_t acc)
{
    bool is_long = flavor != GBA_MUL_SHORT;
    bool is_signed = flavor != GBA_MUL_LONG_UNSIGNED;
    uint64_t multiplier = is_signed ? mc_sign_extend(rs, 32, 34) : (rs & 0x1FFFFFFFFull);
    uint64_t multiplicand = is_signed ? mc_sign_extend(rm, 32, 34) : (rm & 0x1FFFFFFFFull);
    mc_csa csa = {acc, (multiplier & 1u) ? ~multiplicand : 0};
    uint64_t acc_sr = acc >> 34;
    mc_u128 psum = {csa.output & 1u, 0}, pcarry = {csa.carry & 1u, 0};
    csa.output >>= 1;
    csa.carry >>= 1;
    psum = mc_ror(psum, 1);
    pcarry = mc_ror(pcarry, 1);
    int iters = 0;
    do {
        csa = mc_csa_array(csa, multiplicand, multiplier, &acc_sr);
        psum.lo |= csa.output & 0xFFu;
        pcarry.lo |= csa.carry & 0xFFu;
        csa.output >>= 8;
        csa.carry >>= 8;
        psum = mc_ror(psum, 8);
        pcarry = mc_ror(pcarry, 8);
        multiplier = mc_asr(multiplier, 8, 33);
        iters++;
    } while (!(multiplier == 0 || (is_signed && multiplier == 0x1FFFFFFFFull)));
    pcarry.lo |= csa.carry;
    static const int correction[5] = {0, 23, 15, 7, 31};
    pcarry = mc_ror(pcarry, correction[iters]);
    if (!is_long && iters == 4) return (pcarry.hi >> 31) & 1u;
    return (pcarry.hi >> 63) & 1u;
}
