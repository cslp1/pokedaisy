package com.pokedaisy.app.companion.data

import java.io.RandomAccessFile

/**
 * The English Black 2 cart's own tables (IREO rev 0), read at launch. No game assets are bundled: names, descriptions,
 * pockets, places, zones, wild Pokémon, trainers' teams and the Town Map's places all come from the player's cart.
 * File paths and record layouts are pokebw2's (configure.py, tools/data/: item_data, zone_data, encounter_data,
 * trainer_data, town_map_data).
 */
object Gen5RomTables {
    /** A zone header (a/0/1/2, 0x30 bytes): its place name, wild table (0x1FFF = none) and parent zone. */
    data class Zone(val place: Int, val encounters: Int, val parent: Int, val mapType: Int)

    /** One of the Town Map's places (a/0/8/5, 27 u16s): its zone, centre and hit capsule on the 256 x 168 map. */
    data class TownPlace(val zone: Int, val x: Int, val y: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val radius: Int, val fly: Boolean)

    data class Tables(
        val species: Map<Int, String> = emptyMap(),
        val moves: Map<Int, MoveInfo> = emptyMap(),
        val items: Map<Int, String> = emptyMap(),
        val types: Map<Int, SpeciesTypes> = emptyMap(),
        val itemDescriptions: Map<Int, String> = emptyMap(),
        /** Each item's bag pocket as the companion's POCKET_*. */
        val itemPockets: Map<Int, Int> = emptyMap(),
        val abilities: List<String> = emptyList(),
        /** Place names (text bank 109), by a zone's place id. */
        val places: List<String> = emptyList(),
        val zones: List<Zone> = emptyList(),
        /** a/1/2/7's tables, by a zone's encounter id: 0xE8 bytes, or four of them (a season each). */
        val encounters: List<ByteArray> = emptyList(),
        /** Every trainer's team (a/0/9/1 + a/0/9/2), by trainer id. */
        val trainers: Map<Int, List<TrainerMon>> = emptyMap(),
        val townPlaces: List<TownPlace> = emptyList(),
        /** Each zone's Town Map place index, or -1. */
        val zoneTownPlace: IntArray = IntArray(0),
        val map: RegionMapModel? = null,
    ) {
        /** The place name a zone shows on entry, or null. */
        fun zoneName(zone: Int): String? = zones.getOrNull(zone)?.let { places.getOrNull(it.place) }?.takeIf { it.isNotBlank() }
    }

    @Volatile var current = Tables()
        private set

    /** For tests and previews that build tables without a cart. */
    internal fun set(t: Tables) { current = t }

    /** Gen 5 removed the unused ??? type: its old slot goes back in for the companion's type chart. */
    fun type(id: Int) = if (id >= 9) id + 1 else id

    /** bag.h's BAG_POCKET_*: items, medicine, TMs / HMs, berries, key items. */
    private val POCKETS = intArrayOf(POCKET_ITEMS, POCKET_MEDICINE, POCKET_TM_HM, POCKET_BERRIES, POCKET_KEY_ITEMS)

    // Text banks of a/0/0/2 (pokebw2's tools/data and tools/text).
    private const val TEXT_ITEM_DESCRIPTIONS = 63
    private const val TEXT_ITEMS = 64
    private const val TEXT_SPECIES = 90
    private const val TEXT_PLACES = 109
    private const val TEXT_ABILITIES = 374
    private const val TEXT_MOVES = 403

