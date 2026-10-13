package gg.ginco.minigame.asset

import com.hypixel.hytale.assetstore.AssetExtraInfo
import com.hypixel.hytale.assetstore.AssetRegistry
import com.hypixel.hytale.assetstore.AssetStore
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec
import com.hypixel.hytale.assetstore.map.DefaultAssetMap
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap
import com.hypixel.hytale.builtin.instances.InstancesPlugin
import com.hypixel.hytale.codec.codecs.array.ArrayCodec
import com.hypixel.hytale.codec.Codec
import com.hypixel.hytale.codec.KeyedCodec
import com.hypixel.hytale.codec.validation.Validators
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.asset.HytaleAssetStore
import com.hypixel.hytale.server.core.plugin.JavaPlugin
import gg.ginco.minigame.definition.MinigameDefinition
import gg.ginco.minigame.definition.TeamDefinition
import gg.ginco.minigame.definition.TeamDefinitionCodec

/**
 * The framework-owned minigame asset types. A minigame plugin ships *data*:
 * it bundles an asset pack (its jar with `IncludesAssetPack: true` in the
 * manifest) carrying JSON under the pack-relative paths below — the framework
 * owns the asset types, the stores and the compiled [MinigameDefinition] layer
 * ([gg.ginco.minigame.definition.DefinitionStore] serves data-dir definitions
 * first, asset-derived ones for every id the data dir does not own).
 *
 * `/minigame create <minigame> [<map>]` resolves the play through
 * [gg.ginco.minigame.definition.DefinitionStore.resolve]: the map argument
 * pins one instance, omitting it picks a random playable map of the minigame
 * (or its data-dir definition's instanceAsset when one shadows the game).
 *
 * Asset ids are the file name minus extension. Ids across all minigames share
 * these namespaces, so minigames should pick unique-enough file names
 * (convention-scoped, not enforced).
 *
 * Both stores come up during framework setup(). Pack registration for each
 * minigame plugin's jar happens *after* that plugin's setup0(), and
 * `AssetPackRegisterEvent` feeds new packs into already-registered stores —
 * so assets from packs of plugins that load after the framework still land.
 * Benign noise: during setup, `initPendingStores` logs a SEVERE per pack that
 * lacks the paths below (most packs do).
 */
object MinigameAssets {
    private val LOGGER = HytaleLogger.get("MinigameFramework")

    /** Pack-relative store paths (resolved under `<packRoot>/Server/`). */
    const val PATH_MINIGAMES = "Minigames"
    const val PATH_MAPS = "MinigameMaps"

    /**
     * Registers both asset stores on [plugin]'s asset registry. Fresh store
     * instances per setup: the per-plugin registry unregisters them on plugin
     * unload, and re-registering a used store instance fails.
     */
    fun registerStores(plugin: JavaPlugin) {
        val minigames: HytaleAssetStore<String, MinigameAsset, DefaultAssetMap<String, MinigameAsset>> =
            HytaleAssetStore.builder(
                String::class.java,
                MinigameAsset::class.java,
                DefaultAssetMap<String, MinigameAsset>(),
            )
                .setPath(PATH_MINIGAMES)
                .setCodec(MinigameAsset.CODEC)
                .setKeyFunction(MinigameAsset::getId)
                .build()

        val maps: HytaleAssetStore<String, MinigameMapAsset, DefaultAssetMap<String, MinigameMapAsset>> =
            HytaleAssetStore.builder(
                String::class.java,
                MinigameMapAsset::class.java,
                DefaultAssetMap<String, MinigameMapAsset>(),
            )
                .setPath(PATH_MAPS)
                .setCodec(MinigameMapAsset.CODEC)
                .setKeyFunction(MinigameMapAsset::getId)
                .build()

        plugin.assetRegistry.register(minigames)
        plugin.assetRegistry.register(maps)
    }

