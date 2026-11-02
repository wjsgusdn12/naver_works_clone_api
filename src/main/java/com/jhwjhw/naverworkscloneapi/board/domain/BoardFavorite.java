package com.jhwjhw.naverworkscloneapi.board.domain;

import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

@Entity
@Table(name = "board_favorite",
        uniqueConstraints = @UniqueConstraint(name = "uk_board_favorite", columnNames = { "membership_id", "board_id" }))
@Getter
// 사이드바 게시판 즐겨찾기
public class BoardFavorite {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "membership_id", nullable = false)
    private Membership membership;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id", nullable = false)
    private Board board;

    @Column(nullable = false)
    private Instant createdAt;

    protected BoardFavorite() {
    }

    public BoardFavorite(Membership membership, Board board) {
        this.membership = membership;
        this.board = board;
        this.createdAt = Instant.now();
    }
}
