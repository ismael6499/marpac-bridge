package com.nitsu.marpacbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import android.os.Build;

public class MarpacReceiver extends BroadcastReceiver {

    private static final String TAG = "MarpacReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Intent received with action: " + action);

        if ("com.nitsu.marpacbridge.ACTION_CONTROL".equals(action)) {
            // Load saved configuration
            SharedPreferences prefs = context.getSharedPreferences("MarpacBridgePrefs", Context.MODE_PRIVATE);
            String mac = prefs.getString("mac_address", "");
            String unitNum = prefs.getString("unit_number", "");

            String command = intent.getStringExtra("command");
            if (command == null || command.isEmpty()) {
                Log.w(TAG, "Missing required string extra 'command'");
                return;
            }

            // If Tasker / Watch is requesting current state
            if ("get_state".equalsIgnoreCase(command) || "get_volume".equalsIgnoreCase(command) || "query_state".equalsIgnoreCase(command)) {
                int lastPower = prefs.getInt("last_power_state", 0);
                int lastVol = prefs.getInt("last_volume", 5);
                broadcastCurrentState(context, lastPower, lastVol);
                return;
            }

            if (mac.isEmpty() || "AA:BB:CC:DD:EE:FF".equals(mac)) {
                Log.w(TAG, "Device MAC address is not configured yet! Please open the app first.");
                return;
            }

            // Immediately update SharedPreferences state if provided in intent
            SharedPreferences.Editor editor = prefs.edit();
            boolean stateUpdated = false;

            if (intent.hasExtra("state")) {
                int newState = intent.getIntExtra("state", 0);
                editor.putInt("last_power_state", newState);
                stateUpdated = true;
            }

            if (intent.hasExtra("level")) {
                int newVol = intent.getIntExtra("level", 5);
                editor.putInt("last_volume", newVol);
                stateUpdated = true;
            } else if (intent.hasExtra("volume")) {
                int newVol = intent.getIntExtra("volume", 5);
                editor.putInt("last_volume", newVol);
                stateUpdated = true;
            }

            if (stateUpdated) {
                editor.apply();
                broadcastCurrentState(context, prefs.getInt("last_power_state", 0), prefs.getInt("last_volume", 5));
            }

            // Forward command to BLEService
            Intent serviceIntent = new Intent(context, BLEService.class);
            serviceIntent.setAction(BLEService.ACTION_EXECUTE);
            serviceIntent.putExtra("command", command);
            serviceIntent.putExtra("mac_address", mac);
            serviceIntent.putExtra("unit_number", unitNum);

            // Copy relevant command arguments
            if (intent.hasExtra("state")) {
                serviceIntent.putExtra("state", intent.getIntExtra("state", 0));
            }
            if (intent.hasExtra("level")) {
                serviceIntent.putExtra("level", intent.getIntExtra("level", 5));
            }
            if (intent.hasExtra("index")) {
                serviceIntent.putExtra("index", intent.getIntExtra("index", 0));
            }
            if (intent.hasExtra("start_time")) {
                serviceIntent.putExtra("start_time", intent.getStringExtra("start_time"));
            }
            if (intent.hasExtra("end_time")) {
                serviceIntent.putExtra("end_time", intent.getStringExtra("end_time"));
            }
            if (intent.hasExtra("days")) {
                serviceIntent.putExtra("days", intent.getIntExtra("days", 127));
            }
            if (intent.hasExtra("volume")) {
                serviceIntent.putExtra("volume", intent.getIntExtra("volume", 5));
            }

            try {
                Log.d(TAG, "Starting BLEService for command (foreground): " + command);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Exception e) {
                Log.w(TAG, "Foreground service start blocked: " + e.getMessage() + ". Falling back to background service.");
                try {
                    context.startService(serviceIntent);
                } catch (Exception ex) {
                    Log.e(TAG, "All service starts failed: " + ex.getMessage());
                }
            }
        }
    }

    private void broadcastCurrentState(Context context, int powerState, int volume) {
        String timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        Intent stateIntent = new Intent("com.nitsu.marpacbridge.ACTION_STATE_CHANGED");
        stateIntent.putExtra("power", powerState);
        stateIntent.putExtra("power_text", powerState == 1 ? "ON" : "OFF");
        stateIntent.putExtra("volume", volume);
        stateIntent.putExtra("last_update", timestamp);
        context.sendBroadcast(stateIntent);

        // Also broadcast explicit response intent for Tasker queries
        Intent responseIntent = new Intent("com.nitsu.marpacbridge.ACTION_STATE_RESPONSE");
        responseIntent.putExtra("power", powerState);
        responseIntent.putExtra("power_text", powerState == 1 ? "ON" : "OFF");
        responseIntent.putExtra("volume", volume);
        responseIntent.putExtra("last_update", timestamp);
        context.sendBroadcast(responseIntent);
    }
}
