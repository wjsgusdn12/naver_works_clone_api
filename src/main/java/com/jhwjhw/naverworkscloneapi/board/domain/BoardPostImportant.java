package com.jhwjhw.naverworkscloneapi.board.domain;

import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

@Entity
@Table(name = "board_post_important",
        uniqueConstraints = @UniqueConstraint(name = "uk_board_post_important", columnNames = { "post_id", "membership_id" }))
@Getter
// 게시글 "중요" 개인 표시(깃발). 표시한 사람에게만 보이고, 사이드바 "중요" 목록이 이것으로 정해짐.
public class BoardPostImportant {
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
    private Instant createdAt;

    protected BoardPostImportant() {
    }

    public BoardPostImportant(BoardPost post, Membership membership) {
        this.post = post;
        this.membership = membership;
        this.createdAt = Instant.now();
    }
}
