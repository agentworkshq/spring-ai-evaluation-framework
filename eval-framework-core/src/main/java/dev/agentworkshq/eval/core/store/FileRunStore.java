package dev.agentworkshq.eval.core.store;

import dev.agentworkshq.eval.core.model.Echo;
import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ResourceNotFoundException;
import dev.agentworkshq.eval.core.exception.ServiceException;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.EvalRun;
import dev.agentworkshq.eval.core.model.NameRules;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FileRunStore implements RunStore {

    private static final Logger log = LoggerFactory.getLogger(FileRunStore.class);

    private static final String PREFIX = "run-";
    private static final String SUFFIX = ".json";

    private final Path root;
    private final StoreLimits limits;
    private final RunCodec codec;
    private final boolean readOnly;
    private final ReentrantLock saveLock = new ReentrantLock();

    public FileRunStore(Path root, StoreLimits limits) {
        this(root, limits, false);
    }

    public static FileRunStore readOnly(Path root, StoreLimits limits) {
        return new FileRunStore(root, limits, true);
    }

    private FileRunStore(Path root, StoreLimits limits, boolean readOnly) {
        Objects.requireNonNull(root, "root");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.readOnly = readOnly;
        this.codec = new RunCodec(limits);
        try {
            if (Files.exists(root) && !Files.isDirectory(root)) {
                throw new ValidationException(ErrorMessages.EVAL_E020, "not a directory");
            }
            if (readOnly && !Files.isDirectory(root)) {
                throw new ValidationException(ErrorMessages.EVAL_E020, "the directory does not exist");
            }
            Files.createDirectories(root);
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new ServiceException(ErrorMessages.EVAL_E020, e, "cannot create or open the directory");
        }
    }

    @Override
    public void save(StoredRun run) {
        Objects.requireNonNull(run, "run");
        if (readOnly) {
            throw new ValidationException(ErrorMessages.EVAL_E020, "the store is read-only");
        }
        run.config().keySet().forEach(EvalRun::requireSafeConfigKey);
        byte[] bytes = codec.write(run);
        Path target = pathFor(run.runId());
        Path temp = null;
        try {
            temp = Files.createTempFile(root, "run-tmp-", ".tmp");
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            saveLock.lock();
            try {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ValidationException(ErrorMessages.EVAL_E018, Echo.safe(run.runId()));
                }
                Files.move(temp, target);
            } finally {
                saveLock.unlock();
            }
            log.debug("Saved run file {}", target.getFileName());
        } catch (FileAlreadyExistsException e) {
            throw new ValidationException(ErrorMessages.EVAL_E018, e, Echo.safe(run.runId()));
        } catch (IOException e) {
            throw new ServiceException(ErrorMessages.EVAL_E023, e, "save");
        } finally {
            deleteQuietly(temp);
        }
    }

    @Override
    public StoredRun load(String runId) {
        NameRules.require(runId, "runId");
        Path path = pathFor(runId);
        StoredRun run = read(path, runId);
        if (run == null) {
            throw new ResourceNotFoundException(ErrorMessages.EVAL_E017, Echo.safe(runId));
        }
        return run;
    }

    @Override
    public List<RunHeader> list() {
        List<RunHeader> headers = new ArrayList<>();
        for (Path file : candidates()) {
            StoredRun run = read(file, null);
            if (run != null) {
                headers.add(new RunHeader(run.runId(), run.datasetName(), run.targetName(), run.startedAt(), run.cases().size()));
            }
        }
        headers.sort(Comparator.comparing(RunHeader::startedAt).thenComparing(RunHeader::runId));
        log.debug("Listed {} runs", headers.size());
        return List.copyOf(headers);
    }

    @Override
    public Optional<StoredRun> latest(String datasetName, String targetName) {
        NameRules.require(datasetName, "datasetName");
        Objects.requireNonNull(targetName, "targetName");
        StoredRun best = null;
        for (Path file : candidates()) {
            StoredRun run = read(file, null);
            if (run == null || !run.datasetName().equals(datasetName) || !run.targetName().equals(targetName)) {
                continue;
            }
            if (best == null || run.startedAt().isAfter(best.startedAt())
                    || run.startedAt().equals(best.startedAt()) && run.runId().compareTo(best.runId()) > 0) {
                best = run;
            }
        }
        return Optional.ofNullable(best);
    }

    private Path pathFor(String runId) {
        NameRules.require(runId, "runId");
        return root.resolve(PREFIX + runId + SUFFIX);
    }

    private List<Path> candidates() {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.length() > PREFIX.length() + SUFFIX.length() && name.startsWith(PREFIX) && name.endsWith(SUFFIX)
                        && NameRules.isValid(name.substring(PREFIX.length(), name.length() - SUFFIX.length()))
                        && Files.isRegularFile(path)) {
                    files.add(path);
                }
            }
        } catch (IOException e) {
            throw new ServiceException(ErrorMessages.EVAL_E023, e, "list");
        }
        if (files.size() > limits.maxRuns()) {
            throw new ValidationException(ErrorMessages.EVAL_E021, files.size(), limits.maxRuns());
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }

    private StoredRun read(Path path, String expectedId) {
        String name = path.getFileName().toString();
        Path real;
        try {
            real = path.toRealPath();
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new ServiceException(ErrorMessages.EVAL_E023, e, "read");
        }
        if (!real.startsWith(root)) {
            throw new ValidationException(ErrorMessages.EVAL_E020, "run file leaves the store directory: " + Echo.safe(name));
        }
        if (!real.getFileName().toString().equals(name) || !Files.isRegularFile(real)) {
            return null;
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(real)) {
            bytes = in.readNBytes((int) Math.min((long) limits.maxFileBytes() + 1, Integer.MAX_VALUE));
        } catch (IOException e) {
            throw new ServiceException(ErrorMessages.EVAL_E023, e, "read");
        }
        StoredRun run = codec.read(bytes, name);
        String idInName = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
        if (!run.runId().equals(idInName) || expectedId != null && !run.runId().equals(expectedId)) {
            throw new ValidationException(ErrorMessages.EVAL_E019, Echo.safe(name), ": run id does not match the file name");
        }
        log.debug("Read run file {}", name);
        return run;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Could not delete temp file {}", path.getFileName());
        }
    }
}
