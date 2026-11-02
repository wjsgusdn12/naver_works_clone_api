package com.jhwjhw.naverworkscloneapi.board;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardAttachment;
import com.jhwjhw.naverworkscloneapi.board.domain.BoardType;
import com.jhwjhw.naverworkscloneapi.chat.domain.ChatAttachmentKind;
import com.jhwjhw.naverworkscloneapi.common.response.ApiResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/board")
@RequiredArgsConstructor
public class BoardController {
    private final BoardService service;

    // JWT에서 구성원 ID를 추출하는 메서드
    private Long id(Jwt jwt) {
        return jwt.getClaim("membershipId");
    }

    // 없는 게시판을 먼저 만듦. 다른 요청이 동시에 만들어 실패하면 이미 생긴 것이므로 무시.
    private void ensureBoards(Long membershipId) {
        try {
            service.ensureBoards(membershipId);
        } catch (DataIntegrityViolationException ignored) {
            // 다른 요청이 같은 게시판을 먼저 만듦
        }
    }

    // 사이드바: 볼 수 있는 게시판 목록(즐겨찾기·쓰기·이름 변경 가능 여부 포함)
    @GetMapping("/sidebar")
    public ApiResponse<BoardResponse.Sidebar> sidebar(@AuthenticationPrincipal Jwt jwt) {
        ensureBoards(id(jwt));
        return ApiResponse.success("BOARD_SIDEBAR_FOUND", "게시판 목록을 조회했습니다.", service.sidebar(id(jwt)));
    }

    // 게시판 홈: 최신글·문의하기·공지사항·자유게시판
    @GetMapping("/home")
    public ApiResponse<BoardResponse.Home> home(@AuthenticationPrincipal Jwt jwt) {
        ensureBoards(id(jwt));
        return ApiResponse.success("BOARD_HOME_FOUND", "게시판 홈을 조회했습니다.", service.home(id(jwt)));
    }

