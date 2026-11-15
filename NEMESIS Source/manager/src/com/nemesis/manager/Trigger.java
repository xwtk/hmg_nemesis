package com.nemesis.manager;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings offers Setup and Navigation. A new install uses Navigation. */
final class Trigger {
    static final String PREFIX = "mobis.intent.action.";
    private static final String PREFS = "nemesis_options";
    private static final String KEY = "trigger";
    private static final String DEFAULT = "SHORTCUT_NAVI";

    static final class Item {
        final String suffix;
        final String label;
        final String[] actions;

        Item(String suffix, String label, String[] actions) {
            this.suffix = suffix;
            this.label = label;
            this.actions = actions;
        }
    }

    private static String[] also(String suffix, String extra) {
        return new String[] { PREFIX + suffix, extra };
    }

    /** Order is the Settings picker list. */
    static final Item[] ALL = new Item[] {
            new Item("SHORTCUT_SETUP", "Setup",
                    also("SHORTCUT_SETUP", "com.hkmc.intent.action.SETUP_SHORTCUT")),
            new Item("SHORTCUT_NAVI", "Navigation",
                    also("SHORTCUT_NAVI", "com.hkmc.intent.action.NAVI_SHORTCUT")),
    };

    static String suffix(Context c) {
        String saved = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, DEFAULT);
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].suffix.equals(saved)) return saved;
        }
        return DEFAULT;
    }

    static Item selected(Context c) {
        String s = suffix(c);
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].suffix.equals(s)) return ALL[i];
        }
        return ALL[0];
    }

    static boolean matches(Context c, String action) {
        if (action == null) return false;
        Item item = selected(c);
        for (int i = 0; i < item.actions.length; i++) {
            if (action.equals(item.actions[i])) return true;
        }
        return false;
    }

    static String label(Context c) {
        return selected(c).label;
    }

    static void save(Context c, String suffix) {
        SharedPreferences.Editor ed = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        ed.putString(KEY, suffix);
        ed.commit();
        c.getSharedPreferences("setup_press", Context.MODE_PRIVATE)
                .edit().putInt("count", 0).putLong("last", 0).putBoolean("mobis", false).commit();
    }
}
