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
