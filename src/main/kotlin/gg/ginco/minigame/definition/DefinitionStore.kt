package gg.ginco.minigame.definition

import com.hypixel.hytale.codec.ExtraInfo
import com.hypixel.hytale.codec.util.RawJsonReader
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import com.hypixel.hytale.builtin.instances.InstancesPlugin
import gg.ginco.minigame.asset.MinigameAssets
import gg.ginco.minigame.component.TeamData
import gg.ginco.minigame.util.Teams
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads, caches and serves [MinigameDefinition]s.
 *
 * Two layers, data-dir first:
 * - **Data dir** — `<dataDir>/definitions/<gameTypeId>.json`, loaded at boot
 *   (and via `/minigame defs reload`), written by `/minigame defs save` (the
 *   in-game editor write path: configure an instance, save it, and every
 *   future instance of that game type reproduces the setup).
 * - **Asset packs** — the framework-owned `Minigame`/`MinigameMap` asset
 *   types ([gg.ginco.minigame.asset.MinigameAssets]) compile into
 *   definitions; they fill every game type id the data dir does not own. An
 *   editor save shadows the shipped asset for that id until the file is
 *   removed — explicit user intent wins over packaged defaults.
 *
 * Storage is v1-plain JSON; swapping in the Hytale asset-pack backend later
 * only replaces this class's file I/O — consumers keep the same API.
 */
object DefinitionStore {

    private lateinit var dir: Path
    private val definitions = LinkedHashMap<String, MinigameDefinition>()

    /** Points the store at [dataDir]/definitions and loads everything found. Returns the count. */
    fun init(dataDir: Path): Int {
        dir = dataDir.resolve("definitions")
        Files.createDirectories(dir)
        return loadAll()
    }

    /** Re-reads every definition file from disk. Returns the count. */
    fun loadAll(): Int {
        synchronized(definitions) { definitions.clear() }
        var count = 0
        Files.list(dir).use { stream ->
            for (file in stream.filter { it.fileName.toString().endsWith(".json") }) {
                try {
                    val def = decode(Files.readString(file))
                    synchronized(definitions) { definitions[def.gameTypeId] = def }
                    count++
                } catch (e: Exception) {
                    System.err.println("[MinigameFramework] failed to load definition ${file.fileName}: ${e.message}")
                }
            }
        }
        return count
    }

    // ---- codec-based file I/O (hytale-codec generated codecs) --------------

    /** Decodes a definition from JSON text via the generated codec. */
    private fun decode(text: String): MinigameDefinition {
        RawJsonReader(text.toCharArray()).use { reader ->
            return MinigameDefinitionCodec.decodeJson(reader, ExtraInfo())
                ?: throw IllegalArgumentException("definition decoded to null")
        }
    }

    /** Encodes a definition to pretty JSON text via the codec + BSON JSON writer. */
    private fun encode(definition: MinigameDefinition): String {
        val bson = MinigameDefinitionCodec.encode(definition, ExtraInfo())
        val settings = org.bson.json.JsonWriterSettings.builder()
            .outputMode(org.bson.json.JsonMode.RELAXED)
            .indent(true)
            .build()
        return bson.asDocument().toJson(settings)
    }

    fun all(): List<MinigameDefinition> {
        val dataDir = synchronized(definitions) { definitions.values.toList() }
        if (dataDir.isEmpty()) return MinigameAssets.definitions()
        val owned = dataDir.mapTo(HashSet()) { it.gameTypeId }
        return dataDir + MinigameAssets.definitions().filter { it.gameTypeId !in owned }
    }

    /** Data-dir definitions first — an editor save shadows a shipped asset. */
    fun get(gameTypeId: String): MinigameDefinition? =
        synchronized(definitions) { definitions[gameTypeId] }
            ?: MinigameAssets.definitions().firstOrNull { it.gameTypeId == gameTypeId }

