package io.github.pkyit.notifyx.core.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkyit.notifyx.core.spi.FailedRecord;
import io.github.pkyit.notifyx.core.spi.FailureStore;
import io.github.pkyit.notifyx.core.spi.RecordStatus;

/**
 * 本地文件失败存储（设计书 §9.9）：一个事件一个 JSON 文件，按小时分片。
 *
 * <p>目录结构：{@code <root>/yyyyMMddHH/<recordId>.json}。与内存实现相比，
 * 进程重启后失败记录仍在，是「通知不丢」的底线保障。</p>
 *
 * <p><b>磁盘保护</b>：默认 10,000 个文件 / 100MB 上限，超出后优先删除最旧的
 * {@code DEAD} 记录，再删最旧文件，并累加 {@link #overflowCount()}——
 * 否则通知持续失败会把磁盘写满，把业务系统拖死。</p>
 *
 * <p><b>能力边界</b>：本实现负责「落盘 + 乐观锁状态机」，自动重投需要完整的
 * {@code NotifyEnvelope} 序列化（P2/P3 与 MQ 模式一起做）；当前落盘记录可用于人工导出与
 * 后续补偿任务。</p>
 *
 * <p>@implNote 线程安全；写文件先写临时文件再原子移动，避免半截 JSON。</p>
 *
 * @since 1.0
 */
public final class LocalFileFailureStore implements FailureStore {

    /** 默认最大文件数。 */
    public static final int DEFAULT_MAX_FILES = 10_000;

    /** 默认最大占用字节数（100MB）。 */
    public static final long DEFAULT_MAX_BYTES = 100L * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(LocalFileFailureStore.class);

    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHH");

    private static final String JSON_SUFFIX = ".json";

    private final Path root;
    private final int maxFiles;
    private final long maxBytes;
    private final Map<String, Path> index = new ConcurrentHashMap<>();
    private final AtomicLong approxBytes = new AtomicLong();
    private final AtomicLong overflow = new AtomicLong();

    /**
     * 使用默认上限构造。
     *
     * @param root 根目录
     */
    public LocalFileFailureStore(Path root) {
        this(root, DEFAULT_MAX_FILES, DEFAULT_MAX_BYTES);
    }

    /**
     * 构造存储。
     *
     * @param root 根目录
     * @param maxFiles 最大文件数
     * @param maxBytes 最大占用字节数
     */
    public LocalFileFailureStore(Path root, int maxFiles, long maxBytes) {
        this.root = root.toAbsolutePath();
        this.maxFiles = Math.max(1, maxFiles);
        this.maxBytes = Math.max(1024L, maxBytes);
        try {
            Files.createDirectories(this.root);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot create failure store dir: " + this.root, ex);
        }
        scan();
        log.info("notifyx file failure store ready: dir={}, records={}, maxFiles={}, maxBytes={}",
                this.root, index.size(), this.maxFiles, this.maxBytes);
    }

    @Override
    public synchronized void save(FailedRecord record) {
        if (record == null) {
            return;
        }
        Path target = pathOf(record);
        try {
            writeAtomically(target, toJson(record));
            index.put(record.recordId(), target);
            approxBytes.addAndGet(Files.size(target));
            maintainCapacity();
        } catch (IOException ex) {
            // 落盘失败不能影响主流程：通知已经失败，再抛异常只会让问题变大
            log.warn("notifyx failed to persist failure record {}: {}", record.recordId(), ex.toString());
        }
    }

    @Override
    public synchronized List<FailedRecord> loadPending(int limit) {
        long now = System.currentTimeMillis();
        List<FailedRecord> pending = new ArrayList<>();
        for (FailedRecord record : loadAll()) {
            if (record.status() == RecordStatus.PENDING && record.nextRetryAt() <= now) {
                pending.add(record);
            }
        }
        pending.sort(Comparator.comparingLong(FailedRecord::nextRetryAt));
        if (limit <= 0) {
            return List.of();
        }
        return pending.size() <= limit ? pending : pending.subList(0, limit);
    }

    @Override
    public synchronized boolean updateStatus(String recordId, int expectedVersion, RecordStatus status, int attempts) {
        Path path = pathFor(recordId);
        if (path == null) {
            return false;
        }
        Optional<FailedRecord> loaded = read(path);
        if (loaded.isEmpty() || loaded.get().version() != expectedVersion) {
            return false;
        }
        FailedRecord current = loaded.get();
        FailedRecord updated = new FailedRecord(current.recordId(), status, current.version() + 1,
                current.envelopeJson(), current.channel(), current.failureReason(), attempts,
                current.createdAt(), current.nextRetryAt());
        try {
            writeAtomically(path, toJson(updated));
            return true;
        } catch (IOException ex) {
            log.warn("notifyx failed to update failure record {}: {}", recordId, ex.toString());
            return false;
        }
    }

