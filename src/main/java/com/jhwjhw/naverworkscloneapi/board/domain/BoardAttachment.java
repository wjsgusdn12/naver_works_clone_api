package com.jhwjhw.naverworkscloneapi.board.domain;

import com.jhwjhw.naverworkscloneapi.chat.domain.ChatAttachmentKind;
import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

@Entity
@Table(name = "board_attachment")
@Getter
// 게시글 첨부(본문 속 사진 포함). 글을 쓰는 중에 먼저 올리므로 저장 전까지 post가 null.
public class BoardAttachment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private BoardPost post;

    // 댓글 첨부면 그 댓글(post도 그 댓글의 글로 함께 채움). 글 첨부는 null.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comment_id")
    private BoardComment comment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploader_membership_id", nullable = false)
    private Membership uploader;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChatAttachmentKind kind;

    @Column(nullable = false)
    private String originalName;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long size;

    @Column(nullable = false, length = 100)
    private String storageKey;

    @Column(length = 100)
    private String thumbnailKey;

    private Integer width;
    private Integer height;

    // 본문 속에 들어간 사진이면 true(아래 첨부 목록에는 안 보임)
    @Column(nullable = false)
    private boolean inlineImage;

    @Column(nullable = false)
    private Instant createdAt;

    protected BoardAttachment() {
    }

    public BoardAttachment(Membership uploader, ChatAttachmentKind kind, String originalName, String contentType,
            long size, String storageKey, String thumbnailKey, Integer width, Integer height) {
        this.uploader = uploader;
        this.kind = kind;
        this.originalName = originalName;
        this.contentType = contentType;
        this.size = size;
        this.storageKey = storageKey;
        this.thumbnailKey = thumbnailKey;
        this.width = width;
        this.height = height;
        this.createdAt = Instant.now();
    }

    // 글에 붙이거나(post) 떼어 냄(null). 떼어 낸 첨부는 청소 작업이 파일을 정리함.
    public void attachTo(BoardPost post, boolean inlineImage) {
        this.post = post;
        this.comment = null;
        this.inlineImage = post != null && inlineImage;
    }

    // 댓글에 붙이거나(comment) 떼어 냄(null). 댓글 첨부도 그 글에 함께 묶여 글을 볼 수 있는 사람만 받음.
    public void attachToComment(BoardComment comment) {
        this.comment = comment;
        this.post = comment == null ? null : comment.getPost();
        this.inlineImage = false;
    }
}
