package dev.agentworkshq.eval.core.report;

import dev.agentworkshq.eval.core.compare.Comparison;
import dev.agentworkshq.eval.core.compare.RunSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MarkdownReport {

    public static final String MARKER = "<!-- eval-framework-report -->";

    private MarkdownReport() {
    }

    public static String render(RunSummary summary, Comparison comparison, ReportOptions options) {
        return render(summary, comparison, null, options);
    }

    public static String render(
            RunSummary summary, Comparison comparison, List<String> gateViolations, ReportOptions options) {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(options, "options");
        ReportModel model = ReportModel.build(summary, comparison, gateViolations, options, MarkdownReport::dynamic);
        List<String> lines = new ArrayList<>();
        lines.add(MARKER);
        if (model.gatePassed() == null) {
            lines.add("### Evaluation results");
        } else {
            lines.add("### Evaluation gate: " + (model.gatePassed() ? "PASSED" : "FAILED"));
        }
        lines.add("");
        lines.add("| Field | Value |");
        lines.add("| --- | --- |");
        model.runRows().forEach(r -> lines.add(row(r)));
        heading(lines, "Metrics");
        table(lines, model.metrics());
        if (model.gatePassed() != null) {
            heading(lines, "Violations");
            if (model.violations().isEmpty()) {
                lines.add("No violations.");
            }
            model.violations().forEach(v -> lines.add("- " + v));
            if (!model.notes().isEmpty()) {
                heading(lines, "Notes");
                model.notes().forEach(n -> lines.add("- " + n));
            }
            if (model.configChanges() != null) {
                heading(lines, "Config changes");
                table(lines, model.configChanges());
            }
            if (model.caseChanges() != null) {
                heading(lines, "Case changes");
                if (model.caseChanges().rows().isEmpty()) {
                    lines.add("No case changes.");
                } else {
                    table(lines, model.caseChanges());
                }
                if (model.moreCaseChanges() > 0) {
                    lines.add("");
                    lines.add("... and " + model.moreCaseChanges() + " more");
                }
            }
        }
        if (model.runConfig() != null) {
            lines.add("");
            lines.add("<details>");
            lines.add("<summary>Run config</summary>");
            lines.add("");
            table(lines, model.runConfig());
            lines.add("");
            lines.add("</details>");
        }
        return cap(lines, options.maxChars());
    }

    private static String dynamic(String text, int maxChars, boolean inTable) {
        return MarkdownText.code(text, maxChars, inTable);
    }

    private static void heading(List<String> lines, String title) {
        lines.add("");
        lines.add("#### " + title);
        lines.add("");
    }

    private static void table(List<String> lines, ReportModel.Table table) {
        lines.add(row(table.headers()));
        lines.add("|" + " --- |".repeat(table.headers().size()));
        table.rows().forEach(r -> lines.add(row(r)));
    }

    private static String row(List<String> cells) {
        return "| " + String.join(" | ", cells) + " |";
    }

    private static String cap(List<String> lines, int maxChars) {
        long total = 0;
        for (String line : lines) {
            total += line.length() + 1;
        }
        if (total <= maxChars) {
            return String.join("\n", lines) + "\n";
        }
        String reserved = truncationLine(total);
        long budget = maxChars - reserved.length() - 2;
        int keep = 0;
        long used = 0;
        while (keep < lines.size() && used + lines.get(keep).length() + 1 <= budget) {
            used += lines.get(keep).length() + 1;
            keep++;
        }
        keep = Math.max(keep, 1);
        int open = lines.subList(0, keep).lastIndexOf("<details>");
        if (open > lines.subList(0, keep).lastIndexOf("</details>")) {
            keep = open;
            used = 0;
            for (int i = 0; i < keep; i++) {
                used += lines.get(i).length() + 1;
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < keep; i++) {
            out.append(lines.get(i)).append('\n');
        }
        out.append('\n').append(truncationLine(total - used)).append('\n');
        return out.toString();
    }

    private static String truncationLine(long omitted) {
        return "Report truncated, " + omitted + " characters left out.";
    }
}
