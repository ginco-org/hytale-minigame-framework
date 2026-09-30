package gg.ginco.minigame.definition

import com.hypixel.hytale.codec.ExtraInfo
import com.hypixel.hytale.codec.util.RawJsonReader
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import gg.ginco.minigame.component.TeamData
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

    fun all(): List<MinigameDefinition> =
        synchronized(definitions) { definitions.values.toList() }

    fun get(gameTypeId: String): MinigameDefinition? =
        synchronized(definitions) { definitions[gameTypeId] }

    /** Persists [definition] to disk and the cache. */
    fun save(definition: MinigameDefinition) {
        synchronized(definitions) { definitions[definition.gameTypeId] = definition }
        val file = dir.resolve(definition.gameTypeId + ".json")
        Files.createDirectories(file.parent)
        Files.writeString(file, encode(definition))
    }

    /**
     * Captures a live instance's teams into a [MinigameDefinition] and
     * persists it (the editor save path). Regions are NOT captured: they are
     * world content (chunk-store resource) and persist with the world.
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
