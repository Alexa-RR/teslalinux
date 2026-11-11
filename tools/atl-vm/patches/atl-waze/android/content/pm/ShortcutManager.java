/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.content.pm;

import android.atl.ATLLoadedApp;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Parcel;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * ShortcutManager executes operations on an app's set of shortcuts (AOSP API; the rules of AOSP's
 * ShortcutService for one package).
 *
 * <p>teslalinux: the car device has no launcher. Shortcuts are still real: the manifest shortcuts
 * are parsed from the app's android.app.shortcuts resource, dynamic shortcuts are kept with
 * AOSP's checks (immutable manifest shortcuts, the per-activity maximum, update/disable/enable
 * rules, implicit ranks) and stored in the app's data directory so they survive restarts, as the
 * system service stores them. Pinning needs a launcher: isRequestPinShortcutSupported() is false
 * and requestPinShortcut/createShortcutResultIntent report that, as on a device whose launcher
 * does not support pinning; so there are never pinned or cached shortcuts. Shortcut usage reports
 * go to the usage-stats service on Android; there is none here.
 */
public class ShortcutManager {
    private static final String TAG = "ShortcutManager";

    /** AOSP ShortcutService.DEFAULT_MAX_SHORTCUTS_PER_ACTIVITY */
    private static final int MAX_SHORTCUTS_PER_ACTIVITY = 15;
    /** AOSP ShortcutService.DEFAULT_MAX_ICON_DIMENSION_DP */
    private static final int MAX_ICON_DIMENSION_DP = 96;
    private static final int STORE_VERSION = 1;

    public static final int FLAG_MATCH_MANIFEST = 1 << 0;
    public static final int FLAG_MATCH_DYNAMIC = 1 << 1;
    public static final int FLAG_MATCH_PINNED = 1 << 2;
    public static final int FLAG_MATCH_CACHED = 1 << 3;

    /** the package's shortcuts by id (one store for the process, like the system service's) */
    private static LinkedHashMap<String, ShortcutInfo> sShortcuts;

    private final Context mContext;

    /** @hide */
    public ShortcutManager(Context context) {
        mContext = context;
    }

    /** teslalinux: created by Context.getSystemService(Class) through its no-argument constructor */
    public ShortcutManager() {
        this(ATLLoadedApp.getPrimaryApplication().getApplication());
    }

    private Context context() {
        return mContext != null ? mContext : ATLLoadedApp.getPrimaryApplication().getApplication();
    }

    // ------------------------------------------------------------------------------- the store

    private static File storeFile() {
        String dataDir = ATLLoadedApp.getPrimaryApplication().pkg.applicationInfo.dataDir;
        if (dataDir == null)
            dataDir = android.os.Environment.getDataDirectory().getPath();
        return new File(dataDir, "atl_shortcuts.bin");
    }

    private LinkedHashMap<String, ShortcutInfo> shortcuts() {
        synchronized (ShortcutManager.class) {
            if (sShortcuts == null) {
                sShortcuts = new LinkedHashMap<>();
                loadDynamic();
                loadManifestShortcuts();
            }
            return sShortcuts;
        }
    }

    private void loadDynamic() {
        File f = storeFile();
        if (!f.exists())
            return;
        Parcel p = Parcel.obtain();
        try {
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            p.unmarshall(data, 0, data.length);
            p.setDataPosition(0);
            if (p.readInt() != STORE_VERSION)
                return;
            int n = p.readInt();
            for (int i = 0; i < n; i++) {
                ShortcutInfo si = ShortcutInfo.CREATOR.createFromParcel(p);
                if (si.isDynamic())
                    sShortcuts.put(si.getId(), si);
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "cannot read " + f + ": " + e);
        } finally {
            p.recycle();
        }
    }

