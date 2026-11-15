package com.nemesis.manager;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Launchable apps as list rows, key = package name. */
final class AppList {
    private AppList() {
    }

    /** userOnly drops apps on the system image (home grid); Auto-launch lists them all. */
    static ArrayList<TwoLineAdapter.Row> load(Context c, boolean withManager, boolean userOnly) {
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
        ArrayList<TwoLineAdapter.Row> rows = new ArrayList<TwoLineAdapter.Row>();
        ArrayList<String> seen = new ArrayList<String>();
        int sys = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        for (int i = 0; i < found.size(); i++) {
            ResolveInfo ri = found.get(i);
            String pkg = ri.activityInfo.packageName;
            if (seen.contains(pkg) || c.getPackageName().equals(pkg)) continue;
            if (userOnly && (ri.activityInfo.applicationInfo.flags & sys) != 0) continue;
            seen.add(pkg);
            rows.add(new TwoLineAdapter.Row(String.valueOf(ri.loadLabel(pm)), pkg, pkg, false));
        }
        Collections.sort(rows, new Comparator<TwoLineAdapter.Row>() {
            public int compare(TwoLineAdapter.Row a, TwoLineAdapter.Row b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        if (withManager) {
            rows.add(0, new TwoLineAdapter.Row(c.getString(R.string.manager_name),
                    c.getPackageName(), c.getPackageName(), false));
        }
        return rows;
    }
}
