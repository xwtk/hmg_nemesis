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
        dateSortsToTheSecond();
        reasonsAndInstalled();
        updatesPicksNewest();
        depPlanOrder();
        if (failed != 0) {
            System.err.println("FAIL: " + failed + " store logic checks");
            System.exit(1);
        }
        System.out.println("ALL STORE LOGIC TESTS PASSED");
    }

    private static void versions() {
        check(StoreList.isLegacy("1.0") && !StoreList.isLegacy("1.1") && !StoreList.isLegacy(""), "legacy descriptor");
        StoreList.Entry old = new StoreList.Entry();
        old.compat = StoreList.FULL;
        old.legacy = true;
        check(StoreList.shade(old) == StoreList.PARTIAL, "legacy is never green");
        check(StoreList.hiddenCount(java.util.Collections.singletonList(old)) == 0, "legacy stays listed");
        old.compat = StoreList.NONE;
        check(StoreList.shade(old) == StoreList.NONE, "legacy keeps red");
        check(UpdateRule.networkAllowed(false, 1, null), "Wi-Fi by default");
        check(UpdateRule.networkAllowed(false, 7, "bnep0"), "Bluetooth hotspot by default");
        check(UpdateRule.networkAllowed(false, 9, "usb0"), "USB hotspot by default");
        check(UpdateRule.networkAllowed(false, -1, "rndis0"), "USB tether with no active type");
        check(UpdateRule.networkAllowed(false, 0, "usb0"), "USB hotspot reported as mobile");
        check(UpdateRule.networkAllowed(false, 0, "eth0"), "USB Ethernet hotspot reported as mobile");
        check(UpdateRule.networkAllowed(false, 5, "wlan0"), "the route decides, not hipri");
        check(!UpdateRule.networkAllowed(false, 1, "rmnet0"), "SIM route is off even with Wi-Fi active");
        check(!UpdateRule.networkAllowed(false, -1, "rmnet0"), "SIM interface is off");
        check(!UpdateRule.networkAllowed(false, 0, "odd0"), "unknown name on mobile is off");
        check(UpdateRule.networkAllowed(false, 1, "odd0"), "unknown name on Wi-Fi is on");
        check(!UpdateRule.networkAllowed(false, -1, null), "no route is off");
        check(UpdateRule.networkAllowed(true, 0, "rmnet0"), "Use Telematics Internet allows the SIM");
        String routes = "Iface\tDestination\tGateway\nwlan0\t0001A8C0\t00000000\nrmnet0\t00000000\t0101A8C0\n";
        check("rmnet0".equals(UpdateRule.defaultRouteIface(routes)), "default route interface");
        check(UpdateRule.defaultRouteIface("Iface\tDestination\nwlan0\t0001A8C0\n") == null, "no default route");
        String metrics = "rmnet0\t00000000\t01\t0003\t0\t0\t100\t00000000\nusb0\t00000000\t01\t0003\t0\t0\t5\t00000000\n";
        check("usb0".equals(UpdateRule.defaultRouteIface(metrics)), "lowest metric default route");
        check(UpdateRule.specMatch("any", "") && UpdateRule.specMatch("", null), "any spec");
        check(UpdateRule.specMatch(">=1.2", "1.10") && !UpdateRule.specMatch(">=1.2", "1.1"), "at least");
        check(UpdateRule.specMatch("<=2.0", "2") && !UpdateRule.specMatch("<=2.0", "2.1"), "at most");
        check(UpdateRule.specMatch("1.0-2.0", "1.5") && !UpdateRule.specMatch("1.0-2.0", "2.1"), "range");
        check(UpdateRule.specMatch("1.0", "1.0.0") && !UpdateRule.specMatch("1.0", ""), "exact");
        check(UpdateRule.compare("1.16.1", "1.16") > 0, "1.16.1 is newer than 1.16");
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

    private static void dateSortsToTheSecond() {
        java.util.ArrayList<StoreList.Entry> all = new java.util.ArrayList<StoreList.Entry>();
        all.add(row("A", "2026-11-01 10:00:05", StoreList.FULL));
        all.add(row("B", "2026-11-01 10:00:09", StoreList.FULL));
        all.add(row("C", "2026-11-01", StoreList.FULL));
        java.util.List<StoreList.Entry> l = StoreList.view(all, "", true, true, false);
        check("B".equals(l.get(0).name) && "A".equals(l.get(1).name) && "C".equals(l.get(2).name), "date sorts to the second, newest first");
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

    private static void reasonsAndInstalled() {
        String out = "compat=partial\ndep-missing a\nos=any\nos X not in Y\nmodel m not in n\n";
        check("Software X not in Y\nCar model m not in n".equals(StoreList.reasons(out)), "readable reasons only");
        check("".equals(StoreList.reasons(null)), "no reasons");
        java.util.Map<String, String> m = StoreList.parseInstalled("a|A|0|tool|1.2|desc\nb|B|1|tool||\nbad\n");
        check("1.2".equals(m.get("a")) && "".equals(m.get("b")) && m.size() == 2, "pkg-list versions");
    }

    private static StoreList.Entry update(String id, String have, String ver, String zip) {
        StoreList.Entry e = row(id, "", StoreList.FULL);
        e.id = id;
        e.isInstalled = true;
        e.installed = have;
        e.version = ver;
        e.localZip = zip;
        return e;
    }

    private static void updatesPicksNewest() {
        List<StoreList.Entry> all = new ArrayList<StoreList.Entry>();
        all.add(update("a", "1.0", "1.1", ""));
        all.add(update("a", "1.0", "1.1", "/usb/a.zip"));
        all.add(update("b", "1.0", "2.0", "/usb/b.zip"));
        all.add(update("b", "1.0", "3.0", ""));
        all.add(update("same", "1.0", "1.0", ""));
        all.add(update("older", "2.0", "1.0", ""));
        StoreList.Entry locked = update("locked", "1.0", "2.0", "");
        locked.locked = true;
        all.add(locked);
        StoreList.Entry red = update("red", "1.0", "2.0", "");
        red.compat = StoreList.NONE;
        all.add(red);
        StoreList.Entry fresh = update("fresh", "", "2.0", "");
        fresh.isInstalled = false;
        all.add(fresh);
        List<StoreList.Entry> u = StoreList.updates(all);
        check(u.size() == 2, "only real updates");
        check("/usb/a.zip".equals(u.get(0).localZip), "the stick wins a tie");
        check("3.0".equals(u.get(1).version), "newest wins");
    }

    private static StoreList.Entry dep(String id, String ver, String depends) {
        StoreList.Entry e = new StoreList.Entry();
        e.id = id;
        e.version = ver;
        e.depends = depends;
        return e;
    }

    private static void depPlanOrder() {
        final java.util.Map<String, String> have = new java.util.HashMap<String, String>();
        have.put("old", "1.0");
        have.put("ok", "2.0");
        have.put("bad", "1.5");
        final java.util.Map<String, StoreList.Entry> store = new java.util.HashMap<String, StoreList.Entry>();
        store.put("a", dep("a", "1.0", "b, self"));
        store.put("b", dep("b", "1.0", "a"));
        store.put("old", dep("old", "2.0", ""));
        DepPlan.Source s = new DepPlan.Source() {
            public String installed(String id) {
                return have.get(id);
            }

            public StoreList.Entry find(String id, String spec) {
                StoreList.Entry e = store.get(id);
                return e != null && UpdateRule.specMatch(spec, e.version) ? e : null;
            }
        };
        DepPlan p = DepPlan.of("self", "a, ok@>=1, old@>=2", s);
        check(p.steps.size() == 3 && p.missing.isEmpty(), "a, b and old are steps; no loop");
        check("b".equals(p.steps.get(0).id) && "a".equals(p.steps.get(1).id), "dependencies first");
        p = DepPlan.of("self", "none, nine@>=9, ok@>=9, old@>=9", s);
        check(p.steps.isEmpty() && p.missing.size() == 4, "nothing in any Store");
        check("none".equals(p.missing.get(0)), "missing, any version");
        check("nine (need >=9)".equals(p.missing.get(1)), "missing, needs a version");
        check("ok (have 2.0, need >=9)".equals(p.missing.get(2)), "installed too old");
        p = DepPlan.of("self", "!ok, !bad@>=2, !absent, !old@<=1.0", s);
        check(p.steps.isEmpty() && p.missing.isEmpty(), "! items are not dependencies");
        check(p.conflicts.size() == 2 && p.conflicts.contains("ok") && p.conflicts.contains("old"), "installed conflicts only");
        List<String> u = DepPlan.unmet("noise\ndep-missing a\ndep-version b have 1.0 need >=2\ndep-version c have unknown need 1\n");
        check(u.size() == 3 && "a".equals(u.get(0)) && "b (have 1.0, need >=2)".equals(u.get(1))
                && "c (have no version, need 1)".equals(u.get(2)), "native dep lines");
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
