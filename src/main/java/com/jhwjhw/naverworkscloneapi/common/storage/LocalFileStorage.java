package com.jhwjhw.naverworkscloneapi.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

// 서버 디스크에 저장. 파일 이름은 UUID로 만들어 사용자가 보낸 이름으로 경로를 조작할 수 없게 함.
@Component
public class LocalFileStorage implements FileStorage {
    private static final DateTimeFormatter FOLDER = DateTimeFormatter.ofPattern("yyyy/MM");
    private final Path root;

    public LocalFileStorage(@Value("${app.storage.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public String save(InputStream content) throws IOException {
        // 한 폴더에 파일이 너무 몰리지 않도록 월별 폴더로 나눔.
        String key = LocalDate.now(ZoneOffset.UTC).format(FOLDER) + "/" + UUID.randomUUID();
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.copy(content, target);
        return key;
    }

    @Override
    public Resource load(String key) {
        Path path = resolve(key);
        return Files.isRegularFile(path) ? new FileSystemResource(path) : null;
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    @Override
    public long usableSpace() throws IOException {
        Files.createDirectories(root);
        return Files.getFileStore(root).getUsableSpace();
    }

    @Override
    public void forEachKeyBefore(Instant before, Consumer<String> action) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).filter(path -> modifiedBefore(path, before))
                    // 위치 키는 "2026/10/uuid"처럼 / 로 구분(윈도우에서도 같게)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .forEach(action);
        }
    }

    private static boolean modifiedBefore(Path path, Instant before) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(before);
        } catch (IOException exception) {
            return false;
        }
    }

    // 키가 저장 폴더 밖을 가리키면(../ 등) 거부.
    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("저장소 밖의 경로입니다.");
        }
        return path;
    }
}
