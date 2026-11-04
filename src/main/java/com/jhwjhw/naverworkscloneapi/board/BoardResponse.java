package com.jhwjhw.naverworkscloneapi.board;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardType;
import com.jhwjhw.naverworkscloneapi.board.domain.PostStatus;
import com.jhwjhw.naverworkscloneapi.chat.domain.ChatAttachmentKind;
import java.time.Instant;
import java.util.List;

public final class BoardResponse {
    private BoardResponse() {
    }

    // 사이드바의 게시판 한 줄. myCompany: 내 회사 게시판인지(관리자는 다른 회사 것도 받음).
    public record BoardItem(Long id, BoardType type, String name, Long companyId, String companyName,
            Long departmentId, boolean myCompany, boolean favorite, boolean canWrite, boolean canRename) {
    }

    public record Sidebar(List<BoardItem> boards, boolean admin) {
    }

    // 목록의 글 한 줄. important: 내가 중요 표시(깃발)한 글. companyName: 그 게시판의 회사, read: 내가 읽었는지, hasFile: 아래 첨부 파일이 있는지,
    // answered: 문의하기 답변 완료, reactionCount: 이모지 리액션을 남긴 사람 수,
    // matchText: 파일·댓글 검색일 때 검색어와 맞은 파일 이름·댓글 내용(그 밖에는 null).
    // daily: 매일 자동 게시글(증시 브리핑) — 화면이 제목을 포인트 색으로 그림.
    public record PostSummary(Long id, Long boardId, String boardName, BoardType boardType, String companyName,
            String title, String preview, Long authorMembershipId, String authorName, Instant createdAt, boolean important,
            boolean mustRead, boolean read, int viewCount, int commentCount, boolean hasFile, boolean answered,
            Instant deletedAt, PostStatus status, Instant publishAt, int reactionCount, String matchText,
            boolean daily, boolean system) {
    }

    // 검색 결과 탭별 건수(게시글: 제목·본문, 작성자, 파일, 댓글)
    public record SearchCounts(long post, long author, long file, long comment) {
    }

    // pinned: 게시판 첫 페이지에만 주는 중요 글(맨 위 고정, 검색 중이면 없음). page는 0부터.
    public record PostPage(List<PostSummary> pinned, List<PostSummary> items, int page, int totalPages,
            long totalElements) {
    }

    // 게시판 홈의 네 영역과 그 영역의 "더 보기"가 열 게시판
    public record Home(List<PostSummary> latest, List<PostSummary> inquiries, List<PostSummary> notices,
            List<PostSummary> free, Long noticeBoardId, Long freeBoardId, Long inquiryBoardId) {
    }

    public record Attachment(Long id, ChatAttachmentKind kind, String name, String contentType, long size,
            Integer width, Integer height, boolean inline) {
    }

    // parentId: 답글이면 맨 위 댓글 ID. deleted: 지웠지만 답글이 남아 자리만 보여 주는 댓글(글쓴이·내용 없음).
    public record Comment(Long id, Long parentId, Long authorMembershipId, String authorName, boolean authorAdmin,
            String content, Instant createdAt, boolean canDelete, boolean deleted) {
    }

    // 글 리액션 한 종류. mine: 내가 누른 이모지, names: 누른 사람(누른 순서)
    public record Reaction(String emoji, int count, boolean mine, List<String> names) {
    }

    // important: 내가 중요 표시(깃발)한 글
    public record PostDetail(Long id, BoardItem board, String title, String content, Long authorMembershipId,
            String authorName, String authorDepartmentName, Instant createdAt, Instant updatedAt, boolean important,
            boolean mustRead, int viewCount, Instant answeredAt, Instant deletedAt, long version,
            List<Attachment> attachments, List<Comment> comments, boolean canEdit, boolean canDelete,
            boolean canRestore, boolean canSeeReaders, boolean canComment, PostStatus status, Instant publishAt,
            boolean allowComment, boolean commentNotify, List<Reaction> reactions, boolean canReact,
            boolean daily, boolean system) {
    }

    // companyName: 공통 게시판은 여러 회사 사람이 섞이므로 함께 보여 줌
    public record Reader(Long membershipId, String name, String departmentName, String companyName) {
    }

    // 필독 읽음 확인: 읽은 사람과 아직 안 읽은 사람(그 게시판을 볼 수 있는 사용 중인 구성원 기준)
    public record Readers(List<Reader> read, List<Reader> unread) {
    }
}
