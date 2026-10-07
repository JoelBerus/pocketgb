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

.PHONY: all lib test asan fuzz fuzz-link fuzz-progress fuzz-progress-smoke fuzz-pgbm fuzz-pgbm-smoke oracle check-header check-globals clean

all: lib

lib: $(BUILD)/libpocketgb.a

$(BUILD)/%.o: src/%.c $(wildcard include/*.h) $(wildcard src/*.h src/*.inc)
	@mkdir -p $(BUILD)
	$(CC) $(CSTD) $(CFLAGS) $(INC) -c $< -o $@

$(BUILD)/libpocketgb.a: $(OBJS)
	@mkdir -p $(BUILD)
	@if [ -z "$(OBJS)" ]; then echo "core/src vacío: aún no hay implementación (ver docs/hitos/M1-cpu.md)"; exit 1; fi
	ar rcs $@ $^

$(BUILD)/gbtest: $(SRCS) $(TEST_SRCS) $(wildcard include/*.h) $(wildcard src/*.h src/*.inc tests/*.h)
	@mkdir -p $(BUILD)
	$(CC) $(CSTD) $(CFLAGS) $(EXTRA) -DGB_TEST_HOOKS $(INC) $(SRCS) $(TEST_SRCS) -o $@ -lm

check-roms:
	@[ -d $(ROMS) ] || { echo "Faltan ROMs de prueba: ejecuta tools/fetch-test-roms.sh"; exit 1; }

test: check-roms $(BUILD)/gbtest
	python3 tests/run_suite.py --bin $(BUILD)/gbtest --roms $(ROMS) --suite tests/suite.txt --hito $(HITO)

asan: check-roms
	$(MAKE) BUILD=$(BUILD)/asan EXTRA="$(SAN)" CFLAGS="-O1 -g" test

fuzz: $(BUILD)/gbtest
	@mkdir -p $(BUILD) fuzz/corpus/fuzz_load_rom fuzz/corpus/fuzz_state_load fuzz/corpus/fuzz_link fuzz/corpus/fuzz_progress fuzz/corpus/fuzz_pgbm
	@printf 'int LLVMFuzzerTestOneInput(const unsigned char*d,unsigned long n){(void)d;(void)n;return 0;}' > $(BUILD)/probe.c
	@$(CC) -fsanitize=fuzzer $(BUILD)/probe.c -o $(BUILD)/probe 2>/dev/null || { \
	  echo "Este clang no trae libFuzzer (el de Apple no lo incluye)."; \
	  echo "macOS: brew install llvm && make fuzz CC=\$$(brew --prefix llvm)/bin/clang"; exit 1; }
	$(BUILD)/gbtest --fuzz-seeds fuzz/corpus
	@for f in fuzz/fuzz_*.c; do \
	  n=$$(basename $$f .c); mkdir -p fuzz/corpus/$$n; \
	  $(CC) -std=c11 -g -O2 -fsanitize=fuzzer,address,undefined -fno-sanitize-recover=all $(INC) $$f $(SRCS) -o $(BUILD)/$$n || exit 1; \
	  opts="-max_len=1048576"; \
	  [ $$n = fuzz_link ] && opts="-max_len=16384 -len_control=0"; \
	  [ $$n = fuzz_progress ] && opts="-max_len=33000 -len_control=0"; \
	  [ $$n = fuzz_pgbm ] && opts="-max_len=16384 -dict=fuzz/fuzz_pgbm.dict"; \
	  $(BUILD)/$$n -max_total_time=$(FUZZ_SECONDS) $$opts fuzz/corpus/$$n || exit 1; \
	done

# Campaña dedicada de M9: evita gastar FUZZ_SECONDS también en los otros dos
# fuzzers cuando solo se valida el cable virtual.
fuzz-link: $(BUILD)/gbtest
	@mkdir -p $(BUILD) fuzz/corpus/fuzz_load_rom fuzz/corpus/fuzz_state_load fuzz/corpus/fuzz_link fuzz/corpus/fuzz_progress fuzz/corpus/fuzz_pgbm
	@printf 'int LLVMFuzzerTestOneInput(const unsigned char*d,unsigned long n){(void)d;(void)n;return 0;}' > $(BUILD)/probe.c
	@$(CC) -fsanitize=fuzzer $(BUILD)/probe.c -o $(BUILD)/probe 2>/dev/null || { \
	  echo "Este clang no trae libFuzzer (el de Apple no lo incluye)."; \
	  echo "macOS: brew install llvm && make fuzz-link CC=\$$(brew --prefix llvm)/bin/clang"; exit 1; }
	$(BUILD)/gbtest --fuzz-seeds fuzz/corpus
	$(CC) -std=c11 -g -O2 -fsanitize=fuzzer,address,undefined -fno-sanitize-recover=all $(INC) fuzz/fuzz_link.c $(SRCS) -o $(BUILD)/fuzz_link
	$(BUILD)/fuzz_link -max_total_time=$(FUZZ_SECONDS) -max_len=16384 -len_control=0 fuzz/corpus/fuzz_link

# Campaña dedicada de N6-C: solo el lector de progreso Pokémon (pgb_progress_read).
fuzz-progress: $(BUILD)/gbtest
	@mkdir -p $(BUILD) fuzz/corpus/fuzz_load_rom fuzz/corpus/fuzz_state_load fuzz/corpus/fuzz_link fuzz/corpus/fuzz_progress fuzz/corpus/fuzz_pgbm
	@printf 'int LLVMFuzzerTestOneInput(const unsigned char*d,unsigned long n){(void)d;(void)n;return 0;}' > $(BUILD)/probe.c
	@$(CC) -fsanitize=fuzzer $(BUILD)/probe.c -o $(BUILD)/probe 2>/dev/null || { \
	  echo "Este clang no trae libFuzzer (el de Apple no lo incluye)."; \
	  echo "macOS: brew install llvm && make fuzz-progress CC=\$$(brew --prefix llvm)/bin/clang"; \
	  echo "Sin libFuzzer: make fuzz-progress-smoke (mutación aleatoria con ASan/UBSan, sin cobertura guiada)."; exit 1; }
	$(BUILD)/gbtest --fuzz-seeds fuzz/corpus
	$(CC) -std=c11 -g -O2 -fsanitize=fuzzer,address,undefined -fno-sanitize-recover=all $(INC) fuzz/fuzz_progress.c $(SRCS) -o $(BUILD)/fuzz_progress
	$(BUILD)/fuzz_progress -max_total_time=$(FUZZ_SECONDS) -max_len=33000 -len_control=0 fuzz/corpus/fuzz_progress

# Campaña dedicada de N7-C: solo el contenedor .pgbm (pgbm_parse / pgbm_encode).
fuzz-pgbm: $(BUILD)/gbtest
	@mkdir -p $(BUILD) fuzz/corpus/fuzz_load_rom fuzz/corpus/fuzz_state_load fuzz/corpus/fuzz_link fuzz/corpus/fuzz_progress fuzz/corpus/fuzz_pgbm
	@printf 'int LLVMFuzzerTestOneInput(const unsigned char*d,unsigned long n){(void)d;(void)n;return 0;}' > $(BUILD)/probe.c
	@$(CC) -fsanitize=fuzzer $(BUILD)/probe.c -o $(BUILD)/probe 2>/dev/null || { \
	  echo "Este clang no trae libFuzzer (el de Apple no lo incluye)."; \
	  echo "macOS: brew install llvm && make fuzz-pgbm CC=\$$(brew --prefix llvm)/bin/clang"; \
	  echo "Sin libFuzzer: make fuzz-pgbm-smoke (mutación aleatoria con ASan/UBSan, sin cobertura guiada)."; exit 1; }
	$(BUILD)/gbtest --fuzz-seeds fuzz/corpus
	$(CC) -std=c11 -g -O2 -fsanitize=fuzzer,address,undefined -fno-sanitize-recover=all $(INC) fuzz/fuzz_pgbm.c $(SRCS) -o $(BUILD)/fuzz_pgbm
	$(BUILD)/fuzz_pgbm -max_total_time=$(FUZZ_SECONDS) -max_len=16384 -dict=fuzz/fuzz_pgbm.dict fuzz/corpus/fuzz_pgbm

# Humo sin libFuzzer del contenedor .pgbm: el mismo fuzz_pgbm.c con un conductor propio (FUZZ_STANDALONE).
fuzz-pgbm-smoke: $(BUILD)/gbtest
	@mkdir -p $(BUILD) fuzz/corpus/fuzz_load_rom fuzz/corpus/fuzz_state_load fuzz/corpus/fuzz_link fuzz/corpus/fuzz_progress fuzz/corpus/fuzz_pgbm
	$(BUILD)/gbtest --fuzz-seeds fuzz/corpus
	$(CC) -std=c11 -g -O1 $(SAN) -DFUZZ_STANDALONE $(INC) fuzz/fuzz_pgbm.c $(SRCS) -o $(BUILD)/fuzz_pgbm_smoke
	$(BUILD)/fuzz_pgbm_smoke $(FUZZ_SECONDS) fuzz/corpus/fuzz_pgbm

# Humo sin libFuzzer: el mismo fuzz_progress.c con un conductor propio (FUZZ_STANDALONE) que parte de
# las semillas, muta al azar durante FUZZ_SECONDS y corre con ASan + UBSan. No guía por cobertura.
fuzz-progress-smoke: $(BUILD)/gbtest
	@mkdir -p $(BUILD) fuzz/corpus/fuzz_load_rom fuzz/corpus/fuzz_state_load fuzz/corpus/fuzz_link fuzz/corpus/fuzz_progress fuzz/corpus/fuzz_pgbm
	$(BUILD)/gbtest --fuzz-seeds fuzz/corpus
	$(CC) -std=c11 -g -O1 $(SAN) -DFUZZ_STANDALONE $(INC) fuzz/fuzz_progress.c $(SRCS) -o $(BUILD)/fuzz_progress_smoke
	$(BUILD)/fuzz_progress_smoke $(FUZZ_SECONDS) fuzz/corpus/fuzz_progress

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
	echo '#include "pocketgb_progress.h"' | $(CC) $(CSTD) -Iinclude -x c -c - -o $(BUILD)/check_header_progress.o
	echo '#include "pocketgb_progress.h"' | $(CC) -std=c++17 -Wall -Wextra -Werror -Iinclude -x c++ -c - -o $(BUILD)/check_header_progress_cpp.o
	@echo "pocketgb_progress.h compila aislado (C11 y C++17): OK"
	echo '#include "pocketgb_pgbm.h"' | $(CC) $(CSTD) -Iinclude -x c -c - -o $(BUILD)/check_header_pgbm.o
	echo '#include "pocketgb_pgbm.h"' | $(CC) -std=c++17 -Wall -Wextra -Werror -Iinclude -x c++ -c - -o $(BUILD)/check_header_pgbm_cpp.o
	@echo "pocketgb_pgbm.h compila aislado (C11 y C++17): OK"

check-globals: lib
	@out=$$(nm $(BUILD)/libpocketgb.a | grep -E ' [bBdD] ' || true); \
	if [ -n "$$out" ]; then echo "Estado global mutable encontrado:"; echo "$$out"; exit 1; else echo "Sin estado global mutable: OK"; fi

clean:
	rm -rf $(BUILD)
