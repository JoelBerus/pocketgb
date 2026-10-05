# PocketGB · núcleo GBA — ver docs/10-gba-spec.md y docs/06-testing.md §GBA
# Uso: make test HITO=G1 | make asan HITO=G1 | make sst | make fuzz FUZZ_SECONDS=600
ifeq ($(origin CC),default)
CC := clang
endif
HITO    ?= G1
FUZZ_SECONDS ?= 60
BUILD   := build
CSTD    := -std=c11 -Wall -Wextra -Werror -pedantic -fno-common
CFLAGS  ?= -O2 -g
INC     := -Iinclude -Isrc
# SHA-256 propio compartido con el núcleo GB (sin copiarlo).
SRCS    := $(wildcard src/*.c) ../core/src/sha256.c
OBJS    := $(patsubst %.c,$(BUILD)/obj/%.o,$(notdir $(SRCS)))
TEST_SRCS := $(wildcard tests/*.c)
ROMS    := tests/roms
SAN     := -fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=all
SST_LIMIT ?= 0

.PHONY: all lib test asan check-roms check-header check-globals check-symbols clean

all: lib

lib: $(BUILD)/libpocketgba.a

$(BUILD)/obj/%.o: src/%.c include/pocketgba.h $(wildcard src/*.h)
	@mkdir -p $(BUILD)/obj
	$(CC) $(CSTD) $(CFLAGS) $(INC) -c $< -o $@

$(BUILD)/obj/sha256.o: ../core/src/sha256.c
	@mkdir -p $(BUILD)/obj
	$(CC) $(CSTD) $(CFLAGS) -I../core/include -I../core/src -c $< -o $@

$(BUILD)/libpocketgba.a: $(OBJS)
	ar rcs $@ $^

$(BUILD)/gbatest: $(SRCS) $(TEST_SRCS) include/pocketgba.h $(wildcard src/*.h)
	@mkdir -p $(BUILD)
	$(CC) $(CSTD) $(CFLAGS) $(EXTRA) -DGBA_TEST_HOOKS $(INC) -I../core/include $(SRCS) $(TEST_SRCS) -o $@ -lm

check-roms:
	@[ -d $(ROMS) ] || { echo "Faltan ROMs de prueba GBA: ejecuta tools/fetch-gba-test-roms.sh"; exit 1; }

test: check-roms $(BUILD)/gbatest
	python3 tests/run_suite.py --bin $(BUILD)/gbatest --roms $(ROMS) --suite tests/suite.txt --hito $(HITO) --sst-limit $(SST_LIMIT)

asan: check-roms
	$(MAKE) BUILD=$(BUILD)/asan EXTRA="$(SAN)" CFLAGS="-O1 -g" SST_LIMIT=$(if $(filter 0,$(SST_LIMIT)),5000,$(SST_LIMIT)) test

check-header:
	@mkdir -p $(BUILD)
	echo '#include "pocketgba.h"' | $(CC) $(CSTD) -Iinclude -x c -c - -o $(BUILD)/check_header.o
	@echo "pocketgba.h compila aislado: OK"

check-globals: lib
	@out=$$(nm $(BUILD)/libpocketgba.a | grep -E ' [bBdDcCsS] ' || true); \
	syms=$$(nm $(BUILD)/libpocketgba.a | wc -l); \
	if [ "$$syms" -eq 0 ]; then echo "nm no produjo salida"; exit 1; fi; \
	if [ -n "$$out" ]; then echo "Estado global mutable encontrado:"; echo "$$out"; exit 1; else echo "Sin estado global mutable: OK"; fi

# Los dos núcleos se enlazan en el mismo binario de la app: ningún símbolo
# global puede repetirse (sha256 lo aporta solo el núcleo GB).
check-symbols: lib
	$(MAKE) -C ../core lib
	@nm -g --defined-only ../core/build/libpocketgb.a | awk 'NF==3{print $$3}' | sort -u > $(BUILD)/gb.syms
	@nm -g --defined-only $(BUILD)/libpocketgba.a | grep -v ' sha256$$' | awk 'NF==3{print $$3}' | sort -u > $(BUILD)/gba.syms
	@dup=$$(comm -12 $(BUILD)/gb.syms $(BUILD)/gba.syms); \
	if [ -n "$$dup" ]; then echo "Símbolos repetidos entre núcleos:"; echo "$$dup"; exit 1; fi; \
	bad=$$(grep -vE '^_?(gba_|arm_)' $(BUILD)/gba.syms || true); \
	if [ -n "$$bad" ]; then echo "Símbolos GBA sin prefijo gba_/arm_:"; echo "$$bad"; exit 1; fi; \
	echo "Símbolos de los dos núcleos sin colisiones: OK"

clean:
	rm -rf $(BUILD)
