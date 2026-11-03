package com.jhwjhw.naverworkscloneapi.board;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// 검색 결과 미리보기: 검색어가 본문 뒤쪽에 있으면 그 둘레로 잘라 검색어가 보이게.
class BoardPreviewTests {
    @Test
    void cutsAroundKeywordWhenItIsBeyondHead() {
        String text = "가".repeat(300) + " 삼성전자 강세 " + "나".repeat(200);
        String preview = BoardService.preview(text, "삼성");
        assertThat(preview).startsWith("…").contains("삼성전자").endsWith("…");
        assertThat(preview.indexOf("삼성")).isEqualTo(31);
    }

    @Test
    void keepsHeadWhenKeywordIsNearStartOrMissing() {
        String text = "삼성 " + "가".repeat(300);
        assertThat(BoardService.preview(text, "삼성")).startsWith("삼성");
        assertThat(BoardService.preview(text, "없는말")).startsWith("삼성");
        assertThat(BoardService.preview("Samsung   rises", "SAMSUNG")).isEqualTo("Samsung rises");
    }
}