    /**
     * Compiles every [MinigameAsset] into one [MinigameDefinition] — the
     * game type's teams/behaviors with its default map's instanceAsset (the
     * definition's identity layer; `/minigame create` picks the actual map per
     * its `resolve`). Map-less minigames compile the legacy in-world mode.
     * Warnings log loudly for author errors but never abort: one broken asset
     * must not take other minigames' definitions down.
     */
    fun definitions(): List<MinigameDefinition> {
        val games = minigames()
        if (games.isEmpty()) return emptyList()
        val mapsById = maps().associateBy { it.id }

        val definitions = ArrayList<MinigameDefinition>(games.size)
        for (game in games) {
            if (game.teams.isEmpty()) {
                LOGGER.atWarning().log("Minigame '${game.id}' declares no Teams — skipping")
                continue
            }

            // Loud author validation for listed maps (resolve() refuses them
            // at create time; this is boot-time visibility).
            for (mapId in game.maps) {
                val map = mapsById[mapId]
                when {
                    map == null -> LOGGER.atWarning().log(
                        "Minigame '${game.id}' lists unknown map '$mapId'")
                    !InstancesPlugin.doesInstanceAssetExist(map.instance ?: "") ->
                        LOGGER.atWarning().log(
                            "Minigame '${game.id}' lists map '$mapId' whose instance asset '${map.instance}' no asset pack provides")
                }
            }

            val defaultInstance = game.defaultMap
                ?.let(mapsById::get)?.instance
                ?.takeIf { InstancesPlugin.doesInstanceAssetExist(it) }
                .orEmpty()
            definitions.add(
                MinigameDefinition(
                    gameTypeId = game.id,
                    displayName = game.displayName ?: game.id,
                    instanceAsset = defaultInstance,
                    teams = game.teams,
                    behaviors = game.behaviors,
                    minPlayers = game.minPlayers,
                    maxPlayers = game.maxPlayers,
                ))
        }
        return definitions
    }

    // ---- lookups (create-time resolution --------------------------------)

    fun minigames(): List<MinigameAsset> {
        val store: AssetStore<String, MinigameAsset, DefaultAssetMap<String, MinigameAsset>> =
            AssetRegistry.getAssetStore(MinigameAsset::class.java) ?: return emptyList()
        return store.assetMap.assetMap.values.sortedBy { it.id }
    }

    fun maps(): List<MinigameMapAsset> {
        val store: AssetStore<String, MinigameMapAsset, DefaultAssetMap<String, MinigameMapAsset>> =
            AssetRegistry.getAssetStore(MinigameMapAsset::class.java) ?: return emptyList()
        return store.assetMap.assetMap.values.sortedBy { it.id }
    }

    fun minigameById(id: String): MinigameAsset? = minigames().firstOrNull { it.id == id }

    fun mapById(id: String): MinigameMapAsset? = maps().firstOrNull { it.id == id }

    /**
     * The listed maps of [game] that can actually be played — known map assets
     * with an existing (some pack's) instance asset. Unplayable ones are
     * skipped silently here; boot-time warnings point at each of them.
     */
    fun playableMaps(game: MinigameAsset): List<MinigameMapAsset> =
        game.maps.mapNotNull(::mapById).filter { map ->
            val instance = map.instance ?: return@filter false
            InstancesPlugin.doesInstanceAssetExist(instance)
        }
}

/**
 * The minigame asset (asset id = file name): registers a game type —
 * display name, teams, behaviors and its playable maps.
 */
class MinigameAsset : JsonAssetWithMap<String, DefaultAssetMap<String, MinigameAsset>> {
    var data: AssetExtraInfo.Data? = null
    private var assetId: String = ""
    override fun getId(): String = assetId

    var displayName: String? = null
    var defaultMap: String? = null
    var maps: List<String> = emptyList()
    var behaviors: List<String> = emptyList()
    var teams: List<TeamDefinition> = emptyList()
    /**
     * Player thresholds for auto-start and the join cap. A map asset's own
     * MinPlayers/MaxPlayers override these per map; `<= 0` means: MinPlayers
     * disables auto-start (admin start only), MaxPlayers uncapped.
     */
    var minPlayers: Int = 0
    var maxPlayers: Int = 0

