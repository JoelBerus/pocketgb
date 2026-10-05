/* Mapa de las ROMs homebrew de prueba (código propio, MIT). */
ENTRY(_start)
MEMORY {
    rom   (rx)  : ORIGIN = 0x08000000, LENGTH = 32M
    iwram (rwx) : ORIGIN = 0x03000000, LENGTH = 31K
}
SECTIONS {
    .text : { KEEP(*(.text.start)) *(.text .text.*) *(.rodata .rodata.*) } > rom
    .iwram : { __data_start = .; *(.iwram .iwram.*) *(.data .data.*) . = ALIGN(4); __data_end = .; } > iwram AT > rom
    __data_lma = LOADADDR(.iwram);
    .bss (NOLOAD) : { __bss_start = .; *(.bss .bss.* COMMON) . = ALIGN(4); __bss_end = .; } > iwram
    /DISCARD/ : { *(.ARM.exidx*) *(.ARM.attributes) *(.comment) }
}
