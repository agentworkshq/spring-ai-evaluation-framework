package dev.agentworkshq.eval.core.report;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ServiceException;
import dev.agentworkshq.eval.core.exception.ValidationException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ReportWriter.class);

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final List<String> EXTENSIONS = List.of(".md", ".txt", ".json");

    private final Path root;

    public ReportWriter(Path outputDir) {
        Objects.requireNonNull(outputDir, "outputDir");
        try {
            if (Files.exists(outputDir) && !Files.isDirectory(outputDir)) {
                throw new ValidationException(ErrorMessages.EVAL_E024, "the output location is not a directory");
            }
            Files.createDirectories(outputDir);
            this.root = outputDir.toRealPath();
        } catch (IOException e) {
            throw new ServiceException(ErrorMessages.EVAL_E025, e, "output directory");
        }
    }

    public Path write(String fileName, String content) {
        Objects.requireNonNull(content, "content");
        if (fileName == null || !NAME.matcher(fileName).matches() || EXTENSIONS.stream().noneMatch(fileName::endsWith)) {
            throw new ValidationException(ErrorMessages.EVAL_E024, "invalid file name");
        }
        Path target = root.resolve(fileName);
        byte[] bytes = content.replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
        Path temp = null;
        try {
            if (Files.exists(target)) {
                Path real = target.toRealPath();
                if (!real.startsWith(root) || !Files.isRegularFile(real)) {
                    throw new ValidationException(ErrorMessages.EVAL_E024, "the target leaves the output directory");
                }
            } else if (Files.isSymbolicLink(target)) {
                throw new ValidationException(ErrorMessages.EVAL_E024, "the target is a broken symbolic link");
            }
            temp = Files.createTempFile(root, "report-tmp-", ".tmp");
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Wrote report {} with {} bytes", fileName, bytes.length);
            return target;
        } catch (IOException e) {
            throw new ServiceException(ErrorMessages.EVAL_E025, e, fileName);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.debug("Could not delete temp file {}", temp.getFileName());
                }
            }
        }
    }
}
