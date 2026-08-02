package com.nitsu.marpacbridge;

import android.Manifest;
 import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

public class BLEService extends Service {

    private static final String TAG = "MarpacBLEService";

    public static final String ACTION_EXECUTE = "com.nitsu.marpacbridge.ACTION_EXECUTE";
    public static final String ACTION_STATUS_UPDATE = "com.nitsu.marpacbridge.ACTION_STATUS_UPDATE";
    public static final String ACTION_UNIT_NUMBER_FOUND = "com.nitsu.marpacbridge.ACTION_UNIT_NUMBER_FOUND";
    /** Emitted to indicate command execution state for the UI badge. */
    public static final String ACTION_CMD_STATE = "com.nitsu.marpacbridge.ACTION_CMD_STATE";
    public static final String EXTRA_CMD_STATE = "cmd_state"; // values: "PROGRESS" | "OK" | "ERROR"

    private BroadcastReceiver bondStateReceiver;
    private boolean isBondReceiverRegistered = false;

    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_UNIT_NUMBER = "unit_number";
    public static final String ACTION_LOG = "com.nitsu.marpacbridge.ACTION_LOG";
    public static final String EXTRA_LOG_LINE = "log_line";

    // UUIDs
    private static final UUID SERVICE_UUID = UUID.fromString("00005600-D102-11E1-9B23-00025B005AA5");
    private static final UUID CHAR_UUID = UUID.fromString("00005601-D102-11E1-9B23-00025B005AA5");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    
    private String deviceMacAddress;
    private String unitNumber;
    
    private final Queue<String> commandQueue = new LinkedList<>();
    private boolean isWriting = false;
    private boolean isConnecting = false;
    private boolean isConnected = false;
    private int retryCount = 0;

    private Looper serviceLooper;
    private Handler serviceHandler;
    private final Runnable disconnectRunnable = this::disconnectGatt;

    private static final String NOTIF_CHANNEL_ID = "marpac_ble_channel";
    private static final int NOTIF_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        HandlerThread thread = new HandlerThread("BLEServiceThread");
        thread.start();
        serviceLooper = thread.getLooper();
        serviceHandler = new Handler(serviceLooper);

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Marpac Bridge active"));

