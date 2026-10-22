package com.pokedaisy.app.companion.data

/**
 * Pokémon Black 2 (English, IREO rev 0) in DS mode: the companion's reads of main RAM. Read only, no game code is run.
 *
 * The save lives in RAM as the save file's block (pokebw2's include/save/, PKHeX's SaveBlockAccessor5B2W2): SaveControl
 * ([BLACK2_SAVE_CONTROL]) + 0x10 -> SaveData, whose + 0x34 is the buffer. Its blocks: bag 0x18400, party 0x18E00, the
 * player's info 0x19400, event work 0x1FF00, money / badges 0x21100, Pokédex 0x21400. The live position is GameSystem
 * ([BLACK2_GAME_SYSTEM]) + 0x1C -> GameData, whose PlayerState (+ 0x114) is { u16 zone, u16, fx32 x, y, z }.
 *
 * Battles (battle overlay 167): its .bss word [BLACK2_BATTLE_MAIN] points at the BtlMainModule while the overlay is
 * in; checked by its first pokeCon pointing back at it. Its pokeCons[0] (+ 0xC8) holds the four clients' BattleParty
 * { BattleMon *mons[6]; u8 count } (0x1C each) - client 0 the player, 1 the foe - whose BattleMons (0x224 bytes, laid out
 * in btl_pokeparam.h) are the live battlers, the party's first ones out. Checked on headless captures of the rival's
 * battle at the Aspertia lookout (TEPIG vs OSHAWOTT, before and after a turn).
 */
internal const val BLACK2_SAVE_CONTROL = 0x0209A338L
internal const val BLACK2_GAME_SYSTEM = 0x021410F4L
internal const val BLACK2_BATTLE_MAIN = 0x021DD87CL
/** The save buffer's party count, where it sits in practice (the buffer is at 0x022055E4): the old fixed read. */
internal const val BLACK2_PARTY_COUNT = 0x0221E3E8L
internal const val GEN5_PARTY_MON_SIZE = 0xDC

internal const val B2_BAG = 0x18400
internal const val B2_PARTY = 0x18E00
internal const val B2_PLAYER = 0x19400
internal const val B2_EVENT_WORK = 0x1FF00
internal const val B2_GAME_INFO = 0x21100
internal const val B2_POKEDEX = 0x21400

/** event_work.h: 431 vars from 0x4000, then the flags. Trainer flags are 0x5F0 + the trainer (TrainerFlagGet). */
internal const val B2_VAR_COUNT = 431
internal const val B2_FLAG_BYTES = 3064 / 8
const val B2_TRAINER_FLAGS = 0x5F0

/** The bag's pockets (BagSave): items, key items, TMs / HMs, medicine, berries, and their companion pockets. */
private val B2_POCKET_SIZES = intArrayOf(310, 83, 109, 48, 64)
private val B2_POCKETS = intArrayOf(POCKET_ITEMS, POCKET_KEY_ITEMS, POCKET_TM_HM, POCKET_MEDICINE, POCKET_BERRIES)

private const val MAIN_RAM = 0x02000000L
private const val MAIN_RAM_END = 0x02400000L
private fun inMain(p: Long) = p in MAIN_RAM until MAIN_RAM_END
private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
private fun u16(b: ByteArray, o: Int) = u16le(b, o)
private fun u32(b: ByteArray, o: Int) = u32le(b, o)
private val EMPTY_BATTLE_MON = BattleMon(0, 0, TYPE_NONE, TYPE_NONE, 0, 0, 0, IntArray(4), IntArray(4))

fun decodeGen5PartyMon(raw: ByteArray, off: Int = 0): Mon? = decodeDsPartyMon(raw, off, gen5 = true)

/** The save buffer in RAM, or -1 before the game has one (boot, title screen). */
fun black2SaveBuffer(r: MemoryReader): Long {
    val sc = u32(r.readCoreMemory(BLACK2_SAVE_CONTROL, 4), 0)
    if (!inMain(sc)) return -1
    val sd = u32(r.readCoreMemory(sc + 0x10, 4), 0)
    if (!inMain(sd)) return -1
    val buf = u32(r.readCoreMemory(sd + 0x34, 4), 0)
    return if (buf in MAIN_RAM until MAIN_RAM_END - 0x26000) buf else -1
}

