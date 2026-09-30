package gg.ginco.minigame.stats

import gg.ginco.jellyparty.codec.annotations.SerializableObject
import gg.ginco.jellyparty.codec.annotations.SerialWithCodec

/**
 * Lifetime counters for one game type: instance-wide totals plus per-player
 * entries keyed by uuid (as string — JSON object keys). Values accumulate
 * additively across instances.
 */
@SerializableObject(extraImports = ["com.hypixel.hytale.codec.codecs.map.MapCodec"])
data class LifetimeStatsFile(
    var gameTypeId: String = "",
    @SerialWithCodec("MapCodec(Codec.INTEGER) { java.util.HashMap() }")
    var counters: MutableMap<String, Int> = mutableMapOf(),
    var players: MutableList<LifetimePlayerEntry> = mutableListOf(),
)

/** One player's lifetime counters for a game type. */
@SerializableObject(extraImports = ["com.hypixel.hytale.codec.codecs.map.MapCodec"])
data class LifetimePlayerEntry(
    var uuid: String = "",
    @SerialWithCodec("MapCodec(Codec.INTEGER) { java.util.HashMap() }")
    var counters: MutableMap<String, Int> = mutableMapOf()
)
