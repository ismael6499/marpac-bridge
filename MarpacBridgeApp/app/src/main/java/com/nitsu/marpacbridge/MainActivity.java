package com.nitsu.marpacbridge;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.widget.ImageButton;
import android.widget.ScrollView;
import android.text.Html;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final long SCAN_PERIOD = 10000; // 10 seconds scan limit

    private TextView tvUnitNumber, tvVolumeLabel, tvToggleLogs, tvLogs;
    private TextView tvStatusIcon;
    private EditText etMacAddress;
    private Button btnScan, btnQueryUnit, btnPowerOn, btnPowerOff, btnSyncTime,
            btnCopyLogs, btnClearLogs, btnDisconnect, btnForget, btnVolUp, btnVolDown, btnAddSchedule;
    private ImageButton btnHelp;
    private View layoutLogsHeader, layoutLogsContainer;
    private ScrollView svLogs;
    private LinearLayout llSchedulesContainer;

    private static final int MAX_SCHEDULES = 3;

    // Badge animation state
    private final Handler badgeHandler = new Handler();
    private Runnable badgeDotRunnable;
    private int dotCount = 0;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private Handler handler;
    private boolean isScanning = false;
    private SharedPreferences prefs;

    private final BroadcastReceiver bleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BLEService.ACTION_STATUS_UPDATE.equals(action)) {
                // Connection status card removed
            } else if (BLEService.ACTION_CMD_STATE.equals(action)) {
                String state = intent.getStringExtra(BLEService.EXTRA_CMD_STATE);
                updateStatusBadge(state);
            } else if (BLEService.ACTION_UNIT_NUMBER_FOUND.equals(action)) {
                String unitNum = intent.getStringExtra(BLEService.EXTRA_UNIT_NUMBER);
                if (unitNum != null) {
                    tvUnitNumber.setText(unitNum);
                    prefs.edit().putString("unit_number", unitNum).apply();
                    Toast.makeText(context, "Unit Number Saved: " + unitNum, Toast.LENGTH_SHORT).show();
                }
            } else if (BLEService.ACTION_LOG.equals(action)) {
                String logLine = intent.getStringExtra(BLEService.EXTRA_LOG_LINE);
                if (logLine != null && tvLogs != null) {
                    tvLogs.append(logLine + "\n");
                    final ScrollView sv = (ScrollView) tvLogs.getParent();
                    sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("MarpacBridgePrefs", MODE_PRIVATE);
        handler = new Handler();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        initUI();
        loadSavedConfig();
        setupListeners();
        
        checkAndRequestPermissionsOnStart();
        checkBatteryOptimization();
    }

    private void checkAndRequestPermissionsOnStart() {
        List<String> permissionsNeeded = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    private void initUI() {
        tvUnitNumber = findViewById(R.id.tv_unit_number);
        tvVolumeLabel = findViewById(R.id.tv_volume_label);
        etMacAddress = findViewById(R.id.et_mac_address);
        btnScan = findViewById(R.id.btn_scan);
        btnQueryUnit = findViewById(R.id.btn_query_unit);
        btnDisconnect = findViewById(R.id.btn_disconnect);
        btnForget = findViewById(R.id.btn_forget);
        btnPowerOn = findViewById(R.id.btn_power_on);
        btnPowerOff = findViewById(R.id.btn_power_off);
        btnSyncTime = findViewById(R.id.btn_sync_time);
        btnHelp = findViewById(R.id.btn_help);
        
        layoutLogsHeader = findViewById(R.id.layout_logs_header);
        layoutLogsContainer = findViewById(R.id.layout_logs_container);
        tvToggleLogs = findViewById(R.id.tv_toggle_logs);
        tvLogs = findViewById(R.id.tv_logs);
        btnCopyLogs = findViewById(R.id.btn_copy_logs);
        btnClearLogs = findViewById(R.id.btn_clear_logs);
        svLogs = findViewById(R.id.sv_logs);
        btnVolDown = findViewById(R.id.btn_vol_down);
        btnVolUp = findViewById(R.id.btn_vol_up);
        btnAddSchedule = findViewById(R.id.btn_add_schedule);
        llSchedulesContainer = findViewById(R.id.ll_schedules_container);
        tvStatusIcon = findViewById(R.id.tv_status_icon);
    }

    private void loadSavedConfig() {
        String savedMac = prefs.getString("mac_address", "00:22:A3:05:49:33");
        etMacAddress.setText(savedMac);
        String savedUnit = prefs.getString("unit_number", "Not Queried");
        tvUnitNumber.setText(savedUnit);
        int savedVol = prefs.getInt("last_volume", 5);
        tvVolumeLabel.setText(String.format("Volume / Speed: %02d", savedVol));
        renderSchedules();
    }

    private void checkPairingAndExecute(Runnable action) {
        String savedUnit = prefs.getString("unit_number", "Not Queried");
        if ("Not Queried".equals(savedUnit) || savedUnit.isEmpty() || "0000".equals(savedUnit)) {
            new MaterialAlertDialogBuilder(this)
                .setTitle("Pair Your Device")
                .setMessage("Please hold the physical power button on your Yogasleep machine for 5 seconds to enter pairing mode, then press 'Continue'.")
                .setPositiveButton("Continue", (dialog, which) -> action.run())
                .setNegativeButton("Cancel", null)
                .show();
        } else {
            action.run();
        }
    }

    private void setupListeners() {
        btnScan.setOnClickListener(v -> checkPermissionsAndScan());
        
        btnQueryUnit.setOnClickListener(v -> checkPairingAndExecute(() -> {
            saveMacAddress();
            startBLEServiceWithAction("query_info");
        }));

        btnDisconnect.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(this)
                .setTitle("Disconnect")
                .setMessage("Are you sure you want to disconnect from the device?")
                .setPositiveButton("Disconnect", (dialog, which) -> startBLEServiceWithAction("disconnect"))
                .setNegativeButton("Cancel", null)
                .show();
        });

        btnForget.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(this)
                .setTitle("Forget Device")
                .setMessage("This will unpair the device and erase its stored Unit Number. You will need to re-pair manually. Continue?")
                .setPositiveButton("Forget", (dialog, which) -> {
                    startBLEServiceWithAction("unpair");
                    prefs.edit().remove("unit_number").apply();
                    tvUnitNumber.setText("Not Queried");
                    Toast.makeText(this, "Device forgotten & unpaired", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
        });

        btnPowerOn.setOnClickListener(v -> checkPairingAndExecute(() -> {
            saveMacAddress();
            startBLEServiceWithAction("power", "state", 1);
        }));

        btnPowerOff.setOnClickListener(v -> checkPairingAndExecute(() -> {
            saveMacAddress();
            startBLEServiceWithAction("power", "state", 0);
        }));

        btnSyncTime.setOnClickListener(v -> checkPairingAndExecute(() -> {
            saveMacAddress();
            startBLEServiceWithAction("sync_time");
        }));

        btnVolDown.setOnClickListener(v -> {
            int cur = prefs.getInt("last_volume", 5);
            if (cur > 1) {
                int next = cur - 1;
                tvVolumeLabel.setText(String.format("Volume / Speed: %02d", next));
                prefs.edit().putInt("last_volume", next).apply();
                saveMacAddress();
                startBLEServiceWithAction("volume", "level", next);
            }
        });

        btnVolUp.setOnClickListener(v -> {
            int cur = prefs.getInt("last_volume", 5);
            if (cur < 10) {
                int next = cur + 1;
                tvVolumeLabel.setText(String.format("Volume / Speed: %02d", next));
                prefs.edit().putInt("last_volume", next).apply();
                saveMacAddress();
                startBLEServiceWithAction("volume", "level", next);
            }
        });

        btnAddSchedule.setOnClickListener(v -> {
            try {
                JSONArray arr = new JSONArray(prefs.getString("schedules_json", "[]"));
                if (arr.length() >= MAX_SCHEDULES) {
                    Toast.makeText(this, "Maximum " + MAX_SCHEDULES + " plans reached", Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (Exception ignored) {}
            showAddScheduleDialog(-1);
        });

        btnHelp.setOnClickListener(v -> showHelpDialog());

        svLogs.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });

        tvLogs.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });

        layoutLogsHeader.setOnClickListener(v -> {
            if (layoutLogsContainer.getVisibility() == View.VISIBLE) {
                layoutLogsContainer.setVisibility(View.GONE);
                tvToggleLogs.setText("Show");
            } else {
                layoutLogsContainer.setVisibility(View.VISIBLE);
                tvToggleLogs.setText("Hide");
            }
        });

        btnCopyLogs.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("Marpac Bridge Logs", tvLogs.getText().toString());
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(MainActivity.this, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show();
            }
        });

        btnClearLogs.setOnClickListener(v -> tvLogs.setText(""));
    }

    private void saveMacAddress() {
        String mac = etMacAddress.getText().toString().trim().toUpperCase();
        prefs.edit().putString("mac_address", mac).apply();
    }

    private void startBLEServiceWithAction(String command) {
        Intent intent = new Intent(this, BLEService.class);
        intent.setAction(BLEService.ACTION_EXECUTE);
        intent.putExtra("command", command);
        intent.putExtra("mac_address", etMacAddress.getText().toString().trim());
        intent.putExtra("unit_number", prefs.getString("unit_number", ""));
        startService(intent);
    }

    private void startBLEServiceWithAction(String command, String extraKey, int extraValue) {
        Intent intent = new Intent(this, BLEService.class);
        intent.setAction(BLEService.ACTION_EXECUTE);
        intent.putExtra("command", command);
        intent.putExtra(extraKey, extraValue);
        intent.putExtra("mac_address", etMacAddress.getText().toString().trim());
        intent.putExtra("unit_number", prefs.getString("unit_number", ""));
        startService(intent);
    }

    private void checkPermissionsAndScan() {
        List<String> permissionsNeeded = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            startBLEScan();
        }
    }

    private void startBLEScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) return;

        if (isScanning) return;

        isScanning = true;
        btnScan.setText("Scanning...");
        btnScan.setEnabled(false);

        handler.postDelayed(() -> {
            if (isScanning) {
                stopBLEScan();
            }
        }, SCAN_PERIOD);

        try {
            bleScanner.startScan(scanCallback);
        } catch (SecurityException e) {
            Toast.makeText(this, "Permission Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            stopBLEScan();
        }
    }

    private void stopBLEScan() {
        isScanning = false;
        btnScan.setText("Scan");
        btnScan.setEnabled(true);
        if (bleScanner != null) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (SecurityException ignored) {}
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            try {
                String name = device.getName();
                if (name != null && (name.toLowerCase().contains("dohm") || name.toLowerCase().contains("marpac") || name.toLowerCase().contains("yogasleep"))) {
                    stopBLEScan();
                    etMacAddress.setText(device.getAddress());
                    saveMacAddress();
                    Toast.makeText(MainActivity.this, "Found Device: " + name, Toast.LENGTH_SHORT).show();
                }
            } catch (SecurityException ignored) {}
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BLEService.ACTION_STATUS_UPDATE);
        filter.addAction(BLEService.ACTION_UNIT_NUMBER_FOUND);
        filter.addAction(BLEService.ACTION_LOG);
        filter.addAction(BLEService.ACTION_CMD_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bleReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bleReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(bleReceiver);
        badgeHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startBLEScan();
            } else {
                Toast.makeText(this, "Bluetooth/Location permissions are required for scanning", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Schedule Management
    // ──────────────────────────────────────────────────────────────────────────

    // ──────────────────────────────────────────────────────────────────────────
    // Command Status Badge
    // ──────────────────────────────────────────────────────────────────────────

    private void updateStatusBadge(String state) {
        if (tvStatusIcon == null) return;
        // Cancel any pending resets or dot animation
        badgeHandler.removeCallbacks(badgeDotRunnable != null ? badgeDotRunnable : () -> {});
        badgeHandler.removeCallbacksAndMessages(null);

        switch (state) {
            case "PROGRESS":
                tvStatusIcon.setVisibility(View.VISIBLE);
                tvStatusIcon.setTextColor(ContextCompat.getColor(this, R.color.accent_neon));
                dotCount = 0;
                badgeDotRunnable = new Runnable() {
                    @Override
                    public void run() {
                        dotCount = (dotCount + 1) % 4;
                        String[] frames = {"·", "··", "···", "····"};
                        tvStatusIcon.setText(frames[dotCount]);
                        badgeHandler.postDelayed(this, 400);
                    }
                };
                badgeHandler.post(badgeDotRunnable);
                break;

            case "OK":
                if (badgeDotRunnable != null) badgeHandler.removeCallbacks(badgeDotRunnable);
                tvStatusIcon.setVisibility(View.VISIBLE);
                tvStatusIcon.setText("✓");
                tvStatusIcon.setTextColor(ContextCompat.getColor(this, R.color.green_neon));
                // Auto-hide after 3 seconds
                badgeHandler.postDelayed(() -> tvStatusIcon.setVisibility(View.GONE), 3000);
                break;

            case "ERROR":
                if (badgeDotRunnable != null) badgeHandler.removeCallbacks(badgeDotRunnable);
                tvStatusIcon.setVisibility(View.VISIBLE);
                tvStatusIcon.setText("✗");
                tvStatusIcon.setTextColor(ContextCompat.getColor(this, R.color.red_neon));
                // Auto-hide after 5 seconds
                badgeHandler.postDelayed(() -> tvStatusIcon.setVisibility(View.GONE), 5000);
                break;

            default:
                if (badgeDotRunnable != null) badgeHandler.removeCallbacks(badgeDotRunnable);
                tvStatusIcon.setVisibility(View.GONE);
                break;
        }
    }

    private void renderSchedules() {
        if (llSchedulesContainer == null) return;
        llSchedulesContainer.removeAllViews();
        try {
            JSONArray arr = new JSONArray(prefs.getString("schedules_json", "[]"));
            if (arr.length() == 0) {
                TextView empty = new TextView(this);
                empty.setText("No plans yet. Tap '+ Add Plan' to create one.");
                empty.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
                empty.setTextSize(13f);
                llSchedulesContainer.addView(empty);
                return;
            }
            String[] dayNames = {"Sun","Mon","Tue","Wed","Thu","Fri","Sat"};
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                int id       = obj.getInt("id");
                int days     = obj.getInt("days");
                boolean enabled = obj.getBoolean("enabled");
                String type  = obj.optString("type", "hardware");

                boolean onEnabled  = obj.optBoolean("on_enabled", true);
                int     onHour     = obj.optInt("on_hour",  22);
                int     onMinute   = obj.optInt("on_minute", 0);
                int     onVolume   = obj.optInt("on_volume", 5);
                boolean offEnabled = obj.optBoolean("off_enabled", true);
                int     offHour    = obj.optInt("off_hour",  7);
                int     offMinute  = obj.optInt("off_minute", 0);

                // Day label
                StringBuilder daysLabel = new StringBuilder();
                for (int d = 0; d < 7; d++) {
                    if ((days & (1 << d)) != 0) {
                        if (daysLabel.length() > 0) daysLabel.append(" ");
                        daysLabel.append(dayNames[d]);
                    }
                }

                // Row card
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.setMargins(0, 8, 0, 8);
                row.setLayoutParams(rowParams);
                row.setPadding(16, 12, 16, 12);
                row.setBackgroundResource(android.R.drawable.dialog_holo_dark_frame);

                // Header row: type + days + toggle + delete
                LinearLayout headerRow = new LinearLayout(this);
                headerRow.setOrientation(LinearLayout.HORIZONTAL);
                headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

                TextView tvDays = new TextView(this);
                tvDays.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                String typeTag = "local".equals(type) ? "📱 Phone Alarm\n" : "💾 Yogasleep Memory\n";
                tvDays.setText(typeTag + daysLabel.toString());
                tvDays.setTextColor(ContextCompat.getColor(this, R.color.accent_neon));
                tvDays.setTextSize(12f);
                headerRow.addView(tvDays);

                Switch sw = new Switch(this);
                sw.setChecked(enabled);
                sw.setOnCheckedChangeListener((cb, isChecked) -> {
                    try {
                        JSONArray a = new JSONArray(prefs.getString("schedules_json", "[]"));
                        for (int j = 0; j < a.length(); j++) {
                            JSONObject o = a.getJSONObject(j);
                            if (o.getInt("id") == id) { o.put("enabled", isChecked); a.put(j, o); break; }
                        }
                        prefs.edit().putString("schedules_json", a.toString()).apply();
                        
                        if ("local".equals(type)) {
                            // Register/Unregister local phone alarm manager
                            if (isChecked) {
                                if (onEnabled)  ScheduleAlarmReceiver.scheduleAlarm(this, id * 10,     onHour,  onMinute);
                                if (offEnabled) ScheduleAlarmReceiver.scheduleAlarm(this, id * 10 + 1, offHour, offMinute);
                            } else {
                                ScheduleAlarmReceiver.cancelAlarm(this, id * 10);
                                ScheduleAlarmReceiver.cancelAlarm(this, id * 10 + 1);
                            }
                        } else {
                            // Hardware sync
                            startBLEServiceWithAction("sync_schedules");
                        }
                    } catch (Exception ignored) {}
                });
                headerRow.addView(sw);

                Button btnDel = new Button(this);
                btnDel.setText("✕");
                btnDel.setTextSize(14f);
                btnDel.setPadding(16, 4, 16, 4);
                btnDel.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                btnDel.setTextColor(ContextCompat.getColor(this, R.color.red_neon));
                btnDel.setOnClickListener(vv -> new MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Plan")
                    .setMessage("Delete this scheduled plan?")
                    .setPositiveButton("Delete", (d2, w) -> {
                        try {
                            if ("local".equals(type)) {
                                ScheduleAlarmReceiver.cancelAlarm(this, id * 10);
                                ScheduleAlarmReceiver.cancelAlarm(this, id * 10 + 1);
                            }
                            JSONArray a = new JSONArray(prefs.getString("schedules_json", "[]"));
                            JSONArray newArr = new JSONArray();
                            for (int j = 0; j < a.length(); j++)
                                if (a.getJSONObject(j).getInt("id") != id) newArr.put(a.getJSONObject(j));
                            prefs.edit().putString("schedules_json", newArr.toString()).apply();
                            
                            if (!"local".equals(type)) {
                                startBLEServiceWithAction("sync_schedules");
                            }
                            renderSchedules();
                        } catch (Exception ignored) {}
                    })
                    .setNegativeButton("Cancel", null).show());
                headerRow.addView(btnDel);
                row.addView(headerRow);

                // Time info lines
                if (onEnabled) {
                    TextView tvOn = new TextView(this);
                    StringBuilder onText = new StringBuilder(String.format("🟢 ON   %02d:%02d  vol %d", onHour, onMinute, onVolume));
                    JSONArray onConds = obj.optJSONArray("on_conditions");
                    if (onConds != null && onConds.length() > 0) {
                        String conj = obj.optString("on_cond_conjunction", "AND");
                        onText.append("  [If: ");
                        for (int k = 0; k < onConds.length(); k++) {
                            if (k > 0) onText.append(" ").append(conj).append(" ");
                            JSONObject c = onConds.getJSONObject(k);
                            onText.append(c.optString("var")).append(" ").append(c.optString("op")).append(" ").append(c.optString("val"));
                        }
                        onText.append("]");
                    }
                    tvOn.setText(onText.toString());
                    tvOn.setTextColor(ContextCompat.getColor(this, R.color.text_bright));
                    tvOn.setTextSize(13f);
                    row.addView(tvOn);
                }
                if (offEnabled) {
                    TextView tvOff = new TextView(this);
                    StringBuilder offText = new StringBuilder(String.format("🔴 OFF  %02d:%02d", offHour, offMinute));
                    JSONArray offConds = obj.optJSONArray("off_conditions");
                    if (offConds != null && offConds.length() > 0) {
                        String conj = obj.optString("off_cond_conjunction", "AND");
                        offText.append("  [If: ");
                        for (int k = 0; k < offConds.length(); k++) {
                            if (k > 0) offText.append(" ").append(conj).append(" ");
                            JSONObject c = offConds.getJSONObject(k);
                            offText.append(c.optString("var")).append(" ").append(c.optString("op")).append(" ").append(c.optString("val"));
                        }
                        offText.append("]");
                    }
                    tvOff.setText(offText.toString());
                    tvOff.setTextColor(ContextCompat.getColor(this, R.color.text_bright));
                    tvOff.setTextSize(13f);
                    row.addView(tvOff);
                }

                llSchedulesContainer.addView(row);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error loading schedules", Toast.LENGTH_SHORT).show();
        }
    }

    private void addConditionRow(Context context, LinearLayout container, String initialVar, String initialOp, String initialVal) {
        float dp = context.getResources().getDisplayMetrics().density;
        int margin4 = (int)(4 * dp);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, margin4, 0, margin4);
        row.setLayoutParams(lp);

        // 1. Variable Spinner
        android.widget.Spinner spVar = new android.widget.Spinner(context);
        String[] vars = {"Power State", "Volume / Speed"};
        android.widget.ArrayAdapter<String> adapterVar = new android.widget.ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, vars);
        adapterVar.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spVar.setAdapter(adapterVar);

        // 2. Operator Spinner
        android.widget.Spinner spOp = new android.widget.Spinner(context);

        // 3. Value Spinner
        android.widget.Spinner spVal = new android.widget.Spinner(context);

        spVar.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) { // Power State
                    String[] ops = {"==", "!="};
                    android.widget.ArrayAdapter<String> adapterOp = new android.widget.ArrayAdapter<>(context,
                            android.R.layout.simple_spinner_item, ops);
                    adapterOp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spOp.setAdapter(adapterOp);

                    String[] vals = {"ON", "OFF"};
                    android.widget.ArrayAdapter<String> adapterVal = new android.widget.ArrayAdapter<>(context,
                            android.R.layout.simple_spinner_item, vals);
                    adapterVal.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spVal.setAdapter(adapterVal);

                    if ("power".equals(initialVar)) {
                        spOp.setSelection("!=".equals(initialOp) ? 1 : 0);
                        spVal.setSelection("OFF".equalsIgnoreCase(initialVal) ? 1 : 0);
                    }
                } else { // Volume / Speed
                    String[] ops = {"==", "!=", "<", ">", "<=", ">="};
                    android.widget.ArrayAdapter<String> adapterOp = new android.widget.ArrayAdapter<>(context,
                            android.R.layout.simple_spinner_item, ops);
                    adapterOp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spOp.setAdapter(adapterOp);

                    String[] vals = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10"};
                    android.widget.ArrayAdapter<String> adapterVal = new android.widget.ArrayAdapter<>(context,
                            android.R.layout.simple_spinner_item, vals);
                    adapterVal.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spVal.setAdapter(adapterVal);

                    if ("speed".equals(initialVar)) {
                        int opIdx = 0;
                        for (int j = 0; j < ops.length; j++) {
                            if (ops[j].equals(initialOp)) { opIdx = j; break; }
                        }
                        spOp.setSelection(opIdx);

                        int valIdx = 4;
                        try { valIdx = Integer.parseInt(initialVal) - 1; } catch (Exception ignored) {}
                        spVal.setSelection(Math.max(0, Math.min(9, valIdx)));
                    }
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        if ("speed".equals(initialVar)) {
            spVar.setSelection(1);
        } else {
            spVar.setSelection(0);
        }

        row.addView(spVar);
        row.addView(spOp);
        row.addView(spVal);

        Button btnDel = new Button(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnDel.setText("✕");
        btnDel.setPadding(0, 0, 0, 0);
        btnDel.setTextColor(ContextCompat.getColor(context, R.color.red_neon));
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams((int)(36 * dp), (int)(36 * dp));
        delLp.setMargins((int)(6 * dp), 0, 0, 0);
        btnDel.setLayoutParams(delLp);
        btnDel.setOnClickListener(v -> container.removeView(row));
        row.addView(btnDel);

        row.setTag(new Object[]{spVar, spOp, spVal});
        container.addView(row);
    }

    @android.annotation.SuppressLint("UseSwitchCompatOrMaterialCode")
    private void showAddScheduleDialog(int editId) {
        float dp = getResources().getDisplayMetrics().density;
        int pad16 = (int)(16 * dp);
        int pad8  = (int)(8  * dp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad16, pad16, pad16, pad8);

        // ── TYPE SELECTION ───────────────────────────────────────────
        TextView tvTypeLabel = new TextView(this);
        tvTypeLabel.setText("Plan Mode:");
        tvTypeLabel.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        tvTypeLabel.setTextSize(13f);
        root.addView(tvTypeLabel);

        android.widget.RadioGroup rgType = new android.widget.RadioGroup(this);
        rgType.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        android.widget.RadioButton rbHardware = new android.widget.RadioButton(this);
        rbHardware.setText("  💾 Hardware Slot (Max 3)  ");
        rbHardware.setTextColor(ContextCompat.getColor(this, R.color.text_bright));
        rbHardware.setId(View.generateViewId());
        rbHardware.setChecked(true);

        android.widget.RadioButton rbLocal = new android.widget.RadioButton(this);
        rbLocal.setText("  📱 Local Phone Alarm  ");
        rbLocal.setTextColor(ContextCompat.getColor(this, R.color.text_bright));
        rbLocal.setId(View.generateViewId());

        rgType.addView(rbHardware);
        rgType.addView(rbLocal);
        root.addView(rgType);

        // State variables for time selection
        final int[] onTime = {22, 0};
        final int[] offTime = {7, 0};

        // ── ON section ───────────────────────────────────────────────
        CheckBox cbOn = new CheckBox(this);
        cbOn.setText("  🟢  Turn ON");
        cbOn.setChecked(true);
        cbOn.setEnabled(false); // Hardware forces both ON & OFF
        cbOn.setTextColor(ContextCompat.getColor(this, R.color.text_bright));
        cbOn.setTextSize(15f);
        root.addView(cbOn);

        LinearLayout layoutOnContainer = new LinearLayout(this);
        layoutOnContainer.setOrientation(LinearLayout.VERTICAL);
        layoutOnContainer.setPadding((int)(12 * dp), 0, 0, 0);

        // Time ON row
        LinearLayout onTimeRow = new LinearLayout(this);
        onTimeRow.setOrientation(LinearLayout.HORIZONTAL);
        onTimeRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView tvOnTimeLbl = new TextView(this);
        tvOnTimeLbl.setText("Time ON: ");
        tvOnTimeLbl.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        tvOnTimeLbl.setTextSize(14f);
        Button btnTimeOn = new Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnTimeOn.setText(String.format("%02d:%02d", onTime[0], onTime[1]));
        btnTimeOn.setTextColor(ContextCompat.getColor(this, R.color.accent_neon));
        btnTimeOn.setOnClickListener(v -> {
            new android.app.TimePickerDialog(this, (view, hourOfDay, minute) -> {
                onTime[0] = hourOfDay;
                onTime[1] = minute;
                btnTimeOn.setText(String.format("%02d:%02d", hourOfDay, minute));
            }, onTime[0], onTime[1], true).show();
        });
        onTimeRow.addView(tvOnTimeLbl);
        onTimeRow.addView(btnTimeOn);
        layoutOnContainer.addView(onTimeRow);

        TextView tvVolLabel = new TextView(this);
        tvVolLabel.setText("Volume (1–10):");
        tvVolLabel.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        tvVolLabel.setTextSize(13f);
        layoutOnContainer.addView(tvVolLabel);

        NumberPicker npVol = new NumberPicker(this);
        npVol.setMinValue(1); npVol.setMaxValue(10);
        npVol.setValue(prefs.getInt("last_volume", 5));
        npVol.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        layoutOnContainer.addView(npVol);

        // ON Conditions container (Local only)
        LinearLayout layoutOnCondSection = new LinearLayout(this);
        layoutOnCondSection.setOrientation(LinearLayout.VERTICAL);
        layoutOnCondSection.setVisibility(View.GONE); // Default hardware mode

        TextView tvOnCondTitle = new TextView(this);
        tvOnCondTitle.setText("⚡ Conditions to turn ON:");
        tvOnCondTitle.setTextColor(ContextCompat.getColor(this, R.color.text_bright));
        tvOnCondTitle.setTextSize(13f);
        tvOnCondTitle.setPadding(0, pad8, 0, 4);
        layoutOnCondSection.addView(tvOnCondTitle);

        LinearLayout onConjRow = new LinearLayout(this);
        onConjRow.setOrientation(LinearLayout.HORIZONTAL);
        onConjRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView tvOnConjLbl = new TextView(this);
        tvOnConjLbl.setText("Match mode: ");
        tvOnConjLbl.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        tvOnConjLbl.setTextSize(12f);
        android.widget.Spinner spOnConj = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> conjAdapter1 = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"ALL conditions (AND)", "ANY condition (OR)"});
        conjAdapter1.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spOnConj.setAdapter(conjAdapter1);
        onConjRow.addView(tvOnConjLbl);
        onConjRow.addView(spOnConj);
        layoutOnCondSection.addView(onConjRow);

        LinearLayout containerOnConds = new LinearLayout(this);
        containerOnConds.setOrientation(LinearLayout.VERTICAL);
        layoutOnCondSection.addView(containerOnConds);

        Button btnAddOnCond = new Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnAddOnCond.setText("+ Add ON Condition");
        btnAddOnCond.setTextSize(12f);
        btnAddOnCond.setTextColor(ContextCompat.getColor(this, R.color.accent_neon));
        btnAddOnCond.setOnClickListener(v -> addConditionRow(this, containerOnConds, "power", "==", "OFF"));
        layoutOnCondSection.addView(btnAddOnCond);

        layoutOnContainer.addView(layoutOnCondSection);
        root.addView(layoutOnContainer);

        // ── OFF section ──────────────────────────────────────────────
        CheckBox cbOff = new CheckBox(this);
        cbOff.setText("  🔴  Turn OFF");
        cbOff.setChecked(true);
        cbOff.setEnabled(false); // Hardware forces both ON & OFF
        cbOff.setTextColor(ContextCompat.getColor(this, R.color.text_bright));
        cbOff.setTextSize(15f);
        LinearLayout.LayoutParams offCbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        offCbLp.setMargins(0, pad16, 0, 0);
        cbOff.setLayoutParams(offCbLp);
        root.addView(cbOff);

        LinearLayout layoutOffContainer = new LinearLayout(this);
        layoutOffContainer.setOrientation(LinearLayout.VERTICAL);
        layoutOffContainer.setPadding((int)(12 * dp), 0, 0, 0);

        // Time OFF row
        LinearLayout offTimeRow = new LinearLayout(this);
        offTimeRow.setOrientation(LinearLayout.HORIZONTAL);
        offTimeRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView tvOffTimeLbl = new TextView(this);
        tvOffTimeLbl.setText("Time OFF: ");
        tvOffTimeLbl.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        tvOffTimeLbl.setTextSize(14f);
        Button btnTimeOff = new Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnTimeOff.setText(String.format("%02d:%02d", offTime[0], offTime[1]));
        btnTimeOff.setTextColor(ContextCompat.getColor(this, R.color.accent_neon));
        btnTimeOff.setOnClickListener(v -> {
            new android.app.TimePickerDialog(this, (view, hourOfDay, minute) -> {
                offTime[0] = hourOfDay;
                offTime[1] = minute;
                btnTimeOff.setText(String.format("%02d:%02d", hourOfDay, minute));
            }, offTime[0], offTime[1], true).show();
        });
        offTimeRow.addView(tvOffTimeLbl);
        offTimeRow.addView(btnTimeOff);
        layoutOffContainer.addView(offTimeRow);

        // OFF Conditions container (Local only)
        LinearLayout layoutOffCondSection = new LinearLayout(this);
        layoutOffCondSection.setOrientation(LinearLayout.VERTICAL);
        layoutOffCondSection.setVisibility(View.GONE); // Default hardware mode

        TextView tvOffCondTitle = new TextView(this);
        tvOffCondTitle.setText("⚡ Conditions to turn OFF:");
        tvOffCondTitle.setTextColor(ContextCompat.getColor(this, R.color.text_bright));
        tvOffCondTitle.setTextSize(13f);
        tvOffCondTitle.setPadding(0, pad8, 0, 4);
        layoutOffCondSection.addView(tvOffCondTitle);

        LinearLayout offConjRow = new LinearLayout(this);
        offConjRow.setOrientation(LinearLayout.HORIZONTAL);
        offConjRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView tvOffConjLbl = new TextView(this);
        tvOffConjLbl.setText("Match mode: ");
        tvOffConjLbl.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        tvOffConjLbl.setTextSize(12f);
        android.widget.Spinner spOffConj = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> conjAdapter2 = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"ALL conditions (AND)", "ANY condition (OR)"});
        conjAdapter2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spOffConj.setAdapter(conjAdapter2);
        offConjRow.addView(tvOffConjLbl);
        offConjRow.addView(spOffConj);
        layoutOffCondSection.addView(offConjRow);

        LinearLayout containerOffConds = new LinearLayout(this);
        containerOffConds.setOrientation(LinearLayout.VERTICAL);
        layoutOffCondSection.addView(containerOffConds);

        Button btnAddOffCond = new Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnAddOffCond.setText("+ Add OFF Condition");
        btnAddOffCond.setTextSize(12f);
        btnAddOffCond.setTextColor(ContextCompat.getColor(this, R.color.accent_neon));
        btnAddOffCond.setOnClickListener(v -> addConditionRow(this, containerOffConds, "power", "==", "ON"));
        layoutOffCondSection.addView(btnAddOffCond);

        layoutOffContainer.addView(layoutOffCondSection);
        root.addView(layoutOffContainer);

        // Dynamic behavior depending on type choice
        rgType.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isLocal = (checkedId == rbLocal.getId());
            cbOn.setEnabled(isLocal);
            cbOff.setEnabled(isLocal);
            layoutOnCondSection.setVisibility(isLocal && cbOn.isChecked() ? View.VISIBLE : View.GONE);
            layoutOffCondSection.setVisibility(isLocal && cbOff.isChecked() ? View.VISIBLE : View.GONE);
            if (!isLocal) {
                cbOn.setChecked(true);
                cbOff.setChecked(true);
                layoutOnContainer.setVisibility(View.VISIBLE);
                layoutOffContainer.setVisibility(View.VISIBLE);
            }
        });

        // Local check listeners
        cbOn.setOnCheckedChangeListener((cb, isChecked) -> {
            layoutOnContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            layoutOnCondSection.setVisibility(rbLocal.isChecked() && isChecked ? View.VISIBLE : View.GONE);
        });
        cbOff.setOnCheckedChangeListener((cb, isChecked) -> {
            layoutOffContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            layoutOffCondSection.setVisibility(rbLocal.isChecked() && isChecked ? View.VISIBLE : View.GONE);
        });

        // ── Days of week (2 rows: 4 + 3) ────────────────────────────
        TextView tvDaysLabel = new TextView(this);
        tvDaysLabel.setText("Repeat on:");
        tvDaysLabel.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        tvDaysLabel.setTextSize(13f);
        LinearLayout.LayoutParams daysLblLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        daysLblLp.setMargins(0, pad16, 0, pad8);
        tvDaysLabel.setLayoutParams(daysLblLp);
        root.addView(tvDaysLabel);

        String[] dayNames = {"Sun","Mon","Tue","Wed","Thu","Fri","Sat"};
        CheckBox[] dayBoxes = new CheckBox[7];

        LinearLayout daysRow1 = new LinearLayout(this);
        daysRow1.setOrientation(LinearLayout.HORIZONTAL);
        daysRow1.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        for (int d = 0; d < 4; d++) {
            dayBoxes[d] = new CheckBox(this);
            dayBoxes[d].setText(dayNames[d]);
            dayBoxes[d].setChecked(true);
            dayBoxes[d].setTextColor(ContextCompat.getColor(this, R.color.text_bright));
            dayBoxes[d].setTextSize(12f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            dayBoxes[d].setLayoutParams(lp);
            daysRow1.addView(dayBoxes[d]);
        }
        root.addView(daysRow1);

        LinearLayout daysRow2 = new LinearLayout(this);
        daysRow2.setOrientation(LinearLayout.HORIZONTAL);
        daysRow2.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        for (int d = 4; d < 7; d++) {
            dayBoxes[d] = new CheckBox(this);
            dayBoxes[d].setText(dayNames[d]);
            dayBoxes[d].setChecked(true);
            dayBoxes[d].setTextColor(ContextCompat.getColor(this, R.color.text_bright));
            dayBoxes[d].setTextSize(12f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            dayBoxes[d].setLayoutParams(lp);
            daysRow2.addView(dayBoxes[d]);
        }
        View dummy = new View(this);
        LinearLayout.LayoutParams dummyLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        dummy.setLayoutParams(dummyLp);
        daysRow2.addView(dummy);
        root.addView(daysRow2);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);

        new MaterialAlertDialogBuilder(this, R.style.Theme_MarpacBridge)
            .setTitle("➕ Add Schedule Plan")
            .setView(sv)
            .setPositiveButton("Save", (dialog, which) -> {
                boolean isLocal = rbLocal.isChecked();
                boolean wantsOn  = cbOn.isChecked();
                boolean wantsOff = cbOff.isChecked();
                if (isLocal && !wantsOn && !wantsOff) {
                    Toast.makeText(this, "Enable at least ON or OFF", Toast.LENGTH_SHORT).show();
                    return;
                }
                int days = 0;
                for (int d = 0; d < 7; d++) if (dayBoxes[d].isChecked()) days |= (1 << d);
                if (days == 0) {
                    Toast.makeText(this, "Select at least one day", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    JSONArray arr = new JSONArray(prefs.getString("schedules_json", "[]"));
                    
                    if (!isLocal) {
                        int hwCount = 0;
                        for (int j = 0; j < arr.length(); j++) {
                            if (!"local".equals(arr.getJSONObject(j).optString("type"))) {
                                hwCount++;
                            }
                        }
                        if (hwCount >= MAX_SCHEDULES) {
                            Toast.makeText(this, "Maximum " + MAX_SCHEDULES + " hardware plans supported by Yogasleep memory", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }

                    int newId = (int)(System.currentTimeMillis() % 100000);
                    JSONObject plan = new JSONObject();
                    plan.put("id", newId);
                    plan.put("days", days);
                    plan.put("enabled", true);
                    plan.put("type", isLocal ? "local" : "hardware");
                    
                    plan.put("on_enabled",  wantsOn);
                    plan.put("on_hour",     onTime[0]);
                    plan.put("on_minute",   onTime[1]);
                    plan.put("on_volume",   npVol.getValue());
                    
                    plan.put("off_enabled", wantsOff);
                    plan.put("off_hour",    offTime[0]);
                    plan.put("off_minute",  offTime[1]);
                    
                    if (isLocal) {
                        // Serialize ON conditions
                        plan.put("on_cond_conjunction", spOnConj.getSelectedItemPosition() == 1 ? "OR" : "AND");
                        JSONArray onCondArr = new JSONArray();
                        for (int k = 0; k < containerOnConds.getChildCount(); k++) {
                            View r = containerOnConds.getChildAt(k);
                            Object[] tag = (Object[]) r.getTag();
                            if (tag != null && tag.length == 3) {
                                android.widget.Spinner spVar = (android.widget.Spinner) tag[0];
                                android.widget.Spinner spOp = (android.widget.Spinner) tag[1];
                                android.widget.Spinner spVal = (android.widget.Spinner) tag[2];
                                JSONObject cObj = new JSONObject();
                                cObj.put("var", spVar.getSelectedItemPosition() == 0 ? "power" : "speed");
                                cObj.put("op", spOp.getSelectedItem() != null ? spOp.getSelectedItem().toString() : "==");
                                cObj.put("val", spVal.getSelectedItem() != null ? spVal.getSelectedItem().toString() : "");
                                onCondArr.put(cObj);
                            }
                        }
                        plan.put("on_conditions", onCondArr);

                        // Serialize OFF conditions
                        plan.put("off_cond_conjunction", spOffConj.getSelectedItemPosition() == 1 ? "OR" : "AND");
                        JSONArray offCondArr = new JSONArray();
                        for (int k = 0; k < containerOffConds.getChildCount(); k++) {
                            View r = containerOffConds.getChildAt(k);
                            Object[] tag = (Object[]) r.getTag();
                            if (tag != null && tag.length == 3) {
                                android.widget.Spinner spVar = (android.widget.Spinner) tag[0];
                                android.widget.Spinner spOp = (android.widget.Spinner) tag[1];
                                android.widget.Spinner spVal = (android.widget.Spinner) tag[2];
                                JSONObject cObj = new JSONObject();
                                cObj.put("var", spVar.getSelectedItemPosition() == 0 ? "power" : "speed");
                                cObj.put("op", spOp.getSelectedItem() != null ? spOp.getSelectedItem().toString() : "==");
                                cObj.put("val", spVal.getSelectedItem() != null ? spVal.getSelectedItem().toString() : "");
                                offCondArr.put(cObj);
                            }
                        }
                        plan.put("off_conditions", offCondArr);
                    }

                    // Legacy fields
                    plan.put("action",  wantsOn ? 1 : 0);
                    plan.put("hour",    wantsOn ? onTime[0] : offTime[0]);
                    plan.put("minute",  wantsOn ? onTime[1] : offTime[1]);
                    plan.put("volume",  npVol.getValue());

                    arr.put(plan);
                    prefs.edit().putString("schedules_json", arr.toString()).apply();
                    
                    if (isLocal) {
                        checkExactAlarmPermissionAndSchedule(newId, wantsOn, onTime[0], onTime[1], wantsOff, offTime[0], offTime[1]);
                    } else {
                        startBLEServiceWithAction("sync_schedules");
                    }
                    
                    renderSchedules();
                    Toast.makeText(this, "Plan saved", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Error saving plan", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showHelpDialog() {

        String htmlContent =
            "<b><big>📱 Guía de Integración con Tasker</big></b><br><br>" +

            // ── SECTION 0: PREREQUISITE ──────────────────────────────────
            "<b>⚙️ Requisito previo</b><br>" +
            "La app debe estar configurada al menos una vez (MAC guardada, Unit Number obtenido con 'Query Unit'). " +
            "Tasker manda intents en segundo plano; la app no necesita estar abierta.<br><br>" +
            "<font color='#6366F1'>• Action:</font> <tt>com.nitsu.marpacbridge.ACTION_CONTROL</tt><br>" +
            "<font color='#6366F1'>• Package:</font> <tt>com.nitsu.marpacbridge</tt><br>" +
            "<font color='#6366F1'>• Target:</font> Broadcast Receiver<br>" +
            "<font color='#6366F1'>• Cat:</font> None (dejar vacío)<br><br>" +

            "<hr><br>" +

            // ── SECTION 1: ENCENDER ──────────────────────────────────────
            "<b>🟢 TASK 1 — Encender la máquina</b><br><br>" +
            "En Tasker: <b>+ → Plugin → Send Intent</b><br>" +
            "Completar los campos así:<br>" +
            "&nbsp;&nbsp;• Action: <tt>com.nitsu.marpacbridge.ACTION_CONTROL</tt><br>" +
            "&nbsp;&nbsp;• Extra: <tt>command:power</tt><br>" +
            "&nbsp;&nbsp;• Extra: <tt>state:1</tt><br>" +
            "&nbsp;&nbsp;• Target: Broadcast Receiver<br><br>" +
            "<b>Con reintentos (recomendado):</b> Usá la acción <b>Send Intent</b> dentro de un loop de Tasker:<br>" +
            "&nbsp;&nbsp;1. Variable Set: <tt>%retry = 0</tt><br>" +
            "&nbsp;&nbsp;2. While <tt>%retry &lt; 5</tt><br>" +
            "&nbsp;&nbsp;3. &nbsp;&nbsp;Send Intent (con command:power / state:1)<br>" +
            "&nbsp;&nbsp;4. &nbsp;&nbsp;Wait: 6 segundos (tiempo para conectar BLE)<br>" +
            "&nbsp;&nbsp;5. &nbsp;&nbsp;Variable Add: <tt>%retry + 1</tt><br>" +
            "&nbsp;&nbsp;6. End While<br>" +
            "<i>El servicio ignora comandos duplicados si ya está conectado, es seguro reintentar.</i><br><br>" +

            "<hr><br>" +

            // ── SECTION 2: APAGAR ───────────────────────────────────────
            "<b>🔴 TASK 2 — Apagar la máquina</b><br><br>" +
            "Igual que la Task de encender, pero con <tt>state:0</tt>:<br>" +
            "&nbsp;&nbsp;• Extra: <tt>command:power</tt><br>" +
            "&nbsp;&nbsp;• Extra: <tt>state:0</tt><br><br>" +
            "Si usás reintentos, el mismo loop de 5 intentos con 6s de wait funciona igual.<br><br>" +

            "<hr><br>" +

            // ── SECTION 3: VOLUMEN ──────────────────────────────────────
            "<b>🔊 TASK 3 — Cambiar el volumen</b><br><br>" +
            "Extra: <tt>command:volume</tt><br>" +
            "Extra: <tt>level:N</tt> donde N es un número entre <b>1 y 10</b><br><br>" +
            "<b>Ejemplos:</b><br>" +
            "&nbsp;&nbsp;• Volumen mínimo: <tt>level:1</tt><br>" +
            "&nbsp;&nbsp;• Volumen medio: &nbsp;<tt>level:5</tt><br>" +
            "&nbsp;&nbsp;• Volumen máximo: <tt>level:10</tt><br><br>" +
            "<i>Nota: la app sube/baja el volumen de a un paso por vez para proteger el motor. " +
            "Si el salto es grande, puede tardar unos segundos en llegar al nivel final.</i><br><br>" +

            "<hr><br>" +

            // ── SECTION 4: ENCENDER + VOLUMEN ───────────────────────────
            "<b>🟢🔊 TASK 4 — Encender y fijar volumen en un solo intent</b><br><br>" +
            "Podés encender y setear el volumen juntos con:<br>" +
            "&nbsp;&nbsp;• Extra: <tt>command:power</tt><br>" +
            "&nbsp;&nbsp;• Extra: <tt>state:1</tt><br>" +
            "&nbsp;&nbsp;• Extra: <tt>level:7</tt> (el volumen deseado)<br><br>" +
            "Esto enciende la máquina y ajusta el volumen en la misma conexión BLE.<br><br>" +

            "<hr><br>" +

            // ── SECTION 5: SINCRONIZAR HORA ─────────────────────────────
            "<b>🕐 TASK 5 — Sincronizar hora del dispositivo</b><br><br>" +
            "Para que los planes internos del hardware funcionen con la hora correcta:<br>" +
            "&nbsp;&nbsp;• Extra: <tt>command:sync_time</tt><br><br>" +
            "Se recomienda llamar esto una vez por día (ej: al despertar o al conectarse a WiFi).<br><br>" +

            "<hr><br>" +

            // ── SECTION 6: PLANES LOCALES ────────────────────────────────
            "<b>📅 TASK 6 — Planes de encendido/apagado automático</b><br><br>" +
            "Usá la sección <b>Planes</b> dentro de esta app para programar hasta <b>15 planes</b> " +
            "de encendido y/o apagado por días y hora. Los planes usan AlarmManager del teléfono, " +
            "no dependen del hardware.<br><br>" +
            "Cada plan puede:<br>" +
            "&nbsp;&nbsp;• Encender a una hora fija con un volumen específico<br>" +
            "&nbsp;&nbsp;• Apagar a una hora fija<br>" +
            "&nbsp;&nbsp;• Repetirse en días específicos de la semana<br><br>" +
            "<i>No hace falta Tasker para los planes; se configuran directamente en la app.</i><br><br>" +

            "<hr><br>" +

            // ── SECTION 7: TASKER PROFILES ───────────────────────────────
            "<b>⏰ TASK 7 — Perfil Tasker basado en hora (alternativa)</b><br><br>" +
            "Si preferís que Tasker maneje el horario en vez de la app:<br><br>" +
            "1. Crear perfil: <b>+ → Time → From 22:00 To 22:00</b> (una sola hora)<br>" +
            "2. Vincular la Task de <b>Encender</b> o <b>Apagar</b> con reintentos.<br>" +
            "3. Para días específicos, habilitá la opción <b>Day</b> en el perfil de tiempo.<br><br>" +
            "<b>Ejemplo de Task completa con 5 reintentos:</b><br>" +
            "&nbsp;&nbsp;A1. Variable Set <tt>%intento = 1</tt><br>" +
            "&nbsp;&nbsp;A2. Goto: A4 <i>(ir al envío directo)</i><br>" +
            "&nbsp;&nbsp;A3. Wait 6s<br>" +
            "&nbsp;&nbsp;A4. Send Intent (command:power / state:1)<br>" +
            "&nbsp;&nbsp;A5. Variable Add <tt>%intento + 1</tt><br>" +
            "&nbsp;&nbsp;A6. If <tt>%intento &lt;= 5</tt> → Goto A3<br>" +
            "&nbsp;&nbsp;A7. End If<br><br>" +

            "<hr><br>" +

            // ── SECTION 8: GOOGLE HOME ───────────────────────────────────
            "<b>🎙️ TASK 8 — Control por voz con Google Home (AutoVoice)</b><br><br>" +
            "Para usar \"Ok Google, enciende el Marpac\":<br><br>" +
            "1. Instalá <b>AutoVoice</b> → ir a <b>Smart Home Devices</b>.<br>" +
            "2. Registrá un nuevo dispositivo virtual de tipo <b>Interruptor</b> llamado <b>Marpac</b>.<br>" +
            "3. En Google Home vinculá el servicio <b>AutoVoice</b>. El interruptor virtual aparecerá.<br>" +
            "4. En Tasker creá un perfil: <b>Plugin → AutoVoice Smart Home Device</b> para On y otro para Off.<br>" +
            "5. Asignale la Task de encender/apagar respectivamente.<br><br>" +

            "<hr><br>" +

            // ── QUICK REFERENCE ──────────────────────────────────────────
            "<b>📋 Referencia rápida de comandos</b><br><br>" +
            "<tt>command:power</tt> + <tt>state:1</tt> → Encender<br>" +
            "<tt>command:power</tt> + <tt>state:0</tt> → Apagar<br>" +
            "<tt>command:volume</tt> + <tt>level:N</tt> → Volumen N (1–10)<br>" +
            "<tt>command:power</tt> + <tt>state:1</tt> + <tt>level:N</tt> → Encender con volumen<br>" +
            "<tt>command:sync_time</tt> → Sincronizar hora<br>";

        android.text.Spanned formattedText;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            formattedText = Html.fromHtml(htmlContent, Html.FROM_HTML_MODE_COMPACT);
        } else {
            formattedText = Html.fromHtml(htmlContent);
        }

        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(formattedText);
        tv.setPadding(50, 40, 50, 40);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_bright));
        tv.setTextSize(14f);
        scrollView.addView(tv);

        new MaterialAlertDialogBuilder(this, R.style.Theme_MarpacBridge)
                .setTitle("📱 Guía de Integración")
                .setView(scrollView)
                .setPositiveButton("Cerrar", null)
                .show();
    }

    private void checkExactAlarmPermissionAndSchedule(int newId, boolean wantsOn, int onHour, int onMinute, boolean wantsOff, int offHour, int offMinute) {
        boolean canSchedule = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            canSchedule = alarmManager != null && alarmManager.canScheduleExactAlarms();
        }

        if (!canSchedule) {
            new MaterialAlertDialogBuilder(this, R.style.Theme_MarpacBridge)
                .setTitle("🔑 Alarms and Reminders Permission")
                .setMessage("To trigger the white noise machine at the exact scheduled time in the background, Android requires the 'Alarms and Reminders' permission.\n\nPlease grant it in the next screen, otherwise the plan will run with a delay (inexact mode).")
                .setPositiveButton("Grant", (dialog, which) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Exception e) {
                            try {
                                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                                startActivity(intent);
                            } catch (Exception ignored) {}
                        }
                    }
                })
                .setNegativeButton("Keep Inexact", (dialog, which) -> {
                    Toast.makeText(this, "Saving plan in inexact backup mode (may trigger with delay)", Toast.LENGTH_LONG).show();
                })
                .setOnDismissListener(dialog -> {
                    doScheduleLocalAlarms(newId, wantsOn, onHour, onMinute, wantsOff, offHour, offMinute);
                })
                .show();
        } else {
            doScheduleLocalAlarms(newId, wantsOn, onHour, onMinute, wantsOff, offHour, offMinute);
        }
    }

    private void doScheduleLocalAlarms(int newId, boolean wantsOn, int onHour, int onMinute, boolean wantsOff, int offHour, int offMinute) {
        if (wantsOn)  ScheduleAlarmReceiver.scheduleAlarm(this, newId * 10,     onHour,  onMinute);
        if (wantsOff) ScheduleAlarmReceiver.scheduleAlarm(this, newId * 10 + 1, offHour, offMinute);
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new MaterialAlertDialogBuilder(this, R.style.Theme_MarpacBridge)
                    .setTitle("🔋 Disable Battery Optimization")
                    .setMessage("To allow Tasker intents and background alarms to execute reliably without needing to open the app, please set Battery Optimization to 'Unrestricted' (Don't Optimize) for Marpac Bridge.")
                    .setPositiveButton("Configure", (dialog, which) -> {
                        try {
                            android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Exception e) {
                            try {
                                android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                                startActivity(intent);
                            } catch (Exception ignored) {}
                        }
                    })
                    .setNegativeButton("Ignore", null)
                    .show();
            }
        }
    }
}
