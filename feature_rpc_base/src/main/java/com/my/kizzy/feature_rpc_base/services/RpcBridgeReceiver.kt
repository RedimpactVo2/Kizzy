package com.my.kizzy.feature_rpc_base.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.my.kizzy.data.rpc.RpcPresenceBridge

class RpcBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RpcPresenceBridge.receive(context.applicationContext, intent)
    }
}
