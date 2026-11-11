/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.provider;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.AndroidException;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Properties;

/**
 * The Settings provider contains global system-level device preferences (AOSP API).
 *
 * <p>teslalinux: AOSP's SettingsProvider runs in the system server. ATL keeps the three tables
 * (system, secure, global) in this process with the values a freshly set-up car device has,
 * stores changes in the app's data directory (atl_settings.properties) and notifies the content
 * observers of the changed setting's URI, as the provider does. As on Android, apps may only write
 * the system table (WRITE_SETTINGS, which this device grants), secure and global are read-only for
 * them, a missing setting is null (or a SettingNotFoundException for the getters without a
 * default), and android_id is a random 64-bit value generated on first use and kept from then on.
 */
public final class Settings {
    private static final String TAG = "Settings";

    public static final String AUTHORITY = "settings";

    public static final String ACTION_SETTINGS = "android.settings.SETTINGS";
    public static final String ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS";
    public static final String ACTION_LOCATION_SOURCE_SETTINGS = "android.settings.LOCATION_SOURCE_SETTINGS";
    public static final String ACTION_WIFI_SETTINGS = "android.settings.WIFI_SETTINGS";
    public static final String ACTION_WIRELESS_SETTINGS = "android.settings.WIRELESS_SETTINGS";
    public static final String ACTION_BLUETOOTH_SETTINGS = "android.settings.BLUETOOTH_SETTINGS";
    public static final String ACTION_DATE_SETTINGS = "android.settings.DATE_SETTINGS";
    public static final String ACTION_DISPLAY_SETTINGS = "android.settings.DISPLAY_SETTINGS";
    public static final String ACTION_SOUND_SETTINGS = "android.settings.SOUND_SETTINGS";
    public static final String ACTION_ACCESSIBILITY_SETTINGS = "android.settings.ACCESSIBILITY_SETTINGS";
    public static final String ACTION_SECURITY_SETTINGS = "android.settings.SECURITY_SETTINGS";
    public static final String ACTION_LOCALE_SETTINGS = "android.settings.LOCALE_SETTINGS";
    public static final String ACTION_APP_NOTIFICATION_SETTINGS = "android.settings.APP_NOTIFICATION_SETTINGS";
    public static final String ACTION_CHANNEL_NOTIFICATION_SETTINGS = "android.settings.CHANNEL_NOTIFICATION_SETTINGS";
    public static final String ACTION_MANAGE_OVERLAY_PERMISSION = "android.settings.action.MANAGE_OVERLAY_PERMISSION";
    public static final String ACTION_MANAGE_WRITE_SETTINGS = "android.settings.action.MANAGE_WRITE_SETTINGS";
    public static final String ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS";
    public static final String ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS";
    public static final String ACTION_APP_LOCALE_SETTINGS = "android.settings.APP_LOCALE_SETTINGS";
    public static final String ACTION_REQUEST_SCHEDULE_EXACT_ALARM = "android.settings.REQUEST_SCHEDULE_EXACT_ALARM";
    public static final String EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE";
    public static final String EXTRA_CHANNEL_ID = "android.provider.extra.CHANNEL_ID";

    /** Thrown when a setting has no value and the getter has no default. */
    public static class SettingNotFoundException extends AndroidException {
        public SettingNotFoundException(String msg) {
            super(msg);
        }
    }

    /** Common base for tables of name/value settings. */
    public static class NameValueTable implements BaseColumns {
        public static final String NAME = "name";
        public static final String VALUE = "value";

        public static Uri getUriFor(Uri uri, String name) {
            return Uri.withAppendedPath(uri, name);
        }
    }

    // ------------------------------------------------------------------------------- the store

    private static final HashMap<String, String> sValues = new HashMap<>();
    private static boolean sLoaded;

    private static File storeFile() {
        String dataDir = android.atl.ATLLoadedApp.getPrimaryApplication().pkg.applicationInfo.dataDir;
        if (dataDir == null)
            dataDir = android.os.Environment.getDataDirectory().getPath();
        return new File(dataDir, "atl_settings.properties");
    }