    /**
     * Resolves `/minigame create [gameTypeId] [mapId]` into the pieces to
     * spawn ([ResolvedMinigame.Ready]) or a clean sender message (failure).
     *
     * - An unknown game type (no definition, no asset) resolves **bare**:
     *   the legacy raw create — an unconfigured Waiting instance.
     * - A data-dir definition owns its game type id end to end: teams,
     *   behaviors and (without a map argument) its instanceAsset — exactly
     *   the pre-asset behavior. A map argument overrides the instance target.
     * - Asset-backed games: teams/behaviors from the minigame asset; without
     *   a map argument a random **playable** map is picked; with one, that
     *   map. A map is playable when its asset exists and its instance asset
     *   is provided by some pack.
     */
    fun resolve(gameTypeId: String, mapId: String?): ResolvedMinigame {
        val dataDirDef: MinigameDefinition? = synchronized(definitions) { definitions[gameTypeId] }
        val game = MinigameAssets.minigameById(gameTypeId)
        if (dataDirDef == null && game == null) {
            // Legacy raw create: the game type was never configured.
            return ResolvedMinigame.Ready(
                gameTypeId = gameTypeId,
                displayName = gameTypeId,
                instanceAsset = "",
                mapId = null,
                teams = emptyList(),
                behaviors = emptyList(),
                minPlayers = 0,
                maxPlayers = 0,
                bare = true,
            )
        }

        // Map argument: pins the instance target, whatever layer owns teams.
        if (mapId != null) {
            val map = MinigameAssets.mapById(mapId)
                ?: return ResolvedMinigame.Failure(
                    gameTypeId, "Unknown map '$mapId' — maps come from MinigameMap assets (Server/MinigameMaps).")
            val instance = map.instance
            if (instance == null || !InstancesPlugin.doesInstanceAssetExist(instance)) {
                return ResolvedMinigame.Failure(
                    gameTypeId, "Map '$mapId' names instance asset '$instance', which does not exist.")
            }
            if (!teamsOf(dataDirDef, game).any()) {
                return ResolvedMinigame.Failure(
                    gameTypeId, "Minigame '$gameTypeId' declares no Teams.")
            }
            return ResolvedMinigame.Ready(
                gameTypeId = gameTypeId,
                displayName = displayNameOf(dataDirDef, game, map.displayName, mapId),
                instanceAsset = instance,
                mapId = mapId,
                teams = teamsOf(dataDirDef, game),
                behaviors = behaviorsOf(dataDirDef, game),
                minPlayers = minPlayersOf(dataDirDef, game),
                maxPlayers = maxPlayersOf(dataDirDef, game, map),
                bare = false,
                )
        }

        // A data-dir definition owns the game type id when present: exactly
        // the pre-asset create behavior (its instanceAsset may be empty for
        // the legacy in-world mode).
        if (dataDirDef != null) {
            return ResolvedMinigame.Ready(
                gameTypeId = dataDirDef.gameTypeId,
                displayName = dataDirDef.displayName.ifEmpty { dataDirDef.gameTypeId },
                instanceAsset = dataDirDef.instanceAsset,
                mapId = null,
                teams = dataDirDef.teams,
                behaviors = dataDirDef.behaviors,
                minPlayers = minPlayersOf(dataDirDef, game),
                maxPlayers = maxPlayersOf(dataDirDef, game, null),
                bare = false,
                )
        }

        checkNotNull(game)
        if (game.teams.isEmpty()) {
            return ResolvedMinigame.Failure(gameTypeId, "Minigame '$gameTypeId' declares no Teams.")
        }

        // Random playable map when omitted. Playable = known map asset with
        // an instance asset some pack provides.
        val playable = MinigameAssets.playableMaps(game)
        if (game.maps.isNotEmpty() && playable.isEmpty()) {
            return ResolvedMinigame.Failure(
                gameTypeId,
                "Minigame '$gameTypeId' lists ${game.maps.size} map(s) but none is playable — " +
                    "every listed map is unknown or its instance asset is missing.")
        }
        if (game.maps.isEmpty()) {
            return ResolvedMinigame.Ready(
                gameTypeId = gameTypeId,
                displayName = game.displayName ?: gameTypeId,
                instanceAsset = "",
                mapId = null,
                teams = game.teams,
                behaviors = game.behaviors,
                minPlayers = minPlayersOf(dataDirDef, game),
                maxPlayers = maxPlayersOf(dataDirDef, game, null),
                bare = false,
                )
        }

        val chosen = playable.random()
        return ResolvedMinigame.Ready(
            gameTypeId = gameTypeId,
            displayName = displayNameOf(dataDirDef, game, chosen.displayName, chosen.id),
            instanceAsset = checkNotNull(chosen.instance),
            mapId = chosen.id,
            teams = game.teams,
            behaviors = game.behaviors,
            minPlayers = minPlayersOf(dataDirDef, game),
            maxPlayers = maxPlayersOf(dataDirDef, game, chosen),
            bare = false,
            )
    }

