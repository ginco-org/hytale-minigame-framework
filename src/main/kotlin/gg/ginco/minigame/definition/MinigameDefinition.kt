package gg.ginco.minigame.definition

import gg.ginco.jellyparty.codec.annotations.SerializableObject

/**
 * A durable minigame definition: the non-spatial config of a game type —
 * its teams (and future rule data). Region markers are WORLD content
 * (chunk-store resource) and are not part of definitions.
 *
 * Definitions live as JSON in the plugin's data directory (`definitions/
 * <gameTypeId>.json`), are loaded at boot (and via `/minigame defs reload`),
 * and are written by `/minigame defs save` — the in-game editor write path:
 * configure an instance with markers and teams, save it, and every future
 * instance of that game type reproduces the setup.
 *
 * Serialization goes through the hytale-codec KSP processor (generated
 * `MinigameDefinitionCodec` in the companion) — the same codec stack the
 * server's asset pipeline uses, so moving the storage backend to a real
 * Hytale asset pack later only touches [DefinitionStore]'s file I/O.
 */
@SerializableObject
data class MinigameDefinition(
    var gameTypeId: String = "",
    var displayName: String = "",
    var teams: List<TeamDefinition> = emptyList(),
)

@SerializableObject
data class TeamDefinition(
    var name: String = "",
    var color: Int = 0xFFFFFF,
    var maxPlayers: Int = -1,
)
