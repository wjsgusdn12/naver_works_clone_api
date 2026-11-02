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
    // TYPE(공지·자유·문의 같은 종류 전체, type 필요)·TRASH(휴지통)
    @GetMapping("/post/list")
    public ApiResponse<BoardResponse.PostPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam BoardService.View view, @RequestParam(required = false) Long boardId,
            @RequestParam(required = false) BoardType type, @RequestParam(required = false) Long cursor) {
        return ApiResponse.success("BOARD_POST_LIST_FOUND", "글 목록을 조회했습니다.",
                service.list(id(jwt), view, boardId, type, cursor));
    }

    // 글 보기. 처음 여는 것이면 읽음 기록·조회수 1.
    @GetMapping("/post/{postId}")
    public ApiResponse<BoardResponse.PostDetail> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable Long postId) {
        try {
            service.markRead(id(jwt), postId);
        } catch (DataIntegrityViolationException ignored) {
            // 같은 사람이 동시에 열어 이미 기록됨
        }
        return ApiResponse.success("BOARD_POST_FOUND", "글을 조회했습니다.", service.detail(id(jwt), postId));
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
