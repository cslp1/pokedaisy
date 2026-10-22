package com.pokedaisy.app.companion.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * A DS game's POKéDEX (Platinum, HeartGold / SoulSilver): its pages and front pictures, which [NdsArt] takes from the
 * player's own cart into rom-art once ([NdsArt.PLATINUM_DEX], [NdsArt.HEARTGOLD_DEX] ...); the seen / caught flags from
 * the save ([readGen4PokedexState]). Species ids are National Dex numbers (1-493).
 */
class Gen4DexTables(
    /** The pages' file and the pictures' folder under rom-art. */
    val pages: String,
    val fronts: String,
    /** Each machine's move: TM01..TM92, then HM01..HM08 (the games' HM05 differ). */
    val tmMoves: IntArray,
    val speciesMax: Int = 493,
    val machineCount: Int = 92,
    val gen5: Boolean = false,
)

val GEN4_DEX_PLATINUM = Gen4DexTables(NdsArt.PLATINUM_DEX, NdsArt.PLATINUM_FRONT, tmMovesPlatinum)
val GEN4_DEX_HEARTGOLD = Gen4DexTables(NdsArt.HEARTGOLD_DEX, NdsArt.HEARTGOLD_FRONT, tmMovesHeartGold)

/** Platinum's dex: Sinnoh's 210 (their order from the cart, [Gen4Dex.regionalOrder]) and the National Dex's 493, in
 * feet and pounds as the US game prints its pages. */
val POKEDEX_PLATINUM = PokedexTables(
    entries = 0, frontPics = 0, palettes = 0, speciesInfo = 0, speciesToNational = 0, abilityNames = 0, footprints = 0,
    speciesCount = 494, nationalCount = 493, regionalCount = 210, regionName = "SINNOH",
    gen4 = GEN4_DEX_PLATINUM, categorySuffix = "Pokémon",
)

/** HeartGold / SoulSilver's dex: Johto's 256 (their order from the cart, [Gen4Dex.regionalOrder]) and the National Dex's
 * 493, in feet and pounds as the US games print their pages. */
val POKEDEX_HEARTGOLD = PokedexTables(
    entries = 0, frontPics = 0, palettes = 0, speciesInfo = 0, speciesToNational = 0, abilityNames = 0, footprints = 0,
    speciesCount = 494, nationalCount = 493, regionalCount = 256, regionName = "JOHTO",
    gen4 = GEN4_DEX_HEARTGOLD, categorySuffix = "Pokémon",
)

/** Black 2's pages and pictures ([Gen5Art], from the cart): 649 species, TM01..TM95 then HM01..HM06, Gen 5's
 * evolution methods. */
val GEN5_DEX_BLACK2 = Gen4DexTables(Gen5Art.DEX, Gen5Art.FRONT, black2MachineMoves, speciesMax = 649, machineCount = 95, gen5 = true)

/** Black 2's dex: Unova's 300 (their order from the cart's personal data) and the National Dex's 649, in feet and
 * pounds as the English cart prints its pages. */
val POKEDEX_BLACK2 = PokedexTables(
    entries = 0, frontPics = 0, palettes = 0, speciesInfo = 0, speciesToNational = 0, abilityNames = 0, footprints = 0,
    speciesCount = 650, nationalCount = 649, regionalCount = 300, regionName = "UNOVA",
    gen4 = GEN5_DEX_BLACK2, categorySuffix = "Pokémon",
)

/** A dex read from the cart (a Game Boy's bytes, a DS game's files), not a GBA ROM's tables. */
val PokedexTables.cartDex: Boolean get() = gameBoy || gen4 != null

object Gen4Dex {
    /** One cart's pages and the rest of each species' row, as NdsArt wrote them. */
    internal class Pages(
        val entries: Map<Int, DexEntry>,
        /** National numbers in the regional Dex's order (Sinnoh's, Johto's). */
        val regional: IntArray,
        val evolutions: Map<Int, List<EvoLink>>,
        val levelUp: Map<Int, List<LevelMove>>,
        /** TM01 .. TM92 then HM01 .. HM08 a bit each, low bit first. */
        val machines: Map<Int, ByteArray>,
    )

    @Volatile private var pagesFor: Pair<String, Long>? = null
    @Volatile private var pages: Pages? = null

    /** No.[national]'s page, or null before the cart's pages are in. */
    fun entry(t: Gen4DexTables, national: Int): DexEntry? = load(t)?.entries?.get(national)