    private static void defaults() {
        // a freshly set-up device: provisioned, location on (the car's GPS), Wi-Fi up, no
        // developer options, animations at normal speed, no accessibility services
        sValues.put("global/device_provisioned", "1");
        sValues.put("global/airplane_mode_on", "0");
        sValues.put("global/auto_time", "1");
        sValues.put("global/auto_time_zone", "1");
        sValues.put("global/adb_enabled", "0");
        sValues.put("global/development_settings_enabled", "0");
        sValues.put("global/animator_duration_scale", "1.0");
        sValues.put("global/transition_animation_scale", "1.0");
        sValues.put("global/window_animation_scale", "1.0");
        sValues.put("global/always_finish_activities", "0");
        sValues.put("global/wifi_on", "1");
        sValues.put("global/bluetooth_on", "0");
        sValues.put("global/data_roaming", "0");
        sValues.put("global/mobile_data", "1");
        sValues.put("global/stay_on_while_plugged_in", "7");
        sValues.put("global/install_non_market_apps", "1");
        sValues.put("secure/user_setup_complete", "1");
        sValues.put("secure/location_mode", "3"); // LOCATION_MODE_HIGH_ACCURACY
        sValues.put("secure/location_providers_allowed", "gps,network");
        sValues.put("secure/accessibility_enabled", "0");
        sValues.put("secure/touch_exploration_enabled", "0");
        sValues.put("secure/enabled_accessibility_services", "");
        sValues.put("secure/install_non_market_apps", "1");
        sValues.put("system/font_scale", "1.0");
        sValues.put("system/accelerometer_rotation", "0");
        sValues.put("system/user_rotation", "0");
        sValues.put("system/screen_brightness", "255");
        sValues.put("system/screen_brightness_mode", "0");
        sValues.put("system/screen_off_timeout", "2147483647");
        sValues.put("system/haptic_feedback_enabled", "0");
        sValues.put("system/sound_effects_enabled", "1");
        sValues.put("system/dtmf_tone", "1");
        sValues.put("system/show_password", "1");
        sValues.put("system/end_button_behavior", "2");
    }

