package com.pokedaisy.app.companion.data

import java.io.File
import java.io.RandomAccessFile

/**
 * A DS game's art, rebuilt from the player's own cart like [RomArt] / [Gen2Art] (nothing of the game is bundled):
 * Platinum's party icons and its Town Map ([townMap]). The cart's file system (header FNT / FAT) gives `/poketool/icongra/pl_poke_icon.narc`;
 * its member 0 is the palette file (three 16-colour palettes), members 7 + species the icons - 4bpp, 8x8 tiles, 4 across,
 * 32x64: the two frames of the party menu's bounce, as the GBA games' sheets. Which palette a species takes is
 * [iconPalettesPlatinum] (the game's sPokemonIconPaletteIndex, generated from the decomp). Written under
 * [PLATINUM_ICONS] in rom-art as `<species>.png` (494 = the egg's), what [activeMonSpriteDir] points at. HeartGold /
 * SoulSilver keep the same archive as `/a/0/2/0` (pokeheartgold's NARC_poketool_icongra_poke_icon), its members in the
 * same order, palettes by [iconPalettesHeartGold], under [HEARTGOLD_ICONS] (one set for both: the same icons).
 */
object NdsArt {
    const val PLATINUM_ICONS = "nds/platinum/pokemon"
    const val HEARTGOLD_ICONS = "nds/heartgold/pokemon"
    /** Item icons (the bag's, 32 x 32), from the cart's item_icon.narc by [itemIconsPlatinum] / [itemIconsHeartGold]. */
    const val PLATINUM_ITEMS = "nds/platinum/items"
    const val HEARTGOLD_ITEMS = "nds/heartgold/items"
    /** HeartGold / SoulSilver's bag pocket buttons ([bagPockets]): pocket 0-7 in the game's order, open or not. */
    fun heartGoldPocket(pocket: Int, open: Boolean) = "nds/heartgold/bag/pocket_${pocket}_${if (open) "on" else "off"}"
    /** The DEX pages from the cart ([dex]): a line a species, tab-separated (see [Gen4Dex]). */
    const val HEARTGOLD_DEX = "nds/heartgold/dex.tsv"
    const val PLATINUM_DEX = "nds/platinum/dex.tsv"
    /** Each species' front picture (80 x 80, its first frame) under here as `<species>.png`. */
    const val HEARTGOLD_FRONT = "nds/heartgold/front"
    const val PLATINUM_FRONT = "nds/platinum/front"
    private const val VERSION = 9
    /** The Town Map's art under rom-art/regionmap: the map, each fly location's block sprite and the player's head. */
    const val PLATINUM_MAP = "platinum_sinnoh"
    fun platinumBlock(shape: Int, variant: Int) = "pt_block_${shape}_$variant"
    const val PLATINUM_HEAD_M = "player_lucas"
    const val PLATINUM_HEAD_F = "player_dawn"
    /** The Town Map's map area on the top screen (left, top, right, bottom): its frame and name bar cropped off. */
    val PLATINUM_MAP_CROP = intArrayOf(20, 0, 236, 168)
    private const val EGG = 494

    /** rom-art's folder, once [prefetch] has run (the DEX reads its pages and pictures from it: [Gen4Dex]). */
    @Volatile var dir: File? = null

    /** In the background, once per cart build (a marker keeps its header's game code, revision and [VERSION]). */
    fun prefetch(filesDir: File, rom: File) {
        Thread({
            runCatching {
                PokegearMap.dir = RomArt.dir(filesDir)
                dir = RomArt.dir(filesDir)
                if (extractTo(rom, RomArt.dir(filesDir))) RomArt.announce()
            }.onFailure { android.util.Log.w("pokedaisy", "NdsArt: failed", it) }
        }, "pokedaisy-nds-art").apply { isDaemon = true; start() }
    }

