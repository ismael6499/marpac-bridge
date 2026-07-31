package com.nitsu.marpacbridge;

import android.content.Intent;
import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class MarpacTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile != null) {
            SharedPreferences prefs = getSharedPreferences("MarpacBridgePrefs", MODE_PRIVATE);
            int lastState = prefs.getInt("last_power_state", 0);
            tile.setState(lastState == 1 ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        Tile tile = getQsTile();
        if (tile != null) {
            SharedPreferences prefs = getSharedPreferences("MarpacBridgePrefs", MODE_PRIVATE);
            String mac = prefs.getString("mac_address", "");
            String unitNum = prefs.getString("unit_number", "");

            if (mac.isEmpty() || "AA:BB:CC:DD:EE:FF".equals(mac)) {
                return; // Device not configured
            }

            int lastState = prefs.getInt("last_power_state", 0);
            int newState = (lastState == 1) ? 0 : 1;

            // Save new state
            prefs.edit().putInt("last_power_state", newState).apply();

            // Update UI tile state instantly
            tile.setState(newState == 1 ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.updateTile();

            // Broadcast state update
            int curVol = prefs.getInt("last_volume", 5);
            String timeStr = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            Intent stateIntent = new Intent("com.nitsu.marpacbridge.ACTION_STATE_CHANGED");
            stateIntent.putExtra("power", newState);
            stateIntent.putExtra("power_text", newState == 1 ? "ON" : "OFF");
            stateIntent.putExtra("volume", curVol);
            stateIntent.putExtra("last_update", timeStr);
            sendBroadcast(stateIntent);

            // Trigger BLEService to toggle the machine
            Intent serviceIntent = new Intent(this, BLEService.class);
            serviceIntent.setAction(BLEService.ACTION_EXECUTE);
            serviceIntent.putExtra("command", "power");
            serviceIntent.putExtra("state", newState);
            serviceIntent.putExtra("mac_address", mac);
            serviceIntent.putExtra("unit_number", unitNum);
            startService(serviceIntent);
        }
    }
}