    /** The regional Dex's order (national numbers), once loaded; [regionalOrder] loads it. */
    fun cachedRegionalOrder(t: Gen4DexTables): IntArray? = pages?.takeIf { pagesFor?.first == pathOf(t) }?.regional

    fun regionalOrder(t: Gen4DexTables): IntArray? = load(t)?.regional?.takeIf { it.isNotEmpty() }

    fun evolutions(t: Gen4DexTables): Map<Int, List<EvoLink>>? = load(t)?.evolutions?.takeIf { it.isNotEmpty() }

    fun levelUp(t: Gen4DexTables, species: Int): List<LevelMove>? = load(t)?.levelUp?.get(species)

    /** [species]' TMs and HMs ("TM06", the move), from the cart's bits and the bag's machine list. */
    fun machines(t: Gen4DexTables, species: Int): List<TeachMove>? {
        val bits = load(t)?.machines?.get(species) ?: return null
        return t.tmMoves.indices.filter { i -> i / 8 < bits.size && (bits[i / 8].toInt() shr (i % 8)) and 1 != 0 }.map { i ->
            TeachMove(if (i < t.machineCount) "TM%02d".format(i + 1) else "HM%02d".format(i - t.machineCount + 1), t.tmMoves[i])
        }
    }

    private fun pathOf(t: Gen4DexTables): String? = NdsArt.dir?.let { File(it, t.pages).path }

    private fun load(t: Gen4DexTables): Pages? {
        val dir = NdsArt.dir ?: return null
        val f = File(dir, t.pages)
        if (!f.isFile) return null
        val key = f.path to f.lastModified()
        if (pagesFor != key) synchronized(this) {
            if (pagesFor != key) {
                pages = runCatching { parse(f.readLines(), t) }.getOrNull()
                pagesFor = key
            }
        }
        return pages
    }

    /** The front picture (80 x 80, the dex page shows it whole). */
    fun front(t: Gen4DexTables, species: Int): Bitmap? {
        val dir = NdsArt.dir ?: return null
        val f = File(dir, "${t.fronts}/$species.png")
        return if (f.isFile) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
    }

