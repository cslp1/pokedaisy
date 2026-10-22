package com.pokedaisy.app.companion.data

/**
 * Pokémon Black 2's guide (English, IREO rev 0). HERE and NEXT BOSS read the player's own cart ([Gen5RomTables]): a
 * zone's wild table (a/1/2/7) for the season the DS clock's month gives, and the trainers' teams (a/0/9/1, a/0/9/2).
 * Trainer ids are pokebw2's generated/trainers.txt; the badge each leader gives is the gym scripts' TrainerCardAddBadge
 * (CHEREN 0 .. MARLON 7), and a beaten trainer's flag is 0x5F0 + its id (TrainerFlagGet). The pages are in our own words.
 * HERE's ITEMS / PEOPLE and the WHERE IS page (generated from them: HMs, key items, gift Pokémon) are
 * GuideAreasBlack2Gen.kt's (scripts/gen_black2_guide.py). Item ids: HM01 420 .. HM06 425, BICYCLE 450, SUPER ROD 447,
 * DOWSING MCHN 471 (pokebw2's generated/items.txt).
 */
object Black2World : GuideWorld {
    /** Grass and dark grass (12 slots each), surf and fishing (5 each): encounter_data.py's groups, 4 bytes a slot. */
    private val LAND_ODDS = listOf(20, 20, 10, 10, 10, 10, 5, 5, 4, 4, 1, 1)
    private val WATER_ODDS = listOf(60, 30, 5, 4, 1)
    private val ROD_ODDS = listOf(40, 40, 15, 4, 1)
    private const val TABLE = 0xE8

    /** The game's season: the month's place in a four-month cycle (January, May, September spring, ...). */
    fun season(month: Int = java.time.LocalDate.now().monthValue): Int = (month - 1) % 4

