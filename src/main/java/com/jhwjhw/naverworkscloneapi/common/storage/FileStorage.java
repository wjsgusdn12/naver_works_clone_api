package com.jhwjhw.naverworkscloneapi.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.core.io.Resource;

// 첨부 파일 저장소. 지금은 서버 디스크(LocalFileStorage)이고, 나중에 S3 등으로 바꿀 때 구현체만 추가.
public interface FileStorage {
    // 받은 내용을 저장하고 나중에 찾을 위치 키를 돌려줌.
    String save(InputStream content) throws IOException;

    // 위치 키로 저장된 파일을 읽음. 없으면 null.
    Resource load(String key);

    // 위치 키의 파일을 지움. 이미 없으면 아무 일 없음(여러 번 불러도 안전).
    void delete(String key) throws IOException;

    // 저장소에 더 쓸 수 있는 공간(바이트).
    long usableSpace() throws IOException;

    // before보다 먼저 저장된 파일의 위치 키를 하나씩 넘겨줌(청소 작업용).
    void forEachKeyBefore(Instant before, Consumer<String> action) throws IOException;
}
