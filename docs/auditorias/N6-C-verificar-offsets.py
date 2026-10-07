#!/usr/bin/env python3
"""N6-C: verifica los desplazamientos del lector de progreso Pokémon contra pret.

Evalúa ram/wram.asm, ram/sram.asm y constants/*.asm de clones de pret/pokered,
pret/pokeyellow, pret/pokegold y pret/pokecrystal con un intérprete mínimo del
subconjunto de rgbasm que usan (db/dw/ds/rb, flag_array y demás macros de
estructuras, UNION/NEXTU, FOR/REPT, IF, SECTION, const_def/const), calcula la
posición de cada etiqueta en el archivo .sav (SRAM banco 1 = archivo + 0x2000) y la
compara con los números de core/src/progress_pokemon.c.

Uso:   python3 N6-C-verificar-offsets.py CARPETA_CON_LOS_CLONES_DE_PRET [core/src/progress_pokemon.c]
Salida: una línea por dato (OK o DIFERENTE) y código de salida 0 solo si todo coincide.

Solo se usan HECHOS (posiciones y tamaños); este script es código propio y no copia
nada de pret (que no declara licencia) ni de PKHeX (GPLv3). Ojo: evalúa con eval()
expresiones de los .asm; ejecútalo solo sobre clones que tú mismo hayas descargado.
Los .asm se leen, no se compilan: no hace falta rgbds.
"""
import re, sys, os, glob

class Undefined(Exception):
    pass

class Ctx:
    def __init__(self):
        self.consts = {"const_value": 0, "const_inc": 1, "_RS": 0}
        self.macros = {}
        self.labels = {}      # nombre -> (id_seccion, offset)
        self.sec = 0
        self.pc = 0
        self.errors = []
        self.uid = 0
        self.equs = {}
        self.mode = "const"   # "const" | "ram"

    # ---------- expresiones ----------
    def ev(self, expr):
        e = expr.strip()
        for nm, tx in self.equs.items():
            if nm in e:
                e = re.sub(r"\b%s\b" % re.escape(nm), " " + tx + " ", e)
        e = re.sub(r"\bdef\(\s*(\w+)\s*\)", lambda m: "1" if (m.group(1) in self.consts or m.group(1) in self.labels) else "0", e, flags=re.I)
        e = re.sub(r"!(?!=)", " not ", e)
        e = e.replace("&&", " and ").replace("||", " or ")
        e = re.sub(r"\$([0-9A-Fa-f]+)", r"0x\1", e)
        e = re.sub(r"(?<![\w)])%([01]+)\b", r"0b\1", e)
        e = re.sub(r"(?<![\w)])&([0-7]+)\b", r"0o\1", e)
        e = e.replace("/", "//")
        e = re.sub(r"'(.)'", lambda m: str(ord(m.group(1))), e)
        e = re.sub(r"\b(?!0x|0b|0o)([A-Za-z_][A-Za-z0-9_]*)\b",
                   lambda m: m.group(1) if m.group(1) in ("not", "and", "or") else "V(%r)" % m.group(1), e)
        # `0x1F` -> el regex anterior no debe tocarlo (empieza por dígito); `V('x')` ya está
        def V(n):
            if n in self.consts:
                return self.consts[n]
            if n in self.labels:
                return self.labels[n][1]
            raise Undefined(n)
        try:
            return int(eval(e, {"V": V, "__builtins__": {}}))
        except Undefined:
            raise
        except Exception as ex:
            raise ValueError("expr %r -> %r: %s" % (expr, e, ex))

def strip_comment(line):
    out, q = [], None
    for ch in line:
        if q:
            out.append(ch)
            if ch == q:
                q = None
        elif ch in "\"'":
            q = ch
            out.append(ch)
        elif ch == ";":
            break
        else:
            out.append(ch)
    return "".join(out).rstrip()

