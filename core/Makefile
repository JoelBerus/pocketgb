# PocketGB core — ver docs/06-testing.md
# Uso: make test HITO=M1 | make asan HITO=M1 | make fuzz FUZZ_SECONDS=600 | make oracle
ifeq ($(origin CC),default)
CC := clang
endif
HITO    ?= M1
FUZZ_SECONDS ?= 60
BUILD   := build
CSTD    := -std=c11 -Wall -Wextra -Werror -pedantic
CFLAGS  ?= -O2 -g
INC     := -Iinclude -Isrc
SRCS    := $(wildcard src/*.c)
OBJS    := $(SRCS:src/%.c=$(BUILD)/%.o)
TEST_SRCS := $(wildcard tests/*.c)
ROMS    := tests/roms
SAN     := -fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=all

.PHONY: all lib test asan fuzz oracle check-header check-globals clean

all: lib

lib: $(BUILD)/libpocketgb.a

$(BUILD)/%.o: src/%.c include/pocketgb.h $(wildcard src/*.h src/*.inc)
	@mkdir -p $(BUILD)
	$(CC) $(CSTD) $(CFLAGS) $(INC) -c $< -o $@

$(BUILD)/libpocketgb.a: $(OBJS)
	@mkdir -p $(BUILD)
	@if [ -z "$(OBJS)" ]; then echo "core/src vacío: aún no hay implementación (ver docs/hitos/M1-cpu.md)"; exit 1; fi
	ar rcs $@ $^

$(BUILD)/gbtest: $(SRCS) $(TEST_SRCS) include/pocketgb.h $(wildcard src/*.h src/*.inc tests/*.h)
	@mkdir -p $(BUILD)
	$(CC) $(CSTD) $(CFLAGS) $(EXTRA) -DGB_TEST_HOOKS $(INC) $(SRCS) $(TEST_SRCS) -o $@

check-roms:
	@[ -d $(ROMS) ] || { echo "Faltan ROMs de prueba: ejecuta tools/fetch-test-roms.sh"; exit 1; }

test: check-roms $(BUILD)/gbtest
	python3 tests/run_suite.py --bin $(BUILD)/gbtest --roms $(ROMS) --suite tests/suite.txt --hito $(HITO)

asan: check-roms
	$(MAKE) BUILD=$(BUILD)/asan EXTRA="$(SAN)" CFLAGS="-O1 -g" test

fuzz: $(BUILD)/gbtest
	@mkdir -p $(BUILD) fuzz/corpus/fuzz_load_rom fuzz/corpus/fuzz_state_load
	@printf 'int LLVMFuzzerTestOneInput(const unsigned char*d,unsigned long n){(void)d;(void)n;return 0;}' > $(BUILD)/probe.c
	@$(CC) -fsanitize=fuzzer $(BUILD)/probe.c -o $(BUILD)/probe 2>/dev/null || { \
	  echo "Este clang no trae libFuzzer (el de Apple no lo incluye)."; \
	  echo "macOS: brew install llvm && make fuzz CC=\$$(brew --prefix llvm)/bin/clang"; exit 1; }
	$(BUILD)/gbtest --fuzz-seeds fuzz/corpus
	@for f in fuzz/fuzz_*.c; do \
	  n=$$(basename $$f .c); mkdir -p fuzz/corpus/$$n; \
	  $(CC) -std=c11 -g -O2 -fsanitize=fuzzer,address,undefined -fno-sanitize-recover=all $(INC) $$f $(SRCS) -o $(BUILD)/$$n || exit 1; \
	  $(BUILD)/$$n -max_total_time=$(FUZZ_SECONDS) -max_len=1048576 fuzz/corpus/$$n || exit 1; \
	done

# Oráculo opcional (dev-only, nunca se enlaza en la app). Requiere git, make y rgbds.
SAMEBOY_TAG ?= v1.0.3
oracle:
	@[ -d ../tools/oracle/SameBoy ] || git clone --depth 1 --branch $(SAMEBOY_TAG) https://github.com/LIJI32/SameBoy.git ../tools/oracle/SameBoy
	$(MAKE) -C ../tools/oracle/SameBoy lib
	@echo "Siguiente: compilar tools/oracle/diff.c contra ../tools/oracle/SameBoy/build/lib (se implementa en M5)"

check-header:
	@mkdir -p $(BUILD)
	echo '#include "pocketgb.h"' | $(CC) $(CSTD) -Iinclude -x c -c - -o $(BUILD)/check_header.o
	@echo "pocketgb.h compila aislado: OK"

check-globals: lib
	@out=$$(nm $(BUILD)/libpocketgb.a | grep -E ' [bBdD] ' || true); \
	if [ -n "$$out" ]; then echo "Estado global mutable encontrado:"; echo "$$out"; exit 1; else echo "Sin estado global mutable: OK"; fi

clean:
	rm -rf $(BUILD)