    /** Writes [rom]'s icons into [dir] if it's a cart this knows and they aren't there yet; true when it wrote any. */
    fun extractTo(rom: File, dir: File): Boolean = RandomAccessFile(rom, "r").use { f ->
        val header = f.read(0, 0x200)
        val code = String(header, 0x0C, 4, Charsets.US_ASCII)
        val rev = header[0x1E].toInt() and 0xFF
        val platinum = code == "CPUE" && rev == 1
        val heartGold = (code == "IPKE" || code == "IPGE") && rev == 0
        if (code == "IREO" && rev == 0) {
            Gen5RomTables.load(f, header)
            Gen5Art.extract(f, header, dir)
            return true
        }
        if (!platinum && !heartGold) return false
        val marker = File(dir, if (platinum) "nds/platinum-built" else "nds/heartgold-built")
        val stamp = "v$VERSION $code $rev"
        if (marker.isFile && runCatching { marker.readText() }.getOrNull() == stamp) return false
        val iconPath = if (platinum) "/poketool/icongra/pl_poke_icon.narc" else "/a/0/2/0"
        val iconPalettes = if (platinum) iconPalettesPlatinum else iconPalettesHeartGold
        val iconDir = if (platinum) PLATINUM_ICONS else HEARTGOLD_ICONS
        val narc = Narc(file(f, header, iconPath) ?: return false)
        val palettes = palettes(narc.member(0))
        for (species in 1..EGG) {
            val pal = palettes.getOrNull(iconPalettes.getOrNull(species)?.toInt() ?: 0) ?: continue
            val icon = icon(narc.member(7 + species), pal) ?: continue
            write(dir, "$iconDir/$species.png", icon.png())
        }
        // The bag's item icons: each its own NCGR (32 x 32) and NCLR (one 16-colour palette).
        file(f, header, if (platinum) "/itemtool/itemdata/item_icon.narc" else "/a/0/1/8")?.let { Narc(it) }?.let { items ->
            val itemDir = if (platinum) PLATINUM_ITEMS else HEARTGOLD_ITEMS
            for ((item, packed) in if (platinum) itemIconsPlatinum else itemIconsHeartGold) {
                val pal = palettes(items.member(packed and 0xFFFF)).firstOrNull() ?: continue
                val icon = icon(items.member(packed ushr 16), pal, height = 32) ?: continue
                write(dir, "$itemDir/$item.png", icon.png())
            }
        }
        if (platinum) {
            file(f, header, "/graphic/tmap_gra.narc")?.let { townMap(Narc(it), dir) }
            dex(f, header, dir, DEX_PLATINUM)
        } else {
            file(f, header, "/a/1/4/4")?.let { pokegear(Narc(it), dir) }
            file(f, header, "/a/0/1/5")?.let { bagPockets(Narc(it), dir) }
            dex(f, header, dir, if (code == "IPGE") DEX_SOULSILVER else DEX_HEARTGOLD)
        }
        marker.parentFile?.mkdirs()
        marker.writeText(stamp)
        true
    }

