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
import java.util.Calendar;

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
                addLog(context, "Today is active in days mask. Executing command: " + (alarmType == 0 ? "ON" : "OFF"));

                Intent serviceIntent = new Intent(context, BLEService.class);
                serviceIntent.setAction(BLEService.ACTION_EXECUTE);
                serviceIntent.putExtra("mac_address", mac);
                serviceIntent.putExtra("unit_number", unitNum);

                if (alarmType == 0) {
                    int volume = target.optInt("on_volume", 5);
                    serviceIntent.putExtra("command", "power");
                    serviceIntent.putExtra("state", 1);
                    serviceIntent.putExtra("level", volume);
                } else {
                    serviceIntent.putExtra("command", "power");
                    serviceIntent.putExtra("state", 0);
                }

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(calendar.getTimeInMillis(), pendingIntent);
                        alarmManager.setAlarmClock(info, pendingIntent);
                        addLog(context, "Rescheduled exact AlarmClock ID " + alarmId + " for tomorrow at " + hour + ":" + minute);
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
                        addLog(context, "Rescheduled inexact Alarm ID " + alarmId + " (no permission) for tomorrow at " + hour + ":" + minute);
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(calendar.getTimeInMillis(), pendingIntent);
                    alarmManager.setAlarmClock(info, pendingIntent);
                    addLog(context, "Rescheduled exact AlarmClock ID " + alarmId + " for tomorrow at " + hour + ":" + minute);
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
                    addLog(context, "Rescheduled exact Alarm ID " + alarmId + " for tomorrow at " + hour + ":" + minute);
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(calendar.getTimeInMillis(), pendingIntent);
                        alarmManager.setAlarmClock(info, pendingIntent);
                        addLog(context, "Scheduled exact AlarmClock ID " + alarmId + " for " + hour + ":" + minute);
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
                        addLog(context, "Scheduled inexact Alarm ID " + alarmId + " (no permission) for " + hour + ":" + minute);
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(calendar.getTimeInMillis(), pendingIntent);
                    alarmManager.setAlarmClock(info, pendingIntent);
                    addLog(context, "Scheduled exact AlarmClock ID " + alarmId + " for " + hour + ":" + minute);
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
                    addLog(context, "Scheduled exact Alarm ID " + alarmId + " for " + hour + ":" + minute);
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
}
