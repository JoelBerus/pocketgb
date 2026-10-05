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

.PHONY: all lib test asan fuzz oracle homebrew check-roms check-header check-globals check-symbols clean

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

test: check-roms $(BUILD)/gbatest homebrew
	python3 tests/run_suite.py --bin $(BUILD)/gbatest --roms $(ROMS) --suite tests/suite.txt --hito $(HITO) --sst-limit $(SST_LIMIT) --hb $(BUILD)/hb

asan: check-roms
	$(MAKE) BUILD=$(BUILD)/asan EXTRA="$(SAN)" CFLAGS="-O1 -g" SST_LIMIT=$(if $(filter 0,$(SST_LIMIT)),5000,$(SST_LIMIT)) test

# libFuzzer (clang con -fsanitize=fuzzer; en macOS: brew install llvm).
fuzz:
	@mkdir -p $(BUILD) fuzz/corpus/fuzz_load_rom
	@for f in fuzz/fuzz_*.c; do \
	  n=$$(basename $$f .c); mkdir -p fuzz/corpus/$$n; \
	  $(CC) -std=c11 -g -O2 -fsanitize=fuzzer,address,undefined -fno-sanitize-recover=all $(INC) -I../core/include $$f $(SRCS) -o $(BUILD)/$$n -lm || exit 1; \
	  cp -n tests/roms/gba-tests/*/*.gba fuzz/corpus/$$n/ 2>/dev/null || true; \
	  $(BUILD)/$$n -max_total_time=$(FUZZ_SECONDS) -max_len=262144 -rss_limit_mb=4096 fuzz/corpus/$$n || exit 1; \
	done

# Oráculo de la PPU (dev-only, nunca en la app): mGBA compilado sin frontends.
MGBA_TAG ?= 0.10.5
MGBA := ../tools/oracle/mgba
oracle: $(BUILD)/gbatest
	@[ -d $(MGBA) ] || git clone -q --depth 1 --branch $(MGBA_TAG) https://github.com/mgba-emu/mgba.git $(MGBA)
	cmake -S $(MGBA) -B $(MGBA)/build -G Ninja -DCMAKE_BUILD_TYPE=Release -DBUILD_QT=OFF -DBUILD_SDL=OFF \
	  -DBUILD_GL=OFF -DBUILD_GLES2=OFF -DBUILD_GLES3=OFF -DUSE_FFMPEG=OFF -DUSE_SQLITE3=OFF -DUSE_ELF=OFF \
	  -DUSE_LUA=OFF -DUSE_EDITLINE=OFF -DUSE_MINIZIP=OFF -DUSE_LIBZIP=OFF -DUSE_DISCORD_RPC=OFF -DUSE_EPOXY=OFF \
	  -DM_CORE_GB=OFF -DBUILD_SHARED=OFF -DBUILD_STATIC=ON -DUSE_LZMA=OFF -DBUILD_LTO=OFF -DCMAKE_C_FLAGS=-DCOLOR_16_BIT > $(BUILD)/mgba-cmake.log
	ninja -C $(MGBA)/build > $(BUILD)/mgba-build.log
	$(CC) -O2 -DCOLOR_16_BIT -I$(MGBA)/include -I$(MGBA)/build/include ../tools/oracle-gba/shot.c $(MGBA)/build/libmgba.a \
	  -lpng -lz -lm -lpthread -o $(BUILD)/mgba-shot
	@echo "Oráculo listo: $(BUILD)/mgba-shot (compara con tools/gba-compare.py)"

# ROMs homebrew propias (MIT) para la PPU: clang --target=armv4t + ld.lld.
HB := tests/homebrew
HB_CFLAGS := --target=armv4t-none-eabi -mthumb -O2 -ffreestanding -nostdlib -fno-builtin -Wall -Werror -Wno-unused-command-line-argument
SCENES := 0 1 2 3 4 5 6 7 8 9 10 11 12 13
HB_TESTS := eeprom eeprom8k rtc
homebrew: $(foreach s,$(SCENES),$(BUILD)/hb/ppu_scene_$(s).gba) $(foreach t,$(HB_TESTS),$(BUILD)/hb/$(t).gba)

$(BUILD)/hb/eeprom8k.gba: $(HB)/eeprom.c $(HB)/gba.h $(HB)/link.ld $(BUILD)/hb/rt.o
	clang $(HB_CFLAGS) -DEE8K -c $< -o $(BUILD)/hb/eeprom8k.o
	ld.lld -T $(HB)/link.ld $(BUILD)/hb/crt0.o $(BUILD)/hb/eeprom8k.o $(BUILD)/hb/support.o -o $(BUILD)/hb/eeprom8k.elf
	llvm-objcopy -O binary $(BUILD)/hb/eeprom8k.elf $@

$(BUILD)/hb/%.gba: $(HB)/%.c $(HB)/gba.h $(HB)/link.ld $(BUILD)/hb/rt.o
	clang $(HB_CFLAGS) -c $< -o $(BUILD)/hb/$*.o
	ld.lld -T $(HB)/link.ld $(BUILD)/hb/crt0.o $(BUILD)/hb/$*.o $(BUILD)/hb/support.o -o $(BUILD)/hb/$*.elf
	llvm-objcopy -O binary $(BUILD)/hb/$*.elf $@

$(BUILD)/hb/rt.o: $(HB)/crt0.s $(HB)/support.c
	@mkdir -p $(BUILD)/hb
	clang $(HB_CFLAGS) -c $(HB)/crt0.s -o $(BUILD)/hb/crt0.o
	clang $(HB_CFLAGS) -c $(HB)/support.c -o $(BUILD)/hb/support.o
	@touch $@

$(BUILD)/hb/ppu_scene_%.gba: $(HB)/ppu_scenes.c $(HB)/gba.h $(HB)/link.ld $(BUILD)/hb/rt.o
	clang $(HB_CFLAGS) -DSCENE=$* -c $< -o $(BUILD)/hb/ppu_scene_$*.o
	ld.lld -T $(HB)/link.ld $(BUILD)/hb/crt0.o $(BUILD)/hb/ppu_scene_$*.o $(BUILD)/hb/support.o -o $(BUILD)/hb/ppu_scene_$*.elf
	llvm-objcopy -O binary $(BUILD)/hb/ppu_scene_$*.elf $@

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
