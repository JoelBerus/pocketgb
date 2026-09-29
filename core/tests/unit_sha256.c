/* unit_sha256.c — vectores de FIPS 180-4 (docs/06-testing.md §Unit tests). */
#include <stdlib.h>
#include <string.h>

#include "unit.h"

static int hex_eq(const uint8_t d[32], const char *hex)
{
    char buf[65];
    for (int i = 0; i < 32; i++)
        snprintf(buf + 2 * i, 3, "%02x", d[i]);
    return strcmp(buf, hex) == 0;
}

void unit_sha256(struct ut *t)
{
    uint8_t d[32];

    sha256((const uint8_t *)"", 0, d);
    CHECK(t, hex_eq(d, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));

    sha256((const uint8_t *)"abc", 3, d);
    CHECK(t, hex_eq(d, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));

    /* 56 bytes: el relleno ocupa un segundo bloque */
    const char *m448 = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
    sha256((const uint8_t *)m448, strlen(m448), d);
    CHECK(t, hex_eq(d, "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"));

    size_t n = 1000000;
    uint8_t *a = malloc(n);
    CHECK(t, a != NULL);
    if (a) {
        memset(a, 'a', n);
        sha256(a, n, d);
        CHECK(t, hex_eq(d, "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"));
        free(a);
    }
}
