package com.my.kizzy.data.rpc

import android.content.Context
import android.content.Intent
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
    const val RIPRO_PACKAGE = "com.my.kizzy.multirpc"
    const val BRIDGE_PERMISSION = "com.my.kizzy.permission.RPC_BRIDGE"
    const val ACTION_UPDATE = "com.my.kizzy.RPC_BRIDGE_UPDATE"
    const val ACTION_REMOVE = "com.my.kizzy.RPC_BRIDGE_REMOVE"
    const val EXTRA_SOURCE = "source_id"
    const val EXTRA_PRESENCE = "presence_json"

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
                if (token.isBlank()) continue

                try {
                    val socket = synchronized(lock) {
                        gateway ?: DiscordWebSocketImpl(token).also { gateway = it }
                    }
                    val shouldConnect = synchronized(lock) {
                        if (connectRequested) false else {
                            connectRequested = true
                            true
                        }
                    }
                    if (shouldConnect) socket.connect()

                    val status = synchronized(lock) {
                        activeSources.values.lastOrNull()?.status ?: "online"
                    }
                    socket.sendActivity(
                        Presence(
                            activities = snapshot,
                            afk = true,
                            since = 0L,
                            status = status
                        )
                    )
                    lastSentAt = System.currentTimeMillis()
                } catch (_: Exception) {
                    synchronized(lock) {
                        connectRequested = false
                    }
                }
            }
        }
    }

    fun submit(context: Context, sourceId: String, presence: Presence) {
        if (context.packageName != RIPRO_PACKAGE) {
            val intent = Intent(ACTION_UPDATE)
                .setPackage(RIPRO_PACKAGE)
                .putExtra(EXTRA_SOURCE, sourceId)
                .putExtra(EXTRA_PRESENCE, json.encodeToString(presence))
            context.sendBroadcast(intent, BRIDGE_PERMISSION)
            return
        }

        synchronized(lock) {
            activeSources[sourceId] = presence
        }
        pending.trySend(Unit)
    }

    fun remove(context: Context, sourceId: String) {
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

    fun receive(context: Context, intent: Intent) {
        if (context.packageName != RIPRO_PACKAGE) return
        val sourceId = intent.getStringExtra(EXTRA_SOURCE) ?: return

        when (intent.action) {
            ACTION_UPDATE -> {
                val payload = intent.getStringExtra(EXTRA_PRESENCE) ?: return
                val presence = runCatching { json.decodeFromString<Presence>(payload) }.getOrNull()
                    ?: return
                synchronized(lock) {
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
