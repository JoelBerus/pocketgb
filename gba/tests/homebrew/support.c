/* support.c — memcpy/memset para código sin biblioteca estándar (MIT, propio). */
#include <stddef.h>
void *memcpy(void *d, const void *s, size_t n) { char *a = d; const char *b = s; while (n--) *a++ = *b++; return d; }
void *memset(void *d, int c, size_t n) { char *a = d; while (n--) *a++ = (char)c; return d; }
void *memmove(void *d, const void *s, size_t n) { char *a = d; const char *b = s; if (a < b) while (n--) *a++ = *b++; else while (n--) a[n] = b[n]; return d; }

/* División del ABI de ARM (el ARM7TDMI no tiene instrucción de dividir). */
#include <stdint.h>
static uint64_t udivmod(uint32_t n, uint32_t d)
{
    uint32_t q = 0, r = 0;
    if (d == 0) return 0;
    for (int i = 31; i >= 0; i--) {
        r = (r << 1) | ((n >> i) & 1u);
        if (r >= d) { r -= d; q |= 1u << i; }
    }
    return q | ((uint64_t)r << 32);
}
uint32_t __aeabi_uidiv(uint32_t n, uint32_t d) { return (uint32_t)udivmod(n, d); }
uint64_t __aeabi_uidivmod(uint32_t n, uint32_t d) { return udivmod(n, d); }
int32_t __aeabi_idiv(int32_t n, int32_t d)
{
    uint32_t un = n < 0 ? 0u - (uint32_t)n : (uint32_t)n, ud = d < 0 ? 0u - (uint32_t)d : (uint32_t)d;
    uint32_t q = (uint32_t)udivmod(un, ud);
    return (n < 0) != (d < 0) ? (int32_t)(0u - q) : (int32_t)q;
}
uint64_t __aeabi_idivmod(int32_t n, int32_t d)
{
    uint32_t un = n < 0 ? 0u - (uint32_t)n : (uint32_t)n, ud = d < 0 ? 0u - (uint32_t)d : (uint32_t)d;
    uint64_t qr = udivmod(un, ud);
    uint32_t q = (uint32_t)qr, r = (uint32_t)(qr >> 32);
    if ((n < 0) != (d < 0)) q = 0u - q;
    if (n < 0) r = 0u - r;
    return q | ((uint64_t)r << 32);
}