    /**
     * Effective thresholds: a map's own MinPlayers/MaxPlayers **overrides**;
     * a data-dir shadow definition only counts when it sets a value (> 0);
     * otherwise the minigame asset's value (0 = auto-start disabled /
     * uncapped). Bare raw creates have none of the three.
     */
    private fun minPlayersOf(def: MinigameDefinition?, game: gg.ginco.minigame.asset.MinigameAsset?, map: gg.ginco.minigame.asset.MinigameMapAsset? = null): Int =
        map?.minPlayers
            ?: def?.minPlayers?.takeIf { it > 0 }
            ?: game?.minPlayers
            ?: 0

    private fun maxPlayersOf(def: MinigameDefinition?, game: gg.ginco.minigame.asset.MinigameAsset?, map: gg.ginco.minigame.asset.MinigameMapAsset?): Int =
        map?.maxPlayers
            ?: def?.maxPlayers?.takeIf { it > 0 }
            ?: game?.maxPlayers
            ?: 0

    private fun teamsOf(dataDirDef: MinigameDefinition?, game: gg.ginco.minigame.asset.MinigameAsset?): List<TeamDefinition> =
        dataDirDef?.teams ?: game?.teams.orEmpty()

    private fun behaviorsOf(dataDirDef: MinigameDefinition?, game: gg.ginco.minigame.asset.MinigameAsset?): List<String> =
        dataDirDef?.behaviors ?: game?.behaviors.orEmpty()

    private fun displayNameOf(
        dataDirDef: MinigameDefinition?,
        game: gg.ginco.minigame.asset.MinigameAsset?,
        mapDisplayName: String?,
        fallback: String,
    ): String = mapDisplayName
        ?: dataDirDef?.displayName?.takeUnless { it.isEmpty() }
        ?: game?.displayName
        ?: fallback

    /** Persists [definition] to disk and the cache. */
    fun save(definition: MinigameDefinition) {
        synchronized(definitions) { definitions[definition.gameTypeId] = definition }
        val file = dir.resolve(definition.gameTypeId + ".json")
        Files.createDirectories(file.parent)
        Files.writeString(file, encode(definition))
    }

    /** Drops [gameTypeId] from the cache and from disk. Returns true when a file was deleted. */
    fun remove(gameTypeId: String): Boolean {
        synchronized(definitions) { definitions.remove(gameTypeId) }
        return Files.deleteIfExists(dir.resolve(gameTypeId + ".json"))
    }

    /**
     * Captures a live instance's teams into a [MinigameDefinition] and
     * persists it (the editor save path). Regions are NOT captured: they are
     * world content (chunk-store resource) and persist with the world.
     */
    fun saveFromInstance(store: Store<EntityStore>, controllerRef: Ref<EntityStore>, gameTypeId: String): MinigameDefinition {
        // The editor captures teams only; the instance asset and display name
        // belong to the definition already on disk and would otherwise be
        // wiped by every save.
        val existing = get(gameTypeId)
        val def = MinigameDefinition(
            gameTypeId = gameTypeId,
            displayName = existing?.displayName ?: "",
            instanceAsset = existing?.instanceAsset ?: "",
            teams = Teams.of(store, controllerRef).map {
                TeamDefinition(
                    name = it.data.name,
                    color = it.data.color,
                    maxPlayers = it.data.maxPlayers,
                )
            },
        )
        save(def)
        return def
    }

    /**
     * Spawns everything a definition describes onto a freshly created
     * instance: its teams. (Regions are world content — they already exist
     * in the world and need no spawning.)
     */
    fun spawnInto(store: Store<EntityStore>, controllerRef: Ref<EntityStore>, definition: MinigameDefinition): Int {
        var created = 0
        for (team in definition.teams) {
            if (Teams.createTeam(store, controllerRef, TeamData(team.name, team.color, 0, team.maxPlayers)) != null) created++
        }
        return created
    }
}
