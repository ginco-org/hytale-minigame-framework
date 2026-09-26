package gg.ginco.minigame.definition

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
 * This is plain data-dir JSON for v1. The storage backend is deliberately
 * swappable: the schema mirrors what a Hytale asset-pack definition would
 * carry, and the hand-written component codecs (Bounds, TeamData) are kept
 * for that move.
 */
@Serializable
data class MinigameDefinition(
    val gameTypeId: String,
    val displayName: String = "",
    val teams: List<TeamDefinition> = emptyList(),
    val markers: List<MarkerDefinition> = emptyList(),
)

@Serializable
data class TeamDefinition(
    val name: String,
    val color: Int = 0xFFFFFF,
    val maxPlayers: Int = -1,
)

/** A region marker: open type id + axis-aligned box. */
@Serializable
data class MarkerDefinition(
    val type: String,
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
)

object DefinitionJson {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun encode(definition: MinigameDefinition): String = json.encodeToString(
        MinigameDefinition.serializer(), definition)

    fun decode(text: String): MinigameDefinition = json.decodeFromString(
        MinigameDefinition.serializer(), text)
}
