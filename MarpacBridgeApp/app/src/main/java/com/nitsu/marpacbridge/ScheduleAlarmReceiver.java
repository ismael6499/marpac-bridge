package com.nitsu.marpacbridge;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class ScheduleAlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "MarpacScheduleAlarm";

    private static void addLog(Context context, String message) {
        Log.d(TAG, message);
        Intent logIntent = new Intent("com.nitsu.marpacbridge.ACTION_LOG");
        logIntent.putExtra("log_line", "[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + "] [AlarmManager] " + message);
        logIntent.setPackage(context.getPackageName());
        context.sendBroadcast(logIntent);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int alarmId = intent.getIntExtra("schedule_id", -1);
        addLog(context, "Alarm triggered! Alarm ID: " + alarmId);
        if (alarmId == -1) return;

        // Decode original schedule ID and type (0 = ON, 1 = OFF)
        int scheduleId = alarmId / 10;
        int alarmType = alarmId % 10;

        SharedPreferences prefs = context.getSharedPreferences("MarpacBridgePrefs", Context.MODE_PRIVATE);
        String schedulesJson = prefs.getString("schedules_json", "[]");
        String mac = prefs.getString("mac_address", "");
        String unitNum = prefs.getString("unit_number", "");

        if (mac.isEmpty() || "AA:BB:CC:DD:EE:FF".equals(mac)) {
            addLog(context, "Device MAC not configured. Skipping scheduled action.");
            return;
        }

        try {
            JSONArray arr = new JSONArray(schedulesJson);
            JSONObject target = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                if (obj.getInt("id") == scheduleId) {
                    target = obj;
                    break;
                }
            }

            if (target == null || !target.getBoolean("enabled")) {
                addLog(context, "Parent plan " + scheduleId + " is deleted or disabled. Skipping execution.");
                return;
            }

            // Verify if the alarm type is actually enabled in the plan
            boolean isActionEnabled = (alarmType == 0) 
                    ? target.optBoolean("on_enabled", true)
                    : target.optBoolean("off_enabled", true);

            if (!isActionEnabled) {
                addLog(context, "Alarm action " + (alarmType == 0 ? "ON" : "OFF") + " is disabled for plan " + scheduleId);
                return;
            }

            // Check day of week mask
            Calendar now = Calendar.getInstance();
            int dayOfWeek = now.get(Calendar.DAY_OF_WEEK); // 1 = Sun, 2 = Mon, ... 7 = Sat
            int dayMask = 1 << (dayOfWeek - 1);
            int scheduleDays = target.getInt("days");

            if ((scheduleDays & dayMask) != 0) {
                String conj = (alarmType == 0)
                        ? target.optString("on_cond_conjunction", "AND")
                        : target.optString("off_cond_conjunction", "AND");
                JSONArray conds = (alarmType == 0)
                        ? target.optJSONArray("on_conditions")
                        : target.optJSONArray("off_conditions");

                if (!evaluateConditions(context, conj, conds)) {
                    addLog(context, "Conditions not met for action " + (alarmType == 0 ? "ON" : "OFF") + ". Skipping execution.");
                } else {
                    addLog(context, "Today is active and conditions met. Executing command: " + (alarmType == 0 ? "ON" : "OFF"));

                    Intent serviceIntent = new Intent(context, BLEService.class);
                    serviceIntent.setAction(BLEService.ACTION_EXECUTE);
                    serviceIntent.putExtra("mac_address", mac);
                    serviceIntent.putExtra("unit_number", unitNum);

                    SharedPreferences.Editor editor = prefs.edit();

                    if (alarmType == 0) {
                        int volume = target.optInt("on_volume", 5);
                        serviceIntent.putExtra("command", "power");
                        serviceIntent.putExtra("state", 1);
                        serviceIntent.putExtra("level", volume);
                        editor.putInt("last_power_state", 1);
                        editor.putInt("last_volume", volume);
                    } else {
                        serviceIntent.putExtra("command", "power");
                        serviceIntent.putExtra("state", 0);
                        editor.putInt("last_power_state", 0);
                    }
                    editor.apply();

                    // Broadcast state update immediately for Tasker / UI / Watch
                    int curPower = prefs.getInt("last_power_state", 0);
                    int curVol = prefs.getInt("last_volume", 5);
                    String timeStr = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
                    Intent stateBroadcast = new Intent("com.nitsu.marpacbridge.ACTION_STATE_CHANGED");
                    stateBroadcast.putExtra("power", curPower);
                    stateBroadcast.putExtra("power_text", curPower == 1 ? "ON" : "OFF");
                    stateBroadcast.putExtra("volume", curVol);
                    stateBroadcast.putExtra("last_update", timeStr);
                    context.sendBroadcast(stateBroadcast);

                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent);
                        } else {
                            context.startService(serviceIntent);
                        }
                    } catch (Exception e) {
                        addLog(context, "Foreground service start blocked: " + e.getMessage() + ". Falling back to background service.");
                        try {
                            context.startService(serviceIntent);
                        } catch (Exception ex) {
                            addLog(context, "All service starts failed: " + ex.getMessage());
                        }
                    }
                }
            } else {
                addLog(context, "Today is skipped by repeat mask. No BLE command sent.");
            }

            // Reschedule this specific alarm for tomorrow
            int hour = (alarmType == 0) 
                    ? target.optInt("on_hour", 22)
                    : target.optInt("off_hour", 7);
            int minute = (alarmType == 0)
                    ? target.optInt("on_minute", 0)
                    : target.optInt("off_minute", 0);

            reschedule(context, alarmId, hour, minute);

        } catch (Exception e) {
            addLog(context, "Exception in alarm trigger: " + e.getMessage());
        }
    }

    private void reschedule(Context context, int alarmId, int hour, int minute) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, ScheduleAlarmReceiver.class);
        intent.putExtra("schedule_id", alarmId);
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                alarmId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.DAY_OF_YEAR, 1); // Tomorrow

        if (alarmManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
                        addLog(context, "Rescheduled exact background timer ID " + alarmId + " for tomorrow at " + hour + ":" + minute);
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
                        addLog(context, "Rescheduled inexact background timer ID " + alarmId + " (no permission) for tomorrow at " + hour + ":" + minute);
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
                    addLog(context, "Rescheduled exact background timer ID " + alarmId + " for tomorrow at " + hour + ":" + minute);
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
                    addLog(context, "Rescheduled background timer ID " + alarmId + " for tomorrow at " + hour + ":" + minute);
                }
            } catch (Exception e) {
                addLog(context, "Reschedule failed: " + e.getMessage());
            }
        }
    }

    public static void scheduleAlarm(Context context, int alarmId, int hour, int minute) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, ScheduleAlarmReceiver.class);
        intent.putExtra("schedule_id", alarmId);
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                alarmId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        // If the scheduled time is in the past today, schedule it for tomorrow
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        if (alarmManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
                        addLog(context, "Scheduled exact background timer ID " + alarmId + " for " + hour + ":" + minute);
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
                        addLog(context, "Scheduled inexact background timer ID " + alarmId + " (no permission) for " + hour + ":" + minute);
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
                    addLog(context, "Scheduled exact background timer ID " + alarmId + " for " + hour + ":" + minute);
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
                    addLog(context, "Scheduled background timer ID " + alarmId + " for " + hour + ":" + minute);
                }
            } catch (Exception e) {
                addLog(context, "Schedule failed: " + e.getMessage());
            }
        }
    }

    public static void cancelAlarm(Context context, int alarmId) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, ScheduleAlarmReceiver.class);
        intent.putExtra("schedule_id", alarmId);
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                alarmId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
            addLog(context, "Cancelled alarm ID " + alarmId);
        }
    }

    private static class BooleanExpressionEvaluator {
        private final List<String> tokens;
        private int pos = 0;

        public BooleanExpressionEvaluator(List<String> tokens) {
            this.tokens = tokens;
        }

        public boolean parse() {
            if (tokens.isEmpty()) return true;
            try {
                return parseExpression();
            } catch (Exception e) {
                Log.e("MarpacScheduleAlarm", "Error parsing boolean expression", e);
                return true;
            }
        }

        private boolean parseExpression() {
            boolean left = parseTerm();
            while (pos < tokens.size() && "OR".equalsIgnoreCase(tokens.get(pos))) {
                pos++;
                boolean right = parseTerm();
                left = left || right;
            }
            return left;
        }

        private boolean parseTerm() {
            boolean left = parseFactor();
            while (pos < tokens.size() && "AND".equalsIgnoreCase(tokens.get(pos))) {
                pos++;
                boolean right = parseFactor();
                left = left && right;
            }
            return left;
        }

        private boolean parseFactor() {
            if (pos >= tokens.size()) return true;
            String token = tokens.get(pos);
            if ("(".equals(token)) {
                pos++;
                boolean result = parseExpression();
                if (pos < tokens.size() && ")".equals(tokens.get(pos))) {
                    pos++;
                }
                return result;
            } else if ("true".equalsIgnoreCase(token)) {
                pos++;
                return true;
            } else if ("false".equalsIgnoreCase(token)) {
                pos++;
                return false;
            } else {
                pos++;
                return true;
            }
        }
    }

    private static boolean evaluateConditions(Context context, String conjunction, JSONArray conditions) {
        if (conditions == null || conditions.length() == 0) {
            return true;
        }
        SharedPreferences prefs = context.getSharedPreferences("MarpacBridgePrefs", Context.MODE_PRIVATE);
        int currentPower = prefs.getInt("last_power_state", 0);
        int currentSpeed = prefs.getInt("last_volume", 5);

        List<String> tokens = new ArrayList<>();

        for (int i = 0; i < conditions.length(); i++) {
            try {
                JSONObject cond = conditions.getJSONObject(i);
                
                String openParen = cond.optString("open_paren", "");
                for (int p = 0; p < openParen.length(); p++) {
                    if (openParen.charAt(p) == '(') tokens.add("(");
                }

                String var = cond.getString("var");
                String op = cond.getString("op");
                String val = cond.getString("val");

                boolean match = false;
                if ("power".equals(var)) {
                    int expectedPower = "ON".equalsIgnoreCase(val) ? 1 : 0;
                    if ("==".equals(op)) {
                        match = (currentPower == expectedPower);
                    } else if ("!=".equals(op)) {
                        match = (currentPower != expectedPower);
                    }
                } else if ("speed".equalsIgnoreCase(var) || "volumen".equalsIgnoreCase(var)) {
                    int currentVal = currentSpeed;
                    int expectedVal = Integer.parseInt(val);
                    switch (op) {
                        case "==": match = (currentVal == expectedVal); break;
                        case "!=": match = (currentVal != expectedVal); break;
                        case "<":  match = (currentVal < expectedVal); break;
                        case ">":  match = (currentVal > expectedVal); break;
                        case "<=": match = (currentVal <= expectedVal); break;
                        case ">=": match = (currentVal >= expectedVal); break;
                    }
                }
                tokens.add(match ? "true" : "false");

                String closeParen = cond.optString("close_paren", "");
                for (int p = 0; p < closeParen.length(); p++) {
                    if (closeParen.charAt(p) == ')') tokens.add(")");
                }

                if (i < conditions.length() - 1) {
                    String logicOp = cond.optString("logic_op", conjunction != null ? conjunction : "AND");
                    tokens.add(logicOp);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error evaluating condition item " + i, e);
            }
        }

        BooleanExpressionEvaluator evaluator = new BooleanExpressionEvaluator(tokens);
        return evaluator.parse();
    }
}
