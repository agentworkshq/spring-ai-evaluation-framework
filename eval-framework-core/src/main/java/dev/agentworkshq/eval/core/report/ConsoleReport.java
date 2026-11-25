package dev.agentworkshq.eval.core.report;

import dev.agentworkshq.eval.core.compare.Comparison;
import dev.agentworkshq.eval.core.compare.RunSummary;
import java.util.List;
import java.util.Objects;

public final class ConsoleReport {

    private ConsoleReport() {
    }

    public static String render(RunSummary summary, Comparison comparison, ReportOptions options) {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(options, "options");
        ReportModel model = ReportModel.build(summary, comparison, options, (text, max, inTable) -> Sanitizer.plain(text, max));
        StringBuilder out = new StringBuilder();
        for (List<String> row : model.runRows()) {
            out.append(String.format("%-10s", row.get(0) + ":")).append(row.get(1)).append('\n');
        }
        section(out, "Metrics");
        table(out, model.metrics());
        if (model.gatePassed() != null) {
            out.append('\n').append(model.gatePassed() ? "GATE: PASSED" : "GATE: FAILED").append('\n');
            model.violations().forEach(v -> out.append("  - ").append(v).append('\n'));
            if (!model.notes().isEmpty()) {
                section(out, "Notes");
                model.notes().forEach(n -> out.append("  - ").append(n).append('\n'));
            }
            if (model.configChanges() != null) {
                section(out, "Config changes");
                table(out, model.configChanges());
            }
            section(out, "Case changes");
            if (model.caseChanges().rows().isEmpty()) {
                out.append("none\n");
            } else {
                table(out, model.caseChanges());
            }
            if (model.moreCaseChanges() > 0) {
                out.append("... and ").append(model.moreCaseChanges()).append(" more\n");
            }
        }
        if (model.runConfig() != null) {
            section(out, "Run config");
            table(out, model.runConfig());
        }
        return out.toString();
    }

    private static void section(StringBuilder out, String title) {
        out.append('\n').append(title).append('\n');
    }

    private static void table(StringBuilder out, ReportModel.Table table) {
        int[] widths = new int[table.headers().size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = table.headers().get(i).length();
            for (List<String> row : table.rows()) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }
        StringBuilder rule = new StringBuilder("+");
        for (int w : widths) {
            rule.append("-".repeat(w + 2)).append('+');
        }
        out.append(rule).append('\n');
        out.append(row(table.headers(), widths)).append('\n').append(rule).append('\n');
        for (List<String> r : table.rows()) {
            out.append(row(r, widths)).append('\n');
        }
        out.append(rule).append('\n');
    }

    private static String row(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < widths.length; i++) {
            sb.append(' ').append(cells.get(i)).append(" ".repeat(widths[i] - cells.get(i).length())).append(" |");
        }
        return sb.toString();
    }
}
