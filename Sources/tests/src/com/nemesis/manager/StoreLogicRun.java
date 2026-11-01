package com.nemesis.manager;

import java.util.ArrayList;
import java.util.List;

/** Host driver for UpdateRule and StoreList. Not packaged into the Manager APK. */
public class StoreLogicRun {
    private static int failed;
    private static final long DAY = UpdateRule.PERIOD_MS;
    private static final long NOW = 1790000000000L;

    public static void main(String[] args) {
        versions();
        dueWithGoodClock();
        dueWithBadClock();
        listParsing();
        unsafePathsDropped();
        wrongTypeGivesNothing();
        compatNames();
        searchMatchesNameAndDescription();
        defaultFilterHidesPartialAndNone();
        sorting();
        if (failed != 0) {
            System.err.println("FAIL: " + failed + " store logic checks");
            System.exit(1);
        }
        System.out.println("ALL STORE LOGIC TESTS PASSED");
    }

    private static void versions() {
        check(UpdateRule.compare("1.16", "1.15") > 0, "1.16 is newer than 1.15");
        check(UpdateRule.compare("1.9", "1.10") < 0, "numeric, not text");
        check(UpdateRule.compare("1.16", "1.16.0") == 0, "missing part is zero");
        check(UpdateRule.compare(null, "") == 0, "null and empty are equal");
    }

    private static void dueWithGoodClock() {
        check(UpdateRule.due(NOW, 0, 5, 0), "never checked is due");
        check(!UpdateRule.due(NOW, NOW - DAY + 1, 5, 0), "under a day is not due");
        check(UpdateRule.due(NOW, NOW - DAY, 5, 0), "exactly a day is due");
        check(UpdateRule.due(NOW, NOW + 1000, 5, 0), "last check in the future is due");
    }

    private static void dueWithBadClock() {
        long bad = UpdateRule.CLOCK_SANE_MS - 1;
        check(!UpdateRule.clockSane(bad), "2015 clock is not sane");
        check(UpdateRule.due(bad, NOW, 100, 0), "no elapsed mark is due");
        check(!UpdateRule.due(bad, NOW, 100 + DAY - 1, 100), "elapsed under a day is not due");
        check(UpdateRule.due(bad, NOW, 100 + DAY, 100), "elapsed of a day is due");
        check(UpdateRule.due(bad, NOW, 50, 100), "elapsed went back: new boot, due");
    }

    private static final String LIST = "[nemesis]\ntype=list\n"
            + "[package.0]\npath=a/one.zip\nname=\"One\"\nshort-desc=first thing\npublished=2026-01-02\n"
            + "[package.1]\npath=two.zip\nname=Two\npublished=2026-03-01 # note\n"
            + "[package.2]\npath=three.zip\n";

    private static void listParsing() {
        List<StoreList.Entry> l = StoreList.parseList(LIST);
        check(l.size() == 3, "three rows");
        check("a/one.zip".equals(l.get(0).path), "path kept");
        check("One".equals(l.get(0).name), "quotes removed");
        check("2026-03-01".equals(l.get(1).published), "comment removed");
        check("three.zip".equals(l.get(2).name), "name falls back to the path");
    }

    private static void unsafePathsDropped() {
        check(!StoreList.relOk("/etc/passwd"), "absolute path refused");
        check(!StoreList.relOk("a/../../b.zip"), "dot-dot refused");
        check(!StoreList.relOk(""), "empty refused");
        check(StoreList.relOk("a/b.zip"), "relative accepted");
        List<StoreList.Entry> l = StoreList.parseList("[nemesis]\ntype=list\n"
                + "[package.0]\npath=../x.zip\n[package.1]\npath=ok.zip\n");
        check(l.size() == 1 && "ok.zip".equals(l.get(0).path), "bad path dropped, next row kept");
    }

    private static void wrongTypeGivesNothing() {
        check(StoreList.parseList("[nemesis]\ntype=tool\n[package.0]\npath=x.zip\n").isEmpty(),
                "not a list");
        check(StoreList.parseList(null).isEmpty(), "null text");
    }

    private static void compatNames() {
        check(StoreList.compatOf("full") == StoreList.FULL, "full");
        check(StoreList.compatOf("partial") == StoreList.PARTIAL, "partial");
        check(StoreList.compatOf("incompatible") == StoreList.NONE, "incompatible");
        check(StoreList.compatOf(null) == StoreList.UNKNOWN, "missing is unknown");
    }

    private static StoreList.Entry row(String name, String date, int compat) {
        StoreList.Entry e = new StoreList.Entry();
        e.name = name;
        e.published = date;
        e.compat = compat;
        return e;
    }

    private static void searchMatchesNameAndDescription() {
        StoreList.Entry e = row("Maps", "", StoreList.FULL);
        e.shortDesc = "Navigation helper";
        check(StoreList.matches(e, "MAP"), "name, any case");
        check(StoreList.matches(e, "navig"), "description");
        check(!StoreList.matches(e, "radio"), "no match");
        check(StoreList.matches(e, "  "), "blank matches all");
    }

    private static List<StoreList.Entry> sample() {
        List<StoreList.Entry> all = new ArrayList<StoreList.Entry>();
        all.add(row("Beta", "2026-02-01", StoreList.FULL));
        all.add(row("alpha", "2026-03-01", StoreList.UNKNOWN));
        all.add(row("Gamma", "2026-01-01", StoreList.PARTIAL));
        all.add(row("Delta", "2026-04-01", StoreList.NONE));
        return all;
    }

    private static void defaultFilterHidesPartialAndNone() {
        List<StoreList.Entry> all = sample();
        check(StoreList.hiddenCount(all) == 2, "two hidden");
        check(StoreList.view(all, "", false, true, false).size() == 2, "default shows two");
        check(StoreList.view(all, "", true, true, false).size() == 4, "show all shows four");
    }

    private static void sorting() {
        List<StoreList.Entry> all = sample();
        List<StoreList.Entry> v = StoreList.view(all, "", true, true, false);
        check("Delta".equals(v.get(0).name), "date descending: newest first");
        v = StoreList.view(all, "", true, true, true);
        check("Gamma".equals(v.get(0).name), "date ascending: oldest first");
        v = StoreList.view(all, "", true, false, true);
        check("alpha".equals(v.get(0).name), "name ascending ignores case");
        v = StoreList.view(all, "", true, false, false);
        check("Gamma".equals(v.get(0).name), "name descending");
    }

    private static void check(boolean cond, String msg) {
        if (cond) {
            System.out.println("OK: " + msg);
            return;
        }
        failed++;
        System.err.println("FAIL: " + msg);
    }
}