    /**
     * Platinum's Town Map (town_map/main.c, graphics.c), as its top screen draws it: the region background and the map
     * (two tilemaps over one 4bpp tile set and palette) into regionmap/[PLATINUM_MAP] (cropped to [PLATINUM_MAP_CROP]),
     * each fly location's 16x16 block sprite in its three palettes (5 not visited yet, 6 a town's blue, 7 a city's red:
     * fly_locations.c's `5 + palette + isUnlocked`) and the player's two heads (Lucas, Dawn), each in its own palette.
     */
    private fun townMap(narc: Narc, dir: File) {
        val mapPal = palettes(narc.member(0)).flatMap { it.toList() }.toIntArray()
        val sprPal = palettes(narc.member(2))
        val tiles = ncgrData(narc.member(19)) ?: return
        val img = RomArt.Image(256, 192)
        img.argb.fill(mapPal.getOrElse(0) { 0xFF000000.toInt() })
        for (screen in intArrayOf(22, 24)) drawScreen(img, narc.member(screen) ?: return, tiles, mapPal)   // MAIN_3, then MAIN_2
        val (l, t, r, b) = PLATINUM_MAP_CROP.toList()
        val crop = RomArt.Image(r - l, b - t)
        for (y in 0 until crop.height) System.arraycopy(img.argb, (t + y) * 256 + l, crop.argb, y * crop.width, crop.width)
        write(dir, "regionmap/$PLATINUM_MAP.png", crop.png())
        val blockTiles = ncgrData(narc.member(16)) ?: return
        val blocks = ncerCells(narc.member(17) ?: return)
        for ((shape, cell) in blocks.withIndex()) {
            for ((variant, pal) in intArrayOf(5, 6, 7).withIndex()) {
                val sprite = RomArt.Image(16, 16)
                drawCell(sprite, 8, 8, cell, blockTiles, sprPal.getOrNull(pal) ?: continue)
                write(dir, "regionmap/${platinumBlock(shape, variant)}.png", sprite.png())
            }
        }
        val headTiles = ncgrData(narc.member(10)) ?: return
        val heads = ncerCells(narc.member(11) ?: return)
        for ((i, name) in listOf(PLATINUM_HEAD_M, PLATINUM_HEAD_F).withIndex()) {
            val cell = heads.getOrNull(i) ?: continue
            val sprite = RomArt.Image(16, 16)
            drawCell(sprite, 8, 8, cell, headTiles, sprPal.getOrNull(cell.firstOrNull()?.pal ?: 0) ?: continue)
            write(dir, "regionmap/$name.png", sprite.png())
        }
    }

    /** An NCGR's tile bytes (its RAHC block's data). */
    internal fun ncgrData(ncgr: ByteArray?): ByteArray? {
        ncgr ?: return null
        val o = indexOf(ncgr, "RAHC") ?: return null
        val size = u32(ncgr, o + 0x18).toInt()
        val off = o + 8 + u32(ncgr, o + 0x1C).toInt()
        return ncgr.copyOfRange(off, minOf(ncgr.size, off + size))
    }

    /** One 4bpp 8x8 tile's pixel ([x], [y]) colour index. */
    private fun tilePixel(tiles: ByteArray, tile: Int, x: Int, y: Int): Int {
        val i = tile * 32 + y * 4 + x / 2
        if (i !in tiles.indices) return 0
        val b = u8(tiles, i)
        return if (x and 1 == 1) b shr 4 else b and 15
    }

    /** An NSCR (text BG: tile, h / v flip, palette per entry) over [tiles], colour 0 clear, onto the top-left of [img]. */
    private fun drawScreen(img: RomArt.Image, nscr: ByteArray, tiles: ByteArray, pal: IntArray) {
        val o = indexOf(nscr, "NRCS") ?: return
        val cols = u16(nscr, o + 8) / 8
        val rows = u16(nscr, o + 0xA) / 8
        for (ty in 0 until minOf(rows, img.height / 8)) for (tx in 0 until minOf(cols, img.width / 8)) {
            val e = u16(nscr, o + 0x14 + (ty * cols + tx) * 2)
            val tile = e and 0x3FF
            val hf = e and 0x400 != 0
            val vf = e and 0x800 != 0
            val p = e shr 12
            for (y in 0 until 8) for (x in 0 until 8) {
                val v = tilePixel(tiles, tile, if (hf) 7 - x else x, if (vf) 7 - y else y)
                if (v == 0) continue
                pal.getOrNull(p * 16 + v)?.let { img.argb[(ty * 8 + y) * img.width + tx * 8 + x] = it }
            }
        }
    }

    /** One OAM of an NCER cell: its offset from the sprite's centre, size, first tile, palette and flips. */
    private class Oam(val x: Int, val y: Int, val w: Int, val h: Int, val tile: Int, val pal: Int, val hf: Boolean, val vf: Boolean)

