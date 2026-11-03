package com.jhwjhw.naverworkscloneapi.board.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;

@Entity
@Table(name = "board_daily_greeting")
@Getter
// 매일 자유게시판 인사 글을 올린 날짜. 날짜가 기본 키라 같은 날 두 번 기록되지 않음.
public class BoardDailyGreeting {
    @Id
    private LocalDate greetingDate;

    @Column(nullable = false)
    private Long postId;

    @Column(nullable = false)
    private Instant createdAt;

    protected BoardDailyGreeting() {
    }

    public BoardDailyGreeting(LocalDate greetingDate, Long postId) {
        this.greetingDate = greetingDate;
        this.postId = postId;
        this.createdAt = Instant.now();
    }
}