    private void save() {
        File f = storeFile();
        File tmp = new File(f.getPath() + ".new");
        Parcel p = Parcel.obtain();
        try {
            ArrayList<ShortcutInfo> dynamic = new ArrayList<>();
            for (ShortcutInfo si : sShortcuts.values()) {
                if (si.isDynamic())
                    dynamic.add(si.clone(ShortcutInfo.CLONE_REMOVE_ICON));
            }
            p.writeInt(STORE_VERSION);
            p.writeInt(dynamic.size());
            for (ShortcutInfo si : dynamic)
                si.writeToParcel(p, 0);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(p.marshall());
                out.getFD().sync();
            }
            if (!tmp.renameTo(f))
                Log.w(TAG, "cannot replace " + f);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "cannot write " + f + ": " + e);
        } finally {
            p.recycle();
        }
    }

    /** AOSP ShortcutParser: the shortcuts of the launcher activities' android.app.shortcuts XML */
    private void loadManifestShortcuts() {
        ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
        Resources res = app.default_resources;
        for (PackageParser.Activity a : app.pkg.activities) {
            if (a.metaData == null || !isLauncherActivity(a))
                continue;
            int xml = a.metaData.getInt("android.app.shortcuts", 0);
            if (xml == 0)
                continue;
            ComponentName activity = new ComponentName(app.pkg.packageName, a.className);
            int count = 0;
            try (XmlResourceParser parser = res.getXml(xml)) {
                ShortcutInfo si;
                while ((si = parseNextShortcut(parser, res, activity)) != null) {
                    if (count >= MAX_SHORTCUTS_PER_ACTIVITY) {
                        Log.w(TAG, "More than " + MAX_SHORTCUTS_PER_ACTIVITY + " shortcuts found for " + activity + ". Skipping the rest.");
                        break;
                    }
                    if (sShortcuts.containsKey(si.getId())) {
                        Log.w(TAG, "Duplicate shortcut ID detected. Skipping it.");
                        continue;
                    }
                    si.setImplicitRank(count++);
                    sShortcuts.put(si.getId(), si);
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception caught while parsing shortcut XML for package " + app.pkg.packageName, e);
            }
        }
    }

    private static boolean isLauncherActivity(PackageParser.Activity a) {
        if (a.intents == null)
            return false;
        for (PackageParser.ActivityIntentInfo ii : a.intents) {
            if (ii.hasAction(Intent.ACTION_MAIN) && ii.hasCategory(Intent.CATEGORY_LAUNCHER))
                return true;
        }
        return false;
    }

    private static final String NS = "http://schemas.android.com/apk/res/android";

    /** the next <shortcut> of the XML with its <intent>s and <categories>, or null at the end */
    private ShortcutInfo parseNextShortcut(XmlResourceParser parser, Resources res, ComponentName activity)
            throws Exception {
        int type;
        while ((type = parser.next()) != XmlResourceParser.END_DOCUMENT) {
            if (type != XmlResourceParser.START_TAG || !"shortcut".equals(parser.getName()))
                continue;
            String id = parser.getAttributeValue(NS, "shortcutId");
            boolean enabled = parser.getAttributeBooleanValue(NS, "enabled", true);
            int iconRes = parser.getAttributeResourceValue(NS, "icon", 0);
            int shortRes = parser.getAttributeResourceValue(NS, "shortcutShortLabel", 0);
            int longRes = parser.getAttributeResourceValue(NS, "shortcutLongLabel", 0);
            int disabledRes = parser.getAttributeResourceValue(NS, "shortcutDisabledMessage", 0);
            if (id == null || id.isEmpty()) {
                Log.e(TAG, "android:shortcutId must be provided. activity=" + activity);
                skipElement(parser);
                continue;
            }
            if (shortRes == 0) {
                Log.e(TAG, "android:shortcutShortLabel must be provided. activity=" + activity);
                skipElement(parser);
                continue;
            }
            ArrayList<Intent> intents = new ArrayList<>();
            android.util.ArraySet<String> categories = new android.util.ArraySet<>();
            int depth = parser.getDepth();
            while ((type = parser.next()) != XmlResourceParser.END_DOCUMENT
                   && (type != XmlResourceParser.END_TAG || parser.getDepth() > depth)) {
                if (type != XmlResourceParser.START_TAG)
                    continue;
                if ("intent".equals(parser.getName())) {
                    Intent intent = new Intent(parser.getAttributeValue(NS, "action"));
                    String pkg = parser.getAttributeValue(NS, "targetPackage");
                    String cls = parser.getAttributeValue(NS, "targetClass");
                    if (pkg != null && cls != null)
                        intent.setComponent(new ComponentName(pkg, cls));
                    String data = parser.getAttributeValue(NS, "data");
                    if (data != null)
                        intent.setData(android.net.Uri.parse(data));
                    if (intent.getAction() == null)
                        intent.setAction(Intent.ACTION_VIEW);
                    // AOSP: manifest shortcut intents are launched as a new task
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 0x00008000 /* FLAG_ACTIVITY_CLEAR_TASK */
                                    | 0x00200000 /* FLAG_ACTIVITY_TASK_ON_HOME */);
                    intents.add(intent);
                } else if ("categories".equals(parser.getName())) {
                    String name = parser.getAttributeValue(NS, "name");
                    if (name != null)
                        categories.add(name);
                }
            }
            if (enabled && intents.isEmpty()) {
                Log.e(TAG, "Shortcut " + id + " has no intent. Skipping it.");
                continue;
            }
            ShortcutInfo.Builder b = new ShortcutInfo.Builder(context(), id)
                .setActivity(activity)
                .setShortLabel(res.getText(shortRes));
            if (longRes != 0)
                b.setLongLabel(res.getText(longRes));
            if (disabledRes != 0)
                b.setDisabledMessage(res.getText(disabledRes));
            if (!categories.isEmpty())
                b.setCategories(categories);
            if (!intents.isEmpty())
                b.setIntents(intents.toArray(new Intent[0]));
            else
                b.setIntent(new Intent(Intent.ACTION_VIEW));
            ShortcutInfo si = b.build();
            si.addFlags(ShortcutInfo.FLAG_MANIFEST | ShortcutInfo.FLAG_IMMUTABLE
                        | (iconRes != 0 ? ShortcutInfo.FLAG_HAS_ICON_RES : 0));
            if (!enabled) {
                si.addFlags(ShortcutInfo.FLAG_DISABLED);
                si.setDisabledReason(ShortcutInfo.DISABLED_REASON_BY_APP);
            }
            return si;
        }
        return null;
    }

    private static void skipElement(XmlResourceParser parser) throws Exception {
        int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlResourceParser.END_DOCUMENT
               && (type != XmlResourceParser.END_TAG || parser.getDepth() > depth)) {
        }
    }

    // ------------------------------------------------------------------------------ the rules

    private static void throwIfImmutable(ShortcutInfo existing) {
        if (existing != null && existing.isImmutable()) {
            throw new IllegalArgumentException("Manifest shortcut ID=" + existing.getId()
                    + " may not be manipulated via APIs");
        }
    }

    /** AOSP fixUpIncomingShortcutInfo: the publisher's checks and default activity */
    private ShortcutInfo incoming(ShortcutInfo si, boolean forUpdate) {
        if (si == null)
            throw new NullPointerException("shortcut must not be null");
        if (!context().getPackageName().equals(si.getPackage()))
            throw new SecurityException("Shortcut package name mismatch");
        ShortcutInfo copy = si.clone(0);
        if (!forUpdate) {
            if (copy.getActivity() == null)
                copy.setActivity(defaultActivity());
            copy.enforceMandatoryFields(false);
        }
        copy.replaceFlags(copy.getFlags() & (ShortcutInfo.FLAG_LONG_LIVED | ShortcutInfo.FLAG_HAS_ICON_RES));
        return copy;
    }

    /** AOSP: the publisher's main (launcher) activity */
    private ComponentName defaultActivity() {
        ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
        for (PackageParser.Activity a : app.pkg.activities) {
            if (isLauncherActivity(a))
                return new ComponentName(app.pkg.packageName, a.className);
        }
        throw new IllegalStateException("Launcher activity not found for package " + app.pkg.packageName);
    }

    /** AOSP enforceMaxActivityShortcuts: dynamic plus manifest shortcuts per activity */
    private void checkMaxPerActivity(LinkedHashMap<String, ShortcutInfo> map) {
        java.util.HashMap<ComponentName, Integer> counts = new java.util.HashMap<>();
        for (ShortcutInfo si : map.values()) {
            if (!si.usesQuota())
                continue;
            Integer n = counts.get(si.getActivity());
            n = n == null ? 1 : n + 1;
            if (n > MAX_SHORTCUTS_PER_ACTIVITY)
                throw new IllegalArgumentException("Max number of dynamic shortcuts exceeded");
            counts.put(si.getActivity(), n);
        }
    }

    /** AOSP adjustRanks: the dynamic shortcuts of each activity ranked 0..n-1 (explicit rank, then age) */
    private void adjustRanks(LinkedHashMap<String, ShortcutInfo> map) {
        ArrayList<ShortcutInfo> dynamic = new ArrayList<>();
        for (ShortcutInfo si : map.values()) {
            if (si.isDynamic())
                dynamic.add(si);
        }
        Collections.sort(dynamic, new Comparator<ShortcutInfo>() {
            public int compare(ShortcutInfo a, ShortcutInfo b) {
                int c = String.valueOf(a.getActivity()).compareTo(String.valueOf(b.getActivity()));
                if (c != 0)
                    return c;
                c = Integer.compare(a.getRank(), b.getRank());
                if (c != 0)
                    return c;
                return Integer.compare(a.getImplicitRank(), b.getImplicitRank());
            }
        });
        ComponentName current = null;
        int rank = 0;
        for (ShortcutInfo si : dynamic) {
            if (!java.util.Objects.equals(current, si.getActivity())) {
                current = si.getActivity();
                rank = 0;
            }
            si.setImplicitRank(rank);
            si.setRank(rank++);
        }
    }

    private List<ShortcutInfo> collect(int matchFlags) {
        ArrayList<ShortcutInfo> out = new ArrayList<>();
        synchronized (ShortcutManager.class) {
            for (ShortcutInfo si : shortcuts().values()) {
                if (((matchFlags & FLAG_MATCH_DYNAMIC) != 0 && si.isDynamic())
                    || ((matchFlags & FLAG_MATCH_MANIFEST) != 0 && si.isDeclaredInManifest())
                    || ((matchFlags & FLAG_MATCH_PINNED) != 0 && si.isPinned())
                    || ((matchFlags & FLAG_MATCH_CACHED) != 0 && si.isCached())) {
                    out.add(si.clone(ShortcutInfo.CLONE_REMOVE_FOR_CREATOR));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------ the API

    /**
     * Publish the list of dynamic shortcuts. If there are already dynamic or pinned shortcuts with
     * the same IDs, each mutable shortcut is updated.
     */
    public boolean setDynamicShortcuts(List<ShortcutInfo> shortcutInfoList) {
        synchronized (ShortcutManager.class) {
            LinkedHashMap<String, ShortcutInfo> map = shortcuts();
            java.util.HashSet<String> ids = new java.util.HashSet<>();
            for (ShortcutInfo si : shortcutInfoList) {
                if (!ids.add(si.getId()))
                    throw new IllegalArgumentException("Shortcut ID " + si.getId() + " is used more than once");
                throwIfImmutable(map.get(si.getId()));
            }
            LinkedHashMap<String, ShortcutInfo> next = new LinkedHashMap<>();
            for (ShortcutInfo si : map.values()) {
                if (!si.isDynamic())
                    next.put(si.getId(), si);
            }
            int order = 0;
            for (ShortcutInfo si : shortcutInfoList) {
                ShortcutInfo n = incoming(si, false);
                n.addFlags(ShortcutInfo.FLAG_DYNAMIC);
                n.setImplicitRank(order++);
                next.put(n.getId(), n);
            }
            checkMaxPerActivity(next);
            adjustRanks(next);
            map.clear();
            map.putAll(next);
            save();
        }
        return true;
    }

    /** Return all dynamic shortcuts from the caller app. */
    public List<ShortcutInfo> getDynamicShortcuts() {
        return collect(FLAG_MATCH_DYNAMIC);
    }

    /** Return all static (manifest) shortcuts from the caller app. */
    public List<ShortcutInfo> getManifestShortcuts() {
        return collect(FLAG_MATCH_MANIFEST);
    }

    /** Returns the shortcuts that match the given flags. */
    public List<ShortcutInfo> getShortcuts(int matchFlags) {
        return collect(matchFlags);
    }

    /** Publish the list of dynamic shortcuts (added to, or updating, the existing ones). */
    public boolean addDynamicShortcuts(List<ShortcutInfo> shortcutInfoList) {
        synchronized (ShortcutManager.class) {
            LinkedHashMap<String, ShortcutInfo> map = shortcuts();
            LinkedHashMap<String, ShortcutInfo> next = new LinkedHashMap<>(map);
            int order = next.size();
            for (ShortcutInfo si : shortcutInfoList) {
                throwIfImmutable(map.get(si.getId()));
                ShortcutInfo n = incoming(si, false);
                n.addFlags(ShortcutInfo.FLAG_DYNAMIC);
                if (!n.hasRank())
                    n.setRank(ShortcutInfo.RANK_NOT_SET);
                n.setImplicitRank(order++);
                next.put(n.getId(), n);
            }
            checkMaxPerActivity(next);
            adjustRanks(next);
            map.clear();
            map.putAll(next);
            save();
        }
        return true;
    }

    /** Delete dynamic shortcuts by ID. */
    public void removeDynamicShortcuts(List<String> shortcutIds) {
        synchronized (ShortcutManager.class) {
            LinkedHashMap<String, ShortcutInfo> map = shortcuts();
            for (String id : shortcutIds) {
                ShortcutInfo si = map.get(id);
                throwIfImmutable(si);
                if (si != null && si.isDynamic())
                    map.remove(id);
            }
            adjustRanks(map);
            save();
        }
    }

    /** Delete all dynamic shortcuts from the caller app. */
    public void removeAllDynamicShortcuts() {
        synchronized (ShortcutManager.class) {
            LinkedHashMap<String, ShortcutInfo> map = shortcuts();
            java.util.Iterator<ShortcutInfo> it = map.values().iterator();
            while (it.hasNext()) {
                if (it.next().isDynamic())
                    it.remove();
            }
            save();
        }
    }

    /** Delete long lived shortcuts by ID (they are only dynamic here: nothing is cached). */
    public void removeLongLivedShortcuts(List<String> shortcutIds) {
        synchronized (ShortcutManager.class) {
            LinkedHashMap<String, ShortcutInfo> map = shortcuts();
            for (String id : shortcutIds) {
                ShortcutInfo si = map.get(id);
                throwIfImmutable(si);
                if (si != null && (si.isDynamic() || si.isCached()))
                    map.remove(id);
            }
            adjustRanks(map);
            save();
        }
    }

    /** Return all pinned shortcuts (none: pinning needs a launcher). */
    public List<ShortcutInfo> getPinnedShortcuts() {
        return collect(FLAG_MATCH_PINNED);
    }

    /** Update all existing shortcuts with the same IDs; target shortcuts may be pinned and/or dynamic. */
    public boolean updateShortcuts(List<ShortcutInfo> shortcutInfoList) {
        synchronized (ShortcutManager.class) {
            LinkedHashMap<String, ShortcutInfo> map = shortcuts();
            for (ShortcutInfo si : shortcutInfoList) {
                ShortcutInfo target = map.get(si.getId());
                if (target == null || !target.isAlive())
                    continue; // AOSP: unknown IDs are ignored
                throwIfImmutable(target);
                ShortcutInfo n = incoming(si, true);
                target.copyNonNullFieldsFrom(n);
                target.updateTimestamp();
            }
            adjustRanks(map);
            save();
        }
        return true;
    }

    /** Disable pinned shortcuts (and remove the dynamic ones with these IDs). */
    public void disableShortcuts(List<String> shortcutIds) {
        disableShortcuts(shortcutIds, (CharSequence) null);
    }

    /** Disable shortcuts, with the message shown when the user selects one. */
    public void disableShortcuts(List<String> shortcutIds, CharSequence disabledMessage) {
        synchronized (ShortcutManager.class) {
            LinkedHashMap<String, ShortcutInfo> map = shortcuts();
            for (String id : shortcutIds) {
                ShortcutInfo si = map.get(id);
                if (si == null)
                    continue;
                throwIfImmutable(si);
                if (si.isPinned()) {
                    si.clearFlags(ShortcutInfo.FLAG_DYNAMIC);
                    si.addFlags(ShortcutInfo.FLAG_DISABLED);
                    si.setDisabledReason(ShortcutInfo.DISABLED_REASON_BY_APP);
                } else {
                    // not pinned: disabling removes it (AOSP deleteOrDisableWithId)
                    map.remove(id);
                }
            }
            adjustRanks(map);
            save();
        }
    }

    /** Re-enable pinned shortcuts that were previously disabled. */
    public void enableShortcuts(List<String> shortcutIds) {
        synchronized (ShortcutManager.class) {
            LinkedHashMap<String, ShortcutInfo> map = shortcuts();
            for (String id : shortcutIds) {
                ShortcutInfo si = map.get(id);
                if (si == null)
                    continue;
                throwIfImmutable(si);
                si.clearFlags(ShortcutInfo.FLAG_DISABLED);
                si.setDisabledReason(ShortcutInfo.DISABLED_REASON_NOT_DISABLED);
            }
            save();
        }
    }

    /** Return the maximum number of static and dynamic shortcuts that each launcher icon can have at a time. */
    public int getMaxShortcutCountPerActivity() {
        return MAX_SHORTCUTS_PER_ACTIVITY;
    }

    /** @hide */
    public int getMaxShortcutCountForActivity() {
        return getMaxShortcutCountPerActivity();
    }

    /** Return the number of times the caller app can call the rate-limited APIs (no limit for a foreground app). */
    public int getRemainingCallCount() {
        return Integer.MAX_VALUE;
    }

    /** Return true when rate-limiting is active for the caller app (never: the app is always in the foreground). */
    public boolean isRateLimitingActive() {
        return false;
    }

    /** Return the max width for icons, in pixels. */
    public int getIconMaxWidth() {
        return iconMaxDimension();
    }

    /** Return the max height for icons, in pixels. */
    public int getIconMaxHeight() {
        return iconMaxDimension();
    }

    private int iconMaxDimension() {
        float density = context().getResources().getDisplayMetrics().density;
        return (int) (MAX_ICON_DIMENSION_DP * density);
    }

    /**
     * Apps that publish shortcuts should call this method whenever the user selects the shortcut
     * containing the given ID. On Android the report goes to the usage-stats service for launcher
     * predictions; this platform has neither, so the report has no receiver. An ID the app does
     * not have is ignored, as in AOSP.
     */
    public void reportShortcutUsed(String shortcutId) {
        if (shortcutId == null)
            throw new NullPointerException("shortcutId");
    }

    /** Return TRUE if the app is running on a device whose default launcher supports pinning (none here). */
    public boolean isRequestPinShortcutSupported() {
        return false;
    }

    /**
     * Request to create a pinned shortcut. The default launcher does not support it (there is no
     * launcher), so, as AOSP does then, nothing is pinned and false is returned.
     */
    public boolean requestPinShortcut(ShortcutInfo shortcut, IntentSender resultIntent) {
        if (shortcut == null)
            throw new NullPointerException("shortcut must not be null");
        return false;
    }

    /**
     * Returns an Intent which can be used by the default launcher to pin a shortcut, or null when
     * the default launcher does not support pinning (as here, with no launcher).
     */
    public Intent createShortcutResultIntent(ShortcutInfo shortcut) {
        if (shortcut == null)
            throw new NullPointerException("shortcut must not be null");
        return null;
    }

    /**
     * Publish a single dynamic shortcut. If the shortcut count would exceed the maximum, the
     * lowest-ranked dynamic shortcut of the activity is removed first.
     */
    public void pushDynamicShortcut(ShortcutInfo shortcut) {
        synchronized (ShortcutManager.class) {
            LinkedHashMap<String, ShortcutInfo> map = shortcuts();
            throwIfImmutable(map.get(shortcut.getId()));
            ShortcutInfo n = incoming(shortcut, false);
            n.addFlags(ShortcutInfo.FLAG_DYNAMIC);
            if (!n.hasRank())
                n.setRank(0);
            n.setImplicitRank(0);
            map.remove(n.getId());
            int count = 0;
            ShortcutInfo lowest = null;
            for (ShortcutInfo si : map.values()) {
                if (si.usesQuota() && java.util.Objects.equals(si.getActivity(), n.getActivity())) {
                    count++;
                    if (si.isDynamic() && (lowest == null || si.getRank() >= lowest.getRank()))
                        lowest = si;
                }
            }
            if (count >= MAX_SHORTCUTS_PER_ACTIVITY && lowest != null)
                map.remove(lowest.getId());
            // pushed shortcuts go before the others of the same rank
            for (ShortcutInfo si : map.values()) {
                if (si.isDynamic() && si.getRank() >= n.getRank())
                    si.setRank(si.getRank() + 1);
            }
            map.put(n.getId(), n);
            adjustRanks(map);
            save();
        }
    }
}
