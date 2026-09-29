package gg.ginco.minigame.definition

import gg.ginco.jellyparty.codec.annotations.SerializableObject

/**
 * A durable minigame definition: everything needed to spawn an instance's
 * teams and region markers without anyone placing them by hand.
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
    var markers: List<MarkerDefinition> = emptyList(),
)

@SerializableObject
data class TeamDefinition(
    var name: String = "",
    var color: Int = 0xFFFFFF,
    var maxPlayers: Int = -1,
)

/** A region marker: open type id + axis-aligned box. */
@SerializableObject
data class MarkerDefinition(
    var type: String = "",
    var minX: Double = 0.0,
    var minY: Double = 0.0,
    var minZ: Double = 0.0,
    var maxX: Double = 0.0,
    var maxY: Double = 0.0,
    var maxZ: Double = 0.0,
)
