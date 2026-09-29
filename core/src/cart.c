/*
 * cart.c — cabecera del cartucho, ROM-only y MBC1, RAM externa (SRAM).
 *
 * El ROM es entrada no confiable (docs/03-core-spec.md §Cabecera, §Seguridad):
 * los tamaños se validan contra la cabecera y contra el archivo, y todo índice
 * de banco se reduce con % contra el número real de bancos.
 * MBC3 (RTC) y MBC5 llegan en M3.
 */
#include <stdlib.h>
#include <string.h>

#include "internal.h"

enum { ROM_BANK = 0x4000, RAM_BANK = 0x2000 };

static const uint32_t ram_sizes[6] = { 0, 0, 8u * 1024, 32u * 1024, 128u * 1024, 64u * 1024 };

static bool is_mfr_char(uint8_t ch)
{
    return (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9');
}

static void parse_title(const uint8_t *rom, char out[17])
{
    size_t n = 16;
    uint8_t flag = rom[0x143];
    if (flag == 0x80 || flag == 0xC0) {
        n = 15;
        if (is_mfr_char(rom[0x13F]) && is_mfr_char(rom[0x140]) &&
            is_mfr_char(rom[0x141]) && is_mfr_char(rom[0x142]))
            n = 11;
    }
    size_t i = 0;
    for (; i < n && rom[0x134 + i] != 0x00; i++) {
        uint8_t ch = rom[0x134 + i];
        out[i] = (ch >= 0x20 && ch <= 0x7E) ? (char)ch : '?';
    }
    out[i] = '\0';
}

void cart_free(gb *g)
{
    free(g->cart.rom);
    free(g->cart.ram);
    memset(&g->cart, 0, sizeof g->cart);
}

gb_result cart_load(gb *g, const uint8_t *data, size_t len)
{
    if (len < 0x150)
        return GB_ERR_ROM_TOO_SMALL;
    if (len > GB_ROM_MAX_BYTES)
        return GB_ERR_ROM_TOO_LARGE;

    uint8_t rom_code = data[0x148];
    if (rom_code > 8)
        return GB_ERR_BAD_ROM_SIZE_CODE;   /* incluye 0x52–0x54, fuera de alcance */
    uint32_t rom_size = 32u * 1024u << rom_code;
    if (rom_size > len)
        return GB_ERR_ROM_TRUNCATED;

    uint8_t ram_code = data[0x149];
    if (ram_code >= sizeof ram_sizes / sizeof ram_sizes[0])
        return GB_ERR_BAD_RAM_SIZE_CODE;

    struct gb_cart c;
    memset(&c, 0, sizeof c);
    uint8_t type = data[0x147];
    switch (type) {
    case 0x00: c.mbc = MBC_NONE; break;
    case 0x01: c.mbc = MBC_MBC1; break;
    case 0x02: c.mbc = MBC_MBC1; c.has_ram = true; break;
    case 0x03: c.mbc = MBC_MBC1; c.has_ram = true; c.has_battery = true; break;
    default:   return GB_ERR_UNSUPPORTED_MBC;
    }

    c.rom_size = rom_size;
    c.rom_banks = rom_size / ROM_BANK;
    c.ram_size = c.has_ram ? ram_sizes[ram_code] : 0;
    c.ram_banks = c.ram_size / RAM_BANK;
    if (c.ram_size == 0)
        c.has_ram = false;

    c.rom = malloc(rom_size);
    if (!c.rom)
        return GB_ERR_OUT_OF_MEMORY;
    memcpy(c.rom, data, rom_size);   /* el excedente del archivo se ignora */
    if (c.ram_size) {
        c.ram = malloc(c.ram_size);
        if (!c.ram) {
            free(c.rom);
            return GB_ERR_OUT_OF_MEMORY;
        }
        memset(c.ram, 0x00, c.ram_size);
    }
    g->cart = c;

    /* Información para el frontend */
    gb_rom_info *info = &g->info;
    memset(info, 0, sizeof *info);
    parse_title(c.rom, info->title);
    info->cgb_flag = c.rom[0x143];
    info->cart_type = type;
    info->rom_bytes = rom_size;
    info->sram_bytes = c.has_battery ? c.ram_size : 0;
    info->has_battery = c.has_battery;
    info->has_rtc = false;

    uint8_t x = 0;
    for (uint32_t i = 0x134; i <= 0x14C; i++)
        x = (uint8_t)(x - c.rom[i] - 1);
    info->header_checksum_ok = x == c.rom[0x14D];

    uint16_t sum = 0;
    for (uint32_t i = 0; i < rom_size; i++)
        if (i != 0x14E && i != 0x14F)
            sum = (uint16_t)(sum + c.rom[i]);
    info->global_checksum_ok = sum == (uint16_t)(c.rom[0x14E] << 8 | c.rom[0x14F]);

    sha256(c.rom, rom_size, info->fingerprint);
    return GB_OK;
}

void cart_reset(gb *g)
{
    struct gb_cart *c = &g->cart;
    c->ram_enabled = false;
    c->ram_written = false;
    c->sram_dirty = false;
    c->bank_lo = 0;
    c->bank_hi = 0;
    c->mode = 0;
}

uint8_t cart_rom_read(const gb *g, uint16_t addr)
{
    const struct gb_cart *c = &g->cart;
    uint32_t bank;
    if (c->mbc == MBC_NONE) {
        bank = addr >> 14;   /* rom_size ≥ 32 KiB: 0x0000–0x7FFF siempre está */
    } else if (addr < 0x4000) {
        bank = c->mode ? (uint32_t)c->bank_hi << 5 : 0;
    } else {
        bank = (uint32_t)c->bank_hi << 5 | (c->bank_lo ? c->bank_lo : 1);
    }
    bank %= c->rom_banks;
    return c->rom[bank * ROM_BANK + (addr & 0x3FFF)];
}

void cart_rom_write(gb *g, uint16_t addr, uint8_t v)
{
    struct gb_cart *c = &g->cart;
    if (c->mbc != MBC_MBC1)
        return;
    switch (addr >> 13) {
    case 0: {   /* 0000–1FFF: habilitar RAM */
        bool enable = (v & 0x0F) == 0x0A;
        if (c->ram_enabled && !enable && c->ram_written && c->has_battery) {
            c->sram_dirty = true;
            c->ram_written = false;
        }
        c->ram_enabled = enable;
        break;
    }
    case 1: c->bank_lo = v & 0x1F; break;   /* 2000–3FFF */
    case 2: c->bank_hi = v & 0x03; break;   /* 4000–5FFF */
    case 3: c->mode = v & 0x01; break;      /* 6000–7FFF */
    default: break;
    }
}

/* Offset en c->ram, o -1 si la RAM no es accesible. */
static long ram_offset(const struct gb_cart *c, uint16_t addr)
{
    if (!c->has_ram || !c->ram_enabled)
        return -1;
    uint32_t bank = (c->mbc == MBC_MBC1 && c->mode) ? c->bank_hi : 0;
    bank %= c->ram_banks;
    return (long)(bank * RAM_BANK + (addr & 0x1FFF));
}

uint8_t cart_ram_read(const gb *g, uint16_t addr)
{
    long off = ram_offset(&g->cart, addr);
    return off < 0 ? 0xFF : g->cart.ram[off];
}

void cart_ram_write(gb *g, uint16_t addr, uint8_t v)
{
    long off = ram_offset(&g->cart, addr);
    if (off < 0)
        return;
    g->cart.ram[off] = v;
    g->cart.ram_written = true;
}

/* ---- API pública de SRAM ---- */

size_t gb_sram_save_size(const gb *g)
{
    if (!g || !g->rom_loaded)
        return 0;
    return g->info.sram_bytes;
}

gb_result gb_sram_load(gb *g, const uint8_t *data, size_t len)
{
    if (!g || (!data && len))
        return GB_ERR_NULL_ARG;
    if (!g->rom_loaded)
        return GB_ERR_NO_ROM;
    if (len != gb_sram_save_size(g))
        return GB_ERR_SRAM_SIZE;
    if (len)
        memcpy(g->cart.ram, data, len);
    return GB_OK;
}

gb_result gb_sram_save(const gb *g, uint8_t *out, size_t cap)
{
    if (!g || (!out && cap))
        return GB_ERR_NULL_ARG;
    if (!g->rom_loaded)
        return GB_ERR_NO_ROM;
    size_t n = gb_sram_save_size(g);
    if (cap < n)
        return GB_ERR_BUFFER_TOO_SMALL;
    if (n)
        memcpy(out, g->cart.ram, n);
    return GB_OK;
}

bool gb_sram_dirty(const gb *g)
{
    return g && g->rom_loaded && g->cart.sram_dirty;
}

void gb_sram_clear_dirty(gb *g)
{
    if (g)
        g->cart.sram_dirty = false;
}
