package com.jhwjhw.naverworkscloneapi.board.domain;

import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

@Entity
@Table(name = "board_post")
@Getter
// 게시글. 지우면 휴지통으로(deletedAt 기록)만 옮기고 행은 그대로 둠.
public class BoardPost {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id", nullable = false)
    private Board board;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_membership_id", nullable = false)
    private Membership author;

    @Column(nullable = false, length = 200)
    private String title;

    // 편집기 문서(서버가 허용한 형식만 남겨 다시 만든 JSON)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String contentJson;

    // 검색·미리보기용 글자만 뽑은 본문
    @Column(nullable = false, columnDefinition = "TEXT")
    private String contentText;

    // 목록 맨 위 고정
    @Column(nullable = false)
    private boolean important;

    // 필독(읽음 확인)
    @Column(nullable = false)
    private boolean mustRead;

    // 아래 세 값은 저장소의 update 문으로만 바꿈(글 version을 올리지 않도록).
    // 읽은 사람 수(한 사람은 한 번만 셈)
    @Column(nullable = false)
    private int viewCount;

    // 지우지 않은 댓글 수
    @Column(nullable = false)
    private int commentCount;

    // 문의하기 글에 관리자가 처음 답변(댓글)한 시각
    private Instant answeredAt;

    // 휴지통으로 옮긴 시각과 옮긴 사람
    private Instant deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by_membership_id")
    private Membership deletedBy;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    // 글을 쓰거나 고치거나 댓글이 달린 마지막 시각("글/댓글 업데이트 순" 정렬). V34 이전 글은 null.
    private Instant activityAt;

    // 임시저장·등록예약·등록. 목록·검색·알림은 등록된 글만.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PostStatus status = PostStatus.PUBLISHED;

    // 등록예약 시각(예약 글만). 공개되면 비움.
    private Instant publishAt;

    // 댓글 허용, 댓글이 달리면 글쓴이에게 알림. 문의하기는 답변이 댓글이라 항상 둘 다 켬.
    @Column(nullable = false)
    private boolean allowComment = true;

    @Column(nullable = false)
    private boolean commentNotify = true;

    @Version
    private long version;

    protected BoardPost() {
    }

    public BoardPost(Board board, Membership author, String title, String contentJson, String contentText,
            boolean important, boolean mustRead) {
        this.board = board;
        this.author = author;
        this.title = title;
        this.contentJson = contentJson;
        this.contentText = contentText;
        this.important = important;
        this.mustRead = mustRead;
        this.createdAt = Instant.now();
        this.activityAt = this.createdAt;
    }

    public void edit(String title, String contentJson, String contentText, boolean important, boolean mustRead) {
        this.title = title;
        this.contentJson = contentJson;
        this.contentText = contentText;
        this.important = important;
        this.mustRead = mustRead;
        this.updatedAt = Instant.now();
        this.activityAt = this.updatedAt;
    }

    // 사람이 고친 것이 아니라 시스템이 본문만 새로 채움(매일 인사 글의 증시 분석 보강).
    // 그래서 "(수정됨)"·업데이트 순 정렬 시각은 바꾸지 않음.
    public void refreshContentBySystem(String contentJson, String contentText) {
        this.contentJson = contentJson;
        this.contentText = contentText;
    }

    public void changeCommentOptions(boolean allowComment, boolean commentNotify) {
        this.allowComment = allowComment;
        this.commentNotify = commentNotify;
    }

    // 임시저장(DRAFT)으로 두거나 at에 등록예약(SCHEDULED). 등록된 글에는 쓰지 않음.
    public void keepUnpublished(PostStatus status, Instant at) {
        this.status = status;
        this.publishAt = status == PostStatus.SCHEDULED ? at : null;
    }

    // 지금 공개. 등록 시각을 공개한 때로 맞춰 목록 맨 위에 오게 함.
    public void publish(Instant at) {
        this.status = PostStatus.PUBLISHED;
        this.publishAt = null;
        this.createdAt = at;
        this.activityAt = at;
        this.updatedAt = null;
    }

    public boolean isPublished() {
        return status == PostStatus.PUBLISHED;
    }

    public void moveToTrash(Membership by, Instant at) {
        this.deletedAt = at;
        this.deletedBy = by;
    }

    public void restore() {
        this.deletedAt = null;
        this.deletedBy = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
