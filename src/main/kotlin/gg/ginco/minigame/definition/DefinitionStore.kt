package gg.ginco.minigame.definition

import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.TeamData
import gg.ginco.minigame.util.Regions
import gg.ginco.minigame.util.Teams
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads, caches and persists [MinigameDefinition]s as JSON files under
 * `<dataDir>/definitions/<gameTypeId>.json`.
 *
 * Initialised from the plugin's data directory at start; `/minigame defs
 * reload` re-reads from disk, `/minigame defs save` persists a live
 * instance's current teams and markers back into its definition.
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
                    val def = DefinitionJson.decode(Files.readString(file))
                    synchronized(definitions) { definitions[def.gameTypeId] = def }
                    count++
                } catch (e: Exception) {
                    System.err.println("[MinigameFramework] failed to load definition ${file.fileName}: ${e.message}")
                }
            }
        }
        return count
    }

    fun all(): List<MinigameDefinition> =
        synchronized(definitions) { definitions.values.toList() }

    fun get(gameTypeId: String): MinigameDefinition? =
        synchronized(definitions) { definitions[gameTypeId] }

    /** Persists [definition] to disk and the cache. */
    fun save(definition: MinigameDefinition) {
        synchronized(definitions) { definitions[definition.gameTypeId] = definition }
        val file = dir.resolve(definition.gameTypeId + ".json")
        Files.createDirectories(file.parent)
        Files.writeString(file, DefinitionJson.encode(definition))
    }

    /**
     * Captures a live instance's teams and markers into a
     * [MinigameDefinition] and persists it (the editor save path).
     */
    fun saveFromInstance(store: Store<EntityStore>, controllerRef: Ref<EntityStore>, gameTypeId: String): MinigameDefinition {
        val def = MinigameDefinition(
            gameTypeId = gameTypeId,
            teams = Teams.of(store, controllerRef).map {
                TeamDefinition(
                    name = it.data.name,
                    color = it.data.color,
                    maxPlayers = it.data.maxPlayers,
                )
            },
            markers = Regions.markersOf(store, controllerRef).map {
                MarkerDefinition(
                    type = it.type,
                    minX = it.bounds.min.x, minY = it.bounds.min.y, minZ = it.bounds.min.z,
                    maxX = it.bounds.max.x, maxY = it.bounds.max.y, maxZ = it.bounds.max.z,
                )
            },
        )
        save(def)
        return def
    }

    /**
     * Spawns everything a definition describes onto a freshly created
     * instance: teams (with their rosters) and region markers. Returns the
     * number of entities created.
     */
    fun spawnInto(store: Store<EntityStore>, controllerRef: Ref<EntityStore>, definition: MinigameDefinition): Int {
        var created = 0
        for (team in definition.teams) {
            if (Teams.createTeam(store, controllerRef, TeamData(team.name, team.color, 0, team.maxPlayers)) != null) created++
        }
        for (marker in definition.markers) {
            val markerRef = Regions.createMarkerWithBounds(store, controllerRef, marker.type,
                org.joml.Vector3d(marker.minX, marker.minY, marker.minZ),
                org.joml.Vector3d(marker.maxX, marker.maxY, marker.maxZ))
            if (markerRef != null) created++
        }
        return created
    }
}