    internal fun load(f: RandomAccessFile, header: ByteArray) {
        current = Tables()
        fun archive(path: String) = NdsArt.Narc(requireNotNull(NdsArt.file(f, header, path)))
        val text = archive("/a/0/0/2")
        fun bank(n: Int) = decodeText(requireNotNull(text.member(n)))
        val species = bank(TEXT_SPECIES)
        val items = bank(TEXT_ITEMS)
        val moves = bank(TEXT_MOVES)
        require(species.getOrNull(495) == "Snivy" && moves.getOrNull(33) == "Tackle") { "Unknown Black 2 text tables" }
        val personal = archive("/a/0/1/6")
        val moveData = archive("/a/0/2/1")
        val types = (1..649).associateWith { id ->
            val p = requireNotNull(personal.member(id))
            SpeciesTypes(type(p[6].toInt() and 255), type(p[7].toInt() and 255))
        }
        val moveInfo = (1..559).associateWith { id ->
            val m = requireNotNull(moveData.member(id))
            MoveInfo(moves[id], type(m[0].toInt() and 255), m[3].toInt() and 255)
        }
        // Item data (a/0/2/4, 36 bytes): the u16 at 8 keeps the field pocket in bits 7-10.
        val itemData = archive("/a/0/2/4")
        val pockets = HashMap<Int, Int>()
        for (id in 1 until items.size) {
            val d = itemData.member(id) ?: continue
            if (d.size < 10) continue
            val pocket = (u16(d, 8) shr 7) and 0xF
            POCKETS.getOrNull(pocket)?.let { pockets[id] = it }
        }
        val descriptions = bank(TEXT_ITEM_DESCRIPTIONS)
        val zones = archive("/a/0/1/2").member(0)?.let { z ->
            (0 until z.size / 0x30).map { i ->
                val o = i * 0x30
                Zone(place = u16(z, o + 0x1A) and 0x3FF, encounters = u16(z, o + 0x14) and 0x1FFF, parent = u16(z, o + 0x18), mapType = z[o].toInt() and 255)
            }
        }.orEmpty()
        val encounterArchive = archive("/a/1/2/7")
        val encounters = (0 until 2048).map { encounterArchive.member(it) ?: return@map null }.takeWhile { it != null }.map { it!! }
        val places = bank(TEXT_PLACES)
        val townPlaces = archive("/a/0/8/5").member(0)?.let { t ->
            (0 until t.size / 54).map { i ->
                val o = i * 54
                fun w(k: Int) = u16(t, o + k * 2)
                TownPlace(w(0), w(2), w(3), w(6), w(7), w(8), w(9), w(10), w(12) != 0)
            }
        }.orEmpty()
        val tables = Tables(
            species = (1..649).associateWith { species[it] }, moves = moveInfo,
            items = items.mapIndexed { id, name -> id to name }.toMap(), types = types,
            itemDescriptions = descriptions.mapIndexed { id, d -> id to d.replace('\n', ' ') }.filter { it.second.isNotBlank() }.toMap(),
            itemPockets = pockets,
            abilities = bank(TEXT_ABILITIES),
            places = places,
            zones = zones,
            encounters = encounters,
            trainers = trainers(archive("/a/0/9/1"), archive("/a/0/9/2")),
            townPlaces = townPlaces,
        )
        current = withTownMap(tables)
    }

    /** The Town Map ([Gen5Art] draws its picture): each place a section, its capsule as a box of 4-pixel tiles. */
    internal fun withTownMap(t: Tables): Tables {
        if (t.townPlaces.isEmpty()) return t
        val sections = HashMap<Int, MapSecInfo>()
        for ((i, p) in t.townPlaces.withIndex()) {
            val name = t.zoneName(p.zone) ?: continue
            val l = (minOf(p.x0, p.x1) - p.radius / 2).coerceAtLeast(0) / MAP_TILE
            val r = (maxOf(p.x0, p.x1) + p.radius / 2) / MAP_TILE
            val top = (minOf(p.y0, p.y1) - p.radius / 2).coerceAtLeast(0) / MAP_TILE
            val b = (maxOf(p.y0, p.y1) + p.radius / 2) / MAP_TILE
            sections[i] = MapSecInfo(name, 0, l, top, maxOf(1, r - l), maxOf(1, b - top))
        }
        val byZone = t.townPlaces.withIndex().associate { (i, p) -> p.zone to i }
        val byPlace = t.townPlaces.withIndex().groupBy({ t.zones.getOrNull(it.value.zone)?.place }, { it.index })
        val zoneTownPlace = IntArray(t.zones.size) { zone ->
            var z = zone
            var found = -1
            for (step in 0 until 4) {
                found = byZone[z] ?: -1
                if (found >= 0) break
                val parent = t.zones.getOrNull(z)?.parent ?: break
                if (parent == z || parent >= t.zones.size) break
                z = parent
            }
            if (found < 0) found = byPlace[t.zones[zone].place]?.firstOrNull() ?: -1
            found
        }
        val model = RegionMapModel(arrayOf(Gen5Art.TOWN_MAP), sections, listOf(null), tile = MAP_TILE)
        return t.copy(zoneTownPlace = zoneTownPlace, map = model)
    }

