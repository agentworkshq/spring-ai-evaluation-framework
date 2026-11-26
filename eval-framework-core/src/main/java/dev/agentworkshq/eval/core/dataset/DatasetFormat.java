package dev.agentworkshq.eval.core.dataset;

import dev.agentworkshq.eval.core.model.Echo;
import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import java.util.List;
import java.util.Locale;

public enum DatasetFormat {
    YAML(".yaml", ".yml"),
    JSON(".json"),
    JSONL(".jsonl");

    private final List<String> extensions;

    DatasetFormat(String... extensions) {
        this.extensions = List.of(extensions);
    }

    public static DatasetFormat fromFileName(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        for (DatasetFormat format : values()) {
            for (String ext : format.extensions) {
                if (lower.endsWith(ext)) {
                    return format;
                }
            }
        }
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        String ext = dot < 0 ? "none" : Echo.safe(fileName.substring(dot));
        throw new ValidationException(ErrorMessages.EVAL_E004, ext);
    }

    String stripExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (lower.endsWith(ext)) {
                return fileName.substring(0, fileName.length() - ext.length());
            }
        }
        return fileName;
    }
}
