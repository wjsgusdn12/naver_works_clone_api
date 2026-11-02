package com.jhwjhw.naverworkscloneapi.board;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardType;
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

    // 목록의 글 한 줄. read: 내가 읽었는지, hasFile: 아래 첨부 파일이 있는지, answered: 문의하기 답변 완료.
    public record PostSummary(Long id, Long boardId, String boardName, BoardType boardType, String title,
            String preview, Long authorMembershipId, String authorName, Instant createdAt, boolean important,
            boolean mustRead, boolean read, int viewCount, int commentCount, boolean hasFile, boolean answered,
            Instant deletedAt) {
    }

    // pinned: 게시판 첫 페이지에만 주는 중요 글(맨 위 고정). nextCursor가 null이면 마지막 페이지.
    public record PostPage(List<PostSummary> pinned, List<PostSummary> items, Long nextCursor) {
    }

    // 게시판 홈의 네 영역과 그 영역의 "더 보기"가 열 게시판
    public record Home(List<PostSummary> latest, List<PostSummary> inquiries, List<PostSummary> notices,
            List<PostSummary> free, Long noticeBoardId, Long freeBoardId, Long inquiryBoardId) {
    }

    public record Attachment(Long id, ChatAttachmentKind kind, String name, String contentType, long size,
            Integer width, Integer height, boolean inline) {
    }

    public record Comment(Long id, Long authorMembershipId, String authorName, boolean authorAdmin, String content,
            Instant createdAt, boolean canDelete) {
    }

    public record PostDetail(Long id, BoardItem board, String title, String content, Long authorMembershipId,
            String authorName, String authorDepartmentName, Instant createdAt, Instant updatedAt, boolean important,
            boolean mustRead, int viewCount, Instant answeredAt, Instant deletedAt, long version,
            List<Attachment> attachments, List<Comment> comments, boolean canEdit, boolean canDelete,
            boolean canRestore, boolean canSeeReaders, boolean canComment) {
    }

    public record Reader(Long membershipId, String name, String departmentName) {
    }

    // 필독 읽음 확인: 읽은 사람과 아직 안 읽은 사람(그 게시판을 볼 수 있는 사용 중인 구성원 기준)
    public record Readers(List<Reader> read, List<Reader> unread) {
    }
}