        // Broadcast receiver for bonding state updates
        bondStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                    BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                    if (dev != null && dev.getAddress().equalsIgnoreCase(deviceMacAddress)) {
                        addLog("Bond state changed to: " + bondState + " for device: " + dev.getAddress());
                        if (bondState == BluetoothDevice.BOND_BONDED) {
                            addLog("Device bonded successfully! Resuming command execution queue...");
                            serviceHandler.post(BLEService.this::processQueue);
                        } else if (bondState == BluetoothDevice.BOND_NONE) {
                            addLog("Device pairing failed or was removed.");
                        }
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(bondStateReceiver, filter);
        isBondReceiverRegistered = true;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "Marpac BLE Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background BLE connection to Yogasleep device");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, notifIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, NOTIF_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("Marpac Bridge")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_EXECUTE.equals(intent.getAction())) {
            String command = intent.getStringExtra("command");
            String mac = intent.getStringExtra("mac_address");
            String savedUnit = intent.getStringExtra("unit_number");

            if (mac == null || mac.isEmpty() || "AA:BB:CC:DD:EE:FF".equals(mac)) {
                broadcastStatus("Error: Invalid MAC");
                stopSelf();
                return START_NOT_STICKY;
            }

            final String targetMac = mac;
            final String newUnit = intent.getStringExtra("unit_number");

            serviceHandler.post(() -> {
                addLog("New command received: " + command);
                broadcastCmdState("PROGRESS");
                
                if ("disconnect".equals(command)) {
                    addLog("Manual disconnect requested.");
                    disconnectGatt();
                    return;
                }
                
                if ("unpair".equals(command)) {
                    addLog("Manual unpair (removeBond) requested.");
                    unpairDevice();
                    return;
                }
                
                // Update unit number in memory if valid
                if (newUnit != null && !newUnit.isEmpty() && !"Not Queried".equals(newUnit) && !"0000".equals(newUnit)) {
                    this.unitNumber = newUnit;
                } else if (this.unitNumber == null || "0000".equals(this.unitNumber) || "Not Queried".equals(this.unitNumber) || this.unitNumber.isEmpty()) {
                    this.unitNumber = "0000";
                }

                if (targetMac.equals(this.deviceMacAddress) && (bluetoothGatt != null || isConnecting)) {
                    addLog("Already connected/connecting to " + targetMac + ". Enqueuing command directly...");
                    parseAndQueueCommand(command, intent);
                    if (isConnected && writeCharacteristic != null) {
                        processQueue();
                    } else {
                        addLog("Waiting for connection or service discovery to complete...");
                    }
                } else {
                    addLog("Starting fresh connection to: " + targetMac);
                    this.deviceMacAddress = targetMac;
                    commandQueue.clear();
                    closeGattInstance();
                    parseAndQueueCommand(command, intent);
                    processQueue();
                }
            });
        }
        return START_NOT_STICKY;
    }

    private void parseAndQueueCommand(String command, Intent intent) {
        if (command == null) return;

        // Auto-query info if we don't have the unit number yet
        if (!"query_info".equals(command) && ("0000".equals(unitNumber) || unitNumber.isEmpty() || "Not Queried".equals(unitNumber))) {
            addLog("Unit number is unknown (" + unitNumber + "). Prepending query command ('i$')...");
            enqueueCommand("i$");
        }

        switch (command) {
            case "query_info":
                enqueueCommand("i$");
                break;

            case "power":
                int state = intent.getIntExtra("state", 0);
                enqueueCommand("M," + unitNumber + "," + state + "$");
                if (state == 1 && intent.hasExtra("level")) {
                    int targetLevel = intent.getIntExtra("level", 5);
                    int lastLevel = getSharedPreferences("MarpacBridgePrefs", MODE_PRIVATE)
                            .getInt("last_volume", 5);
                    rampVolume(lastLevel, targetLevel);
                } else if (intent.hasExtra("level")) {
                    int targetLevel = intent.getIntExtra("level", 5);
                    int lastLevel = getSharedPreferences("MarpacBridgePrefs", MODE_PRIVATE)
                            .getInt("last_volume", 5);
                    rampVolume(lastLevel, targetLevel);
                }
                getSharedPreferences("MarpacBridgePrefs", MODE_PRIVATE)
                    .edit()
                    .putInt("last_power_state", state)
                    .apply();
                notifyStateChange();
                break;

            case "volume":
                int level = intent.getIntExtra("level", 5);
                int lastVol = getSharedPreferences("MarpacBridgePrefs", MODE_PRIVATE)
                        .getInt("last_volume", 5);
                rampVolume(lastVol, level);
                break;

            case "sync_time":
                java.time.LocalTime time = java.time.LocalTime.now();
                int dayOfWeek = java.time.LocalDate.now().getDayOfWeek().getValue(); // Mon=1..Sun=7
                // Yogasleep format: Sun=1, Mon=2, Tue=3, Wed=4, Thu=5, Fri=6, Sat=7
                int yogasleepDay = (dayOfWeek == 7) ? 1 : (dayOfWeek + 1);
                enqueueCommand(String.format("T,%s,%s,%d$", unitNumber, time.format(java.time.format.DateTimeFormatter.ofPattern("HHmm")), yogasleepDay));
                break;

            case "schedule":
                // Legacy
                break;

            case "sync_schedules":
                SharedPreferences prefs = getSharedPreferences("MarpacBridgePrefs", MODE_PRIVATE);
                String schedulesJsonStr = prefs.getString("schedules_json", "[]");
                addLog("Syncing hardware schedules to machine: " + schedulesJsonStr);
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(schedulesJsonStr);
                    java.util.List<org.json.JSONObject> hwPlans = new java.util.ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject obj = arr.getJSONObject(i);
                        String type = obj.optString("type", "hardware");
                        if (!"local".equals(type)) {
                            hwPlans.add(obj);
                        }
                    }
                    addLog("Found " + hwPlans.size() + " hardware plans to sync to machine.");

                    // Yogasleep physical hardware only supports exactly 3 slots (1 to 3)
                    for (int i = 0; i < 3; i++) {
                        int slotIndex = i + 1;
                        if (i < hwPlans.size()) {
                            org.json.JSONObject obj = hwPlans.get(i);
                            boolean enabled = obj.optBoolean("enabled", true);
                            
                            int onHour = obj.optInt("on_hour", 22);
                            int onMin = obj.optInt("on_minute", 0);
                            int onVol = obj.optInt("on_volume", obj.optInt("volume", 5));
                            
                            int offHour = obj.optInt("off_hour", 7);
                            int offMin = obj.optInt("off_minute", 0);
                            
                            int daysVal = obj.optInt("days", 127);

                            // Send valid 24h times to pass firmware validation checks
                            String startFmt = String.format("%02d%02d", onHour, onMin);
                            String endFmt = String.format("%02d%02d", offHour, offMin);

                            // If schedule is disabled, we set days to 0 and volume to 0 (so it never triggers)
                            int finalDays = enabled ? daysVal : 0;
                            int finalVolume = enabled ? onVol : 0;

                            // Send 3 P commands per slot
                            enqueueCommand("P," + unitNumber + ",1," + slotIndex + "," + startFmt + "$");
                            enqueueCommand("P," + unitNumber + ",2," + slotIndex + "," + endFmt + "$");
                            enqueueCommand(String.format("P,%s,3,%d,%02X,%02d$", unitNumber, slotIndex, finalDays, finalVolume));
                        } else {
                            // Unused slot: clear it by sending 00 days and 00 volume
                            enqueueCommand("P," + unitNumber + ",1," + slotIndex + ",2200$");
                            enqueueCommand("P," + unitNumber + ",2," + slotIndex + ",0700$");
                            enqueueCommand("P," + unitNumber + ",3," + slotIndex + ",00,00$");
                        }
                    }
                } catch (Exception e) {
                    addLog("Error preparing sync_schedules: " + e.getMessage());
                }
                break;
        }
    }

    private synchronized void enqueueCommand(String payload) {
        addLog("Enqueuing BLE command: " + payload);
        commandQueue.add(payload);
    }

    private void rampVolume(int fromLevel, int toLevel) {
        toLevel = Math.max(1, Math.min(10, toLevel));
        enqueueCommand(String.format("S,%s,%02d$", unitNumber, toLevel));
        getSharedPreferences("MarpacBridgePrefs", MODE_PRIVATE)
                .edit().putInt("last_volume", toLevel).apply();
        addLog("Volume set to: " + toLevel);
        notifyStateChange();
    }

    private void notifyStateChange() {
        SharedPreferences prefs = getSharedPreferences("MarpacBridgePrefs", MODE_PRIVATE);
        int curPower = prefs.getInt("last_power_state", 0);
        int curVol = prefs.getInt("last_volume", 5);
        String timeStr = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        Intent intent = new Intent("com.nitsu.marpacbridge.ACTION_STATE_CHANGED");
        intent.putExtra("power", curPower);
        intent.putExtra("power_text", curPower == 1 ? "ON" : "OFF");
        intent.putExtra("volume", curVol);
        intent.putExtra("last_update", timeStr);
        sendBroadcast(intent);
    }

    private synchronized void processQueue() {
        if (isWriting) return;

        if (commandQueue.isEmpty()) {
            addLog("Queue empty. Scheduling disconnect in 30 seconds...");
            serviceHandler.removeCallbacks(disconnectRunnable);
            serviceHandler.postDelayed(disconnectRunnable, 30000);
            return;
        } else {
            serviceHandler.removeCallbacks(disconnectRunnable);
        }

        if (bluetoothGatt == null) {
            connectGatt();
            return;
        }



        String payload = commandQueue.peek();
        if (payload == null) return;

        // If the next command is a control command (needs unit ID) but we are still querying (unitNumber is 0000),
        // we hold the queue and wait for the notification to resolve it.
        if (!payload.startsWith("i") && ("0000".equals(unitNumber) || unitNumber.isEmpty() || "Not Queried".equals(unitNumber))) {
            addLog("Waiting for unit number to be resolved before sending control commands...");
            return;
        }

        // Dynamically replace 0000 with the resolved unit number if we resolved it
        if (!"0000".equals(unitNumber) && !"Not Queried".equals(unitNumber) && !unitNumber.isEmpty()) {
            if (payload.contains(",0000,")) {
                payload = payload.replace(",0000,", "," + unitNumber + ",");
                addLog("Dynamically updated payload with resolved unit ID: " + payload);
            }
        }

        if (writeCharacteristic != null) {
            isWriting = true;
            writeCharacteristic.setValue(payload);
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    addLog("Writing command: " + payload);
                    bluetoothGatt.writeCharacteristic(writeCharacteristic);
                } else {
                    addLog("Error: Missing BT Connect Permission");
                    stopSelf();
                }
            } catch (SecurityException e) {
                addLog("Security Error writing BLE: " + e.getMessage());
                stopSelf();
            }
        }
    }

    private void connectGatt() {
        if (isConnecting) return;
        isConnecting = true;
        broadcastStatus("Connecting...");
        addLog("Connecting to MAC: " + deviceMacAddress + "...");

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceMacAddress);
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                } else {
                    bluetoothGatt = device.connectGatt(this, false, gattCallback);
                }

                if (device.getBondState() == BluetoothDevice.BOND_NONE) {
                    addLog("Device not bonded. Initiating bonding handshake (createBond) after connectGatt...");
                    try {
                        device.createBond();
                    } catch (Exception e) {
                        addLog("Error initiating bond: " + e.getMessage());
                    }
                } else {
                    addLog("Device bond state is: " + device.getBondState() + ". Skipping createBond.");
                }
            } else {
                addLog("Error: No BT Connect Permission");
                stopSelf();
            }
        } catch (SecurityException e) {
            addLog("Security Error connecting BLE: " + e.getMessage());
            stopSelf();
        }
    }

    private void closeGattInstance() {
        if (bluetoothGatt != null) {
            addLog("Closing GATT connection and cleaning up resources...");
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothGatt.close();
                }
            } catch (SecurityException ignored) {}
            bluetoothGatt = null;
            writeCharacteristic = null;
        }
        isWriting = false;
        isConnecting = false;
        isConnected = false;
    }

    private synchronized void disconnectGatt() {
        closeGattInstance();
        broadcastStatus("Disconnected");
        addLog("Disconnected from device.");
        stopSelf();
    }

    private void enableNotifications(BluetoothGatt gatt) {
        BluetoothGattService service = gatt.getService(SERVICE_UUID);
        if (service != null) {
            writeCharacteristic = service.getCharacteristic(CHAR_UUID);
            if (writeCharacteristic != null) {
                addLog("Found custom write characteristic. Enabling notifications...");
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        gatt.setCharacteristicNotification(writeCharacteristic, true);
                        BluetoothGattDescriptor descriptor = writeCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(descriptor);
                        } else {
                            addLog("Warning: Config Descriptor not found. Processing queue...");
                            processQueue();
                        }
                    }
                } catch (SecurityException ignored) {}
            } else {
                addLog("Error: Custom Write Characteristic not found!");
                disconnectGatt();
            }
        } else {
            addLog("Error: Custom Serial Service NOT found on device!");
            broadcastStatus("Error: Service not found");
            disconnectGatt();
        }
    }

    private void onCharacteristicReadHelper(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] val, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            UUID uuid = characteristic.getUuid();
            if (UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb").equals(uuid)) {
                String fwVer = (val != null) ? new String(val).trim() : "Unknown";
                addLog("Handshake successful! Firmware version: " + fwVer);
                enableNotifications(gatt);
            }
        } else {
            addLog("Characteristic read failed with status: " + status + ". Proceeding anyway...");
            enableNotifications(gatt);
        }
    }

    private void onCharacteristicChangedHelper(byte[] data) {
        if (data != null) {
            String response = new String(data).trim();
            addLog("Notification received: " + response);
            if (response.startsWith("I,")) {
                String unitNum = response.replace("I,", "").replace("$", "").trim();
                this.unitNumber = unitNum;
                addLog("Resolved unit number in memory: " + unitNum);
                
                Intent intent = new Intent(ACTION_UNIT_NUMBER_FOUND);
                intent.putExtra(EXTRA_UNIT_NUMBER, unitNum);
                intent.setPackage(getPackageName());
                sendBroadcast(intent);
                
                // Resume processing queue now that we have the resolved ID!
                serviceHandler.post(this::processQueue);
            }
        }
    }

    private void broadcastStatus(String status) {
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra(EXTRA_STATUS, status);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void broadcastCmdState(String state) {
        Intent intent = new Intent(ACTION_CMD_STATE);
        intent.putExtra(EXTRA_CMD_STATE, state);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void addLog(String message) {
        Log.d(TAG, message);
        Intent intent = new Intent(ACTION_LOG);
        intent.putExtra(EXTRA_LOG_LINE, "[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + "] " + message);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                addLog("GATT connection status error: status=" + status + ", newState=" + newState);
                isConnecting = false;
                isConnected = false;
                if (!commandQueue.isEmpty() && retryCount < 5) {
                    retryCount++;
                    broadcastStatus("Retrying... (" + retryCount + "/5)");
                    addLog("Connection failed. Retrying in 1.5s. Attempt: " + retryCount);
                    closeGattInstance();
                    serviceHandler.postDelayed(BLEService.this::connectGatt, 1500);
                } else {
                    if (!commandQueue.isEmpty()) broadcastCmdState("ERROR");
                    retryCount = 0;
                    commandQueue.clear();
                    disconnectGatt();
                }
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnecting = false;
                isConnected = true;
                retryCount = 0;
                broadcastStatus("Connected");
                addLog("GATT Connected! status=" + status + ". Discovering services...");
                try {
                    if (ContextCompat.checkSelfPermission(BLEService.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        BluetoothDevice dev = gatt.getDevice();
                        if (dev.getBondState() == BluetoothDevice.BOND_NONE) {
                            addLog("Device not bonded. Initiating bonding handshake (createBond)...");
                            dev.createBond();
                        } else {
                            addLog("Device bond state code: " + dev.getBondState());
                        }
                        gatt.discoverServices();
                    }
                } catch (SecurityException ignored) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                addLog("GATT Disconnected! status=" + status + ", newState=" + newState);
                isConnecting = false;
                isConnected = false;
                if (!commandQueue.isEmpty() && retryCount < 5) {
                    retryCount++;
                    broadcastStatus("Retrying... (" + retryCount + "/5)");
                    addLog("Connection failed or lost. Retrying in 1.5s. Attempt: " + retryCount);
                    closeGattInstance();
                    serviceHandler.postDelayed(BLEService.this::connectGatt, 1500);
                } else {
                    if (!commandQueue.isEmpty()) broadcastCmdState("ERROR");
                    retryCount = 0;
                    commandQueue.clear();
                    disconnectGatt();
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Services Discovered successfully!");
                
                // Dump services & characteristics to logs
                try {
                    if (ContextCompat.checkSelfPermission(BLEService.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        for (BluetoothGattService s : gatt.getServices()) {
                            addLog("Service Found: " + s.getUuid().toString());
                            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                                addLog("  └─ Char: " + c.getUuid().toString() + " (Props: " + c.getProperties() + ")");
                            }
                        }
                    }
                } catch (SecurityException ignored) {}

                // Try to read Firmware Revision first (as a handshake)
                try {
                    if (ContextCompat.checkSelfPermission(BLEService.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        BluetoothGattService devInfoService = gatt.getService(UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"));
                        if (devInfoService != null) {
                            BluetoothGattCharacteristic fwChar = devInfoService.getCharacteristic(UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb"));
                            if (fwChar != null) {
                                addLog("Reading Firmware Revision for initialization handshake...");
                                gatt.readCharacteristic(fwChar);
                                return;
                            }
                        }
                    }
                } catch (SecurityException ignored) {}

                // Fallback: If firmware service/char is not found, enable notifications immediately
                addLog("Firmware characteristic not found. Skipping handshake...");
                enableNotifications(gatt);
            } else {
                addLog("onServicesDiscovered failed with status: " + status);
                disconnectGatt();
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            onCharacteristicReadHelper(gatt, characteristic, characteristic.getValue(), status);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            onCharacteristicReadHelper(gatt, characteristic, value, status);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            addLog("Notification descriptor written. status=" + status);
            synchronized (BLEService.this) {
                String firstCmd = commandQueue.peek();
                if (firstCmd != null && !firstCmd.startsWith("i") && !firstCmd.startsWith("T")) {
                    LocalTime time = LocalTime.now();
                    int dayOfWeek = LocalDate.now().getDayOfWeek().getValue();
                    String syncCmd = String.format("T,%s,%s,%d$", unitNumber, time.format(DateTimeFormatter.ofPattern("HHmm")), dayOfWeek);
                    addLog("Auto-inserting clock sync command: " + syncCmd);
                    ((LinkedList<String>) commandQueue).addFirst(syncCmd);
                }
            }
            serviceHandler.postDelayed(BLEService.this::processQueue, 300);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            synchronized (BLEService.this) {
                isWriting = false;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    addLog("BLE write success! characteristic=" + characteristic.getUuid().toString());
                    commandQueue.poll();
                    // Signal OK only when queue is fully drained
                    if (commandQueue.isEmpty()) {
                        broadcastCmdState("OK");
                    }
                } else {
                    addLog("BLE write failed! status=" + status + ", char=" + characteristic.getUuid().toString());
                    broadcastCmdState("ERROR");
                }
            }
            serviceHandler.postDelayed(BLEService.this::processQueue, 300);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            onCharacteristicChangedHelper(characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            onCharacteristicChangedHelper(value);
        }
    };

    private void unpairDevice() {
        if (deviceMacAddress == null) return;
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceMacAddress);
        if (device != null) {
            addLog("Unpairing device " + deviceMacAddress + "...");
            try {
                java.lang.reflect.Method m = device.getClass().getMethod("removeBond", (Class[]) null);
                m.invoke(device, (Object[]) null);
                addLog("Unpaired successfully.");
            } catch (Exception e) {
                addLog("Error removing bond: " + e.getMessage());
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnectGatt();
        if (isBondReceiverRegistered && bondStateReceiver != null) {
            try {
                unregisterReceiver(bondStateReceiver);
            } catch (Exception ignored) {}
            isBondReceiverRegistered = false;
        }
        serviceLooper.quit();
    }
}
