package com.nemesis.manager;

/** Host driver for SetupPress. Not packaged into the Manager APK. */
public class SetupPressRun {
    private static int failed;

    public static void main(String[] args) {
        threeQuickPressesOpen();
        fourthPressStartsOver();
        gapResets();
        boundaryStaysInTheSameRun();
        secondTripleOpensAgain();
        countSurvivesANewProcess();
        samePressDoesNotCountTwice();
        lateEchoOfOtherFamilyIgnored();
        hkmcOnlyUnitStillCounts();
        echoInsideDebounceIgnored();
        if (failed != 0) {
            System.err.println("FAIL: " + failed + " setup-press checks");
            System.exit(1);
        }
        System.out.println("ALL SETUP PRESS TESTS PASSED");
    }

    private static void threeQuickPressesOpen() {
        SetupPress p = new SetupPress();
        check(!p.note(10000), "first press does not open");
        check(!p.note(10400), "second press does not open");
        check(p.note(10700), "third press inside the gap opens");
    }

    private static void fourthPressStartsOver() {
        SetupPress p = new SetupPress();
        p.note(0);
        p.note(400);
        check(p.note(800), "third opens");
        check(!p.note(1200), "press after an open starts a new count");
    }

    private static void gapResets() {
        SetupPress p = new SetupPress();
        check(!p.note(5000), "first");
        check(!p.note(5000 + SetupPress.GAP_MS + 1), "gap longer than the limit resets");
        check(!p.note(5000 + SetupPress.GAP_MS + 2), "second of the new run does not open");
    }

    private static void boundaryStaysInTheSameRun() {
        SetupPress p = new SetupPress();
        p.note(1000);
        check(!p.note(1000 + SetupPress.GAP_MS), "exactly one gap stays in the same run");
        check(p.note(1000 + SetupPress.GAP_MS + 400), "third of that run opens");
    }

    private static void secondTripleOpensAgain() {
        SetupPress p = new SetupPress();
        p.note(0);
        p.note(400);
        check(p.note(800), "first triple opens");
        check(!p.note(1200), "one");
        check(!p.note(1600), "two");
        check(p.note(2000), "second triple opens");
    }

    private static void countSurvivesANewProcess() {
        SetupPress first = new SetupPress();
        check(!first.note(10000), "first process, first press");
        check(!first.note(10400), "first process, second press");
        SetupPress restarted = new SetupPress();
        restarted.restore(first.count(), first.lastMs());
        check(restarted.note(10700), "third press after the process died opens");
    }

    private static void samePressDoesNotCountTwice() {
        SetupPress p = new SetupPress();
        check(!p.note(10000), "press");
        check(!p.note(10050), "echo of the same press does not count");
        check(p.count() == 1, "still one after the echo");
        check(!p.note(10400), "second press");
        check(p.note(10800), "third press opens");
    }

    private static final String M = "mobis.intent.action.SHORTCUT_SETUP";
    private static final String H = "com.hkmc.intent.action.SETUP_SHORTCUT";

    private static void lateEchoOfOtherFamilyIgnored() {
        SetupPress p = new SetupPress();
        check(!p.note(10000, M), "mobis press");
        check(!p.note(10600, H), "hkmc echo 600 ms later is ignored");
        check(p.count() == 1, "one press after a late echo");
        check(!p.note(10700, M), "second mobis press");
        check(!p.note(11300, H), "echo again");
        check(p.note(11400, M), "third mobis press opens");
    }

    private static void hkmcOnlyUnitStillCounts() {
        SetupPress p = new SetupPress();
        check(!p.note(0, H), "hkmc one");
        check(!p.note(500, H), "hkmc two");
        check(p.note(1000, H), "hkmc three opens when no mobis was seen");
    }

    private static void echoInsideDebounceIgnored() {
        SetupPress p = new SetupPress();
        check(!p.note(10000), "press");
        check(!p.note(10000 + SetupPress.DEBOUNCE_MS - 1), "echo inside the debounce");
        check(p.count() == 1, "debounce keeps one");
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
