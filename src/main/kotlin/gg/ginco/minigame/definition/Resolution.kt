package gg.ginco.minigame.definition

/**
 * The result of resolving a `/minigame create <minigame> [<map>]` request
 * (see [DefinitionStore.resolve]). Either a ready-to-create game with the
 * exact pieces to spawn, or a clean failure message for the sender.
 */
sealed interface ResolvedMinigame {
    val gameTypeId: String

    /** Everything `create` needs: the pieces of a definition plus where to play. */
    data class Ready(
        override val gameTypeId: String,
        val displayName: String,
        /**
         * Instance asset the round plays in; empty means legacy in-world mode
         * (the instance lives in the world `create` was run from).
         */
        val instanceAsset: String,
        /** The map asset the play targets, or null for data-dir/legacy plays. */
        val mapId: String?,
        val teams: List<TeamDefinition>,
        val behaviors: List<String>,
        /**
         * Effective thresholds (map override, else the minigame's): auto-start
         * floor and join cap, `<= 0` = disabled/uncapped.
         */
        val minPlayers: Int,
        val maxPlayers: Int,
        /**
         * True for the legacy raw create — an unknown game type with no
         * definition and no asset: the instance is born bare (no teams, no
         * behaviors) and is set up by hand or by later commands.
         */
        val bare: Boolean,
    ) : ResolvedMinigame {
        /** The same pieces as a plain [MinigameDefinition], for spawnInto. */
        fun toDefinition(): MinigameDefinition = MinigameDefinition(
            gameTypeId = gameTypeId,
            displayName = displayName,
            instanceAsset = instanceAsset,
            teams = teams,
            behaviors = behaviors,
            minPlayers = minPlayers,
            maxPlayers = maxPlayers,
        )
    }

    /** A clean failure for the sender; no side effects happened. */
    data class Failure(
        override val gameTypeId: String,
        val message: String,
    ) : ResolvedMinigame
}