/** Each party slot's last read that decoded. */
private val lastGoodParty = arrayOfNulls<Mon>(PARTY_SIZE)

/** The party: read twice, a slot counted only when both reads agree (the game decrypts a Pokémon in place while it
 * looks at it), else its last good read of the same Pokémon. */
internal fun readGen5Party(r: MemoryReader, countAddr: Long): List<Mon> {
    val size = 4 + PARTY_SIZE * GEN5_PARTY_MON_SIZE
    val first = r.readCoreMemory(countAddr, size)
    val second = r.readCoreMemory(countAddr, size)
    if (first.size < size || second.size < size) return emptyList()
    val count = u8(first, 0).takeIf { it <= PARTY_SIZE && first[0] == second[0] } ?: return emptyList()
    return (0 until count).mapNotNull { slot ->
        val off = 4 + slot * GEN5_PARTY_MON_SIZE
        val personality = u32(first, off)
        val steady = (off until off + GEN5_PARTY_MON_SIZE).all { first[it] == second[it] }
        (if (steady) decodeGen5PartyMon(first, off) else null)?.also { lastGoodParty[slot] = it }
            ?: lastGoodParty[slot]?.takeIf { it.personality == personality && personality != 0L }
    }
}

/** The bag's five pockets, in the game's order; [pocketOf] can move an item to the cart's own pocket. */
fun readGen5Bag(r: MemoryReader, save: Long): List<Item> {
    val raw = r.readCoreMemory(save + B2_BAG, B2_POCKET_SIZES.sum() * 4)
    if (raw.size < B2_POCKET_SIZES.sum() * 4) return emptyList()
    return decodeGen5Bag(raw)
}

internal fun decodeGen5Bag(raw: ByteArray): List<Item> {
    val out = ArrayList<Item>()
    var slot = 0
    for ((p, size) in B2_POCKET_SIZES.withIndex()) {
        for (i in 0 until size) {
            val id = u16(raw, (slot + i) * 4)
            val qty = u16(raw, (slot + i) * 4 + 2)
            if (id in 1..638 && qty in 1..999) out += Item(id, qty, B2_POCKETS[p])
        }
        slot += size
    }
    return out
}

/** The Pokédex (PokeDexSave): magic, a flags word (bit 0: the National Dex), caught, then seen as four kinds. */
fun readGen5Pokedex(r: MemoryReader, save: Long): PokedexState? {
    val d = r.readCoreMemory(save + B2_POKEDEX, 8 + 0x54 * 5)
    return decodeGen5Pokedex(d)
}

internal fun decodeGen5Pokedex(d: ByteArray): PokedexState? {
    if (d.size < 8 + 0x54 * 5 || u32(d, 0) != 0xBEEFCAFEL) return null
    fun bit(base: Int, n: Int) = (d[base + (n - 1) / 8].toInt() shr ((n - 1) % 8)) and 1 != 0
    val seen = (1..649).filter { n -> (0 until 4).any { k -> bit(8 + 0x54 * (1 + k), n) } }.toSet()
    val caught = (1..649).filter { bit(8, it) }.toSet()
    return PokedexState(POKEDEX_BLACK2, seen + caught, caught, national = u32(d, 4) and 1L != 0L)
}

/** The event work and flags, the badges as [GEN4_BADGE_FLAG] + badge, the hour as [GEN4_VAR_HOUR]. */
fun readGen5Progress(r: MemoryReader, save: Long, hour: Int = java.time.LocalTime.now().hour): SaveProgress? {
    val work = r.readCoreMemory(save + B2_EVENT_WORK, B2_VAR_COUNT * 2 + B2_FLAG_BYTES)
    if (work.size < B2_VAR_COUNT * 2 + B2_FLAG_BYTES) return null
    val info = r.readCoreMemory(save + B2_GAME_INFO, 8)
    return SaveProgress(
        work.copyOfRange(B2_VAR_COUNT * 2, work.size), work.copyOfRange(0, B2_VAR_COUNT * 2),
        flagBanks = listOf(SaveBank(GEN4_BADGE_FLAG, 0, 8) to byteArrayOf(info.getOrElse(4) { 0 })),
        varBanks = listOf(SaveBank(GEN4_VAR_HOUR, 0, 1) to byteArrayOf(hour.toByte(), 0)),
    )
}

