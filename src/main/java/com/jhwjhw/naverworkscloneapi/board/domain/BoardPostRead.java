package com.jhwjhw.naverworkscloneapi.board.domain;

import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

@Entity
@Table(name = "board_post_read",
        uniqueConstraints = @UniqueConstraint(name = "uk_board_post_read", columnNames = { "post_id", "membership_id" }))
@Getter
// 글을 처음 연 기록(조회수·필독 읽음 확인). 한 사람은 글마다 한 번만.
public class BoardPostRead {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private BoardPost post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "membership_id", nullable = false)
    private Membership membership;

    @Column(nullable = false)
    private Instant readAt;

    protected BoardPostRead() {
    }

    public BoardPostRead(BoardPost post, Membership membership) {
        this.post = post;
        this.membership = membership;
        this.readAt = Instant.now();
    }
}