def read_lines(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        raw = f.read().split("\n")
    lines, buf = [], ""
    for l in raw:
        l = strip_comment(l)
        if l.endswith("\\") and not l.endswith("\\\\"):
            buf += l[:-1] + " "
            continue
        lines.append((buf + l))
        buf = ""
    return lines

LABEL_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_#@]*)(::?)\s*(.*)$")
BLOCK_OPEN = ("for", "rept", "if", "union", "macro")
FOR_RE = re.compile(r"^for\s+(\w+)\s*,\s*(.+)$", re.I)

def split_args(s):
    args, depth, cur = [], 0, ""
    for ch in s:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            args.append(cur.strip())
            cur = ""
        else:
            cur += ch
    if cur.strip():
        args.append(cur.strip())
    return args

def kw(line):
    m = re.match(r"^\s*([A-Za-z_?]\w*)", line)
    return m.group(1).lower() if m else ""

def run(ctx, lines, i=0, end=None, stop_at=()):
    """Ejecuta lines[i:end]. Devuelve el índice siguiente."""
    n = len(lines) if end is None else end
    while i < n:
        line = lines[i]
        t = line.strip()
        if not t:
            i += 1
            continue
        # etiqueta
        body = t
        m = LABEL_RE.match(body)
        # una línea que empieza por palabra clave sin ':' no es etiqueta
        labels_here = []
        while m:
            labels_here.append(m.group(1))
            body = m.group(3).strip()
            m = LABEL_RE.match(body)
        for lb in labels_here:
            ctx.labels[lb] = (ctx.sec, ctx.pc)
        if not body:
            i += 1
            continue
        k = kw(body)
        rest = body[len(k):].strip() if k else ""
        # bloques
        if k in ("for",):
            mm = FOR_RE.match(body)
            var = mm.group(1)
            a = split_args(mm.group(2))
            if len(a) == 2:
                lo, hi, st = 0, ctx.ev(a[0]), 1
                lo, hi = ctx.ev(a[0]), ctx.ev(a[1])
            elif len(a) == 3:
                lo, hi, st = ctx.ev(a[0]), ctx.ev(a[1]), ctx.ev(a[2])
            else:
                lo, hi, st = 0, ctx.ev(a[0]), 1
            j = find_end(lines, i + 1, ("for", "rept"), ("endr",))
            blk = lines[i + 1:j]
            v = lo
            while (st > 0 and v < hi) or (st < 0 and v > hi):
                sub = [re.sub(r"\{(0?\d*)d:%s\}" % re.escape(var), lambda mm, v=v: ("%" + (mm.group(1) or "") + "d") % v, b) for b in blk]
                ctx.consts[var] = v
                run(ctx, sub)
                v += st
            i = j + 1
            continue
        if k == "rept":
            cnt = ctx.ev(rest)
            j = find_end(lines, i + 1, ("for", "rept"), ("endr",))
            for _ in range(cnt):
                run(ctx, lines[i + 1:j])
            i = j + 1
            continue
        if k == "union":
            start = ctx.pc
            maxend = start
            i += 1
            while True:
                j = find_union_split(lines, i)
                run(ctx, lines[i:j])
                maxend = max(maxend, ctx.pc)
                tag = kw(lines[j])
                i = j + 1
                if tag == "endu":
                    break
                ctx.pc = start
            ctx.pc = maxend
            continue
        if k in ("macro", "macro?"):
            name = rest.lstrip("? ").split()[0]
            j = find_end(lines, i + 1, ("macro",), ("endm",))
            ctx.macros[name.lower()] = lines[i + 1:j]
            i = j + 1
            continue
        if k == "if":
            # if/elif/else/endc: solo condiciones evaluables
            j = find_end(lines, i + 1, ("if",), ("endc",))
            blk = lines[i:j + 1]
            run_if(ctx, blk)
            i = j + 1
            continue
        if k in ("section",):
            ctx.sec += 1
            ctx.pc = 0
            i += 1
            continue
        if k in ("endsection", "pushs", "pops", "include", "assert", "static_assert",
                 "export", "fail", "warn", "charmap", "newcharmap", "setcharmap",
                 "opt", "popo", "pusho", "println", "print", "purge", "incbin",
                 "rsreset", "rsset", "endc", "endr", "endm", "endu", "nextu", "else", "elif"):
            if k == "rsreset":
                ctx.consts["_RS"] = 0
            i += 1
            continue
        if k == "def" or re.match(r"^\w+\s+(equ|=)\s", body, re.I):
            exec_def(ctx, body)
            i += 1
            continue
        if k == "db":
            ctx.pc += max(1, len(split_args(rest)))
            i += 1
            continue
        if k == "dw":
            ctx.pc += 2 * max(1, len(split_args(rest)))
            i += 1
            continue
        if k == "dl":
            ctx.pc += 4 * max(1, len(split_args(rest)))
            i += 1
            continue
        if k == "ds":
            a = split_args(rest)
            try:
                ctx.pc += ctx.ev(a[0])
            except (Undefined, ValueError) as ex:
                ctx.errors.append("ds fallido (%s): %r" % (ex, t))
            i += 1
            continue
        # macros del usuario
        if k in ctx.macros:
            if ctx.mode == "ram":
                expand(ctx, k, split_args(rest))
            else:
                try:
                    expand(ctx, k, split_args(rest))
                except Exception:
                    pass
            i += 1
            continue
        if k in BUILTIN_CONST:
            BUILTIN_CONST[k](ctx, split_args(rest))
            i += 1
            continue
        # desconocido
        if ctx.mode == "ram":
            ctx.errors.append("desconocido: %r" % t)
        i += 1
    return i

CONST_MACROS_OK = set()

def find_end(lines, i, openers, closers):
    depth = 1
    while i < len(lines):
        k = kw(lines[i])
        # etiqueta + palabra clave en la misma línea no se considera
        if k in openers or (k == "macro?" and "macro" in openers):
            depth += 1
        elif k in closers:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise ValueError("bloque sin cerrar")

def find_union_split(lines, i):
    depth = 0
    while i < len(lines):
        k = kw(lines[i])
        if k == "union":
            depth += 1
        elif k in ("nextu", "endu"):
            if depth == 0:
                return i
            if k == "endu":
                depth -= 1
        i += 1
    raise ValueError("UNION sin cerrar")

def run_if(ctx, blk):
    # soporte simple: if cond ... [elif ...] [else ...] endc, sin anidar
    cond_ok, taken = None, False
    cur = []
    branches = []   # (cond_or_None, lines)
    depth = 0
    cond = None
    for ln in blk:
        k = kw(ln)
        if k == "if":
            depth += 1
            if depth == 1:
                cond = ln.strip()[2:].strip()
                cur = []
                continue
        elif k in ("elif", "else") and depth == 1:
            branches.append((cond, cur))
            cond = ln.strip()[4:].strip() if k == "elif" else None
            cur = []
            continue
        elif k == "endc":
            depth -= 1
            if depth == 0:
                branches.append((cond, cur))
                break
        cur.append(ln)
    for c, ls in branches:
        try:
            ok = True if c is None else bool(ctx.ev(c))
        except (Undefined, ValueError):
            ok = False
        if ok:
            run(ctx, ls)
            return

def exec_def(ctx, body):
    m = re.match(r"^(?:def\s+)?(\w+)\s*(equ|=|\+=|-=|equs|rb|rw)\s*(.*)$", body, re.I)
    if not m:
        return
    name, op, val = m.group(1), m.group(2).lower(), m.group(3)
    if op in ("rb", "rw"):
        cnt = 1
        if val.strip():
            try:
                cnt = ctx.ev(val)
            except (Undefined, ValueError):
                return
        ctx.consts[name] = ctx.consts["_RS"]
        ctx.consts["_RS"] += cnt * (2 if op == "rw" else 1)
        return
    if op in ("equs",):
        mm = re.match(r'^"(.*)"$', val.strip())
        if mm and not re.search(r"[{\\]", mm.group(1)):
            ctx.equs[name] = mm.group(1)
        return
    try:
        v = ctx.ev(val)
    except Undefined:
        return
    except ValueError:
        return
    if op == "+=":
        ctx.consts[name] = ctx.consts.get(name, 0) + v
    elif op == "-=":
        ctx.consts[name] = ctx.consts.get(name, 0) - v
    else:
        ctx.consts[name] = v

def expand(ctx, name, args):
    ctx.uid += 1
    body = ctx.macros[name]
    out = []
    for ln in body:
        s = ln
        for idx in range(len(args), 0, -1):
            s = s.replace("\\%d" % idx, args[idx - 1])
        s = re.sub(r"\\\d", "", s)
        s = s.replace("\\@", "_%d" % ctx.uid)
        out.append(s)
    run(ctx, out)

# ---- macros de enumeración (implementación propia equivalente a macros/const.asm) ----
def b_const_def(ctx, a):
    ctx.consts["const_value"] = ctx.ev(a[0]) if len(a) >= 1 else 0
    ctx.consts["const_inc"] = ctx.ev(a[1]) if len(a) >= 2 else 1

def b_const(ctx, a):
    ctx.consts[a[0]] = ctx.consts["const_value"]
    ctx.consts["const_value"] += ctx.consts["const_inc"]

def b_const_skip(ctx, a):
    ctx.consts["const_value"] += ctx.consts["const_inc"] * (ctx.ev(a[0]) if a else 1)

def b_const_next(ctx, a):
    ctx.consts["const_value"] = ctx.ev(a[0])

def b_shift_const(ctx, a):
    ctx.consts[a[0]] = 1 << ctx.consts["const_value"]
    ctx.consts[a[0] + "_F"] = ctx.consts["const_value"]
    ctx.consts["const_value"] += ctx.consts["const_inc"]

BUILTIN_CONST = {
    "const_def": b_const_def, "const": b_const, "const_skip": b_const_skip,
    "const_next": b_const_next, "shift_const": b_shift_const,
    "const_export": b_const,
}

def load_game(repo, mode_files):
    ctx = Ctx()
    # 1) macros de RAM
    for mf in ("macros/ram.asm",):
        p = os.path.join(repo, mf)
        if os.path.exists(p):
            ctx.mode = "const"
            run(ctx, read_lines(p))
    # 2) constantes (varias pasadas: el orden de INCLUDE no se replica)
    files = sorted(glob.glob(os.path.join(repo, "constants", "*.asm")) +
                   glob.glob(os.path.join(repo, "constants", "*.inc")))
    for _ in range(3):
        for p in files:
            ctx.consts["const_value"] = 0
            ctx.consts["const_inc"] = 1
            ctx.mode = "const"
            try:
                run(ctx, read_lines(p))
            except Exception as ex:
                pass
    return ctx

def preprocess_ifs(ctx, lines):
    """Resuelve IF/ELIF/ELSE/ENDC de primer nivel antes de analizar UNION/NEXTU."""
    out, stack, in_macro = [], [], False
    for ln in lines:
        k = kw(ln)
        if k in ("macro", "macro?"):
            in_macro = True
        elif k == "endm":
            in_macro = False
            if all(a for a in stack):
                out.append(ln)
            continue
        if not in_macro and k == "if":
            try:
                c = bool(ctx.ev(ln.strip()[2:].strip()))
            except (Undefined, ValueError):
                c = False
            stack.append([c, c])      # [activo, alguna_rama_tomada]
            continue
        if not in_macro and k == "elif" and stack:
            st = stack[-1]
            try:
                c = bool(ctx.ev(ln.strip()[4:].strip()))
            except (Undefined, ValueError):
                c = False
            st[0] = (not st[1]) and c
            st[1] = st[1] or c
            continue
        if not in_macro and k == "else" and stack:
            st = stack[-1]
            st[0] = not st[1]
            st[1] = True
            continue
        if not in_macro and k == "endc" and stack:
            stack.pop()
            continue
        if all(a[0] for a in stack):
            out.append(ln)
    return out

def eval_ram(ctx, files):
    ctx.mode = "ram"
    for p in files:
        ctx.sec = 0
        ctx.pc = 0
        run(ctx, preprocess_ifs(ctx, read_lines(p)))


CORE = {}

def load_core_constants(path):
    """Constantes del enum de core/src/progress_pokemon.c (NOMBRE = valor)."""
    for m in re.finditer(r"^\s+([A-Z][A-Z0-9_]*)\s*=\s*(0x[0-9A-Fa-f]+|\d+)\b", open(path, encoding="utf-8").read(), re.M):
        CORE[m.group(1)] = int(m.group(2), 0)

def k(name):
    return CORE[name]

def file_off(ctx, label):
    """Posición en el .sav de una etiqueta de SRAM (todas viven en el banco 1)."""
    return 0x2000 + ctx.labels[label][1]

def wram_rel(ctx, label, base):
    return ctx.labels[label][1] - ctx.labels[base][1]

def load(root, game):
    repo = os.path.join(root, game)
    ctx = load_game(repo, None)
    eval_ram(ctx, [os.path.join(repo, "ram", "wram.asm"), os.path.join(repo, "ram", "sram.asm")])
    return ctx

def gen1(root, game):
    c = load(root, game)
    main_off = file_off(c, "sMainData")
    r = lambda lb: main_off + wram_rel(c, lb, "wMainDataStart")
    return {
        "G1_GAME_DATA (sGameData = sPlayerName)": (file_off(c, "sGameData"), k("G1_GAME_DATA")),
        "G1_CHECKSUM (sMainDataCheckSum = sGameDataEnd)": (file_off(c, "sMainDataCheckSum"), k("G1_CHECKSUM")),
        "sGameDataEnd": (file_off(c, "sGameDataEnd"), k("G1_CHECKSUM")),
        "NAME_LENGTH": (c.consts["NAME_LENGTH"], k("NAME_BYTES")),
        "G1_DEX_OWNED": (r("wPokedexOwned"), k("G1_DEX_OWNED")),
        "G1_DEX_SEEN": (r("wPokedexSeen"), k("G1_DEX_SEEN")),
        "NUM_POKEMON": (c.consts["NUM_POKEMON"], k("G1_DEX_BITS")),
        "G1_MONEY": (r("wPlayerMoney"), k("G1_MONEY")),
        "G1_BADGES": (r("wObtainedBadges"), k("G1_BADGES")),
        "G1_PLAY_TIME (horas)": (r("wPlayTimeHours"), k("G1_PLAY_TIME")),
        "wPlayTimeMaxed": (r("wPlayTimeMaxed"), k("G1_PLAY_TIME") + 1),
        "wPlayTimeMinutes": (r("wPlayTimeMinutes"), k("G1_PLAY_TIME") + 2),
        "wPlayTimeSeconds": (r("wPlayTimeSeconds"), k("G1_PLAY_TIME") + 3),
        "wPlayTimeFrames": (r("wPlayTimeFrames"), k("G1_PLAY_TIME") + 4),
    }

def gen2(root, game, crystal):
    c = load(root, game)
    g = file_off(c, "sGameData")
    p = file_off(c, "sPokemonData")
    pd = lambda lb: g + wram_rel(c, lb, "wGameData")      # datos del jugador
    pk = lambda lb: p + wram_rel(c, lb, "wPokemonData")   # datos de Pokémon
    # los tres bloques de sGameData son contiguos, como en la RAM de trabajo
    assert file_off(c, "sCurMapData") == g + wram_rel(c, "wPlayerDataEnd", "wGameData")
    assert p == file_off(c, "sCurMapData") + wram_rel(c, "wCurMapDataEnd", "wCurMapData")
    assert file_off(c, "sGameDataEnd") == p + wram_rel(c, "wPokemonDataEnd", "wPokemonData")
    t = "C" if crystal else "GS"
    pre = "C_" if crystal else "GS_"
    return {
        t + "_CHECK_VALUE_1 (sCheckValue1)": (file_off(c, "sCheckValue1"), k("G2_CHECK_VALUE_1")),
        "G2_GAME_DATA (sGameData)": (g, k("G2_GAME_DATA")),
        t + "_GAME_DATA_END (sGameDataEnd)": (file_off(c, "sGameDataEnd"), k(pre + "GAME_DATA_END")),
        t + "_CHECKSUM (sChecksum, 2 B LE)": (file_off(c, "sChecksum"), k(pre + "CHECKSUM")),
        t + "_CHECK_VALUE_2 (sCheckValue2)": (file_off(c, "sCheckValue2"), k(pre + "CHECK_VALUE_2")),
        "SAVE_CHECK_VALUE_1": (c.consts["SAVE_CHECK_VALUE_1"], k("G2_SAVE_CHECK_1")),
        "SAVE_CHECK_VALUE_2": (c.consts["SAVE_CHECK_VALUE_2"], k("G2_SAVE_CHECK_2")),
        "G2_NAME (wPlayerName)": (pd("wPlayerName"), k("G2_NAME")),
        "NAME_LENGTH": (c.consts["NAME_LENGTH"], k("NAME_BYTES")),
        t + "_PLAY_TIME (wGameTimeHours, 2 B BE)": (pd("wGameTimeHours"), k(pre + "PLAY_TIME")),
        "wGameTimeMinutes": (pd("wGameTimeMinutes"), k(pre + "PLAY_TIME") + 2),
        "wGameTimeSeconds": (pd("wGameTimeSeconds"), k(pre + "PLAY_TIME") + 3),
        "wGameTimeFrames": (pd("wGameTimeFrames"), k(pre + "PLAY_TIME") + 4),
        t + "_MONEY (wMoney, 3 B BE)": (pd("wMoney"), k(pre + "MONEY")),
        t + "_BADGES (wJohtoBadges)": (pd("wJohtoBadges"), k(pre + "BADGES")),
        "wKantoBadges": (pd("wKantoBadges"), k(pre + "BADGES") + 1),
        t + "_DEX_OWNED (wPokedexCaught)": (pk("wPokedexCaught"), k(pre + "DEX_OWNED")),
        t + "_DEX_SEEN (wPokedexSeen)": (pk("wPokedexSeen"), k(pre + "DEX_SEEN")),
        "NUM_POKEMON": (c.consts["NUM_POKEMON"], k("G2_DEX_BITS")),
    }

def main():
    if len(sys.argv) not in (2, 3):
        print(__doc__)
        return 2
    root = sys.argv[1]
    here = os.path.dirname(os.path.abspath(__file__))
    load_core_constants(sys.argv[2] if len(sys.argv) == 3 else
                        os.path.join(here, "..", "..", "core", "src", "progress_pokemon.c"))
    runs = [("pokered (Rojo/Azul)", gen1, ("pokered",)),
            ("pokeyellow (Amarillo)", gen1, ("pokeyellow",)),
            ("pokegold (Oro/Plata)", gen2, ("pokegold", False)),
            ("pokecrystal (Cristal)", gen2, ("pokecrystal", True))]
    bad = 0
    for title, fn, args in runs:
        print("== " + title)
        for name, (got, want) in fn(root, *args).items():
            ok = got == want
            bad += 0 if ok else 1
            print("  %-46s pret=0x%04X  núcleo=0x%04X  %s" % (name, got, want, "OK" if ok else "DIFERENTE"))
    print("%d diferencias" % bad)
    return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main())
