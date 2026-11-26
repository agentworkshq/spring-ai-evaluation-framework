package dev.agentworkshq.eval.core.report;

import dev.agentworkshq.eval.core.compare.CaseChange;
import dev.agentworkshq.eval.core.compare.ChangeKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class Format {

    // room for the longest case listing of a violation: ten case ids with their metric names
    static final int VIOLATION_CHARS = 1900;
    static final String NA = "n/a";

    private static final List<ChangeKind> KIND_ORDER = List.of(ChangeKind.REGRESSION, ChangeKind.SCORE_DROP,
            ChangeKind.FIX, ChangeKind.SCORE_GAIN, ChangeKind.COVERAGE_LOST, ChangeKind.COVERAGE_GAINED);

    private Format() {
    }

    static String score(Double value) {
        return value == null ? NA : String.format(Locale.ROOT, "%.3f", value);
    }

    static String rate(Double value) {
        return value == null ? NA : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    static String delta(Double from, Double to) {
        if (from == null || to == null) {
            return NA;
        }
        String plain = String.format(Locale.ROOT, "%.3f", to - from);
        if (Double.parseDouble(plain) == 0.0) {
            return "0.000";
        }
        return to - from > 0 ? "+" + plain : plain;
    }

    static String statusAndScore(String status, Double score) {
        return score == null ? status : status + " " + score(score);
    }

    static List<CaseChange> orderedChanges(List<CaseChange> changes) {
        List<CaseChange> sorted = new ArrayList<>(changes);
        sorted.sort(Comparator.comparingInt(c -> KIND_ORDER.indexOf(c.kind())));
        return sorted;
    }
}
