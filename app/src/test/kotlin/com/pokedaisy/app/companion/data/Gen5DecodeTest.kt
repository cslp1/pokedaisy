package com.pokedaisy.app.companion.data

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class Gen5DecodeTest {
    private val orders = arrayOf(
        "ABCD", "ABDC", "ACBD", "ACDB", "ADBC", "ADCB", "BACD", "BADC", "BCAD", "BCDA", "BDAC", "BDCA",
        "CABD", "CADB", "CBAD", "CBDA", "CDAB", "CDBA", "DABC", "DACB", "DBAC", "DBCA", "DCAB", "DCBA",
    )
    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
    private fun put32(b: ByteArray, o: Int, v: Long) { put16(b, o, v.toInt()); put16(b, o + 2, (v ushr 16).toInt()) }
    private fun word(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    private fun crypt(b: ByteArray, start: Int, length: Int, seed: Long) {
        var key = seed
        for (i in start until start + length step 2) {
            key = (key * 0x41C64E6DL + 0x6073L) and 0xFFFFFFFFL
            put16(b, i, word(b, i) xor (key ushr 16).toInt())
        }
    }
    private fun mon(order: Int = 0, encrypted: Boolean = true, species: Int = 495): ByteArray {
        val b = ByteArray(0xDC)
        val pid = (order.toLong() shl 13) or 0x123
        put32(b, 0, pid)
        val a = 8 + orders[order].indexOf('A') * 32
        val m = 8 + orders[order].indexOf('B') * 32
        put16(b, a, species); put16(b, a + 2, 1); put32(b, a + 8, 135)
        for (i in 0..5) b[a + 0x10 + i] = (i + 1).toByte()
        put16(b, m, 33); b[m + 8] = 35
        put32(b, m + 0x10, 0x3FFFFFFFL)
        b[m + 0x18] = 2; b[m + 0x19] = 13 // female, Jolly (deliberately different from PID % 25)
        b[0x8C] = 5; put16(b, 0x8E, 17); put16(b, 0x90, 20)
        for (i in 0..4) put16(b, 0x92 + i * 2, 10 + i)
        var sum = 0
        for (i in 8 until 0x88 step 2) sum += word(b, i)
        put16(b, 6, sum)
        if (encrypted) { crypt(b, 8, 0x80, sum.toLong() and 65535); crypt(b, 0x88, 0x54, pid) }
        else b[4] = 3
        return b
    }

    @Test fun allShufflesAndEncryptionStates() {
        for (order in 0..23) for (encrypted in listOf(true, false)) {
            val m = decodeGen5PartyMon(mon(order, encrypted))!!
            assertEquals(495, m.species); assertEquals(5, m.level)
            assertEquals(17, m.hp); assertEquals(20, m.maxHp)
            assertArrayEquals(intArrayOf(33, 0, 0, 0), m.moves)
            assertArrayEquals(intArrayOf(35, 0, 0, 0), m.pp)
            assertEquals(GENDER_SYMBOL_FEMALE, m.genderSymbol)
            assertEquals(13, m.stats!!.nature)
            assertEquals(List(6) { 31 }, m.stats!!.ivs)
            assertEquals(listOf(1, 2, 3, 4, 5, 6), m.stats!!.evs)
            assertEquals(listOf(20, 10, 11, 12, 13, 14), m.stats!!.stats)
        }
    }
    @Test fun corruptionAndTruncationAreRejected() {
        assertNull(decodeGen5PartyMon(mon().also { it[12] = (it[12].toInt() xor 1).toByte() }))
        assertNull(decodeGen5PartyMon(mon().copyOf(219)))
        assertNull(decodeGen5PartyMon(mon(), -1))
        assertNull(decodeGen5PartyMon(mon(species = 650)))
    }
    @Test fun generationFourKeepsItsSpeciesLimitAndSize() {
        assertNull(decodeGen4PartyMon(mon(encrypted = false), 0))
        assertNull(decodeGen4PartyMon(mon(encrypted = false).copyOf(236), 0))
        assertEquals(387, decodeGen4PartyMon(mon(encrypted = false, species = 387).copyOf(236), 0)!!.species)
    }
    @Test fun readerRejectsInvalidCountsAndChangingSlots() {
        val raw = ByteArray(4 + 6 * 220)
        raw[0] = 1; mon().copyInto(raw, 4)
        fun reader(change: Boolean) = object : MemoryReader {
            var n = 0
            override fun readCoreMemory(addr: Long, size: Int): ByteArray {
                assertEquals(BLACK2_PARTY_COUNT, addr); assertEquals(raw.size, size)
                return raw.copyOf().also { if (change && n++ > 0) it[12] = (it[12].toInt() xor 1).toByte() }
            }
        }
        assertEquals(495, readGen5Party(reader(false), BLACK2_PARTY_COUNT).single().species)
        // A slot the game is rewriting keeps its last good read of the same Pokémon.
        assertEquals(495, readGen5Party(reader(true), BLACK2_PARTY_COUNT).single().species)
        raw[0] = 7; assertTrue(readGen5Party(reader(false), BLACK2_PARTY_COUNT).isEmpty())
    }
    @Test fun bagPocketsInTheSavesOrder() {
        val raw = ByteArray((310 + 83 + 109 + 48 + 64) * 4)
        fun put(slot: Int, id: Int, qty: Int) { put16(raw, slot * 4, id); put16(raw, slot * 4 + 2, qty) }
        put(0, 4, 12)            // POKé BALL x12 in ITEMS
        put(1, 1000, 1)          // not an item: dropped
        put(310, 437, 1)         // a key item
        put(310 + 83, 328, 1)    // TM01
        put(310 + 83 + 109, 17, 3) // POTION x3 in MEDICINE
        put(310 + 83 + 109 + 48 + 63, 149, 2) // a berry in the pocket's last slot
        val items = decodeGen5Bag(raw)
        assertEquals(listOf(Item(4, 12, POCKET_ITEMS), Item(437, 1, POCKET_KEY_ITEMS), Item(328, 1, POCKET_TM_HM),
            Item(17, 3, POCKET_MEDICINE), Item(149, 2, POCKET_BERRIES)), items)
    }
    @Test fun pokedexCaughtSeenAndNational() {
        val d = ByteArray(8 + 0x54 * 5)
        put32(d, 0, 0xBEEFCAFEL); put32(d, 4, 1)
        fun set(base: Int, n: Int) { d[base + (n - 1) / 8] = (d[base + (n - 1) / 8].toInt() or (1 shl ((n - 1) % 8))).toByte() }
        set(8, 498)                // caught
        set(8 + 0x54 * 2, 501)     // seen as a female only
        val p = decodeGen5Pokedex(d)!!
        assertEquals(setOf(498), p.caught); assertEquals(setOf(498, 501), p.seen); assertTrue(p.national)
        d[0] = 0; assertNull(decodeGen5Pokedex(d))
    }
    @Test fun encounterTableSlotsMerge() {
        val t = ByteArray(0xE8)
        t[0] = 10; t[3] = 5; t[5] = 0  // grass and surf on, no fishing
        fun slot(o: Int, species: Int, lo: Int, hi: Int) { put16(t, o, species); t[o + 2] = lo.toByte(); t[o + 3] = hi.toByte() }
        for (i in 0 until 12) slot(8 + i * 4, if (i < 2) 504 else 506, 2 + i % 2, 4)
        slot(8 + 4 * 36, 550, 20, 25)
        val e = Black2World.decode(t, 0)
        assertEquals(listOf(EncounterSlot(506, 2, 4, 60), EncounterSlot(504, 2, 4, 40)), e.grass)
        assertEquals(listOf(EncounterSlot(550, 20, 25, 60)), e.water)
        assertTrue(e.superRod.isEmpty())
    }
    @Test fun seasonsFollowTheMonth() {
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3), (1..12).map { Black2World.season(it) })
    }
    @Test fun guideAreaData() {
        val all = allAreaThings(GuideId.BLACK2)
        val things = all.values.flatten()
        assertTrue(things.count { it.kind == AreaKind.ITEM } > 300 && things.count { it.kind == AreaKind.HIDDEN } > 150)
        // HM01 (CUT, 420) and HM06 (DIVE, 425) are handed out; hidden items carry their 0xB04+ flags.
        assertTrue(things.any { it.id == 420 && it.kind == AreaKind.GIFT })
        assertTrue(things.any { it.id == 425 && it.kind == AreaKind.GIFT })
        assertTrue(things.filter { it.kind == AreaKind.HIDDEN }.all { it.flag in 0xB04 until 0xB04 + 300 })
        assertTrue(things.filter { it.kind == AreaKind.ITEM }.all { it.flag > 0 && it.qty >= 1 })
        assertTrue(all.keys.all { it in 0 until 85 })
        assertTrue(gameGuide(GuideId.BLACK2)!!.pages.any { it.title == "STUCK?" })
    }
    @Test fun lz11RoundTrip() {
        // literal "ABCD", then a copy of 8 from 4 back: ABCDABCDABCD
        val packed = byteArrayOf(0x11, 12, 0, 0, 0x08, 'A'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte(), 'D'.code.toByte(), 0x70, 0x03)
        assertEquals("ABCDABCDABCD", String(Gen5Art.lz(packed)))
    }

    @Test fun sequelConfigsReadTheirOwnSavePointers() {
        val expected = GEN5_WHITE2_US.saveControl
        val r = object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int): ByteArray {
                val out = ByteArray(size)
                val word = when (addr) {
                    expected -> 0x02010000L
                    0x02010010L -> 0x02020000L
                    0x02020034L -> 0x02200000L
                    else -> 0L
                }
                if (size >= 4) put32(out, 0, word)
                return out
            }
        }
        assertEquals(0x02200000L, black2SaveBuffer(r, GEN5_WHITE2_US))
        assertEquals(-1L, black2SaveBuffer(r, GEN5_BLACK2_US))
        assertTrue(GameKind.WHITE2.isDsGame)
        assertEquals(GuideId.WHITE2, guideId(GameKind.WHITE2, null))
        assertTrue(gameGuide(GuideId.WHITE2)!!.pages.isNotEmpty())
        assertFalse(com.pokedaisy.app.companion.data.TelemetrySampler.ndsSupported("IRDO", 1))
        assertTrue(com.pokedaisy.app.companion.data.TelemetrySampler.ndsSupported("IRDO", 0))
    }

    @Test fun englishRomTables() {
        val path = System.getenv("WHITE2_ROM") ?: System.getenv("BLACK2_ROM")
        assumeTrue("Set BLACK2_ROM to test the player's local cart", path != null)
        RandomAccessFile(File(path!!), "r").use { f ->
            val header = ByteArray(512); f.readFully(header)
            assertEquals(if (System.getenv("WHITE2_ROM") != null) "IRDO" else "IREO", String(header, 12, 4, Charsets.US_ASCII))
            Gen5RomTables.load(f, header)
        }
        val t = Gen5RomTables.current
        assertEquals(649, t.species.size); assertEquals("Snivy", t.species[495])
        assertEquals("Genesect", t.species[649]); assertEquals("Tackle", t.moves[33]!!.name)
        assertEquals(50, t.moves[33]!!.power); assertEquals("Master Ball", t.items[1])
        assertEquals(SpeciesTypes(12, 12), t.types[495])
        // Bag: pockets and descriptions from the cart.
        assertEquals(POCKET_ITEMS, t.itemPockets[4]); assertEquals(POCKET_MEDICINE, t.itemPockets[17])
        assertEquals(POCKET_TM_HM, t.itemPockets[328]); assertEquals(POCKET_BERRIES, t.itemPockets[149])
        assertTrue(t.itemDescriptions[1].orEmpty().contains("catch", ignoreCase = true))
        assertFalse(t.itemDescriptions.values.any { '\uFFFE' in it || '\uF000' in it })
        // Abilities, places, zones and the Town Map.
        assertEquals("Blaze", t.abilities[66]); assertEquals("Torrent", t.abilities[67])
        assertEquals(615, t.zones.size)
        assertEquals("Aspertia City", t.zoneName(427))
        assertEquals(85, t.townPlaces.size)
        val spot = Gen5RomTables.townMapSpot(427)!!
        assertEquals("Aspertia City", t.map!!.sections[spot.first]!!.name)
        // Trainers: CHEREN's first team, the rival's starter battle.
        assertEquals(listOf(504, 506), t.trainers[156]!!.map { it.species })
        // The rival's first battle, one team per starter the player could pick (TRAINER_RIVAL .. _3).
        assertEquals(setOf(495, 498, 501), (161..163).map { t.trainers[it]!!.single().species }.toSet())
        // The guide's places are the Town Map's: HM01 is handed out in Virbank City.
        val hm01 = allAreaThings(GuideId.BLACK2).entries.first { (_, l) -> l.any { it.id == 420 && it.kind == AreaKind.GIFT } }.key
        assertEquals("Virbank City", t.map!!.sections[hm01]!!.name)
        // HERE on Route 19 (zone with wild grass): every slot a real species.
        val route19 = (0 until t.zones.size).first { t.zoneName(it) == "Route 19" && t.zones[it].encounters != 0x1FFF }
        val wild = Black2World.encounters(0, route19, null)
        assertTrue(wild.grass.isNotEmpty() && wild.grass.all { it.species in 1..649 })
        assertEquals(100, wild.grass.sumOf { it.percent })
    }
    @Test fun englishRomArt() {
        val path = System.getenv("WHITE2_ROM") ?: System.getenv("BLACK2_ROM")
        assumeTrue("Set BLACK2_ROM to test the player's local cart", path != null)
        val dir = java.nio.file.Files.createTempDirectory("b2art").toFile()
        RandomAccessFile(File(path!!), "r").use { f ->
            val header = ByteArray(512); f.readFully(header)
            Gen5RomTables.load(f, header)
            assertTrue(Gen5Art.extract(f, header, dir))
            assertFalse(Gen5Art.extract(f, header, dir))   // once per cart build
        }
        for (p in listOf("${Gen5Art.ICONS}/498.png", "${Gen5Art.ICONS}/650.png", "${Gen5Art.ITEMS}/1.png", "${Gen5Art.ITEMS}/17.png",
            "${Gen5Art.FRONT}/498.png", "regionmap/${Gen5Art.TOWN_MAP}.png", "regionmap/${Gen5Art.HEAD_M}.png")) {
            assertTrue(p, File(dir, p).length() > 100)
        }
        val pages = Gen4Dex.parse(File(dir, Gen5Art.DEX).readLines(), GEN5_DEX_BLACK2)
        assertEquals(649, pages.entries.size)
        val tepig = pages.entries[498]!!
        assertEquals(listOf(65, 63, 45, 45, 45, 45), tepig.baseStats)
        assertEquals(listOf("Blaze"), tepig.abilities); assertEquals("Thick Fat", tepig.hiddenAbility)
        assertEquals(5, tepig.heightDm); assertEquals(99, tepig.weightHg)
        assertEquals(listOf(EvoLink(498, 499, listOf(EvoReq.Level(17)))), pages.evolutions[498])
        assertEquals(300, pages.regional.size); assertEquals(495, pages.regional.first())
        assertTrue(pages.levelUp[498]!!.any { it.move == 33 && it.level == 1 })
        // Karrablast trades for Shelmet; a stone evolution keeps its item.
        assertEquals(listOf(EvoReq.Trade, EvoReq.Special), pages.evolutions[588]!!.single().reqs)
        assertTrue(pages.evolutions[133]!!.any { it.to == 134 && it.reqs == listOf(EvoReq.UseItem(84)) })
        System.getenv("BLACK2_ART_OUT")?.let { dir.copyRecursively(File(it), overwrite = true) }
        dir.deleteRecursively()
    }

    /** A headless capture's main RAM (nds_dump `dump`): BLACK2_CAPTURES/<name>/mainram.bin, never committed. */
    private fun capture(name: String): MemoryReader? {
        val dir = System.getenv("BLACK2_CAPTURES") ?: return null
        val ram = File(dir, "$name/mainram.bin").takeIf { it.isFile }?.readBytes() ?: return null
        return object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int): ByteArray {
                val o = (addr - 0x02000000L).toInt()
                return if (o < 0 || o + size > ram.size) ByteArray(0) else ram.copyOfRange(o, o + size)
            }
        }
    }

    private fun loadRom() {
        val path = System.getenv("WHITE2_ROM") ?: System.getenv("BLACK2_ROM") ?: return
        RandomAccessFile(File(path), "r").use { f -> val h = ByteArray(512); f.readFully(h); Gen5RomTables.load(f, h) }
    }

    @Test fun capturedFieldAfterTheStarter() {
        val r = capture("cap_starter")
        assumeTrue("Set BLACK2_CAPTURES to the headless captures", r != null)
        loadRom()
        val t = readGen5Telemetry(r!!)
        assertEquals(498, t.party.single().species); assertEquals(5, t.party.single().level)
        assertEquals(21, t.party.single().maxHp)
        assertFalse(t.inBattle)
        assertEquals(427, t.mapNum)
        assertEquals(3000L, t.money)
        assertEquals(0, t.playerGender)
        assertEquals(setOf(498), t.pokedex!!.caught)
        assertFalse(t.pokedex!!.national)
        assertFalse(t.progress!!.flag(GEN4_BADGE_FLAG))
        if (System.getenv("BLACK2_ROM") != null) {
            assertEquals("Aspertia City", t.mapSecName)
            assertNotNull(t.regionMapTile)
        }
    }
    @Test fun capturedRivalBattle() {
        val r = capture("cap_b3")
        assumeTrue("Set BLACK2_CAPTURES to the headless captures", r != null)
        val t = readGen5Telemetry(r!!)
        assertTrue(t.inBattle); assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(498, me.species); assertEquals(15, me.hp); assertEquals(21, me.maxHp)
        assertEquals(10 /* FIRE */, me.type1); assertEquals(66, me.ability)
        assertArrayEquals(intArrayOf(33, 39, 0, 0), me.moves); assertEquals(34, me.pp[0])
        assertEquals(501, foe.species); assertEquals(14, foe.hp); assertEquals(20, foe.maxHp)
        assertEquals(11 /* WATER */, foe.type1); assertEquals(67, foe.ability)
        assertEquals(listOf(6, 6, 6, 6, 6, 6, 6), foe.statStages!!.toList())
        assertEquals(501, t.enemyParty.single().species); assertEquals(14, t.enemyParty.single().hp)
        assertEquals(0, t.enemyActive)
        // Before the turn: both at full HP.
        val before = capture("cap_b2")?.let(::readGen5Telemetry)
        if (before != null) {
            assertEquals(21, before.battleMons[BATTLE_POS_PLAYER_LEFT].hp)
            assertEquals(20, before.battleMons[BATTLE_POS_OPPONENT_LEFT].hp)
        }
        // On the field the battle overlay's word is something else: no battle.
        capture("cap_starter")?.let { assertFalse(readGen5Telemetry(it).inBattle) }
    }
}
