@ crt0.s — arranque de las ROMs homebrew de prueba de PocketGB (código propio, MIT).
@ Sin logo de Nintendo: los emuladores que arrancan sin BIOS no lo comprueban.
    .section .text.start, "ax"
    .arm
    .global _start
_start:
    b       reset
    .org    0xA0
    .ascii  "POCKETGBTEST"      @ título
    .ascii  "PGBT"              @ código
    .ascii  "01"                @ fabricante
    .byte   0x96                @ byte fijo
    .org    0xC0
reset:
    mov     r0, #0x12           @ IRQ
    msr     cpsr_c, r0
    ldr     sp, =0x03007FA0
    mov     r0, #0x1F           @ sistema
    msr     cpsr_c, r0
    ldr     sp, =0x03007F00
    ldr     r0, =__data_lma
    ldr     r1, =__data_start
    ldr     r2, =__data_end
1:  cmp     r1, r2
    ldrlo   r3, [r0], #4
    strlo   r3, [r1], #4
    blo     1b
    ldr     r1, =__bss_start
    ldr     r2, =__bss_end
    mov     r3, #0
2:  cmp     r1, r2
    strlo   r3, [r1], #4
    blo     2b
    ldr     r0, =main
    mov     lr, pc
    bx      r0
3:  b       3b
