package com.my.kizzy.data.rpc

import android.content.Context
import android.content.Intent
import android.util.Log
import com.my.kizzy.preference.Prefs
import kizzy.gateway.DiscordWebSocket
import kizzy.gateway.DiscordWebSocketImpl
import kizzy.gateway.entities.presence.Activity
import kizzy.gateway.entities.presence.Presence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Sends activities from both companion apps through one Discord Gateway session.
 *
 * The Kizzy companion only forwards its activity payload. Ripro owns the Gateway connection
 * and merges all active sources before publishing the combined activity list.
 */
object RpcPresenceBridge {
    const val RIPRO_PACKAGE = "com.my.kizzy.ripro.bridge"
    const val BRIDGE_PERMISSION = "com.my.kizzy.permission.RPC_BRIDGE"
    const val ACTION_UPDATE = "com.my.kizzy.RPC_BRIDGE_UPDATE"
    const val ACTION_REMOVE = "com.my.kizzy.RPC_BRIDGE_REMOVE"
    const val EXTRA_SOURCE = "source_id"
    const val EXTRA_PRESENCE = "presence_json"

    private const val MAX_SOURCE_ID_LENGTH = 128
    private const val MAX_PRESENCE_JSON_LENGTH = 65_536
    private const val MAX_SOURCES = 16
    private const val MAX_ACTIVITIES_PER_SOURCE = 10

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()
    private val activeSources = linkedMapOf<String, Presence>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<Unit>(Channel.CONFLATED)

    private var gateway: DiscordWebSocket? = null
    private var connectRequested = false
    private var lastSentAt = 0L

    init {
        scope.launch {
            for (ignored in pending) {
                // User Gateway presence updates are rate-limited. Coalesce rapid updates and
                // keep a safe interval between outgoing presence payloads.
                val waitMs = 4_100L - (System.currentTimeMillis() - lastSentAt)
                if (waitMs > 0) delay(waitMs)

                val snapshot = synchronized(lock) {
                    activeSources.values.flatMap { it.activities.orEmpty() }
                }
                val token = Prefs[Prefs.TOKEN, ""]
                if (token.isBlank()) {
                    Log.w("RiproBridge", "Discord token is empty; sign in to Discord inside Ripro first")
                    continue
                }

                var socket: DiscordWebSocket? = null
                try {
                    val activeSocket = synchronized(lock) {
                        gateway ?: DiscordWebSocketImpl(token).also { gateway = it }
                    }
                    socket = activeSocket
                    val shouldConnect = synchronized(lock) {
                        if (connectRequested) false else {
                            connectRequested = true
                            true
                        }
                    }
                    if (shouldConnect) activeSocket.connect()

                    val status = synchronized(lock) {
                        activeSources.values.lastOrNull()?.status ?: "online"
                    }
                    // sendActivity waits for Discord READY. Bound that wait so a failed
                    // login/network connection cannot permanently stall the update queue.
                    kotlinx.coroutines.withTimeout(25_000L) {
                        activeSocket.sendActivity(
                            Presence(
                                activities = snapshot,
                                afk = false,
                                since = 0L,
                                status = status
                            )
                        )
                    }
                    lastSentAt = System.currentTimeMillis()
                    Log.i("RiproBridge", "Presence update sent; activity_count=${snapshot.size}")
                } catch (error: Exception) {
                    Log.e("RiproBridge", "Discord Gateway update failed: ${error.message}", error)
                    runCatching { socket?.close() }
                    val shouldRetry = synchronized(lock) {
                        if (gateway === socket) gateway = null
                        connectRequested = false
                        activeSources.isNotEmpty()
                    }
                    // Retry the latest merged state after a short backoff.
                    if (shouldRetry) {
                        delay(3_000L)
                        pending.trySend(Unit)
                    }
                }
            }
        }
    }

    fun submit(context: Context, sourceId: String, presence: Presence) {
        if (!isValidSourceId(sourceId)) return
        val boundedPresence = presence.copy(
            activities = presence.activities.orEmpty().take(MAX_ACTIVITIES_PER_SOURCE)
        )

        if (context.packageName != RIPRO_PACKAGE) {
            val intent = Intent(ACTION_UPDATE)
                .setPackage(RIPRO_PACKAGE)
                .putExtra(EXTRA_SOURCE, sourceId)
                .putExtra(EXTRA_PRESENCE, json.encodeToString(boundedPresence))
            context.sendBroadcast(intent, BRIDGE_PERMISSION)
            return
        }

        synchronized(lock) {
            if (!activeSources.containsKey(sourceId) && activeSources.size >= MAX_SOURCES) return
            activeSources[sourceId] = boundedPresence
        }
        pending.trySend(Unit)
    }

    fun remove(context: Context, sourceId: String) {
        if (!isValidSourceId(sourceId)) return
        if (context.packageName != RIPRO_PACKAGE) {
            val intent = Intent(ACTION_REMOVE)
                .setPackage(RIPRO_PACKAGE)
                .putExtra(EXTRA_SOURCE, sourceId)
            context.sendBroadcast(intent, BRIDGE_PERMISSION)
            return
        }

        synchronized(lock) {
            activeSources.remove(sourceId)
        }
        pending.trySend(Unit)
    }

    private fun isValidSourceId(sourceId: String): Boolean =
        sourceId.isNotBlank() && sourceId.length <= MAX_SOURCE_ID_LENGTH

    fun receive(context: Context, intent: Intent) {
        if (context.packageName != RIPRO_PACKAGE) return
        val sourceId = intent.getStringExtra(EXTRA_SOURCE) ?: return
        if (!isValidSourceId(sourceId)) return

        when (intent.action) {
            ACTION_UPDATE -> {
                val payload = intent.getStringExtra(EXTRA_PRESENCE) ?: return
                if (payload.length > MAX_PRESENCE_JSON_LENGTH) {
                    Log.w("RiproBridge", "Rejected oversized bridge payload")
                    return
                }
                val decoded = runCatching { json.decodeFromString<Presence>(payload) }.getOrNull()
                    ?: return
                val presence = decoded.copy(
                    activities = decoded.activities.orEmpty().take(MAX_ACTIVITIES_PER_SOURCE)
                )
                synchronized(lock) {
                    if (!activeSources.containsKey(sourceId) && activeSources.size >= MAX_SOURCES) {
                        Log.w("RiproBridge", "Rejected bridge source limit")
                        return
                    }
                    activeSources[sourceId] = presence
                }
                pending.trySend(Unit)
            }
            ACTION_REMOVE -> {
                synchronized(lock) {
                    activeSources.remove(sourceId)
                }
                pending.trySend(Unit)
            }
        }
    }
}