/** A live battler (BattleMon, btl_pokeparam.h). */
internal fun decodeGen5BattleMon(b: ByteArray, o: Int): BattleMon {
    val species = u16(b, o + 0x0C)
    if (species !in 1..649) return EMPTY_BATTLE_MON
    // conditions[1..5]: paralysis, sleep, freeze, burn, poison (a BattleCondition word each, its type in bits 0-2)
    fun cond(c: Int) = u32(b, o + 0x1C + c * 4) and 7L != 0L
    val status = when {
        cond(2) -> 1L
        cond(5) -> 0x08L
        cond(4) -> 0x10L
        cond(3) -> 0x20L
        cond(1) -> 0x40L
        else -> 0L
    }
    return BattleMon(
        species = species,
        level = u8(b, o + 0x18),
        type1 = Gen5RomTables.type(u8(b, o + 0xF8)),
        type2 = Gen5RomTables.type(u8(b, o + 0xF9)),
        hp = u16(b, o + 0x10),
        maxHp = u16(b, o + 0x0E),
        status1 = status,
        moves = IntArray(4) { u16(b, o + 0x104 + it * 14) },
        pp = IntArray(4) { u8(b, o + 0x104 + it * 14 + 2) },
        ability = u16(b, o + 0x13C),
        item = u16(b, o + 0x12),
        // attack, defense, speed, sp. attack, sp. defense: Gen 3's order
        stats = intArrayOf(u16(b, o + 0xEE), u16(b, o + 0xF0), u16(b, o + 0xF6), u16(b, o + 0xF2), u16(b, o + 0xF4)),
        // attack, defense, sp. attack, sp. defense, speed, accuracy, evasion -> the companion's order
        statStages = intArrayOf(u8(b, o + 0xFC), u8(b, o + 0xFD), u8(b, o + 0x100), u8(b, o + 0xFE), u8(b, o + 0xFF), u8(b, o + 0x101), u8(b, o + 0x102)),
    )
}

private const val B2_BATTLE_MON_SIZE = 0x224

internal class Gen5BattleRead(
    /** BATTLE_POS_* order: player left, foe left, player right, foe right. */
    val mons: List<BattleMon>,
    val foes: List<Mon>,
    val trainer: Boolean,
    val doubles: Boolean,
)

/** Null outside battle (the overlay's word then holds whatever loaded there; the self-check rejects it). */
internal fun readGen5Battle(r: MemoryReader): Gen5BattleRead? {
    val mm = u32(r.readCoreMemory(BLACK2_BATTLE_MAIN, 4), 0)
    if (!inMain(mm)) return null
    val head = r.readCoreMemory(mm, 0xC8 + 4 + 4 * 0x1C)
    if (head.size < 0xC8 + 4 + 4 * 0x1C || u32(head, 0xC8) != mm) return null
    val setup = u32(head, 0)
    if (!inMain(setup)) return null
    val setupHead = r.readCoreMemory(setup, 8)
    val battleType = u32(setupHead, 0)
    val style = u32(setupHead, 4)
    fun party(client: Int): List<Long> {
        val o = 0xC8 + 4 + client * 0x1C
        val count = u8(head, o + 0x18).coerceAtMost(6)
        return (0 until count).map { u32(head, o + it * 4) }.filter(::inMain)
    }
    fun mon(p: Long): BattleMon = decodeGen5BattleMon(r.readCoreMemory(p, B2_BATTLE_MON_SIZE), 0)
    val player = party(0)
    val foe = party(1)
    if (player.isEmpty() || foe.isEmpty()) return null
    val doubles = style == 1L
    val mons = listOf(
        mon(player[0]), mon(foe[0]),
        if (doubles) player.getOrNull(1)?.let(::mon) ?: EMPTY_BATTLE_MON else EMPTY_BATTLE_MON,
        if (doubles) foe.getOrNull(1)?.let(::mon) ?: EMPTY_BATTLE_MON else EMPTY_BATTLE_MON,
    )
    if (mons[0].species == 0 || mons[1].species == 0) return null
    // The foe's whole team: each BattleMon's source Pokémon (personality, IVs, ...) with its live HP and PP.
    val foes = foe.mapNotNull { p ->
        val b = r.readCoreMemory(p, B2_BATTLE_MON_SIZE)
        val live = decodeGen5BattleMon(b, 0)
        if (live.species == 0) return@mapNotNull null
        val src = u32(b, 0).takeIf(::inMain)?.let { decodeGen5PartyMon(r.readCoreMemory(it, GEN5_PARTY_MON_SIZE)) }
        (src ?: Mon(live.species, live.level, live.hp, live.maxHp, live.status1, live.moves, live.pp))
            .copy(hp = live.hp, maxHp = live.maxHp, status = live.status1, moves = live.moves, pp = live.pp, level = live.level)
    }
    return Gen5BattleRead(mons, foes, trainer = battleType != 0L, doubles = doubles)
}