    @Override
    public synchronized long pendingCount() {
        long count = 0;
        for (FailedRecord record : loadAll()) {
            if (record.status() == RecordStatus.PENDING) {
                count++;
            }
        }
        return count;
    }

    /**
     * 当前记录数。
     *
     * @return 文件数
     */
    public int recordCount() {
        return index.size();
    }

    /**
     * 因磁盘上限被删除的记录数。
     *
     * @return 删除数量
     */
    public long overflowCount() {
        return overflow.get();
    }

    /**
     * 根目录。
     *
     * @return 目录
     */
    public Path root() {
        return root;
    }

    private void maintainCapacity() throws IOException {
        if (index.size() <= maxFiles && approxBytes.get() <= maxBytes) {
            return;
        }
        List<Path> files = listFiles();
        files.sort(Comparator.comparingLong(LocalFileFailureStore::lastModified));
        // 先删最旧的 DEAD，仍超限再按时间删最旧文件
        for (Path file : new ArrayList<>(files)) {
            if (index.size() <= maxFiles && approxBytes.get() <= maxBytes) {
                return;
            }
            if (statusOf(file) == RecordStatus.DEAD) {
                deleteQuietly(file, files);
            }
        }
        for (Path file : new ArrayList<>(files)) {
            if (index.size() <= maxFiles && approxBytes.get() <= maxBytes) {
                return;
            }
            deleteQuietly(file, files);
        }
        if (overflow.get() > 0) {
            log.warn("notifyx file failure store overflow: deleted {} record(s), dir={}, maxFiles={}, maxBytes={}",
                    overflow.get(), root, maxFiles, maxBytes);
        }
    }

    private RecordStatus statusOf(Path file) {
        return read(file).map(FailedRecord::status).orElse(null);
    }

    private boolean deleteQuietly(Path file, List<Path> files) {
        long size = 0;
        try {
            size = Files.size(file);
        } catch (IOException ex) {
            log.debug("notifyx cannot size {}: {}", file, ex.toString());
        }
        try {
            if (Files.deleteIfExists(file)) {
                files.remove(file);
                index.values().removeIf(path -> path.equals(file));
                approxBytes.addAndGet(-size);
                overflow.incrementAndGet();
                return true;
            }
        } catch (IOException ex) {
            log.debug("notifyx failed to delete {}: {}", file, ex.toString());
        }
        return false;
    }

    private List<FailedRecord> loadAll() {
        List<FailedRecord> records = new ArrayList<>();
        for (Path file : listFiles()) {
            read(file).ifPresent(records::add);
        }
        return records;
    }

    private void scan() {
        for (Path file : listFiles()) {
            read(file).ifPresent(record -> index.put(record.recordId(), file));
        }
        long bytes = 0;
        for (Path path : index.values()) {
            try {
                bytes += Files.size(path);
            } catch (IOException ex) {
                log.debug("notifyx cannot size {}: {}", path, ex.toString());
            }
        }
        approxBytes.set(bytes);
    }