    // 글 목록. view: LATEST(최신글)·MUST_READ(필독)·IMPORTANT(중요)·MINE(내 게시글)·BOARD(게시판, boardId 필요)·
    // TYPE(공지·자유·문의 같은 종류 전체, type 필요)·TRASH(휴지통). page는 0부터, keyword는 제목·본문·글쓴이 검색.
    // size: 한 쪽 글 수(10~50, 10 단위), sort: CREATED(등록순)·ACTIVITY(글/댓글 업데이트 순), unread: 안 읽은 글만.
    @GetMapping("/post/list")
    public ApiResponse<BoardResponse.PostPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam BoardService.View view, @RequestParam(required = false) Long boardId,
            @RequestParam(required = false) BoardType type, @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "CREATED") BoardService.Sort sort,
            @RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(defaultValue = "ALL") BoardService.SearchScope scope) {
        return ApiResponse.success("BOARD_POST_LIST_FOUND", "글 목록을 조회했습니다.", service.list(id(jwt), view,
                boardId, type, page, keyword, new BoardService.ListOptions(size, sort, unread), scope));
    }

    // 검색 결과 화면 탭별 건수(게시글·작성자·파일·댓글)
    @GetMapping("/post/search/counts")
    public ApiResponse<BoardResponse.SearchCounts> searchCounts(@AuthenticationPrincipal Jwt jwt,
            @RequestParam String keyword) {
        return ApiResponse.success("BOARD_SEARCH_COUNTS_FOUND", "검색 결과 건수를 조회했습니다.",
                service.searchCounts(id(jwt), keyword));
    }

    // 글 보기. 처음 여는 것이면 읽음 기록·조회수 1.
    @GetMapping("/post/{postId}")
    public ApiResponse<BoardResponse.PostDetail> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable Long postId) {
        BoardResponse.PostDetail detail;
        try {
            detail = service.open(id(jwt), postId);
        } catch (DataIntegrityViolationException ignored) {
            // 같은 사람이 동시에 열어 이미 기록됨 → 읽음 기록 없이 다시 읽음
            detail = service.detail(id(jwt), postId);
        }
        return ApiResponse.success("BOARD_POST_FOUND", "글을 조회했습니다.", detail);
    }

    // 목록에서 고른 글을 한꺼번에 읽음으로. 결과는 새로 읽음 처리한 글 수.
    @PostMapping("/post/read")
    public ApiResponse<Integer> markRead(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody BoardRequest.MarkRead request) {
        int marked;
        try {
            marked = service.markReadAll(id(jwt), request.postIds());
        } catch (DataIntegrityViolationException ignored) {
            // 같은 글을 다른 탭에서 동시에 열어 이미 기록됨. 한 번 더 하면 남은 글만 처리됨.
            marked = service.markReadAll(id(jwt), request.postIds());
        }
        return ApiResponse.success("BOARD_POST_READ", "읽음으로 표시했습니다.", marked);
    }

    @PostMapping("/post/create")
    public ApiResponse<BoardResponse.PostDetail> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody BoardRequest.PostCreate request) {
        return ApiResponse.success("BOARD_POST_CREATED", "글을 등록했습니다.", service.create(id(jwt), request));
    }

    @PostMapping("/post/update/{postId}")
    public ApiResponse<BoardResponse.PostDetail> update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long postId,
            @Valid @RequestBody BoardRequest.PostUpdate request) {
        return ApiResponse.success("BOARD_POST_UPDATED", "글을 수정했습니다.", service.update(id(jwt), postId, request));
    }

    // 휴지통으로 옮김(실제로 지우지 않음)
    @PostMapping("/post/delete/{postId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long postId) {
        service.delete(id(jwt), postId);
        return ApiResponse.success("BOARD_POST_DELETED", "글을 휴지통으로 옮겼습니다.", null);
    }

    @PostMapping("/post/restore/{postId}")
    public ApiResponse<Void> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable Long postId) {
        service.restore(id(jwt), postId);
        return ApiResponse.success("BOARD_POST_RESTORED", "글을 복구했습니다.", null);
    }

    // 필독 읽음 현황(글쓴이·관리자)
    @GetMapping("/post/{postId}/readers")
    public ApiResponse<BoardResponse.Readers> readers(@AuthenticationPrincipal Jwt jwt, @PathVariable Long postId) {
        return ApiResponse.success("BOARD_READERS_FOUND", "읽음 현황을 조회했습니다.", service.readers(id(jwt), postId));
    }

    @PostMapping("/comment/create")
    public ApiResponse<BoardResponse.PostDetail> addComment(@AuthenticationPrincipal Jwt jwt,
            @RequestParam Long postId, @Valid @RequestBody BoardRequest.CommentCreate request) {
        return ApiResponse.success("BOARD_COMMENT_CREATED", "댓글을 등록했습니다.",
                service.addComment(id(jwt), postId, request));
    }

    // 중요 표시(개인 깃발) 켜기·끄기. 바뀐 상태(true: 표시됨)를 돌려줌.
    @PostMapping("/post/important")
    public ApiResponse<Boolean> toggleImportant(@AuthenticationPrincipal Jwt jwt, @RequestParam Long postId) {
        return ApiResponse.success("BOARD_IMPORTANT_CHANGED", "중요 표시를 바꿨습니다.",
                service.toggleImportant(id(jwt), postId));
    }

    // 글 리액션 누르기·바꾸기·취소. 바뀐 상세를 돌려줌.
    @PostMapping("/post/reaction")
    public ApiResponse<BoardResponse.PostDetail> react(@AuthenticationPrincipal Jwt jwt, @RequestParam Long postId,
            @Valid @RequestBody BoardRequest.React request) {
        return ApiResponse.success("BOARD_REACTION_CHANGED", "리액션을 반영했습니다.",
                service.react(id(jwt), postId, request.emoji()));
    }

    @PostMapping("/comment/delete/{commentId}")
    public ApiResponse<BoardResponse.PostDetail> deleteComment(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long commentId) {
        return ApiResponse.success("BOARD_COMMENT_DELETED", "댓글을 삭제했습니다.",
                service.deleteComment(id(jwt), commentId));
    }

    @PostMapping("/favorite/toggle")
    public ApiResponse<Boolean> toggleFavorite(@AuthenticationPrincipal Jwt jwt, @RequestParam Long boardId) {
        return ApiResponse.success("BOARD_FAVORITE_TOGGLED", "즐겨찾기를 바꿨습니다.",
                service.toggleFavorite(id(jwt), boardId));
    }

    // 회사·부서 게시판 이름 변경(관리자)
    @PostMapping("/rename")
    public ApiResponse<BoardResponse.BoardItem> rename(@AuthenticationPrincipal Jwt jwt, @RequestParam Long boardId,
            @Valid @RequestBody BoardRequest.Rename request) {
        return ApiResponse.success("BOARD_RENAMED", "게시판 이름을 바꿨습니다.", service.rename(id(jwt), boardId, request));
    }

    // 글쓰기 중 첨부·본문 사진 올리기(저장 전까지는 올린 사람만 볼 수 있음)
    @PostMapping("/attachment/upload")
    public ApiResponse<BoardResponse.Attachment> upload(@AuthenticationPrincipal Jwt jwt,
            @RequestParam MultipartFile file) {
        return ApiResponse.success("BOARD_ATTACHMENT_UPLOADED", "파일을 올렸습니다.", service.upload(id(jwt), file));
    }

    // 첨부 내려받기. inline=true면 사진을 화면 표시용으로, thumbnail=true면 작은 사본(없으면 원본).
    @GetMapping("/attachment/download")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal Jwt jwt, @RequestParam Long attachmentId,
            @RequestParam(defaultValue = "false") boolean inline,
            @RequestParam(defaultValue = "false") boolean thumbnail) {
        BoardService.AttachmentFile file = service.attachmentFile(id(jwt), attachmentId, inline && thumbnail);
        BoardAttachment attachment = file.attachment();
        boolean showInline = inline && attachment.getKind() == ChatAttachmentKind.IMAGE;
        // 일반 파일은 형식을 octet-stream으로 고정해 브라우저가 HTML 등으로 열어 실행하지 못하게 함.
        MediaType type = showInline ? MediaType.parseMediaType(attachment.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        ContentDisposition disposition = (showInline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(attachment.getOriginalName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .body(file.resource());
    }
}