    /** An NCER's cells (KBEC), each a list of OAMs; tile numbers in 32-byte units (1D mapping, the boundary these use). */
    private fun ncerCells(ncer: ByteArray): List<List<Oam>> {
        val o = indexOf(ncer, "KBEC") ?: return emptyList()
        val count = u16(ncer, o + 8)
        val extended = u16(ncer, o + 0xA) == 1
        val data = o + 8 + u32(ncer, o + 0xC).toInt()
        val entry = if (extended) 16 else 8
        val oamBase = data + count * entry
        val sizes = arrayOf(
            arrayOf(8 to 8, 16 to 16, 32 to 32, 64 to 64), arrayOf(16 to 8, 32 to 8, 32 to 16, 64 to 32), arrayOf(8 to 16, 8 to 32, 16 to 32, 32 to 64),
        )
        return (0 until count).map { i ->
            val e = data + i * entry
            val n = u16(ncer, e)
            val off = u32(ncer, e + 4).toInt()
            (0 until n).mapNotNull { k ->
                val a = oamBase + off + k * 6
                if (a + 6 > ncer.size) return@mapNotNull null
                val a0 = u16(ncer, a)
                val a1 = u16(ncer, a + 2)
                val a2 = u16(ncer, a + 4)
                val shape = (a0 shr 14) and 3
                if (shape == 3) return@mapNotNull null
                val (w, h) = sizes[shape][(a1 shr 14) and 3]
                val y = (a0 and 0xFF).let { if (it >= 128) it - 256 else it }
                val x = (a1 and 0x1FF).let { if (it >= 256) it - 512 else it }
                Oam(x, y, w, h, a2 and 0x3FF, a2 shr 12, a1 and 0x1000 != 0, a1 and 0x2000 != 0)
            }
        }
    }

    /** [cell]'s OAMs, 4bpp, centred on ([cx], [cy]) in [img], in palette [pal]; the first on top, as the DS draws them. */
    private fun drawCell(img: RomArt.Image, cx: Int, cy: Int, cell: List<Oam>, tiles: ByteArray, pal: IntArray) {
        for (oam in cell.asReversed()) {
            val across = oam.w / 8
            for (py in 0 until oam.h) for (px in 0 until oam.w) {
                val sx = if (oam.hf) oam.w - 1 - px else px
                val sy = if (oam.vf) oam.h - 1 - py else py
                val v = tilePixel(tiles, oam.tile + (sy / 8) * across + sx / 8, sx % 8, sy % 8)
                if (v == 0) continue
                val x = cx + oam.x + px
                val y = cy + oam.y + py
                if (x in 0 until img.width && y in 0 until img.height) img.argb[y * img.width + x] = pal[v]
            }
        }
    }

    private fun write(dir: File, path: String, bytes: ByteArray) {
        val out = File(dir, path)
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(out)
    }

