package com.pokedaisy.app.companion.data

import java.io.File
import java.io.RandomAccessFile

/**
 * Black 2's art and DEX pages, rebuilt from the player's own cart like [NdsArt] (nothing of the game is bundled), once
 * per cart build (a marker keeps the header's code, revision and [VERSION]). Archive layouts are pokebw2's
 * (tools/data/pokemon_graphics.py, item_icon_data.py, species_data.py, render of the Town Map's a/0/8/4):
 * - party icons: a/0/0/7, palettes in member 0, a species' icon at 8 + 2 x species, its palette [black2IconPalettes];
 * - item icons: a/0/2/5, each item's picture and palette by [black2ItemIcons] (pml_item.c's table, from the ARM9);
 * - DEX pages: personal a/0/1/6 (0x4C a species, the Unova numbers in its last member), level-up moves a/0/1/8,
 *   evolutions a/0/1/9, text banks 442 (entries), 464 (categories), 374 (abilities) - the TSV [Gen4Dex] reads;
 * - front pictures: a/0/0/4, slot 0 of a species' 20 (LZ11, 96 x 96 in four OBJs), palette slot 18;
 * - the Town Map: a/0/8/4's 8bpp tiles (member 7) under maps 17 then 16, palette member 0, cropped to 256 x 168.
 */
object Gen5Art {
    const val ICONS = "nds/black2/pokemon"
    const val ITEMS = "nds/black2/items"
    const val DEX = "nds/black2/dex.tsv"
    const val FRONT = "nds/black2/front"
    /** regionmap/<this>.png */
    const val TOWN_MAP = "black2_unova"
    /** The player's spot on the map: our own drawn marker (the cart's are animated cells), blue / red by gender. */
    const val HEAD_M = "black2_player_m"
    const val HEAD_F = "black2_player_f"
    /** SPECIES_EGG: the egg's icon is written as this species. */
    const val EGG = 650
    private const val EGG_ICON_GROUP = 683
    private const val VERSION = 1

    fun extract(f: RandomAccessFile, header: ByteArray, dir: File): Boolean {
        val code = String(header, 0x0C, 4, Charsets.US_ASCII)
        val rev = header[0x1E].toInt() and 0xFF
        val marker = File(dir, "nds/black2-built")
        val stamp = "v$VERSION $code $rev"
        if (marker.isFile && runCatching { marker.readText() }.getOrNull() == stamp) return false
        icons(f, header, dir)
        itemIcons(f, header, dir)
        runCatching { dex(f, header, dir) }.onFailure { android.util.Log.w("pokedaisy", "Gen5Art: dex", it) }
        runCatching { fronts(f, header, dir) }.onFailure { android.util.Log.w("pokedaisy", "Gen5Art: fronts", it) }
        runCatching { townMap(f, header, dir) }.onFailure { android.util.Log.w("pokedaisy", "Gen5Art: town map", it) }
        markers(dir)
        marker.parentFile?.mkdirs()
        marker.writeText(stamp)
        return true
    }

    private fun narc(f: RandomAccessFile, header: ByteArray, path: String) = NdsArt.file(f, header, path)?.let { NdsArt.Narc(it) }

    private fun icons(f: RandomAccessFile, header: ByteArray, dir: File) {
        val narc = narc(f, header, "/a/0/0/7") ?: return
        val palettes = NdsArt.palettes(narc.member(0))
        for (species in 1..EGG) {
            // The egg's icon is group 683 (after the species and their forms; 650's is a copy of the "?"), in palette 1.
            val group = if (species == EGG) EGG_ICON_GROUP else species
            val bank = if (species == EGG) 1 else (black2IconPalettes.getOrNull(species) ?: 0) and 15
            val pal = palettes.getOrNull(bank) ?: palettes.firstOrNull() ?: continue
            val icon = NdsArt.icon(narc.member(8 + group * 2), pal) ?: continue
            write(dir, "$ICONS/$species.png", icon.png())
        }
    }

    private fun itemIcons(f: RandomAccessFile, header: ByteArray, dir: File) {
        val narc = narc(f, header, "/a/0/2/5") ?: return
        for (item in 1 until black2ItemIcons.size / 2) {
            val pal = NdsArt.palettes(narc.member(black2ItemIcons[item * 2 + 1])).firstOrNull() ?: continue
            val icon = NdsArt.icon(narc.member(black2ItemIcons[item * 2]), pal, height = 32) ?: continue
            write(dir, "$ITEMS/$item.png", icon.png())
        }
    }

