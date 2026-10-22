#!/usr/bin/env python3
"""Black 2's GUIDE area data: HERE's ITEMS (item balls, hidden items) and PEOPLE (gifts, gift Pokémon, in-game trades)
for every Town Map place, written as GuideAreas.kt's lines into GuideAreasBlack2Gen.kt (numbers and place names only).

    python3 scripts/gen_black2_guide.py <pokebw2 checkout> <Black 2 ROM (IREO rev 0)>

The game data is fuddlesworth/pokebw2's res/ (dumped from the cart): zone headers (res/field/zones), events
(res/field/events, one file per zone in order.json's order), scripts (res/field/scripts) and trades (res/field/trades,
generated/trades.txt's order). Place names come from the cart's text bank 109.

- Item balls: an NPC whose script is 7000 + n (script_sys.c: SCRIPTS_GLOBAL_7000), global_7000.s's Script_n setting
  0x800C = item, 0x800D = count; done = the NPC's hide flag.
- Hidden items: a BG event whose script is 8000 + n (SCRIPTS_GLOBAL_8000): 0x8000 = item, 0x8001 = count,
  0x8002 = its flag (HIDDEN_ITEM_FLAGS 0xB04 + k).
- Gifts: a zone's script `WorkSet 0x8000, item` then `RTCallGlobal 2801/2802/2805/2806/2807` (the give-item scripts);
  KEY when the item's field pocket is the key items', else GIFT. Gift Pokémon: `PokePartyAddEx`'s constant species;
  trades: `FieldTradeStart n` with a constant n.

Each zone goes to the Town Map place the app puts it on (Gen5RomTables.withTownMap's rule, repeated here): the place
whose zone it is, else its parent's (up to four steps), else the first place with the same place name. A thing in a
zone whose place name isn't its place's gets that name as where it is.
"""
import json
import re
import struct
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "app/src/main/kotlin/com/pokedaisy/app/companion/data/GuideAreasBlack2Gen.kt"
GIFT_CALLS = {2801, 2802, 2805, 2806, 2807}


def constants(path: Path) -> dict[str, int]:
    """generated/*.txt: NAME [= N] a line, numbered on from the last explicit value."""
    out, n = {}, 0
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r"(\w+)(?:\s*=\s*(-?\w+))?", line)
        if m[2] is not None:
            n = int(m[2], 0) if re.match(r"-?(0x)?[0-9a-fA-F]+$", m[2]) else out[m[2]]
        out.setdefault(m[1], n)
        n += 1
    return out


def rom_text(rom: Path, bank: int) -> list[str]:
    data = rom.read_bytes()
    u16 = lambda b, o: struct.unpack_from("<H", b, o)[0]
    u32 = lambda b, o: struct.unpack_from("<I", b, o)[0]
    fnt = data[u32(data, 0x40):u32(data, 0x40) + u32(data, 0x44)]
    d = 0xF000
    for name in "a/0/0/2".split("/"):
        o, i = u32(fnt, (d & 0xFFF) * 8), u16(fnt, (d & 0xFFF) * 8 + 4)
        while fnt[o]:
            n = fnt[o]; o += 1; s = fnt[o:o + (n & 127)].decode(); o += n & 127
            if n & 128:
                sub = u16(fnt, o); o += 2
                if s == name: d = sub; break
            else:
                if s == name: d = i; break
                i += 1
    fat = u32(data, 0x48) + d * 8
    narc = data[u32(data, fat):u32(data, fat + 4)]
    o = u16(narc, 12); count = u16(narc, o + 8); fatb = o + 12; o += u32(narc, o + 4); o += u32(narc, o + 4); base = o + 8
    b = narc[base + u32(narc, fatb + bank * 8):base + u32(narc, fatb + bank * 8 + 4)]
    out = []
    for k in range(u16(b, 2)):
        sec = u32(b, 12); off = u32(b, sec + 4 + 8 * k); ln = u16(b, sec + 8 + 8 * k)
        words = list(struct.unpack_from("<" + "H" * ln, b, sec + off))
        key = words[-1] ^ 0xFFFF; res = []
        for w in reversed(words):
            res.append(w ^ key); key = ((key >> 3) | (key << 13)) & 0xFFFF
        out.append("".join(chr(c) for c in reversed(res) if c < 0xF000))
    return out


def scripts_of(path: Path) -> dict[int, list[str]]:
    """A script file's Script_n bodies (comments dropped)."""
    out, cur = {}, None
    for line in path.read_text().splitlines():
        m = re.match(r"Script_(\d+):", line)
        if m:
            cur = int(m[1]); out[cur] = []
        elif cur is not None and line.strip() and not line.strip().startswith("//"):
            out[cur].append(line.strip())
    return out


