# M0 · Evidencia de criterios (ejecutada por el desarrollador)

Fecha: 2026-09-28 21:56 · máquina: macOS 26.5.1, Apple clang version 21.0.0 (clang-2100.1.1.101)

## 1. Sin ROMs ni saves en el índice
```
$ git ls-files | grep -Ei '\.(gb|gbc|sav)$' ; echo "exit=$?"
exit=1 (1 = sin coincidencias)
```

## 2. El hook rechaza un archivo con cabecera de cartucho (con otra extensión)
```
$ git commit -m t   # fake.txt: 400 bytes con CE ED 66 66 en 0x104
pre-commit: bloqueado 'fake.txt' (contiene cabecera de cartucho Game Boy)
exit=1
$ git commit -m t   # "con espacio.gb"
pre-commit: bloqueado 'con espacio.gb' (ROM/partida/archivo comprimido)
exit=1
```

## 3. El header compila aislado
```
$ make -C core check-header
pocketgb.h compila aislado: OK
exit=0
```

## 4. Sintaxis de los scripts
```
tools/cloud-setup.sh: OK
tools/fetch-test-roms.sh: OK
.githooks/pre-commit: OK
```

## 5. Vuelta 3: el hook con un nombre que contiene salto de línea (M0-12/M0-15)
```
$ cp t.bin $'rom\nnueva.txt'   # 400 bytes con CE ED 66 66 en 0x104
$ git add -- $'rom\nnueva.txt'
$ git commit -m t
pre-commit: bloqueado $'rom\nnueva.txt' (contiene cabecera de cartucho Game Boy)
exit=1
$ git log --oneline -1   # no se creó commit
2ba0911 M0: correcciones de la auditoría Codex (vuelta 2)
$ git rm --cached -- $'rom\nnueva.txt' && rm -- $'rom\nnueva.txt'   # limpieza
```

## 6. Vuelta 2: valores esperados por boot_regs-cgb (M0-04)
```
$ curl -sL https://raw.githubusercontent.com/Gekkio/mooneye-test-suite/main/misc/boot_regs-cgb.s | grep assert_
  assert_a $11
  assert_f $80
  assert_b $00
  assert_c $00
  assert_d $00
  assert_e $08
  assert_h $00
  assert_l $7C
$ xxd -s 0x143 -l 1 core/tests/roms/mooneye-test-suite/misc/boot_regs-cgb.gb   # 0x00 = ROM DMG → compatibilidad
00000143: 00                                       .
```
