package com.jhwjhw.naverworkscloneapi.board;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class BoardRequest {
    private BoardRequest() {
    }

    // 글쓰기. content는 편집기 문서 JSON, attachmentIds는 아래 첨부 목록에 붙일 파일(본문 속 사진은 본문에서 찾음).
    public record PostCreate(@NotNull Long boardId, @NotBlank @Size(max = 200) String title, String content,
            boolean important, boolean mustRead, @Size(max = 30) List<Long> attachmentIds) {
    }

    // 글 수정. expectedVersion은 수정 화면을 열 때 받은 버전(그 사이 다른 곳에서 바뀌었으면 거절).
    public record PostUpdate(@NotBlank @Size(max = 200) String title, String content, boolean important,
            boolean mustRead, @Size(max = 30) List<Long> attachmentIds, @NotNull @PositiveOrZero Long expectedVersion) {
    }

    public record CommentCreate(@NotBlank @Size(max = 1000) String content) {
    }

    public record Rename(@NotBlank @Size(max = 30) String name) {
    }
}