    /** One line a species in [Gen4Dex]'s columns (Gen 5's evolution methods, 95 TMs then 6 HMs, a hidden ability). */
    private fun dex(f: RandomAccessFile, header: ByteArray, dir: File) {
        val text = narc(f, header, "/a/0/0/2") ?: return
        fun bank(n: Int) = text.member(n)?.let { Gen5RomTables.decodeText(it) }.orEmpty()
        val entries = bank(442)
        val categories = bank(464)
        val abilities = bank(374)
        val personal = narc(f, header, "/a/0/1/6") ?: return
        val levelUp = narc(f, header, "/a/0/1/8") ?: return
        val evolutions = narc(f, header, "/a/0/1/9") ?: return
        var last = 0
        while (personal.member(last + 1) != null) last++
        val unova = personal.member(last) ?: ByteArray(0)
        val sb = StringBuilder()
        for (species in 1..649) {
            val p = personal.member(species) ?: continue
            if (p.size < 0x4C) continue
            fun b(o: Int) = p[o].toInt() and 255
            fun w(o: Int) = b(o) or (b(o + 1) shl 8)
            fun ability(id: Int) = if (id != 0) abilities.getOrElse(id) { "" } else ""
            val regional = if (species * 2 + 2 <= unova.size) w2(unova, species * 2).takeIf { it in 1..300 } ?: 0 else 0
            val cells = listOf(
                species.toString(),
                categories.getOrElse(species) { "" },
                entries.getOrElse(species) { "" }.replace("\n", "\\n"),
                w(0x24).toString(), w(0x26).toString(),
                // HP, ATTACK, DEFENSE, SP. ATK, SP. DEF, SPEED (the record keeps SPEED fourth)
                b(0).toString(), b(1).toString(), b(2).toString(), b(4).toString(), b(5).toString(), b(3).toString(),
                Gen5RomTables.type(b(6)).toString(), Gen5RomTables.type(b(7)).toString(), b(8).toString(), b(0x12).toString(),
                b(0x16).toString(), b(0x17).toString(),
                ability(b(0x18)), ability(b(0x19)),
                regional.toString(),
                evolutions.member(species)?.let { e ->
                    (0 until 7).mapNotNull { i ->
                        if (i * 6 + 6 > e.size) return@mapNotNull null
                        val m = w2(e, i * 6)
                        if (m == 0) null else "$m,${w2(e, i * 6 + 2)},${w2(e, i * 6 + 4)}"
                    }.joinToString(";")
                } ?: "",
                levelUp.member(species)?.let { l ->
                    (0 until l.size / 4).asSequence().map { w2(l, it * 4) to w2(l, it * 4 + 2) }
                        .takeWhile { it.first != 0xFFFF }.joinToString(",") { (move, level) -> "$level:$move" }
                } ?: "",
                (0x28 until 0x38).joinToString("") { "%02x".format(b(it)) },
                ability(b(0x1A)),
            )
            sb.append(cells.joinToString("\t") { it.replace("\t", " ") }).append('\n')
        }
        write(dir, DEX, sb.toString().toByteArray())
    }

    private fun fronts(f: RandomAccessFile, header: ByteArray, dir: File) {
        val narc = narc(f, header, "/a/0/0/4") ?: return
        for (species in 1..649) {
            val pal = NdsArt.palettes(narc.member(species * 20 + 18)).firstOrNull() ?: continue
            val ncgr = narc.member(species * 20)?.let(::lz) ?: continue
            val chars = NdsArt.ncgrData(ncgr)?.takeIf { it.size >= 0x1200 } ?: continue
            val img = RomArt.Image(96, 96)
            // Four OBJs, each its tiles in rows: 64x64, 32x64 right of it, 64x32 under, 32x32 in the corner.
            var tile = 0
            for ((ox, oy, ow, oh) in listOf(listOf(0, 0, 64, 64), listOf(64, 0, 32, 64), listOf(0, 64, 64, 32), listOf(64, 64, 32, 32))) {
                for (ty in 0 until oh / 8) for (tx in 0 until ow / 8) {
                    for (y in 0 until 8) for (x in 0 until 8) {
                        val v = (chars[tile * 32 + y * 4 + x / 2].toInt() and 255).let { if (x and 1 == 1) it shr 4 else it and 15 }
                        if (v != 0) img.argb[(oy + ty * 8 + y) * 96 + ox + tx * 8 + x] = pal[v]
                    }
                    tile++
                }
            }
            write(dir, "$FRONT/$species.png", img.png())
        }
    }

