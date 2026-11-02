package com.jhwjhw.naverworkscloneapi.board;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class BoardRequest {
    private BoardRequest() {
    }

    // 저장 방식: PUBLISH(바로 등록) · DRAFT(임시저장) · SCHEDULE(publishAt에 등록예약). 없으면 PUBLISH.
    public enum SaveMode {
        PUBLISH, DRAFT, SCHEDULE
    }

    // 글쓰기. content는 편집기 문서 JSON, attachmentIds는 아래 첨부 목록에 붙일 파일(본문 속 사진은 본문에서 찾음).
    // 제목은 임시저장이면 비워도 됨(서비스가 저장 방식에 따라 검사).
    public record PostCreate(@NotNull Long boardId, @Size(max = 200) String title, String content,
            boolean important, boolean mustRead, @Size(max = 30) List<Long> attachmentIds, SaveMode mode,
            Instant publishAt) {
        public PostCreate(Long boardId, String title, String content, boolean important, boolean mustRead,
                List<Long> attachmentIds) {
            this(boardId, title, content, important, mustRead, attachmentIds, null, null);
        }
    }

    // 글 수정. expectedVersion은 수정 화면을 열 때 받은 버전(그 사이 다른 곳에서 바뀌었으면 거절).
    // mode는 임시저장·예약 글에서만 의미 있음(등록된 글은 그대로 등록 상태).
    public record PostUpdate(@Size(max = 200) String title, String content, boolean important,
            boolean mustRead, @Size(max = 30) List<Long> attachmentIds, @NotNull @PositiveOrZero Long expectedVersion,
            SaveMode mode, Instant publishAt) {
        public PostUpdate(String title, String content, boolean important, boolean mustRead, List<Long> attachmentIds,
                Long expectedVersion) {
            this(title, content, important, mustRead, attachmentIds, expectedVersion, null, null);
        }
    }

    public record CommentCreate(@NotBlank @Size(max = 1000) String content) {
    }

    // 목록에서 고른 글 읽음 처리(한 번에 100개까지)
    public record MarkRead(@NotEmpty @Size(max = 100) List<@NotNull Long> postIds) {
    }

    public record Rename(@NotBlank @Size(max = 30) String name) {
    }
}