    /** NdsArt's lines: species, category, description, height dm, weight hg, the six stats (the page's order), type 1 / 2,
     * catch rate, gender ratio, egg groups 1 / 2, abilities 1 / 2; then (a cart read since v8) the regional number, the
     * evolutions, the level-up moves and the TM / HM bits. */
    internal fun parse(lines: List<String>, config: Gen4DexTables? = null): Pages {
        val entries = HashMap<Int, DexEntry>()
        val regional = HashMap<Int, Int>()
        val evolutions = HashMap<Int, List<EvoLink>>()
        val levelUp = HashMap<Int, List<LevelMove>>()
        val machines = HashMap<Int, ByteArray>()
        for (line in lines) {
            val c = line.split('\t')
            if (c.size < 19) continue
            val n = c[0].toIntOrNull() ?: continue
            fun i(k: Int) = c.getOrNull(k)?.toIntOrNull() ?: 0
            entries[n] = DexEntry(
                national = n,
                species = n,
                category = c[1].removeSuffix(" Pokémon").removeSuffix(" POKéMON"),
                heightDm = i(3),
                weightHg = i(4),
                description = c[2].replace("\\n", " "),
                type1 = i(11),
                type2 = i(12),
                baseStats = (5..10).map(::i),
                hiddenAbility = c.getOrNull(23)?.takeIf { it.isNotBlank() },
                abilities = listOf(c[17], c[18]).filter { it.isNotBlank() }.distinct(),
                catchRate = i(13),
                genderRatio = i(14),
                eggGroups = listOf(i(15), i(16)).distinct().mapNotNull { eggGroupName(it) },
            )
            if (i(19) > 0) regional[i(19)] = n
            c.getOrNull(20)?.takeIf { it.isNotEmpty() }?.split(';')?.mapNotNull { e ->
                val (m, p, to) = e.split(',').map { it.toIntOrNull() ?: return@mapNotNull null }.takeIf { it.size == 3 } ?: return@mapNotNull null
                if (to !in 1..(config?.speciesMax ?: 493) || to == n) null else EvoLink(n, to, if (config?.gen5 == true && m == 7) listOf(EvoReq.Trade, EvoReq.Special) else evoReqs(if (config?.gen5 == true && m >= 8) m - 1 else m, p))
            }?.distinct()?.takeIf { it.isNotEmpty() }?.let { evolutions[n] = it }
            c.getOrNull(21)?.takeIf { it.isNotEmpty() }?.split(',')?.mapNotNull { e ->
                val (lv, mv) = e.split(':').map { it.toIntOrNull() ?: return@mapNotNull null }.takeIf { it.size == 2 } ?: return@mapNotNull null
                if (mv == 0 || lv > 100) null else LevelMove(lv, mv)
            }?.let { levelUp[n] = it }
            c.getOrNull(22)?.takeIf { it.length >= 2 && it.length % 2 == 0 }?.let { hex ->
                runCatching { ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
            }?.let { machines[n] = it }
        }
        val order = regional.keys.sorted().let { nums -> IntArray(nums.size) { regional.getValue(nums[it]) } }
        return Pages(entries, order, evolutions, levelUp, machines)
    }

    /** pokeheartgold's EVO_* (include/constants/pokemon.h; pokeplatinum's generated/evolution_methods.txt: the same): Gen 3's 1-15, then 16 / 17 a stone on a male / female, 18 /
     * 19 holding an item by day / night, 20 knowing a move, 21 another Pokémon in the party, 22 / 23 a level as a male /
     * female, 24-26 levelling up at a place (Sinnoh's magnetic field, moss rock, ice rock). */
    private fun evoReqs(m: Int, p: Int): List<EvoReq> {
        val level = if (p in 1..100) EvoReq.Level(p) else EvoReq.LevelUp
        return when (m) {
            1 -> listOf(EvoReq.LevelUp, EvoReq.Friendship)
            2 -> listOf(EvoReq.LevelUp, EvoReq.Friendship, EvoReq.Time(EvoTime.DAY))
            3 -> listOf(EvoReq.LevelUp, EvoReq.Friendship, EvoReq.Time(EvoTime.NIGHT))
            4, 13 -> listOf(level)
            5 -> listOf(EvoReq.Trade)
            6 -> listOf(EvoReq.Trade, EvoReq.Hold(p))
            7 -> listOf(EvoReq.UseItem(p))
            8 -> listOf(level, EvoReq.Stats(1))
            9 -> listOf(level, EvoReq.Stats(0))
            10 -> listOf(level, EvoReq.Stats(-1))
            11, 12 -> listOf(level, EvoReq.Random)
            14 -> listOf(level, EvoReq.SpareSlot)
            15 -> listOf(EvoReq.LevelUp, EvoReq.Beauty(p))
            16 -> listOf(EvoReq.UseItem(p), EvoReq.Gender(female = false))
            17 -> listOf(EvoReq.UseItem(p), EvoReq.Gender(female = true))
            18 -> listOf(EvoReq.LevelUp, EvoReq.Hold(p), EvoReq.Time(EvoTime.DAY))
            19 -> listOf(EvoReq.LevelUp, EvoReq.Hold(p), EvoReq.Time(EvoTime.NIGHT))
            20 -> listOf(EvoReq.LevelUp, EvoReq.KnowsMove(p))
            21 -> listOf(EvoReq.LevelUp, EvoReq.SpeciesInParty(p))
            22 -> listOf(level, EvoReq.Gender(female = false))
            23 -> listOf(level, EvoReq.Gender(female = true))
            24, 25, 26 -> listOf(EvoReq.LevelUp, EvoReq.SomePlace)
            else -> listOf(EvoReq.Special)
        }
    }
}

/**
 * The save's Pokédex ([Gen4Config.pokedexOff] into the SaveData's body: magic 0xBEEFCAFE, caught then seen - a bit per
 * species, No.1 first - and the National Dex's byte at [Gen4Config.nationalDexOff]): null before the save is loaded.
 */
fun readGen4PokedexState(r: MemoryReader, cfg: Gen4Config, body: Long): PokedexState? {
    val off = cfg.pokedexOff ?: return null
    val tables = cfg.pokedexTables ?: return null
    val d = r.readCoreMemory(body + off, 0x340)
    if (d.size < 0x340) return null
    val magic = (d[0].toLong() and 0xFF) or ((d[1].toLong() and 0xFF) shl 8) or ((d[2].toLong() and 0xFF) shl 16) or ((d[3].toLong() and 0xFF) shl 24)
    if (magic != 0xBEEFCAFEL) return null
    fun bit(base: Int, n: Int) = (d[base + (n - 1) / 8].toInt() shr ((n - 1) % 8)) and 1 != 0
    val seen = (1..493).filter { bit(4 + 64, it) }.toSet()
    val caught = (1..493).filter { bit(4, it) && it in seen }.toSet()
    return PokedexState(tables, seen, caught, national = d[cfg.nationalDexOff].toInt() != 0)
}
