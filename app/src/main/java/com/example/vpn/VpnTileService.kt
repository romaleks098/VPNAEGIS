package com.example.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.MainActivity
import com.example.model.VpnConnectionState

/**
 * Плитка быстрых настроек (Quick Settings TileService) в шторке уведомлений Android
 * для мгновенного включения/отключения VPN-туннеля sing-box.
 */
class VpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        val currentState = MyVpnService.telemetryState.value.connectionState

        if (currentState == VpnConnectionState.CONNECTED || currentState == VpnConnectionState.CONNECTING) {
            val stopIntent = Intent(this, MyVpnService::class.java).apply {
                action = MyVpnService.ACTION_DISCONNECT
            }
            startService(stopIntent)
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                subtitle = "Отключён"
                updateTile()
            }
        } else {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                // Если разрешение VPN ещё не выдано, открываем MainActivity для диалога согласия
                val openActivity = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivityAndCollapse(openActivity)
            } else {
                val startIntent = Intent(this, MyVpnService::class.java).apply {
                    action = MyVpnService.ACTION_CONNECT
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(startIntent)
                } else {
                    startService(startIntent)
                }
                qsTile?.apply {
                    state = Tile.STATE_ACTIVE
                    subtitle = "VLESS + WARP"
                    updateTile()
                }
            }
        }
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        val telemetry = MyVpnService.telemetryState.value
        when (telemetry.connectionState) {
            VpnConnectionState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "AegisBox VPN"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = telemetry.activeNode?.countryCode ?: "Защищено"
                }
            }
            VpnConnectionState.CONNECTING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Подключение…"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "AegisBox VPN"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Отключён"
                }
            }
        }
        tile.updateTile()
    }
}