    private static void ensureLoaded() {
        if (sLoaded)
            return;
        sLoaded = true;
        defaults();
        File f = storeFile();
        if (f.exists()) {
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(in);
                for (String k : p.stringPropertyNames())
                    sValues.put(k, p.getProperty(k));
            } catch (IOException e) {
                Log.w(TAG, "cannot read " + f + ": " + e);
            }
        }
        if (sValues.get("secure/android_id") == null) {
            // AOSP (O+): a random 64-bit number per app signing key and user, as 16 hex digits
            long id = new java.security.SecureRandom().nextLong();
            sValues.put("secure/android_id", String.format("%016x", id));
            save();
        }
    }

    private static void save() {
        Properties p = new Properties();
        for (java.util.Map.Entry<String, String> e : sValues.entrySet()) {
            if (e.getValue() != null)
                p.setProperty(e.getKey(), e.getValue());
        }
        File f = storeFile();
        File tmp = new File(f.getPath() + ".new");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            p.store(out, "Settings (system/secure/global)");
            out.getFD().sync();
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + f + ": " + e);
            return;
        }
        if (!tmp.renameTo(f))
            Log.w(TAG, "cannot replace " + f);
    }

    static String get(String table, String name) {
        if (name == null)
            throw new NullPointerException("name");
        synchronized (sValues) {
            ensureLoaded();
            return sValues.get(table + "/" + name);
        }
    }

    static boolean put(ContentResolver cr, String table, Uri contentUri, String name, String value) {
        if (name == null)
            throw new NullPointerException("name");
        synchronized (sValues) {
            ensureLoaded();
            String key = table + "/" + name;
            String old = sValues.get(key);
            if (value == null ? old == null : value.equals(old))
                return true;
            if (value == null)
                sValues.remove(key);
            else
                sValues.put(key, value);
            save();
        }
        if (cr != null)
            cr.notifyChange(Uri.withAppendedPath(contentUri, name), null);
        return true;
    }

    static int getInt(String table, String name, int def) {
        String v = get(table, name);
        try {
            return v != null ? Integer.parseInt(v) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static int getInt(String table, String name) throws SettingNotFoundException {
        String v = get(table, name);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new SettingNotFoundException(name);
        }
    }

    static long getLong(String table, String name, long def) {
        String v = get(table, name);
        try {
            return v != null ? Long.parseLong(v) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static long getLong(String table, String name) throws SettingNotFoundException {
        String v = get(table, name);
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new SettingNotFoundException(name);
        }
    }

    static float getFloat(String table, String name, float def) {
        String v = get(table, name);
        try {
            return v != null ? Float.parseFloat(v) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static float getFloat(String table, String name) throws SettingNotFoundException {
        String v = get(table, name);
        if (v == null)
            throw new SettingNotFoundException(name);
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            throw new SettingNotFoundException(name);
        }
    }

    private static void readOnly(String table, String name) {
        throw new SecurityException("Permission denial: writing to " + table + " settings requires:"
                                    + "android.permission.WRITE_SECURE_SETTINGS (" + name + ")");
    }

    /**
     * AOSP: whether the app may draw overlay windows (the SYSTEM_ALERT_WINDOW app op). This device
     * grants every permission an app requests (PackageManager.checkPermission), so it is true when
     * the manifest requests SYSTEM_ALERT_WINDOW.
     */
    public static boolean canDrawOverlays(Context context) {
        return requests(context, "android.permission.SYSTEM_ALERT_WINDOW");
    }

    private static boolean requests(Context context, String permission) {
        java.util.List<String> requested = android.atl.ATLLoadedApp.getPrimaryApplication().pkg.requestedPermissions;
        return requested != null && requested.contains(permission);
    }

    /** System settings: preferences an app with WRITE_SETTINGS may change. */
    public static final class System extends NameValueTable {
        public static final Uri CONTENT_URI = Uri.parse("content://settings/system");
        public static final Uri DEFAULT_NOTIFICATION_URI = getUriFor("notification_sound");
        public static final Uri DEFAULT_RINGTONE_URI = getUriFor("ringtone");
        public static final Uri DEFAULT_ALARM_ALERT_URI = getUriFor("alarm_alert");

        public static final String FONT_SCALE = "font_scale";
        public static final String ACCELEROMETER_ROTATION = "accelerometer_rotation";
        public static final String USER_ROTATION = "user_rotation";
        public static final String SCREEN_BRIGHTNESS = "screen_brightness";
        public static final String SCREEN_BRIGHTNESS_MODE = "screen_brightness_mode";
        public static final String SCREEN_OFF_TIMEOUT = "screen_off_timeout";
        public static final String HAPTIC_FEEDBACK_ENABLED = "haptic_feedback_enabled";
        public static final String SOUND_EFFECTS_ENABLED = "sound_effects_enabled";
        public static final String TIME_12_24 = "time_12_24";
        public static final String TEXT_SHOW_PASSWORD = "show_password";
        public static final String DTMF_TONE_WHEN_DIALING = "dtmf_tone";
        public static final String NOTIFICATION_SOUND = "notification_sound";
        public static final String RINGTONE = "ringtone";

        public static Uri getUriFor(String name) {
            return getUriFor(CONTENT_URI, name);
        }

        /** AOSP: whether the app may write system settings (WRITE_SETTINGS, granted here when requested). */
        public static boolean canWrite(Context context) {
            return requests(context, "android.permission.WRITE_SETTINGS");
        }

        public static String getString(ContentResolver resolver, String name) {
            return get("system", name);
        }

        public static boolean putString(ContentResolver resolver, String name, String value) {
            return put(resolver, "system", CONTENT_URI, name, value);
        }

        public static int getInt(ContentResolver cr, String name, int def) {
            return Settings.getInt("system", name, def);
        }

        public static int getInt(ContentResolver cr, String name) throws SettingNotFoundException {
            return Settings.getInt("system", name);
        }

        public static boolean putInt(ContentResolver cr, String name, int value) {
            return putString(cr, name, Integer.toString(value));
        }

        public static long getLong(ContentResolver cr, String name, long def) {
            return Settings.getLong("system", name, def);
        }

        public static long getLong(ContentResolver cr, String name) throws SettingNotFoundException {
            return Settings.getLong("system", name);
        }

        public static boolean putLong(ContentResolver cr, String name, long value) {
            return putString(cr, name, Long.toString(value));
        }

        public static float getFloat(ContentResolver cr, String name, float def) {
            return Settings.getFloat("system", name, def);
        }

        public static float getFloat(ContentResolver cr, String name) throws SettingNotFoundException {
            return Settings.getFloat("system", name);
        }

        public static boolean putFloat(ContentResolver cr, String name, float value) {
            return putString(cr, name, Float.toString(value));
        }
    }

    /** Secure settings: readable by apps, written by the system only. */
    public static final class Secure extends NameValueTable {
        public static final Uri CONTENT_URI = Uri.parse("content://settings/secure");

        public static final String ANDROID_ID = "android_id";
        public static final String LOCATION_MODE = "location_mode";
        public static final String LOCATION_PROVIDERS_ALLOWED = "location_providers_allowed";
        public static final String ACCESSIBILITY_ENABLED = "accessibility_enabled";
        public static final String ENABLED_ACCESSIBILITY_SERVICES = "enabled_accessibility_services";
        public static final String TOUCH_EXPLORATION_ENABLED = "touch_exploration_enabled";
        public static final String DEFAULT_INPUT_METHOD = "default_input_method";
        public static final String ENABLED_INPUT_METHODS = "enabled_input_methods";
        public static final String INSTALL_NON_MARKET_APPS = "install_non_market_apps";
        public static final String ALLOW_MOCK_LOCATION = "mock_location";

        public static final int LOCATION_MODE_OFF = 0;
        public static final int LOCATION_MODE_SENSORS_ONLY = 1;
        public static final int LOCATION_MODE_BATTERY_SAVING = 2;
        public static final int LOCATION_MODE_HIGH_ACCURACY = 3;

        public static Uri getUriFor(String name) {
            return getUriFor(CONTENT_URI, name);
        }

        public static String getString(ContentResolver resolver, String name) {
            return get("secure", name);
        }

        public static boolean putString(ContentResolver resolver, String name, String value) {
            readOnly("secure", name);
            return false;
        }

        public static int getInt(ContentResolver cr, String name, int def) {
            return Settings.getInt("secure", name, def);
        }

        public static int getInt(ContentResolver cr, String name) throws SettingNotFoundException {
            return Settings.getInt("secure", name);
        }

        public static boolean putInt(ContentResolver cr, String name, int value) {
            return putString(cr, name, Integer.toString(value));
        }

        public static long getLong(ContentResolver cr, String name, long def) {
            return Settings.getLong("secure", name, def);
        }

        public static long getLong(ContentResolver cr, String name) throws SettingNotFoundException {
            return Settings.getLong("secure", name);
        }

        public static float getFloat(ContentResolver cr, String name, float def) {
            return Settings.getFloat("secure", name, def);
        }

        public static float getFloat(ContentResolver cr, String name) throws SettingNotFoundException {
            return Settings.getFloat("secure", name);
        }
    }

    /** Global settings: device-wide, readable by apps, written by the system only. */
    public static final class Global extends NameValueTable {
        public static final Uri CONTENT_URI = Uri.parse("content://settings/global");

        public static final String AIRPLANE_MODE_ON = "airplane_mode_on";
        public static final String AUTO_TIME = "auto_time";
        public static final String AUTO_TIME_ZONE = "auto_time_zone";
        public static final String ADB_ENABLED = "adb_enabled";
        public static final String DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled";
        public static final String ANIMATOR_DURATION_SCALE = "animator_duration_scale";
        public static final String TRANSITION_ANIMATION_SCALE = "transition_animation_scale";
        public static final String WINDOW_ANIMATION_SCALE = "window_animation_scale";
        public static final String ALWAYS_FINISH_ACTIVITIES = "always_finish_activities";
        public static final String DEVICE_PROVISIONED = "device_provisioned";
        public static final String WIFI_ON = "wifi_on";
        public static final String BLUETOOTH_ON = "bluetooth_on";
        public static final String DATA_ROAMING = "data_roaming";
        public static final String STAY_ON_WHILE_PLUGGED_IN = "stay_on_while_plugged_in";
        public static final String DEVICE_NAME = "device_name";
        public static final String BOOT_COUNT = "boot_count";

        public static Uri getUriFor(String name) {
            return getUriFor(CONTENT_URI, name);
        }

        public static String getString(ContentResolver resolver, String name) {
            return get("global", name);
        }

        public static boolean putString(ContentResolver resolver, String name, String value) {
            readOnly("global", name);
            return false;
        }

        public static int getInt(ContentResolver cr, String name, int def) {
            return Settings.getInt("global", name, def);
        }

        public static int getInt(ContentResolver cr, String name) throws SettingNotFoundException {
            return Settings.getInt("global", name);
        }

        public static boolean putInt(ContentResolver cr, String name, int value) {
            return putString(cr, name, Integer.toString(value));
        }

        public static long getLong(ContentResolver cr, String name, long def) {
            return Settings.getLong("global", name, def);
        }

        public static long getLong(ContentResolver cr, String name) throws SettingNotFoundException {
            return Settings.getLong("global", name);
        }

        public static float getFloat(ContentResolver cr, String name, float def) {
            return Settings.getFloat("global", name, def);
        }

        public static float getFloat(ContentResolver cr, String name) throws SettingNotFoundException {
            return Settings.getFloat("global", name);
        }
    }
}
