package com.jhwjhw.naverworkscloneapi.board.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;

@Entity
@Table(name = "board_daily_greeting")
@Getter
// 매일 자유게시판 인사 글을 올린 날짜. 날짜가 기본 키라 같은 날 두 번 기록되지 않음.
// 글에 붙는 증시 분석(미국·한국)을 만든 결과와 진행 상태도 함께 둠(V43).
public class BoardDailyGreeting {
    @Id
    private LocalDate greetingDate;

    @Column(nullable = false)
    private Long postId;

    @Column(nullable = false)
    private Instant createdAt;

    // 만들어 둔 분석 부분(편집기 문서 노드 JSON 배열). 못 받았으면 null.
    @Column(columnDefinition = "TEXT")
    private String usSection;

    @Column(columnDefinition = "TEXT")
    private String krSection;

    // 그 부분을 끝낸 시각(포기 포함). null이면 아직 할 일.
    private Instant usDoneAt;

    private Instant krDoneAt;

    // 서버 한 대만 작업하도록 잡아 둔 기한(저장소의 조건부 갱신으로만 바꿈)
    private Instant leaseUntil;

    protected BoardDailyGreeting() {
    }

    public BoardDailyGreeting(LocalDate greetingDate, Long postId) {
        this.greetingDate = greetingDate;
        this.postId = postId;
        this.createdAt = Instant.now();
    }

    public void finishUs(String section, Instant at) {
        this.usSection = section;
        this.usDoneAt = at;
    }

    public void finishKr(String section, Instant at) {
        this.krSection = section;
        this.krDoneAt = at;
    }
}