    companion object {
        val CODEC: AssetBuilderCodec<String, MinigameAsset> = AssetBuilderCodec.builder(
            MinigameAsset::class.java,
            { MinigameAsset() },
            Codec.STRING,
            { a, s -> a.assetId = s }, { a -> a.assetId },
            { a, d -> a.data = d }, { a -> a.data },
        )
            .appendInherited(
                KeyedCodec("DisplayName", Codec.STRING),
                { a, s -> a.displayName = s }, { a -> a.displayName },
                { a, p -> a.displayName = p.displayName })
            .documentation("Human-readable game type name; defaults to the asset id.")
            .add()
            .appendInherited(
                KeyedCodec("Maps", ArrayCodec(Codec.STRING) { n -> Array(n) { "" } }),
                { a, s -> a.maps = s?.toList() ?: emptyList() },
                { a -> a.maps.toTypedArray() },
                { a, p -> a.maps = p.maps })
            .documentation("Map asset ids (file names under Server/MinigameMaps) this game type is playable on. Empty = legacy in-world mode.")
            .add()
            .appendInherited(
                KeyedCodec("DefaultMap", Codec.STRING),
                { a, s -> a.defaultMap = s }, { a -> a.defaultMap },
                { a, p -> a.defaultMap = p.defaultMap })
            .documentation("Which listed map the bare gameTypeId definition points at. Required once more than one map is listed.")
            .add()
            .appendInherited(
                KeyedCodec("Behaviors", ArrayCodec(Codec.STRING) { n -> Array(n) { "" } }),
                { a, s -> a.behaviors = s?.toList() ?: emptyList() },
                { a -> a.behaviors.toTypedArray() },
                { a, p -> a.behaviors = p.behaviors })
            .documentation("GameBehaviors ids activated for every instance of this game type.")
            .add()
            .appendInherited(
                KeyedCodec("MinPlayers", Codec.INTEGER),
                { a, s -> a.minPlayers = s ?: 0 }, { a -> a.minPlayers },
                { a, p -> a.minPlayers = p.minPlayers })
            .documentation("Auto-start floor: Waiting moves to a countdown once this many players are in; <= 0 = manual start only. A map's MinPlayers overrides this.")
            .add()
            .appendInherited(
                KeyedCodec("MaxPlayers", Codec.INTEGER),
                { a, s -> a.maxPlayers = s ?: 0 }, { a -> a.maxPlayers },
                { a, p -> a.maxPlayers = p.maxPlayers })
            .documentation("Join cap; <= 0 = uncapped. A map's MaxPlayers overrides this.")
            .add()
            .appendInherited(
                KeyedCodec("Teams", ArrayCodec(TeamDefinitionCodec) { n -> Array(n) { TeamDefinition() } }),
                { a, s -> a.teams = s?.toList() ?: emptyList() },
                { a -> a.teams.toTypedArray() },
                { a, p -> a.teams = p.teams })
            .documentation("Teams spawned into every instance (same codec as saved definitions: Name, Color int, MaxPlayers).")
            .add()
            .build()
    }
}

/**
 * The map asset (asset id = file name): one playable map — names the instance
 * asset (`Server/Instances/<name>`, geometry + region contract) it is played in.
 */
class MinigameMapAsset : JsonAssetWithMap<String, DefaultAssetMap<String, MinigameMapAsset>> {
    var data: AssetExtraInfo.Data? = null
    private var assetId: String = ""
    override fun getId(): String = assetId

    var displayName: String? = null
    var instance: String? = null
    /** Per-map threshold overrides; null inherits the minigame asset's value. */
    var minPlayers: Int? = null
    var maxPlayers: Int? = null

    companion object {
        val CODEC: AssetBuilderCodec<String, MinigameMapAsset> = AssetBuilderCodec.builder(
            MinigameMapAsset::class.java,
            { MinigameMapAsset() },
            Codec.STRING,
            { a, s -> a.assetId = s }, { a -> a.assetId },
            { a, d -> a.data = d }, { a -> a.data },
        )
            .appendInherited(
                KeyedCodec("DisplayName", Codec.STRING),
                { a, s -> a.displayName = s }, { a -> a.displayName },
                { a, p -> a.displayName = p.displayName })
            .documentation("Map name shown for the compiled definition; defaults to the asset id.")
            .add()
            .appendInherited(
                KeyedCodec("Instance", Codec.STRING),
                { a, s -> a.instance = s }, { a -> a.instance },
                { a, p -> a.instance = p.instance })
            .addValidator(Validators.nonNull())
            .documentation("Instance asset name under Server/Instances that carries this map's chunks and region contract.")
            .add()
            .appendInherited(
                KeyedCodec("MinPlayers", Codec.INTEGER),
                { a, s -> a.minPlayers = s }, { a -> a.minPlayers },
                { a, p -> a.minPlayers = p.minPlayers })
            .documentation("Optional override of the minigame's auto-start floor for this map.")
            .add()
            .appendInherited(
                KeyedCodec("MaxPlayers", Codec.INTEGER),
                { a, s -> a.maxPlayers = s }, { a -> a.maxPlayers },
                { a, p -> a.maxPlayers = p.maxPlayers })
            .documentation("Optional override of the minigame's join cap for this map.")
            .add()
            .build()
    }
}