    /** The Town Map's tile, in pixels: its places' hit capsules boxed in 4 x 4 cells. */
    const val MAP_TILE = 4

    /** The player's Town Map place and tile for [zone]: the place's own centre. */
    fun townMapSpot(zone: Int): Pair<Int, Pair<Int, Int>>? {
        val t = current
        val place = t.zoneTownPlace.getOrNull(zone)?.takeIf { it >= 0 } ?: return null
        val p = t.townPlaces[place]
        return place to (p.x / MAP_TILE to p.y / MAP_TILE)
    }

    /** trainer_data.py: a record (kind, class, style, count, ...) and a party of 8-byte Pokémon, plus an item (kind bit
     * 1) and four moves (kind bit 0). */
    private fun trainers(records: NdsArt.Narc, parties: NdsArt.Narc): Map<Int, List<TrainerMon>> {
        val out = HashMap<Int, List<TrainerMon>>()
        var id = 1
        while (true) {
            val r = records.member(id) ?: break
            val p = parties.member(id) ?: break
            id++
            if (r.size < 4) continue
            val kind = r[0].toInt() and 255
            val count = r[3].toInt() and 255
            val size = 8 + (if (kind and 2 != 0) 2 else 0) + (if (kind and 1 != 0) 8 else 0)
            if (count == 0 || p.size < size * count) continue
            out[id - 1] = (0 until count).map { i ->
                val o = i * size
                var k = o + 8
                val item = if (kind and 2 != 0) u16(p, k).also { k += 2 } else 0
                val moves = if (kind and 1 != 0) (0 until 4).map { u16(p, k + it * 2) }.filter { it != 0 } else emptyList()
                TrainerMon(u16(p, o + 4), p[o + 2].toInt() and 255, item, moves)
            }
        }
        return out
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)

    /** Gen 5 text bank: section-relative string offsets, UTF-16 words, a rotating XOR key.
     * The encrypted terminator reveals the last key; walking backwards undoes the rotation. 0xFFFE is a new line;
     * 0xF000 starts a control sequence (command, argument count, arguments), dropped. */
    internal fun decodeText(data: ByteArray): List<String> {
        fun u16(o: Int): Int {
            require(o >= 0 && o <= data.size - 2)
            return (data[o].toInt() and 255) or ((data[o + 1].toInt() and 255) shl 8)
        }
        fun u32(o: Int): Int {
            val n = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
            require(n <= Int.MAX_VALUE)
            return n.toInt()
        }
        require(u16(0) >= 1)
        val count = u16(2)
        val section = u32(12)
        require(count <= (data.size - section - 4) / 8)
        return List(count) { i ->
            val start = section + u32(section + 4 + i * 8)
            val length = u16(section + 8 + i * 8)
            require(length > 0 && start >= section && start <= data.size - length * 2)
            var key = u16(start + (length - 1) * 2) xor 0xFFFF
            val chars = IntArray(length - 1)
            for (j in length - 1 downTo 0) {
                val c = u16(start + j * 2) xor key
                if (j < chars.size) chars[j] = c
                key = ((key ushr 3) or (key shl 13)) and 0xFFFF
            }
            val sb = StringBuilder()
            var j = 0
            while (j < chars.size) {
                val c = chars[j]
                when {
                    c == 0xF000 -> { j += 3 + (chars.getOrNull(j + 2) ?: 0); continue }
                    c == 0xFFFE -> sb.append('\n')
                    c == 0xFFFF -> break
                    else -> sb.append(c.toChar())
                }
                j++
            }
            sb.toString()
        }
    }
}