def main(root: Path, rom: Path):
    gen = root / "generated"
    zones_c = constants(gen / "zones.txt")
    places_c = constants(gen / "places.txt")
    items_c = constants(gen / "items.txt")
    species_c = constants(gen / "species.txt")
    flags_c = constants(gen / "flags.txt") if (gen / "flags.txt").exists() else {}
    zone_names = sorted(zones_c, key=zones_c.get)
    zones = []
    for z in zone_names:
        j = json.loads((root / "res/field/zones" / (z.removeprefix("ZONE_").lower() + ".json")).read_text())
        zones.append((z, j))
    place_names = rom_text(rom, 109)
    place_of = lambda j: places_c[j["place"]]
    zone_index = {z: i for i, (z, _) in enumerate(zones)}
    town = json.loads((root / "res/town_map/order.json").read_text())
    town_by_zone = {zone_index[z]: i for i, z in enumerate(town)}
    town_by_place = {}
    for i, z in enumerate(town):
        town_by_place.setdefault(place_of(zones[zone_index[z]][1]), i)

    def town_place(zi: int) -> int:
        z = zi
        for _ in range(4):
            if z in town_by_zone:
                return town_by_zone[z]
            parent = zone_index.get(zones[z][1]["parent"], z)
            if parent == z:
                break
            z = parent
        return town_by_place.get(place_of(zones[zi][1]), -1)

    def flag(name) -> int:
        if not name or name == 0:
            return 0
        if isinstance(name, int):
            return name
        m = re.match(r"EVENT_FLAG_(0x[0-9a-fA-F]+)$", name)
        return int(m[1], 16) if m else flags_c.get(name, 0)

    def pocket(item: int) -> str:
        name = next((k for k, v in items_c.items() if v == item and k != "ITEM_LAST"), None)
        p = root / "res/items" / (name.removeprefix("ITEM_").lower() if name else "") / "data.json"
        return json.loads(p.read_text()).get("field_pocket", "") if name and p.exists() else ""

    balls = scripts_of(root / "res/field/scripts/global_7000.s")
    hidden = scripts_of(root / "res/field/scripts/global_8000.s")

    def work(body, var):
        for l in body:
            m = re.match(rf"WorkSet(?:Const)? {var}, (\d+)$", l)
            if m:
                return int(m[1])
        return None

    trade_order = [l.split()[0] for l in (gen / "trades.txt").read_text().splitlines() if l.startswith("TRADE_")]
    events_order = json.loads((root / "res/field/events/order.json").read_text())
    lines = set()
    for zi, (z, j) in enumerate(zones):
        tp = town_place(zi)
        if tp < 0:
            continue
        here_place = place_of(zones[zone_index[town[tp]]][1])
        where = "" if place_of(j) == here_place else place_names[place_of(j)].upper()
        if z in events_order:
            ev = json.loads((root / "res/field/events" / (z.removeprefix("ZONE_").lower() + ".json")).read_text()) \
                if (root / "res/field/events" / (z.removeprefix("ZONE_").lower() + ".json")).exists() else None
        else:
            ev = None
        if ev:
            for n in ev.get("npcs", []):
                s = n["script"]
                if 7000 < s < 7800 and s - 7000 in balls:
                    item, qty = work(balls[s - 7000], "0x800c"), work(balls[s - 7000], "0x800d") or 1
                    if item:
                        lines.add(f"{tp}|ITEM|{item}|{flag(n['flag'])}|{qty}|0|{where}")
            for b in ev.get("bg_events", []):
                s = b["script"]
                if 8000 < s < 8800 and s - 8000 in hidden:
                    body = hidden[s - 8000]
                    item, qty, fl = work(body, "0x8000"), work(body, "0x8001") or 1, work(body, "0x8002") or 0
                    if item:
                        lines.add(f"{tp}|HIDDEN|{item}|{fl}|{qty}|0|{where}")
        sf = root / "res/field/scripts" / (j["scripts"].removeprefix("SCRIPTS_").lower() + ".s")
        if not sf.exists():
            continue
        body = [l.strip() for l in sf.read_text().splitlines() if l.strip() and not l.strip().startswith("//")]
        for k, l in enumerate(body):
            m = re.match(r"WorkSet 0x8000, (\d+)$", l)
            if m:
                call = next((int(c[1]) for c in (re.match(r"RTCallGlobal (\d+)", x) for x in body[k + 1:k + 4]) if c), None)
                if call in GIFT_CALLS:
                    item = int(m[1])
                    qty = next((int(q[1]) for q in (re.match(r"WorkSet 0x8001, (\d+)$", x) for x in body[k + 1:k + 3]) if q), 1)
                    kind = "KEY" if pocket(item) == "BAG_POCKET_KEY_ITEMS" else "GIFT"
                    lines.add(f"{tp}|{kind}|{item}|0|{qty}|0|{where}")
            m = re.match(r"PokePartyAddEx 0x\w+, (\d+), ", l)
            if m and 0 < int(m[1]) <= 649:
                lines.add(f"{tp}|MON|{int(m[1])}|0|1|0|{where}")
            m = re.match(r"FieldTradeStart (\d+), ", l)
            if m and int(m[1]) < len(trade_order):
                t = json.loads((root / "res/field/trades" / (trade_order[int(m[1])].removeprefix("TRADE_").lower() + ".json")).read_text())
                lines.add(f"{tp}|TRADE|{species_c[t['species']]}|0|1|{species_c[t['wanted_species']]}|{where}")
    out = sorted(lines, key=lambda l: (int(l.split("|")[0]), l))
    # Checks on things the story makes sure of: CHEREN's gift in Aspertia, HM01 (CUT) in Virbank City.
    hm01 = items_c["ITEM_HM01"]
    assert any(f"|GIFT|{hm01}|" in l or f"|KEY|{hm01}|" in l for l in out), "HM01 not found"
    assert sum("|ITEM|" in l for l in out) > 150 and sum("|HIDDEN|" in l for l in out) > 100, "too few items"
    text = "".join(f"{l}\n" for l in out)
    OUT.write_text(
        "// Generated by scripts/gen_black2_guide.py from fuddlesworth/pokebw2's res/ and the cart's place names - do not edit.\n"
        "package com.pokedaisy.app.companion.data\n\n"
        "/** Black 2's item balls, hidden items, gifts, gift Pokémon and trades by Town Map place (GuideAreas.kt's lines). */\n"
        f"internal val B2_AREAS_RAW: List<String> = listOf(\n    \"\"\"\n{text}\"\"\",\n)\n")
    kinds = {}
    for l in out:
        kinds[l.split("|")[1]] = kinds.get(l.split("|")[1], 0) + 1
    print(f"wrote {len(out)} lines {kinds} to {OUT}")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
