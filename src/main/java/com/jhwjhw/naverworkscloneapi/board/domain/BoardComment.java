package com.jhwjhw.naverworkscloneapi.board.domain;

import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

@Entity
@Table(name = "board_comment")
@Getter
// 댓글. 지우면 deletedAt만 기록하고 목록에서 뺌(답글이 남아 있으면 "삭제된 댓글" 자리로 남김).
// parent가 있으면 답글. 답글은 한 단계만이라 parent는 늘 맨 위 댓글.
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private BoardComment parent;

    @Column(nullable = false, length = 1000)
    private String content;

    private Instant deletedAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected BoardComment() {
    }

    public BoardComment(BoardPost post, Membership author, String content) {
        this(post, author, content, null);
    }

    public BoardComment(BoardPost post, Membership author, String content, BoardComment parent) {
        this.post = post;
        this.author = author;
        this.content = content;
        this.parent = parent;
        this.createdAt = Instant.now();
    }

    public void delete(Instant at) {
        this.deletedAt = at;
    }
}
