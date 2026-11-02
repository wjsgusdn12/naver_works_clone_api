package com.jhwjhw.naverworkscloneapi.board.domain;

import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

@Entity
@Table(name = "board_post_reaction",
        uniqueConstraints = @UniqueConstraint(name = "uk_board_post_reaction", columnNames = { "post_id", "membership_id" }))
@Getter
// 게시글 이모지 리액션. 한 사람은 글마다 하나만 남기고, 다른 이모지를 누르면 바뀜.
// 이모지 종류는 채팅 리액션과 같음(ChatMessageReaction.ALLOWED_EMOJIS).
public class BoardPostReaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private BoardPost post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "membership_id", nullable = false)
    private Membership membership;

    @Column(nullable = false, length = 40)
    private String emoji;

    @Column(nullable = false)
    private Instant createdAt;

    protected BoardPostReaction() {
    }

    public BoardPostReaction(BoardPost post, Membership membership, String emoji) {
        this.post = post;
        this.membership = membership;
        this.emoji = emoji;
        this.createdAt = Instant.now();
    }

    // 다른 이모지로 바꾸기. 바꾼 시각을 새로 남겨 누른 순서에 반영.
    public void changeEmoji(String emoji) {
        this.emoji = emoji;
        this.createdAt = Instant.now();
    }
}
