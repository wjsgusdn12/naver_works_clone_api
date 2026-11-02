package com.jhwjhw.naverworkscloneapi.board.domain;

import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

@Entity
@Table(name = "board_comment")
@Getter
// 댓글. 지우면 deletedAt만 기록하고 목록에서 뺌.
public class BoardComment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private BoardPost post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_membership_id", nullable = false)
    private Membership author;

    @Column(nullable = false, length = 1000)
    private String content;

    private Instant deletedAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected BoardComment() {
    }

    public BoardComment(BoardPost post, Membership author, String content) {
        this.post = post;
        this.author = author;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public void delete(Instant at) {
        this.deletedAt = at;
    }
}