    private fun RandomAccessFile.read(off: Long, len: Int): ByteArray {
        val b = ByteArray(len)
        seek(off)
        readFully(b)
        return b
    }

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16))

    /**
     * HeartGold / SoulSilver's Pokégear map (pgmap_gra, a/1/4/4): its pieces raw, for [PokegearMap] to put together for the
     * save's state - the 8bpp tiles (member 10), the 64 x 64 tilemap the game copies the map and its covers from (11) and
     * the six skins' palettes (20-25) - the player's head on it (cells 2 Ethan / 3 Lyra of member 2 over 1, palette 0) and its
     * cursor (cells 0 / 1).
     */
    private fun pokegear(narc: Narc, dir: File) {
        val tiles = ncgrData(narc.member(10)) ?: return
        val nscr = narc.member(11) ?: return
        val o = indexOf(nscr, "NRCS") ?: return
        write(dir, PokegearMap.TILES, tiles)
        write(dir, PokegearMap.TILEMAP, nscr.copyOfRange(o + 0x14, nscr.size))
        for (skin in 0 until PokegearMap.SKINS) {
            val nclr = narc.member(20 + skin) ?: continue
            val t = indexOf(nclr, "TTLP") ?: continue
            write(dir, PokegearMap.palette(skin), nclr.copyOfRange(t + 0x18, nclr.size))
        }
        val headTiles = ncgrData(narc.member(1)) ?: return
        val cells = ncerCells(narc.member(2) ?: return)
        val pals = palettes(narc.member(0))
        // The map cursor's two frames (cells 0 / 1, 14 and 16 pixels: the sprite pulses between them), centred on 16 x 16.
        for (frame in 0..1) {
            val cell = cells.getOrNull(frame) ?: continue
            val sprite = RomArt.Image(16, 16)
            drawCell(sprite, 8, 8, cell, headTiles, pals.getOrNull(cell.firstOrNull()?.pal ?: 0) ?: continue)
            write(dir, "regionmap/${PokegearMap.cursor(frame)}.png", sprite.png())
        }
        for ((cellIndex, name) in listOf(2 to PokegearMap.HEAD_M, 3 to PokegearMap.HEAD_F)) {
            val cell = cells.getOrNull(cellIndex) ?: continue
            val sprite = RomArt.Image(16, 16)
            drawCell(sprite, 8, 8, cell, headTiles, pals.getOrNull(cell.firstOrNull()?.pal ?: 0) ?: continue)
            write(dir, "regionmap/$name.png", sprite.png())
        }
    }

    /**
     * HeartGold / SoulSilver's bag pocket buttons along the touch screen's top (bag_app_sprite.c's POCKET_ICON_1..8): the
     * bag graphics' (a/0/1/5) animation n of member 50 is pocket n's cell in member 49 over the tiles of 51. Their palettes
     * are member 48's (BagApp_LoadObjPaletteByPocket), button n in palette n: the second eight for every pocket, the open
     * pocket's own from the first eight. Each written in both, the same size, cropped to what any of them covers.
     */
    private fun bagPockets(narc: Narc, dir: File) {
        val tiles = ncgrData(narc.member(51)) ?: return
        val cells = ncerCells(narc.member(49) ?: return)
        val nanr = narc.member(50) ?: return
        val pals = palettes(narc.member(48))
        if (pals.size < 16) return
        val drawn = (0 until 8).flatMap { pocket ->
            val cell = cells.getOrNull(firstCell(nanr, pocket) ?: return) ?: return
            // Button n takes OBJ palette n: the pocket's own colour, lit while open.
            listOf(true, false).map { open ->
                val img = RomArt.Image(64, 64)
                drawCell(img, 32, 32, cell, tiles, pals[if (open) pocket else 8 + pocket])
                Triple(pocket, open, img)
            }
        }
        var l = 64; var t = 64; var r = 0; var b = 0
        for ((_, _, img) in drawn) for (y in 0 until 64) for (x in 0 until 64) if (img.argb[y * 64 + x] != 0) {
            l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x + 1); b = maxOf(b, y + 1)
        }
        if (r <= l || b <= t) return
        for ((pocket, open, img) in drawn) {
            val crop = RomArt.Image(r - l, b - t)
            for (y in 0 until crop.height) System.arraycopy(img.argb, (t + y) * 64 + l, crop.argb, y * crop.width, crop.width)
            write(dir, "${heartGoldPocket(pocket, open)}.png", crop.png())
        }
    }

    /** Where one cart keeps what [dex] reads: file paths and text banks. */
    private class DexFiles(
        val msg: String, val description: Int, val category: Int, val abilities: Int,
        val zukan: String, val personal: String, val regional: String, val learnsets: String, val evolutions: String,
        val pics: String, val pages: String, val fronts: String,
    )

    // pret/pokeheartgold: msgdata a/0/2/7 (803 HeartGold's descriptions, 804 SoulSilver's, 816 categories, 720
    // abilities), the dex data a/0/7/4, personal a/0/0/2, the Johto numbers a/1/3/8, wotbl a/0/3/3, evo a/0/3/4, pokegra a/0/0/4.
    private val DEX_HEARTGOLD = DexFiles("/a/0/2/7", 803, 816, 720, "/a/0/7/4", "/a/0/0/2", "/a/1/3/8", "/a/0/3/3",
        "/a/0/3/4", "/a/0/0/4", HEARTGOLD_DEX, HEARTGOLD_FRONT)
    private val DEX_SOULSILVER = DexFiles("/a/0/2/7", 804, 816, 720, "/a/0/7/4", "/a/0/0/2", "/a/1/3/8", "/a/0/3/3",
        "/a/0/3/4", "/a/0/0/4", HEARTGOLD_DEX, HEARTGOLD_FRONT)
    // pret/pokeplatinum (platinum.us/filesys.csv, generated/text_banks.h): pl_msg's 706 descriptions (the US entries),
    // 711 categories, 610 abilities; zukan_data_gira (Giratina's Altered Forme, the dex's default); the Sinnoh numbers
    // in pl_pokezukan (national_to_regional).
    private val DEX_PLATINUM = DexFiles("/msgdata/pl_msg.narc", 706, 711, 610,
        "/application/zukanlist/zkn_data/zukan_data_gira.narc", "/poketool/personal/pl_personal.narc",
        "/poketool/pl_pokezukan.narc", "/poketool/personal/wotbl.narc", "/poketool/personal/evo.narc",
        "/poketool/pokegra/pl_pokegra.narc", PLATINUM_DEX, PLATINUM_FRONT)

    /**
     * The DEX pages ([DexFiles.pages]) and front pictures ([DexFiles.fronts]), all from the cart ([DexFiles] has where):
     * - text (msgdata, by bank): the description (HeartGold and SoulSilver each their own), the category, ability names -
     *   [Gen4Text];
     * - height (dm) and weight (hg): the dex data's members 0 / 1, a u32 per species;
     * - the personal record (by species, 0x2C bytes): base stats, types, catch rate, gender ratio, egg groups,
     *   abilities, and the TM / HM bits (0x1C-0x2B);
     * - the regional Dex number (member 0, a u16 per species), the evolutions (by species: 7 x {method, param,
     *   target}) and the level-up moves (by species: level << 9 | move, ending 0xFFFF);
     * - the front picture: pokegra member species x 6 + 3 (male; + 2 female where there's no male one), its pixels
     *   unscrambled as the game does (pokepic.c's UnscanPokepic_PtHGSS: each u16 XORed with an LCG seeded by the
     *   first), 160 x 80 linear 4bpp, two frames side by side; the palette species x 6 + 4.
     */
    private fun dex(f: RandomAccessFile, header: ByteArray, dir: File, d: DexFiles) {
        val msg = Narc(file(f, header, d.msg) ?: return)
        val text = msg.member(d.description)?.let { Gen4Text.lines(it) } ?: return
        val category = msg.member(d.category)?.let { Gen4Text.lines(it) } ?: return
        val abilities = msg.member(d.abilities)?.let { Gen4Text.lines(it) } ?: emptyList()
        val zukan = Narc(file(f, header, d.zukan) ?: return)
        val heights = zukan.member(0) ?: return
        val weights = zukan.member(1) ?: return
        val personal = Narc(file(f, header, d.personal) ?: return)
        val johto = Narc(file(f, header, d.regional) ?: return).member(0) ?: return
        val learnsets = Narc(file(f, header, d.learnsets) ?: return)
        val evolutions = Narc(file(f, header, d.evolutions) ?: return)
        val sb = StringBuilder()
        for (species in 1..493) {
            val p = personal.member(species) ?: continue
            if (p.size < 0x18) continue
            fun b(o: Int) = u8(p, o)
            fun w(arr: ByteArray, i: Int) = if (i * 4 + 4 <= arr.size) u32(arr, i * 4).toInt() else 0
            val cells = listOf(
                species.toString(),
                category.getOrElse(species) { "" },
                text.getOrElse(species) { "" }.replace("\n", "\\n"),
                w(heights, species).toString(),
                w(weights, species).toString(),
                // HP, ATTACK, DEFENSE, SP. ATK, SP. DEF, SPEED (the record keeps SPEED fourth)
                b(0).toString(), b(1).toString(), b(2).toString(), b(4).toString(), b(5).toString(), b(3).toString(),
                b(6).toString(), b(7).toString(), b(8).toString(), b(0x10).toString(), b(0x14).toString(), b(0x15).toString(),
                abilities.getOrElse(b(0x16)) { "" }, if (b(0x17) != 0) abilities.getOrElse(b(0x17)) { "" } else "",
                // Its regional Dex number (0: not in it), its evolutions (method,param,target;...), its level-up moves
                // (level:move,...) and its TM / HM bits (TM01 .. TM92, HM01 .. HM08, low bit first) as hex.
                if (species * 2 + 2 <= johto.size) u16(johto, species * 2).toString() else "0",
                evolutions.member(species)?.let { e ->
                    (0 until 7).mapNotNull { i ->
                        if (i * 6 + 6 > e.size) return@mapNotNull null
                        val m = u16(e, i * 6)
                        if (m == 0) null else "$m,${u16(e, i * 6 + 2)},${u16(e, i * 6 + 4)}"
                    }.joinToString(";")
                } ?: "",
                learnsets.member(species)?.let { l ->
                    (0 until l.size / 2).asSequence().map { u16(l, it * 2) }.takeWhile { it != 0xFFFF }
                        .joinToString(",") { "${it shr 9}:${it and 0x1FF}" }
                } ?: "",
                if (p.size >= 0x2C) (0x1C until 0x2C).joinToString("") { "%02x".format(b(it)) } else "",
            )
            sb.append(cells.joinToString("\t") { it.replace("\t", " ") }).append('\n')
        }
        write(dir, d.pages, sb.toString().toByteArray())
        val pics = Narc(file(f, header, d.pics) ?: return)
        for (species in 1..493) {
            val pal = palettes(pics.member(species * 6 + 4)).firstOrNull() ?: continue
            val chars = ncgrData(pics.member(species * 6 + 3))?.takeIf { it.size >= 6400 }
                ?: ncgrData(pics.member(species * 6 + 2))?.takeIf { it.size >= 6400 } ?: continue
            val px = unscanPokepic(chars)
            val img = RomArt.Image(80, 80)
            for (y in 0 until 80) for (x in 0 until 80) {
                val v = u8(px, y * 80 + x / 2).let { if (x and 1 == 1) it shr 4 else it and 15 }
                if (v != 0) img.argb[y * 80 + x] = pal[v]
            }
            write(dir, "${d.fronts}/$species.png", img.png())
        }
    }

    /** pokepic.c's UnscanPokepic_PtHGSS: the first 3200 u16s XORed in turn with an LCG (x 1103515245 + 24691) seeded by
     * the first one's own value, its low half each time. */
    private fun unscanPokepic(data: ByteArray): ByteArray {
        val out = data.copyOf()
        var seed = u16(data, 0).toLong()
        for (i in 0 until 3200) {
            val v = u16(out, i * 2) xor (seed and 0xFFFF).toInt()
            out[i * 2] = v.toByte()
            out[i * 2 + 1] = (v shr 8).toByte()
            seed = (seed * 1103515245L + 24691L) and 0xFFFFFFFFL
        }
        return out
    }

    /** An NANR's animation [anim]: its first frame's cell (KNBA: the animations, their frames, the frames' cell data). */
    private fun firstCell(nanr: ByteArray, anim: Int): Int? {
        val o = indexOf(nanr, "KNBA") ?: return null
        val base = o + 8
        if (anim >= u16(nanr, base)) return null
        val anims = base + u32(nanr, base + 4).toInt()
        val frames = base + u32(nanr, base + 8).toInt()
        val positions = base + u32(nanr, base + 12).toInt()
        val frame = frames + u32(nanr, anims + anim * 16 + 12).toInt()
        val pos = positions + u32(nanr, frame).toInt()
        return if (pos + 2 <= nanr.size) u16(nanr, pos) else null
    }

    /** A file in the cart's file system by its path: the FNT walked a directory at a time, the FAT for its bytes. */
    internal fun file(f: RandomAccessFile, header: ByteArray, path: String): ByteArray? {
        val fnt = f.read(u32(header, 0x40), u32(header, 0x44).toInt())
        val fatOff = u32(header, 0x48)
        var id = 0xF000
        for (name in path.trim('/').split('/')) {
            val entry = (id and 0xFFF) * 8
            var p = u32(fnt, entry).toInt()
            var fileId = u16(fnt, entry + 4)
            var found = -1
            while (true) {
                val len = u8(fnt, p++)
                if (len == 0) break
                val isDir = len and 0x80 != 0
                val n = len and 0x7F
                val entryName = String(fnt, p, n, Charsets.US_ASCII)
                p += n
                if (isDir) {
                    val sub = u16(fnt, p)
                    p += 2
                    if (entryName == name) { found = sub; break }
                } else {
                    if (entryName == name) { found = fileId; break }
                    fileId++
                }
            }
            if (found < 0) return null
            id = found
        }
        val fat = f.read(fatOff + id * 8L, 8)
        val start = u32(fat, 0)
        val end = u32(fat, 4)
        if (end <= start || end - start > 32L shl 20) return null
        return f.read(start, (end - start).toInt())
    }

    /** A NARC's members: BTAF (start / end offsets), BTNF, then GMIF holding the bytes. */
    internal class Narc(val d: ByteArray) {
        private val count: Int
        private val fat: Int
        private val base: Int

        init {
            require(String(d, 0, 4, Charsets.US_ASCII) == "NARC") { "not a NARC" }
            var o = u16(d, 0x0C)
            fat = o + 12
            count = u16(d, o + 8)
            o += u32(d, o + 4).toInt()
            o += u32(d, o + 4).toInt()
            base = o + 8
        }

        fun member(i: Int): ByteArray? {
            if (i !in 0 until count) return null
            val s = u32(d, fat + i * 8).toInt()
            val e = u32(d, fat + i * 8 + 4).toInt()
            return d.copyOfRange(base + s, base + e)
        }
    }

    /** An NCLR's palettes: TTLP's 16-colour BGR555 rows, as ARGB. */
    internal fun palettes(nclr: ByteArray?): List<IntArray> {
        nclr ?: return emptyList()
        val o = indexOf(nclr, "TTLP") ?: return emptyList()
        val data = o + 0x18
        val n = (nclr.size - data) / 32
        return (0 until n).map { p ->
            IntArray(16) { c ->
                val v = u16(nclr, data + (p * 16 + c) * 2)
                val r = (v and 31) * 255 / 31
                val g = ((v shr 5) and 31) * 255 / 31
                val b = ((v shr 10) and 31) * 255 / 31
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /** An NCGR icon: 4bpp 8x8 tiles, 4 across, 32 wide and [height] tall (a party icon's two frames: 64; an item: 32);
     * colour 0 clear. */
    internal fun icon(ncgr: ByteArray?, pal: IntArray, height: Int = 64): RomArt.Image? {
        ncgr ?: return null
        val o = indexOf(ncgr, "RAHC") ?: return null
        val px = o + 0x20
        if (ncgr.size < px + 32 * height / 2) return null
        val img = RomArt.Image(32, height)
        for (i in 0 until 32 * height) {
            val b = u8(ncgr, px + i / 2)
            val v = if (i and 1 == 1) b shr 4 else b and 15
            if (v == 0) continue
            val tile = i / 64
            val within = i % 64
            val x = (tile % 4) * 8 + within % 8
            val y = (tile / 4) * 8 + within / 8
            img.argb[y * 32 + x] = pal[v]
        }
        return img
    }

    private fun indexOf(b: ByteArray, magic: String): Int? {
        val m = magic.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..b.size - m.size) {
            for (j in m.indices) if (b[i + j] != m[j]) continue@outer
            return i
        }
        return null
    }
}