fun readGen5Telemetry(r: MemoryReader): Telemetry {
    val save = black2SaveBuffer(r)
    val party = readGen5Party(r, if (save >= 0) save + B2_PARTY + 4 else BLACK2_PARTY_COUNT)
    if (save < 0) return emptyGen5Telemetry(party)
    val items = readGen5Bag(r, save)
    val info = r.readCoreMemory(save + B2_GAME_INFO, 8)
    // The save's trainer block (PKHeX MyStatus5): name at 4, TID 0x14, language 0x1E, version 0x1F, gender 0x21.
    val player = r.readCoreMemory(save + B2_PLAYER, 0x24)
    // Where the player is: the live PlayerState, else the save's own position block.
    val gs = u32(r.readCoreMemory(BLACK2_GAME_SYSTEM, 4), 0)
    val gd = if (inMain(gs)) u32(r.readCoreMemory(gs + 0x1C, 4), 0) else 0L
    val pos = if (inMain(gd)) r.readCoreMemory(gd + 0x114, 0x10) else r.readCoreMemory(save + 0x19500 + 4, 0x10)
    val zone = u16(pos, 0)
    val x = (u32(pos, 4) shr 16).toInt()
    val z = (u32(pos, 12) shr 16).toInt()
    val tables = Gen5RomTables.current
    val spot = Gen5RomTables.townMapSpot(zone)
    val battle = readGen5Battle(r)
    val foePid = battle?.let { b -> b.foes.firstOrNull { it.species == b.mons[BATTLE_POS_OPPONENT_LEFT].species && it.hp == b.mons[BATTLE_POS_OPPONENT_LEFT].hp } }
    return Telemetry(
        frameCounter = 0,
        inBattle = battle != null,
        isDoubleBattle = battle?.doubles == true,
        mapGroup = 0,
        mapNum = zone,
        regionMapSectionId = spot?.first ?: -1,
        x = x,
        y = z,
        facingDirection = 0,
        partyCount = party.size,
        party = party,
        battleMons = battle?.mons ?: List(4) { EMPTY_BATTLE_MON },
        itemCount = items.size,
        items = items,
        money = u32(info, 0).takeIf { it <= 9_999_999 },
        playerGender = u8(player, 0x21).takeIf { it <= 1 } ?: -1,
        mapSecName = tables.zoneName(zone),
        enemyParty = if (battle?.trainer == true) battle.foes else emptyList(),
        battleFoes = battle?.foes ?: emptyList(),
        enemyActive = if (battle?.trainer == true && foePid != null) battle.foes.indexOf(foePid) else -1,
        regionMapTile = spot?.second,
        pokedex = readGen5Pokedex(r, save),
        progress = readGen5Progress(r, save),
    )
}

private fun emptyGen5Telemetry(party: List<Mon>) = Telemetry(
    frameCounter = 0, inBattle = false, isDoubleBattle = false, mapGroup = 0, mapNum = 0, regionMapSectionId = -1,
    x = 0, y = 0, facingDirection = 0, partyCount = party.size, party = party, battleMons = List(4) { EMPTY_BATTLE_MON },
    itemCount = 0, items = emptyList(),
)