    /** The Town Map's top screen: the land (map 17) under the places (map 16), 8bpp tiles, colour 0 clear. */
    private fun townMap(f: RandomAccessFile, header: ByteArray, dir: File) {
        val narc = narc(f, header, "/a/0/8/4") ?: return
        val pal = NdsArt.palettes(narc.member(0)).flatMap { it.toList() }.toIntArray()
        val tiles = NdsArt.ncgrData(narc.member(7)) ?: return
        val img = RomArt.Image(256, MAP_HEIGHT)
        img.argb.fill(pal.getOrElse(0) { 0xFF000000.toInt() })
        for (screen in intArrayOf(17, 16)) {
            val nscr = narc.member(screen) ?: continue
            val o = indexOf(nscr, "NRCS") ?: continue
            for (ty in 0 until MAP_HEIGHT / 8) for (tx in 0 until 32) {
                val e = w2(nscr, o + 0x14 + (ty * 32 + tx) * 2)
                val t = e and 0x3FF
                for (y in 0 until 8) for (x in 0 until 8) {
                    val sx = if (e and 0x400 != 0) 7 - x else x
                    val sy = if (e and 0x800 != 0) 7 - y else y
                    val i = t * 64 + sy * 8 + sx
                    val v = if (i < tiles.size) tiles[i].toInt() and 255 else 0
                    if (v == 0) continue
                    pal.getOrNull(v + (e shr 12) * 256)?.let { img.argb[(ty * 8 + y) * 256 + tx * 8 + x] = it }
                }
            }
        }
        write(dir, "regionmap/$TOWN_MAP.png", img.png())
    }

    /** A 16 x 16 ring for the player ([HEAD_M] / [HEAD_F]): dark outline, white ring, a coloured middle. */
    private fun markers(dir: File) {
        for ((name, fill) in listOf(HEAD_M to 0xFF3070E8.toInt(), HEAD_F to 0xFFE83848.toInt())) {
            val img = RomArt.Image(16, 16)
            for (y in 0 until 16) for (x in 0 until 16) {
                val d = (x - 7.5) * (x - 7.5) + (y - 7.5) * (y - 7.5)
                img.argb[y * 16 + x] = when {
                    d <= 12.0 -> fill
                    d <= 30.0 -> 0xFFFFFFFF.toInt()
                    d <= 56.0 -> 0xFF181820.toInt()
                    else -> 0
                }
            }
            write(dir, "regionmap/$name.png", img.png())
        }
    }

    /** The map's height: rows under it are empty. */
    const val MAP_HEIGHT = 168

    /** LZ10 / LZ11 (the DS BIOS's), or the bytes as they are. */
    internal fun lz(src: ByteArray): ByteArray {
        if (src.isEmpty() || (src[0].toInt() != 0x10 && src[0].toInt() != 0x11)) return src
        val kind = src[0].toInt()
        var size = (src[1].toInt() and 255) or ((src[2].toInt() and 255) shl 8) or ((src[3].toInt() and 255) shl 16)
        var p = 4
        if (size == 0) { size = w2(src, 4) or (w2(src, 6) shl 16); p = 8 }
        val out = ByteArray(size)
        var n = 0
        fun u(i: Int) = src[i].toInt() and 255
        while (n < size) {
            val flags = u(p++)
            for (bit in 7 downTo 0) {
                if (n >= size) break
                if (flags and (1 shl bit) == 0) { out[n++] = src[p++]; continue }
                val a = u(p); val b = u(p + 1); p += 2
                val length: Int
                val distance: Int
                if (kind == 0x10) { length = (a shr 4) + 3; distance = ((a and 15) shl 8 or b) + 1 }
                else when (a shr 4) {
                    0 -> { val c = u(p++); length = ((a and 15) shl 4 or (b shr 4)) + 0x11; distance = ((b and 15) shl 8 or c) + 1 }
                    1 -> { val c = u(p); val d = u(p + 1); p += 2; length = ((a and 15) shl 12 or (b shl 4) or (c shr 4)) + 0x111; distance = ((c and 15) shl 8 or d) + 1 }
                    else -> { length = (a shr 4) + 1; distance = ((a and 15) shl 8 or b) + 1 }
                }
                require(distance <= n) { "bad LZ back-reference" }
                repeat(length) { if (n < size) { out[n] = out[n - distance]; n++ } }
            }
        }
        return out
    }

    private fun w2(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)

    private fun indexOf(b: ByteArray, magic: String): Int? {
        val m = magic.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..b.size - m.size) {
            for (j in m.indices) if (b[i + j] != m[j]) continue@outer
            return i
        }
        return null
    }

    private fun write(dir: File, path: String, bytes: ByteArray) {
        val out = File(dir, path)
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(out)
    }
}