    override fun encounters(group: Int, num: Int, progress: SaveProgress?): MapEncounters {
        val t = Gen5RomTables.current
        val none = MapEncounters(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val id = t.zones.getOrNull(num)?.encounters?.takeIf { it != 0x1FFF } ?: return none
        val data = t.encounters.getOrNull(id) ?: return none
        val base = if (data.size >= TABLE * 4) TABLE * season() else 0
        if (data.size < base + TABLE) return none
        return decode(data, base)
    }

    /** One season's table: rates (7 bytes and flags), then the slot groups. */
    internal fun decode(data: ByteArray, base: Int): MapEncounters {
        fun group(index: Int, offset: Int, count: Int, odds: List<Int>): List<EncounterSlot> {
            if (data[base + index].toInt() and 255 == 0) return emptyList()
            return (0 until count).mapNotNull { i ->
                val o = base + offset + i * 4
                val species = ((data[o].toInt() and 255) or ((data[o + 1].toInt() and 255) shl 8)) and 0x7FF
                if (species == 0) null else Triple(species, (data[o + 2].toInt() and 255)..(data[o + 3].toInt() and 255), odds[i])
            }.groupBy { it.first }.map { (species, g) ->
                EncounterSlot(species, g.minOf { it.second.first }, g.maxOf { it.second.last }, g.sumOf { it.third })
            }.sortedByDescending { it.percent }
        }
        return MapEncounters(
            grass = group(0, 8, 12, LAND_ODDS),
            water = group(3, 8 + 4 * 36, 5, WATER_ODDS),
            rockSmash = emptyList(),
            oldRod = emptyList(),
            goodRod = emptyList(),
            superRod = group(5, 8 + 4 * 46, 5, ROD_ODDS),
        )
    }

    override fun party(trainer: Int, progress: SaveProgress?): List<TrainerMon> = Gen5RomTables.current.trainers[trainer].orEmpty()

    override fun clockNote(progress: SaveProgress?): String = listOf("SPRING", "SUMMER", "AUTUMN", "WINTER")[season()]
}

private const val B2_CHAMPION_IRIS = 341

private fun b2Gym(title: String, where: String, trainer: Int, badge: Int) =
    Boss(title, where, GEN4_BADGE_FLAG + badge) { trainer }

/** The League: each room's trainer flag, or the Champion beaten (a later run clears the rooms again). */
private fun b2League(title: String, trainer: Int) = Boss(title, "POKéMON LEAGUE", B2_TRAINER_FLAGS + trainer,
    doneIf = { p -> p.flag(B2_TRAINER_FLAGS + B2_CHAMPION_IRIS) || p.flag(B2_TRAINER_FLAGS + trainer) }) { trainer }

private val BOSSES_BLACK2 = listOf(
    b2Gym("LEADER CHEREN", "ASPERTIA CITY GYM", 156, 0),
    b2Gym("LEADER ROXIE", "VIRBANK CITY GYM", 157, 1),
    b2Gym("LEADER BURGH", "CASTELIA CITY GYM", 154, 2),
    b2Gym("LEADER ELESA", "NIMBASA CITY GYM", 153, 3),
    b2Gym("LEADER CLAY", "DRIFTVEIL CITY GYM", 158, 4),
    b2Gym("LEADER SKYLA", "MISTRALTON CITY GYM", 155, 5),
    b2Gym("LEADER DRAYDEN", "OPELUCID CITY GYM", 159, 6),
    b2Gym("LEADER MARLON", "HUMILAU CITY GYM", 160, 7),
    b2League("ELITE FOUR SHAUNTAL", 38),
    b2League("ELITE FOUR MARSHAL", 39),
    b2League("ELITE FOUR GRIMSLEY", 40),
    b2League("ELITE FOUR CAITLIN", 41),
    Boss("CHAMPION IRIS", "POKéMON LEAGUE", B2_TRAINER_FLAGS + B2_CHAMPION_IRIS) { B2_CHAMPION_IRIS },
)

private const val B2_HM01 = 420
private const val B2_DOWSING_MCHN = 471
private const val B2_BICYCLE = 450
private const val B2_SUPER_ROD = 447

private fun b2Hm(n: Int, move: String, block: String, hint: String) =
    entry(block, "Use $move (HM%02d). WHERE IS says who gives it to you.".format(n), hint, Have.Item(B2_HM01 + n - 1))

internal val GUIDE_BLACK2 = GameGuide(
    verified = false,
    bosses = BOSSES_BLACK2,
    world = Black2World,
    pages = listOf(
        page(
            "TIPS",
            section(
                "WILD POKéMON",
                entry(
                    "Why did the POKéMON on this route change?",
                    "Many places have a different list for each season, and the season follows the month on your DS " +
                        "clock (your device's): January, May and September are spring, then summer, autumn and winter " +
                        "in turn. HERE shows the list for the season now.",
                    hint = "Look at the calendar.",
                ),
                entry(
                    "What is the dark grass?",
                    "Tall dark grass can start a battle against two wild POKéMON at once. HERE lists the ordinary grass.",
                ),
                entry(
                    "What are the shaking grass and rippling water?",
                    "A patch of grass that shakes, or water that ripples, holds a POKéMON of its own when you walk or " +
                        "fish there. They aren't part of HERE's lists.",
                ),
            ),
            section(
                "ITEMS",
                entry(
                    "How do I find hidden items?",
                    "Items lie buried all over Unova. The DOWSING MCHN beeps faster as you get close to one: HERE lists " +
                        "every hidden item in your area, and WHERE IS says who hands the machine out.",
                    hint = "A key item helps.",
                    have = Have.Item(B2_DOWSING_MCHN),
                ),
            ),
            section(
                "GYMS",
                entry(
                    "In what order are the GYMS?",
                    "CHEREN (NORMAL) in ASPERTIA CITY, ROXIE (POISON) in VIRBANK CITY, BURGH (BUG) in CASTELIA CITY, " +
                        "ELESA (ELECTRIC) in NIMBASA CITY, CLAY (GROUND) in DRIFTVEIL CITY, SKYLA (FLYING) in MISTRALTON " +
                        "CITY, DRAYDEN (DRAGON) in OPELUCID CITY and MARLON (WATER) in HUMILAU CITY. NEXT BOSS shows the " +
                        "next one's team.",
                    hint = "Eight of them, starting at home.",
                ),
            ),
        ),
        page(
            "STUCK?",
            section(
                "BLOCKED PATHS",
                b2Hm(1, "CUT", "A small tree is in the way", "A field move."),
                b2Hm(3, "SURF", "Water in the way", "A field move."),
                b2Hm(4, "STRENGTH", "A big boulder blocks the path", "A field move."),
                b2Hm(5, "WATERFALL", "A waterfall in the way", "A field move."),
                b2Hm(6, "DIVE", "Dark, deep water", "A field move."),
                entry("Too far to walk", "Use FLY (HM02) to go back to a town you've been to. WHERE IS says who gives it to you.",
                    "A field move.", Have.Item(B2_HM01 + 1)),
            ),
            section(
                "GETTING AROUND",
                entry("Moving faster", "Ride the BICYCLE. WHERE IS says who gives it to you.", "A key item.", Have.Item(B2_BICYCLE)),
                entry("Water Pokémon", "Fish with the SUPER ROD in water, or SURF across it. WHERE IS has both.", "A key item.",
                    Have.Item(B2_SUPER_ROD)),
                entry("Who's next?", "NEXT BOSS shows the next GYM LEADER, the ELITE FOUR or the CHAMPION and their teams. " +
                    "The story leads you from one to the next.", "Look at NEXT BOSS."),
            ),
        ),
    ),
)
