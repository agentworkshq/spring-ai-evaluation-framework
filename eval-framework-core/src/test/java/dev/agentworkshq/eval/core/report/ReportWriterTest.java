package dev.agentworkshq.eval.core.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.agentworkshq.eval.core.exception.BaseException;
import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReportWriterTest {

    @TempDir
    Path dir;

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void writesAReportAndCreatesTheDirectory() throws IOException {
        Path out = dir.resolve("a").resolve("b");

        Path file = new ReportWriter(out).write("report.md", "hello\n");

        assertThat(file).hasContent("hello\n");
        assertThat(names(out)).containsExactly("report.md");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../x.md", "a/b.md", "a\\b.md", "x.exe", "x", ".md", "x.md.exe", "", " x.md", "x y.md",
            "x.MD", "..", "C:x.md"})
    void rejectsBadFileNames(String name) throws IOException {
        ReportWriter writer = new ReportWriter(dir.resolve("out"));

        assertThatThrownBy(() -> writer.write(name, "x")).isInstanceOf(ValidationException.class)
                .extracting(e -> ((BaseException) e).getErrorMessage()).isEqualTo(ErrorMessages.EVAL_E024);
        assertThat(names(dir.resolve("out"))).isEmpty();
    }

    @Test
    void rejectsANullAndATooLongName() {
        ReportWriter writer = new ReportWriter(dir.resolve("out"));

        assertThatThrownBy(() -> writer.write(null, "x")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> writer.write("a".repeat(100) + ".md", "x")).isInstanceOf(ValidationException.class);
        writer.write("a".repeat(97) + ".md", "x");
    }

    @ParameterizedTest
    @ValueSource(strings = {"r.md", "r.txt", "r.json", "R-1_a.v2.md"})
    void acceptsTheAllowedExtensions(String name) {
        assertThat(new ReportWriter(dir.resolve("out")).write(name, "x")).exists();
    }

    @Test
    void rejectsARootThatIsARegularFile() throws IOException {
        Path file = Files.writeString(dir.resolve("file.txt"), "x");

        assertThatThrownBy(() -> new ReportWriter(file)).isInstanceOf(ValidationException.class)
                .extracting(e -> ((BaseException) e).getErrorMessage()).isEqualTo(ErrorMessages.EVAL_E024);
    }

    @Test
    void overwritingAnExistingReportWorks() throws IOException {
        ReportWriter writer = new ReportWriter(dir.resolve("out"));
        writer.write("r.md", "first\n");

        Path file = writer.write("r.md", "second\n");

        assertThat(file).hasContent("second\n");
        assertThat(names(dir.resolve("out"))).containsExactly("r.md");
    }

    @Test
    void contentIsUtf8WithLfOnly() throws IOException {
        Path file = new ReportWriter(dir.resolve("out")).write("r.txt", "a\r\nb\rc\né\n");

        assertThat(Files.readAllBytes(file)).isEqualTo("a\nb\nc\né\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aSymlinkLeavingTheDirectoryIsRejected() throws IOException {
        Path outside = Files.writeString(dir.resolve("outside.md"), "keep");
        Path out = Files.createDirectory(dir.resolve("out"));
        try {
            Files.createSymbolicLink(out.resolve("r.md"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links cannot be created here");
        }
        ReportWriter writer = new ReportWriter(out);

        assertThatThrownBy(() -> writer.write("r.md", "overwrite")).isInstanceOf(ValidationException.class);

        assertThat(outside).hasContent("keep");
    }

    @Test
    void aFailedWriteLeavesNoTempFile() throws IOException {
        Path out = Files.createDirectory(dir.resolve("out"));
        Files.createDirectory(out.resolve("r.md"));
        ReportWriter writer = new ReportWriter(out);

        assertThatThrownBy(() -> writer.write("r.md", "x")).isInstanceOf(BaseException.class);

        assertThat(names(out)).containsExactly("r.md");
    }
}