    private List<Path> listFiles() {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        try (DirectoryStream<Path> hours = Files.newDirectoryStream(root)) {
            for (Path hour : hours) {
                if (!Files.isDirectory(hour)) {
                    continue;
                }
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(hour, "*" + JSON_SUFFIX)) {
                    for (Path entry : entries) {
                        if (Files.isRegularFile(entry)) {
                            files.add(entry);
                        }
                    }
                }
            }
        } catch (IOException ex) {
            log.debug("notifyx cannot list failure store {}: {}", root, ex.toString());
        }
        return files;
    }

    private Path pathFor(String recordId) {
        Path cached = index.get(recordId);
        if (cached != null && Files.exists(cached)) {
            return cached;
        }
        for (Path file : listFiles()) {
            if (file.getFileName().toString().equals(safeName(recordId) + JSON_SUFFIX)) {
                index.put(recordId, file);
                return file;
            }
        }
        return null;
    }

    private Path pathOf(FailedRecord record) {
        String hour = HOUR_FORMAT.format(Instant.ofEpochMilli(record.createdAt())
                .atZone(ZoneId.systemDefault()));
        return root.resolve(hour).resolve(safeName(record.recordId()) + JSON_SUFFIX);
    }

    private static String safeName(String recordId) {
        String raw = recordId == null ? "unknown" : recordId;
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return Long.MAX_VALUE;
        }
    }

    private void writeAtomically(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Optional<FailedRecord> read(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, String> json = Json.parseFlat(text);
            return Optional.of(new FailedRecord(
                    json.get("recordId"),
                    RecordStatus.valueOf(json.getOrDefault("status", RecordStatus.PENDING.name())),
                    Integer.parseInt(json.getOrDefault("version", "0")),
                    json.getOrDefault("envelope", ""),
                    json.get("channel"),
                    json.get("failureReason"),
                    Integer.parseInt(json.getOrDefault("attempts", "0")),
                    Long.parseLong(json.getOrDefault("createdAt", "0")),
                    Long.parseLong(json.getOrDefault("nextRetryAt", "0"))));
        } catch (IOException | RuntimeException ex) {
            log.debug("notifyx skip unreadable failure record {}: {}", file, ex.toString());
            return Optional.empty();
        }
    }

    private static String toJson(FailedRecord record) {
        StringBuilder builder = new StringBuilder(256);
        builder.append('{')
                .append("\"recordId\":").append(Json.quote(record.recordId())).append(',')
                .append("\"status\":").append(Json.quote(record.status().name())).append(',')
                .append("\"version\":").append(record.version()).append(',')
                .append("\"envelope\":").append(Json.quote(record.envelopeJson())).append(',')
                .append("\"channel\":").append(Json.quote(record.channel())).append(',')
                .append("\"failureReason\":").append(Json.quote(record.failureReason())).append(',')
                .append("\"attempts\":").append(record.attempts()).append(',')
                .append("\"createdAt\":").append(record.createdAt()).append(',')
                .append("\"nextRetryAt\":").append(record.nextRetryAt())
                .append('}');
        return builder.toString();
    }

    /**
     * 最小 JSON 读写：只支持扁平对象（字符串/数字），避免给 core 引入 Jackson。
     *
     * @implNote 无状态，线程安全。
     */
    private static final class Json {

        private static String quote(String value) {
            if (value == null) {
                return "null";
            }
            StringBuilder builder = new StringBuilder(value.length() + 8).append('"');
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                switch (ch) {
                    case '"' -> builder.append("\\\"");
                    case '\\' -> builder.append("\\\\");
                    case '\n' -> builder.append("\\n");
                    case '\r' -> builder.append("\\r");
                    case '\t' -> builder.append("\\t");
                    default -> builder.append(ch);
                }
            }
            return builder.append('"').toString();
        }

        private static Map<String, String> parseFlat(String text) {
            Map<String, String> result = new LinkedHashMap<>();
            int i = 0;
            int length = text.length();
            while (i < length && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= length || text.charAt(i) != '{') {
                throw new IllegalArgumentException("not a JSON object");
            }
            i++;
            while (true) {
                while (i < length && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',')) {
                    i++;
                }
                if (i >= length) {
                    throw new IllegalArgumentException("unexpected end");
                }
                if (text.charAt(i) == '}') {
                    return result;
                }
                String key = readString(text, i);
                i = skipTo(text, i, ':') + 1;
                while (i < length && Character.isWhitespace(text.charAt(i))) {
                    i++;
                }
                String value;
                if (text.charAt(i) == '"') {
                    int end = endOfString(text, i);
                    value = unescape(text.substring(i + 1, end));
                    i = end + 1;
                } else if (text.startsWith("null", i)) {
                    value = null;
                    i += 4;
                } else {
                    int start = i;
                    while (i < length && text.charAt(i) != ',' && text.charAt(i) != '}') {
                        i++;
                    }
                    value = text.substring(start, i).trim();
                }
                result.put(key, value);
            }
        }

        private static String readString(String text, int start) {
            if (text.charAt(start) != '"') {
                throw new IllegalArgumentException("key must be string");
            }
            int end = endOfString(text, start);
            return unescape(text.substring(start + 1, end));
        }

        private static int endOfString(String text, int start) {
            for (int i = start + 1; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    return i;
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private static int skipTo(String text, int start, char target) {
            for (int i = start; i < text.length(); i++) {
                if (text.charAt(i) == target) {
                    return i;
                }
            }
            throw new IllegalArgumentException("missing '" + target + "'");
        }

        private static String unescape(String raw) {
            StringBuilder builder = new StringBuilder(raw.length());
            for (int i = 0; i < raw.length(); i++) {
                char ch = raw.charAt(i);
                if (ch != '\\' || i + 1 >= raw.length()) {
                    builder.append(ch);
                    continue;
                }
                char next = raw.charAt(++i);
                switch (next) {
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case 'u' -> {
                        if (i + 4 < raw.length()) {
                            builder.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> builder.append(next);
                }
            }
            return builder.toString();
        }
    }
}
