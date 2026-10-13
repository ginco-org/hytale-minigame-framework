package gg.ginco.minigame.definition

import gg.ginco.jellyparty.codec.annotations.SerializableObject

/**
 * A durable minigame definition: the non-spatial config of a game type —
 * its teams (and future rule data). Region markers are WORLD content
 * (chunk-store resource) and are not part of definitions.
 *
 * Definitions have two sources (see [DefinitionStore]): JSON in the
 * framework's data directory (`definitions/<gameTypeId>.json` — editor save
 * path, shadows asset-derived ids), and the framework-owned Minigame /
 * MinigameMap asset types, which minigame plugins ship as data inside their
 * own bundled asset packs (`gg.ginco.minigame.asset.Assets`).
 *
 * Serialization goes through the hytale-codec KSP processor (generated
 * `MinigameDefinitionCodec` in the companion) — the same codec stack the
 * server's asset pipeline uses.
 */
@SerializableObject
data class MinigameDefinition(
    var gameTypeId: String = "",
    var displayName: String = "",
    /**
     * Name of the builtin instance asset (`Server/Instances/<name>`) this game
     * type is played in. Empty means the legacy in-world mode: the instance
     * lives in whatever world `/minigame create` was run from.
     */
    var instanceAsset: String = "",
    var teams: List<TeamDefinition> = emptyList(),
    var behaviors: List<String> = emptyList(),
    /** Auto-start floor (Waiting → Countdown from this many players; <= 0 = manual only). */
    var minPlayers: Int = 0,
    /** Join cap (<= 0 = uncapped). */
    var maxPlayers: Int = 0,
)

@SerializableObject
data class TeamDefinition(
    var name: String = "",
    var color: Int = 0xFFFFFF,
    var maxPlayers: Int = -1,
)
