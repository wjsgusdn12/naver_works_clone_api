package com.jhwjhw.naverworkscloneapi.board;

import com.jhwjhw.naverworkscloneapi.board.domain.Board;
import com.jhwjhw.naverworkscloneapi.board.domain.BoardAttachment;
import com.jhwjhw.naverworkscloneapi.board.domain.BoardComment;
import com.jhwjhw.naverworkscloneapi.board.domain.BoardFavorite;
import com.jhwjhw.naverworkscloneapi.board.domain.BoardPost;
import com.jhwjhw.naverworkscloneapi.board.domain.BoardPostRead;
import com.jhwjhw.naverworkscloneapi.board.domain.BoardType;
import com.jhwjhw.naverworkscloneapi.board.domain.PostStatus;
import com.jhwjhw.naverworkscloneapi.board.repository.BoardAttachmentRepository;
import com.jhwjhw.naverworkscloneapi.board.repository.BoardCommentRepository;
import com.jhwjhw.naverworkscloneapi.board.repository.BoardFavoriteRepository;
import com.jhwjhw.naverworkscloneapi.board.repository.BoardPostReadRepository;
import com.jhwjhw.naverworkscloneapi.board.repository.BoardPostRepository;
import com.jhwjhw.naverworkscloneapi.board.repository.BoardRepository;
import com.jhwjhw.naverworkscloneapi.chat.ChatAttachmentStore;
import com.jhwjhw.naverworkscloneapi.chat.domain.ChatAttachmentKind;
import com.jhwjhw.naverworkscloneapi.identity.domain.Company;
import com.jhwjhw.naverworkscloneapi.identity.domain.Department;
import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import com.jhwjhw.naverworkscloneapi.identity.domain.Role;
import com.jhwjhw.naverworkscloneapi.identity.repository.CompanyRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.DepartmentRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.MembershipRepository;
import com.jhwjhw.naverworkscloneapi.notification.NotificationService;
import com.jhwjhw.naverworkscloneapi.notification.domain.NotificationCategory;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

// 게시판. 권한 규칙:
// - 관리자: 모든 회사·모든 부서 게시판을 보고 쓰고, 남의 글도 휴지통으로 옮길 수 있음. 회사·부서 게시판 이름 변경.
// - 일반: 내 회사의 공지·자유·문의·회사 게시판과 내 부서 게시판만. 공지사항은 읽기만.
// - 문의하기 글은 쓴 사람과 관리자만 봄. 관리자가 댓글을 달면 "답변 완료".
// - 글 수정은 쓴 사람만, 휴지통 옮기기는 쓴 사람·관리자, 복구는 옮긴 사람·관리자(30일 안).
@Service
@RequiredArgsConstructor
public class BoardService {
    static final int PAGE_SIZE = 20;
    static final int HOME_SIZE = 10;
    static final int PINNED_MAX = 20;
    static final int KEYWORD_MAX = 100;
    static final Duration SCHEDULE_MAX = Duration.ofDays(90);
    static final Duration ENSURE_INTERVAL = Duration.ofMinutes(10);
    // 구성원별 마지막 게시판 확인 시각(서버 메모리, 재시작하면 비워짐)
    private final Map<Long, Instant> ensuredAt = new java.util.concurrent.ConcurrentHashMap<>();
    static final List<BoardType> COMMON_TYPES = List.of(BoardType.NOTICE, BoardType.FREE, BoardType.INQUIRY);
    static final Duration TRASH_KEEP = Duration.ofDays(30);
    static final int MAX_ATTACHMENTS = 30;
    static final int UPLOADS_PER_MINUTE = 30;
    private static final int PREVIEW_LENGTH = 120;

    private final BoardRepository boardRepository;
    private final BoardPostRepository postRepository;
    private final BoardPostReadRepository readRepository;
    private final BoardCommentRepository commentRepository;
    private final BoardAttachmentRepository attachmentRepository;
    private final BoardFavoriteRepository favoriteRepository;
    private final MembershipRepository membershipRepository;
    private final CompanyRepository companyRepository;
    private final DepartmentRepository departmentRepository;
    private final BoardContent content;
    private final NotificationService notificationService;
    private final ChatAttachmentStore attachmentStore;

    // 목록 종류(사이드바 메뉴). TYPE: 볼 수 있는 게시판 중 한 종류(공지·자유·문의) 전체를 한 목록으로(관리자는 모든 회사).
    public enum View {
        // DRAFTS(임시저장함)·SCHEDULED(예약함): 내 글 중 그 상태만
        LATEST, MUST_READ, IMPORTANT, MINE, BOARD, TYPE, TRASH, DRAFTS, SCHEDULED
    }

    // 요청한 사람과 관리자 여부
    private record Viewer(Membership me, boolean admin) {
        Long id() {
            return me.getId();
        }
    }

    // ---------- 게시판 ----------

    // 내가 볼 부서 게시판과 공통 게시판 중 아직 없는 것을 만듦(가입·부서 추가 뒤 처음 열 때). 회사 게시판은 만들지 않음.
    // 다른 요청이 동시에 만들면 유일 제약 때문에 이쪽이 실패하는데, 컨트롤러가 그 실패를 무시하고 계속 진행.
    @Transactional
    public void ensureBoards(Long membershipId) {
        // 사이드바·홈을 열 때마다 부르므로, 같은 사람은 10분에 한 번만 확인(부서 추가는 늦어도 10분 안에 반영)
        Instant now = Instant.now();
        Instant checked = ensuredAt.get(membershipId);
        if (checked != null && checked.isAfter(now.minus(ENSURE_INTERVAL))) return;
        ensuredAt.put(membershipId, now);
        Viewer viewer = viewer(membershipId);
        List<Company> companies = viewer.admin() ? companyRepository.findAll() : List.of(viewer.me().getCompany());
        Set<Long> companyIds = companies.stream().map(Company::getId).collect(Collectors.toSet());
        List<Board> existing = viewer.admin() ? boardRepository.findAll()
                : boardRepository.findActiveByCompanyId(viewer.me().getCompany().getId());
        Set<Long> departmentIds = new HashSet<>();
        for (Board board : existing) {
            if (board.getDepartment() != null) departmentIds.add(board.getDepartment().getId());
        }
        // 공통 게시판(공지·자유·문의)은 회사와 상관없이 종류마다 하나. 하나도 없을 때만 만듦(회사 칸은 처음 만든 사람 회사).
        Set<BoardType> commonTypes = boardRepository.findByTypes(COMMON_TYPES).stream().map(Board::getType)
                .collect(Collectors.toSet());
        for (BoardType type : COMMON_TYPES) {
            if (!commonTypes.contains(type)) {
                Company company = viewer.me().getCompany();
                boardRepository.saveAndFlush(new Board(company, null, type, defaultName(type, company.getName())));
            }
        }
        List<Department> departments = viewer.admin() ? departmentRepository.findAllByOrderByNameAsc()
                : departmentRepository.findByCompanyIdOrderByNameAsc(viewer.me().getCompany().getId());
        for (Department department : departments) {
            if (!department.isEnabled() || departmentIds.contains(department.getId())
                    || !companyIds.contains(department.getCompany().getId())) {
                continue;
            }
            boardRepository.saveAndFlush(new Board(department.getCompany(), department, BoardType.DEPARTMENT,
                    truncate(department.getName(), 30)));
        }
    }

    private static String defaultName(BoardType type, String companyName) {
        return switch (type) {
            case NOTICE -> "공지사항";
            case FREE -> "자유게시판";
            case INQUIRY -> "문의하기";
            default -> truncate(companyName, 30);
        };
    }

    // 사이드바: 볼 수 있는 게시판 전체(즐겨찾기·쓰기·이름 변경 가능 여부 포함)
    @Transactional(readOnly = true)
    public BoardResponse.Sidebar sidebar(Long membershipId) {
        Viewer viewer = viewer(membershipId);
        Set<Long> favorites = new HashSet<>(favoriteRepository.findBoardIds(viewer.id()));
        List<Board> visible = visibleBoards(viewer);
        List<BoardResponse.BoardItem> items = visible.stream()
                .filter(board -> !board.getType().common() || board == primary(visible, board.getType()))
                .sorted(Comparator.comparing((Board board) -> !isMyCompany(viewer, board))
                        .thenComparing(board -> board.getCompany().getName())
                        .thenComparing(board -> board.getType().ordinal())
                        .thenComparing(Board::getName))
                .map(board -> boardItem(viewer, board, favorites.contains(board.getId())))
                .toList();
        return new BoardResponse.Sidebar(items, viewer.admin());
    }

    @Transactional
    public boolean toggleFavorite(Long membershipId, Long boardId) {
        Viewer viewer = viewer(membershipId);
        Board board = viewableBoard(viewer, boardId);
        var existing = favoriteRepository.findByMembershipIdAndBoardId(viewer.id(), board.getId());
        if (existing.isPresent()) {
            favoriteRepository.delete(existing.get());
            return false;
        }
        favoriteRepository.save(new BoardFavorite(viewer.me(), board));
        return true;
    }

    // 부서 게시판 이름 변경(관리자만)
    @Transactional
    public BoardResponse.BoardItem rename(Long membershipId, Long boardId, BoardRequest.Rename request) {
        Viewer viewer = viewer(membershipId);
        Board board = viewableBoard(viewer, boardId);
        if (!viewer.admin()) {
            throw new BoardException("BOARD_ADMIN_REQUIRED", "관리자만 게시판 이름을 바꿀 수 있습니다.");
        }
        if (!board.getType().renamable()) {
            throw new BoardException("BOARD_NOT_RENAMABLE", "부서 게시판만 이름을 바꿀 수 있습니다.");
        }
        String name = request.name().strip();
        if (name.isEmpty() || name.length() > 30) {
            throw new BoardException("BOARD_NAME_INVALID", "게시판 이름은 1~30자로 입력해 주세요.");
        }
        board.rename(name);
        boolean favorite = favoriteRepository.findByMembershipIdAndBoardId(viewer.id(), boardId).isPresent();
        return boardItem(viewer, board, favorite);
    }

    // ---------- 목록 ----------

    // 게시판 홈: 최신글·문의하기·공지사항·자유게시판 각 10개(화면에서 좌우 2열로 5줄씩)
    @Transactional(readOnly = true)
    public BoardResponse.Home home(Long membershipId) {
        Viewer viewer = viewer(membershipId);
        List<Board> boards = visibleBoards(viewer);
        Board notice = primary(boards, BoardType.NOTICE);
        Board free = primary(boards, BoardType.FREE);
        Board inquiry = primary(boards, BoardType.INQUIRY);
        // 관리자는 모든 문의를, 일반 사용자는 내 문의만(쿼리가 남의 문의를 뺌)
        List<Long> inquiryBoards = ofType(boards, BoardType.INQUIRY);
        List<BoardPost> latest = homeRows(viewer, ids(boards));
        List<BoardPost> inquiries = homeRows(viewer, inquiryBoards);
        List<BoardPost> notices = notice == null ? List.of() : homeRows(viewer, ofType(boards, BoardType.NOTICE));
        List<BoardPost> frees = free == null ? List.of() : homeRows(viewer, ofType(boards, BoardType.FREE));
        // 네 목록의 읽음·첨부 여부를 쿼리 한 번씩으로 함께 확인
        Map<Long, BoardResponse.PostSummary> byId = summaries(viewer, java.util.stream.Stream
                .of(latest, inquiries, notices, frees).flatMap(List::stream)
                .collect(Collectors.toMap(BoardPost::getId, Function.identity(), (a, b) -> a,
                        java.util.LinkedHashMap::new)).values().stream().toList())
                .stream().collect(Collectors.toMap(BoardResponse.PostSummary::id, Function.identity()));
        Function<List<BoardPost>, List<BoardResponse.PostSummary>> pick = rows -> rows.stream()
                .map(post -> byId.get(post.getId())).toList();
        return new BoardResponse.Home(pick.apply(latest), pick.apply(inquiries), pick.apply(notices), pick.apply(frees),
                notice == null ? null : notice.getId(), free == null ? null : free.getId(),
                inquiry == null ? null : inquiry.getId());
    }

    @Transactional(readOnly = true)
    public BoardResponse.PostPage list(Long membershipId, View view, Long boardId, Integer page) {
        return list(membershipId, view, boardId, null, page, null);
    }

    @Transactional(readOnly = true)
    public BoardResponse.PostPage list(Long membershipId, View view, Long boardId, BoardType type, Integer page) {
        return list(membershipId, view, boardId, type, page, null);
    }

    @Transactional(readOnly = true)
    public BoardResponse.PostPage list(Long membershipId, View view, Long boardId, BoardType type, Integer page,
            String keyword) {
        return list(membershipId, view, boardId, type, page, keyword, ListOptions.DEFAULT);
    }

    // 목록 정렬: CREATED(글 등록순), ACTIVITY(글/댓글 업데이트 순). 둘 다 최신이 위.
    public enum Sort {
        CREATED, ACTIVITY
    }

    // 목록 보기 설정. size: 한 쪽 글 수(10·20·30·40·50 중 하나, 아니면 20), unreadOnly: 안 읽은 글만.
    // 휴지통에는 쪽 크기만 적용.
    public record ListOptions(int size, Sort sort, boolean unreadOnly) {
        public static final ListOptions DEFAULT = new ListOptions(PAGE_SIZE, Sort.CREATED, false);

        public ListOptions {
            if (size < 10 || size > 50 || size % 10 != 0) size = PAGE_SIZE;
            if (sort == null) sort = Sort.CREATED;
        }
    }

    // page: 0부터. keyword가 있으면 제목·본문·글쓴이 이름으로 걸러 내고, 중요 글 고정 없이 한 목록으로 보여 줌.
    @Transactional(readOnly = true)
    public BoardResponse.PostPage list(Long membershipId, View view, Long boardId, BoardType type, Integer page,
            String keyword, ListOptions options) {
        Viewer viewer = viewer(membershipId);
        int pageIndex = page == null ? 0 : Math.max(page, 0);
        int size = options.size();
        String trimmed = keyword == null ? "" : keyword.strip();
        if (trimmed.length() > KEYWORD_MAX) trimmed = trimmed.substring(0, KEYWORD_MAX);
        Search search = new Search(!trimmed.isEmpty(), likePattern(trimmed));
        if (view == View.BOARD || view == View.TYPE) {
            List<Long> scope;
            if (view == View.BOARD) {
                if (boardId == null) throw new BoardException("BOARD_REQUIRED", "게시판을 골라 주세요.");
                Board board = viewableBoard(viewer, boardId);
                // 공통 게시판은 예전에 회사마다 만든 것까지 그 종류 전체가 한 게시판
                scope = board.getType().common() ? ofType(visibleBoards(viewer), board.getType())
                        : List.of(board.getId());
            } else {
                if (type == null) throw new BoardException("BOARD_REQUIRED", "게시판 종류를 골라 주세요.");
                scope = visibleBoards(viewer).stream().filter(board -> board.getType() == type).map(Board::getId)
                        .toList();
            }
            // 검색하지 않을 때 첫 페이지에만 중요 글을 맨 위 고정으로 따로 주고, 아래 목록에서는 뺌.
            boolean pin = !search.on();
            List<BoardResponse.PostSummary> pinned = pin && pageIndex == 0
                    ? summaries(viewer, find(viewer, scope, false, true, false, false, Search.NONE, options, 0,
                            PINNED_MAX).getContent())
                    : List.of();
            return page(viewer, pinned, find(viewer, scope, false, false, pin, false, search, options, pageIndex,
                    size));
        }
        if (view == View.DRAFTS || view == View.SCHEDULED) {
            PostStatus status = view == View.DRAFTS ? PostStatus.DRAFT : PostStatus.SCHEDULED;
            return page(viewer, List.of(), postRepository.findMine(viewer.id(), status, PageRequest.of(pageIndex, size)));
        }
        List<Long> boardIds = ids(visibleBoards(viewer));
        if (view == View.TRASH) {
            Page<BoardPost> rows = boardIds.isEmpty() ? Page.empty(PageRequest.of(pageIndex, size))
                    : postRepository.findTrash(boardIds, viewer.id(), viewer.admin(), Instant.now().minus(TRASH_KEEP),
                            search.on(), search.pattern(), PageRequest.of(pageIndex, size));
            return page(viewer, List.of(), rows);
        }
        return page(viewer, List.of(), find(viewer, boardIds, view == View.MUST_READ, view == View.IMPORTANT, false,
                view == View.MINE, search, options, pageIndex, size));
    }

    // on이 false면 검색하지 않음. pattern은 like에 바로 넣는 소문자 "%검색어%"(%·_·\는 글자 그대로 찾게 이스케이프).
    private record Search(boolean on, String pattern) {
        static final Search NONE = new Search(false, "");
    }

    private static String likePattern(String keyword) {
        String escaped = keyword.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private Page<BoardPost> find(Viewer viewer, List<Long> boardIds, boolean mustRead, boolean important,
            boolean excludeImportant, boolean mine, Search search, ListOptions options, int page, int size) {
        if (boardIds.isEmpty()) return Page.empty(PageRequest.of(page, size));
        return postRepository.findPage(boardIds, viewer.id(), viewer.admin(), BoardType.INQUIRY, mustRead, important,
                excludeImportant, mine, options.unreadOnly(), search.on(), search.pattern(),
                options.sort() == Sort.ACTIVITY, PageRequest.of(page, size));
    }

    // 게시판 홈 한 영역: 최신 HOME_SIZE개(전체 개수는 필요 없어 세지 않음)
    private List<BoardPost> homeRows(Viewer viewer, List<Long> boardIds) {
        if (boardIds.isEmpty()) return List.of();
        return postRepository.findPage(boardIds, viewer.id(), viewer.admin(), BoardType.INQUIRY, false, false, false,
                false, false, false, "", false, org.springframework.data.domain.Pageable.ofSize(HOME_SIZE)
                        .withPage(0)).getContent();
    }

    private BoardResponse.PostPage page(Viewer viewer, List<BoardResponse.PostSummary> pinned, Page<BoardPost> rows) {
        return new BoardResponse.PostPage(pinned, summaries(viewer, rows.getContent()), rows.getNumber(),
                rows.getTotalPages(), rows.getTotalElements());
    }

    // 글 줄 만들기. 읽음·첨부 여부는 페이지 전체를 쿼리 한 번씩으로 확인.
    private List<BoardResponse.PostSummary> summaries(Viewer viewer, List<BoardPost> posts) {
        if (posts.isEmpty()) return List.of();
        List<Long> postIds = posts.stream().map(BoardPost::getId).toList();
        Set<Long> read = new HashSet<>(readRepository.findReadPostIds(viewer.id(), postIds));
        Set<Long> withFiles = new HashSet<>(attachmentRepository.findPostIdsWithFiles(postIds));
        return posts.stream().map(post -> new BoardResponse.PostSummary(post.getId(), post.getBoard().getId(),
                post.getBoard().getName(), post.getBoard().getType(),
                post.getBoard().getType().common() ? "" : post.getBoard().getCompany().getName(),
                post.getTitle(), preview(post.getContentText()),
                post.getAuthor().getId(), post.getAuthor().getUserAccount().getDisplayName(), post.getCreatedAt(),
                post.isImportant(), post.isMustRead(), read.contains(post.getId()), post.getViewCount(),
                post.getCommentCount(), withFiles.contains(post.getId()), post.getAnsweredAt() != null,
                post.getDeletedAt(), post.getStatus(), post.getPublishAt())).toList();
    }

    private static String preview(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= PREVIEW_LENGTH ? oneLine : oneLine.substring(0, PREVIEW_LENGTH) + "…";
    }

    // ---------- 글 ----------

    // 글 열기: 처음 여는 것이면 읽음 기록·조회수 1을 남기고 상세를 돌려줌(한 트랜잭션, 같은 조회 반복 없음).
    // 같은 사람이 동시에 열어 유일 제약에 걸리면 컨트롤러가 detail()로 다시 읽음.
    @Transactional
    public BoardResponse.PostDetail open(Long membershipId, Long postId) {
        Viewer viewer = viewer(membershipId);
        BoardPost post = readablePost(viewer, postId);
        if (!post.isDeleted() && post.isPublished()
                && !readRepository.existsByPostIdAndMembershipId(postId, viewer.id())) {
            readRepository.saveAndFlush(new BoardPostRead(post, viewer.me()));
            postRepository.increaseViewCount(postId);
            // 조회수 변경을 반영해 다시 읽음(벌크 변경이 영속성 컨텍스트를 비움)
            post = readablePost(viewer, postId);
        }
        return detail(viewer, post);
    }

    @Transactional(readOnly = true)
    public BoardResponse.PostDetail detail(Long membershipId, Long postId) {
        Viewer viewer = viewer(membershipId);
        return detail(viewer, readablePost(viewer, postId));
    }

    private BoardResponse.PostDetail detail(Viewer viewer, BoardPost post) {
        Long postId = post.getId();
        boolean author = isAuthor(viewer, post);
        List<BoardResponse.Comment> comments = commentRepository.findVisibleByPostId(postId).stream()
                .map(comment -> new BoardResponse.Comment(comment.getId(), comment.getAuthor().getId(),
                        comment.getAuthor().getUserAccount().getDisplayName(),
                        comment.getAuthor().getRole() == Role.ADMIN, comment.getContent(), comment.getCreatedAt(),
                        !post.isDeleted() && (viewer.admin()
                                || comment.getAuthor().getId().equals(viewer.id()))))
                .toList();
        List<BoardResponse.Attachment> attachments = attachmentRepository.findByPostIdOrderById(postId).stream()
                .map(BoardService::attachment).toList();
        boolean favorite = favoriteRepository.findByMembershipIdAndBoardId(viewer.id(), post.getBoard().getId())
                .isPresent();
        Department department = post.getAuthor().getDepartment();
        return new BoardResponse.PostDetail(post.getId(), boardItem(viewer, post.getBoard(), favorite),
                post.getTitle(), post.getContentJson(), post.getAuthor().getId(),
                post.getAuthor().getUserAccount().getDisplayName(), department == null ? null : department.getName(),
                post.getCreatedAt(), post.getUpdatedAt(), post.isImportant(), post.isMustRead(), post.getViewCount(),
                post.getAnsweredAt(), post.getDeletedAt(), post.getVersion(), attachments, comments,
                author && !post.isDeleted(), !post.isDeleted() && (author || viewer.admin()),
                canRestore(viewer, post), post.isMustRead() && post.isPublished() && (author || viewer.admin()),
                !post.isDeleted() && post.isPublished() && post.isAllowComment(), post.getStatus(), post.getPublishAt(),
                post.isAllowComment(), post.isCommentNotify());
    }

    // 글을 처음 열면 읽음 기록과 조회수 1. 이미 읽었으면 아무 일 없음.
    // 같은 사람이 동시에 두 번 열면 유일 제약 때문에 한쪽이 실패하는데, 컨트롤러가 그 실패를 무시함.
    @Transactional
    public void markRead(Long membershipId, Long postId) {
        Viewer viewer = viewer(membershipId);
        BoardPost post = readablePost(viewer, postId);
        if (post.isDeleted() || !post.isPublished()
                || readRepository.existsByPostIdAndMembershipId(postId, viewer.id())) return;
        readRepository.saveAndFlush(new BoardPostRead(post, viewer.me()));
        postRepository.increaseViewCount(postId);
    }

    // 목록에서 고른 글을 한꺼번에 읽음으로(글을 연 것과 같음: 읽음 기록·조회수 1). 볼 수 없는 글이 섞이면 전체 실패.
    // 돌려주는 값은 새로 읽음 처리한 글 수(이미 읽었거나 휴지통 글은 건너뜀).
    @Transactional
    public int markReadAll(Long membershipId, List<Long> postIds) {
        Viewer viewer = viewer(membershipId);
        // 먼저 모두 볼 수 있는지 확인한 뒤에 기록(중간에 실패해 일부만 처리되는 일이 없게)
        List<BoardPost> posts = new LinkedHashSet<>(postIds).stream().map(id -> readablePost(viewer, id)).toList();
        int marked = 0;
        for (BoardPost post : posts) {
            if (post.isDeleted() || !post.isPublished()
                    || readRepository.existsByPostIdAndMembershipId(post.getId(), viewer.id())) continue;
            readRepository.saveAndFlush(new BoardPostRead(post, viewer.me()));
            postRepository.increaseViewCount(post.getId());
            marked++;
        }
        return marked;
    }

    @Transactional
    public BoardResponse.PostDetail create(Long membershipId, BoardRequest.PostCreate request) {
        Viewer viewer = viewer(membershipId);
        Board board = viewableBoard(viewer, request.boardId());
        if (!canWrite(viewer, board)) {
            throw new BoardException("BOARD_WRITE_FORBIDDEN", "이 게시판에는 글을 쓸 수 없습니다.");
        }
        // 공통 게시판 글은 그 종류의 대표 게시판(처음 만든 것)에 모음
        if (board.getType().common()) board = primary(visibleBoards(viewer), board.getType());
        BoardRequest.SaveMode mode = modeOf(request.mode());
        Instant publishAt = scheduleTime(mode, request.publishAt());
        BoardContent.Sanitized body = content.sanitize(request.content());
        boolean flags = board.getType() != BoardType.INQUIRY;
        BoardPost post = postRepository.save(new BoardPost(board, viewer.me(), title(request.title(), mode),
                body.json(), body.text(), flags && request.important(), flags && request.mustRead()));
        applyCommentOptions(post, request.allowComment(), request.commentNotify());
        attach(viewer, post, request.attachmentIds(), body.imageAttachmentIds());
        if (mode == BoardRequest.SaveMode.PUBLISH) notifyNewPost(viewer, post);
        else post.keepUnpublished(mode == BoardRequest.SaveMode.DRAFT ? PostStatus.DRAFT : PostStatus.SCHEDULED,
                publishAt);
        postRepository.flush();
        return detail(membershipId, post.getId());
    }

    @Transactional
    public BoardResponse.PostDetail update(Long membershipId, Long postId, BoardRequest.PostUpdate request) {
        Viewer viewer = viewer(membershipId);
        BoardPost post = readablePost(viewer, postId);
        if (post.isDeleted() || !isAuthor(viewer, post)) {
            throw new BoardException("BOARD_POST_AUTHOR_REQUIRED", "글쓴이만 수정할 수 있습니다.");
        }
        if (post.getVersion() != request.expectedVersion()) {
            throw new BoardException("BOARD_POST_CONFLICT", "다른 화면에서 글이 바뀌었습니다. 새로 불러온 뒤 다시 수정해 주세요.");
        }
        // 등록된 글은 계속 등록 상태. 임시저장·예약 글은 고르는 방식대로(바로 등록·임시저장·예약 변경).
        BoardRequest.SaveMode mode = post.isPublished() ? BoardRequest.SaveMode.PUBLISH : modeOf(request.mode());
        Instant publishAt = scheduleTime(mode, request.publishAt());
        BoardContent.Sanitized body = content.sanitize(request.content());
        boolean flags = post.getBoard().getType() != BoardType.INQUIRY;
        post.edit(title(request.title(), mode), body.json(), body.text(), flags && request.important(),
                flags && request.mustRead());
        applyCommentOptions(post, request.allowComment(), request.commentNotify());
        attach(viewer, post, request.attachmentIds(), body.imageAttachmentIds());
        if (!post.isPublished()) {
            if (mode == BoardRequest.SaveMode.PUBLISH) {
                post.publish(Instant.now());
                notifyNewPost(viewer, post);
            } else {
                post.keepUnpublished(mode == BoardRequest.SaveMode.DRAFT ? PostStatus.DRAFT : PostStatus.SCHEDULED,
                        publishAt);
            }
        }
        postRepository.flush();
        return detail(membershipId, postId);
    }

    // 휴지통으로 옮김(행은 그대로). 글쓴이·관리자.
    @Transactional
    public void delete(Long membershipId, Long postId) {
        Viewer viewer = viewer(membershipId);
        BoardPost post = readablePost(viewer, postId);
        if (post.isDeleted()) {
            throw new BoardException("BOARD_POST_ALREADY_DELETED", "이미 휴지통에 있는 글입니다.");
        }
        if (!isAuthor(viewer, post) && !viewer.admin()) {
            throw new BoardException("BOARD_POST_AUTHOR_REQUIRED", "글쓴이나 관리자만 지울 수 있습니다.");
        }
        post.moveToTrash(viewer.me(), Instant.now());
    }

    // 휴지통에서 되살림. 옮긴 사람·관리자, 옮긴 지 30일 안.
    @Transactional
    public void restore(Long membershipId, Long postId) {
        Viewer viewer = viewer(membershipId);
        BoardPost post = readablePost(viewer, postId);
        if (!canRestore(viewer, post)) {
            throw new BoardException("BOARD_POST_RESTORE_FORBIDDEN", "복구할 수 없는 글입니다.");
        }
        post.restore();
    }

    private boolean canRestore(Viewer viewer, BoardPost post) {
        return post.isDeleted() && post.getDeletedAt().isAfter(Instant.now().minus(TRASH_KEEP))
                && (viewer.admin() || post.getDeletedBy().getId().equals(viewer.id()));
    }

    // 필독 읽음 확인(글쓴이·관리자). 대상은 그 게시판을 볼 수 있는 사용 중인 구성원.
    @Transactional(readOnly = true)
    public BoardResponse.Readers readers(Long membershipId, Long postId) {
        Viewer viewer = viewer(membershipId);
        BoardPost post = readablePost(viewer, postId);
        if (!post.isMustRead() || !(isAuthor(viewer, post) || viewer.admin())) {
            throw new BoardException("BOARD_READERS_FORBIDDEN", "필독 글의 글쓴이나 관리자만 읽음 현황을 볼 수 있습니다.");
        }
        Board board = post.getBoard();
        List<Membership> targets = readersOf(board);
        Set<Long> readerIds = new HashSet<>(readRepository.findReaderIds(postId));
        List<BoardResponse.Reader> read = new ArrayList<>();
        List<BoardResponse.Reader> unread = new ArrayList<>();
        for (Membership target : targets) {
            Department department = target.getDepartment();
            BoardResponse.Reader reader = new BoardResponse.Reader(target.getId(),
                    target.getUserAccount().getDisplayName(), department == null ? null : department.getName(),
                    target.getCompany().getName());
            (readerIds.contains(target.getId()) ? read : unread).add(reader);
        }
        return new BoardResponse.Readers(read, unread);
    }

    // ---------- 댓글 ----------

    @Transactional
    public BoardResponse.PostDetail addComment(Long membershipId, Long postId, BoardRequest.CommentCreate request) {
        Viewer viewer = viewer(membershipId);
        BoardPost post = readablePost(viewer, postId);
        if (!post.isPublished()) {
            throw new BoardException("BOARD_POST_NOT_PUBLISHED", "등록된 글에만 댓글을 달 수 있습니다.");
        }
        if (!post.isAllowComment()) {
            throw new BoardException("BOARD_COMMENT_DISABLED", "댓글을 받지 않는 글입니다.");
        }
        if (post.isDeleted()) {
            throw new BoardException("BOARD_POST_ALREADY_DELETED", "휴지통에 있는 글에는 댓글을 달 수 없습니다.");
        }
        String text = request.content().strip();
        if (text.isEmpty()) throw new BoardException("BOARD_COMMENT_EMPTY", "댓글을 입력해 주세요.");
        commentRepository.save(new BoardComment(post, viewer.me(), text));
        boolean answer = post.getBoard().getType() == BoardType.INQUIRY && viewer.admin();
        postRepository.changeCommentCount(postId, 1);
        postRepository.touchActivity(postId, Instant.now());
        if (answer) postRepository.markAnswered(postId, Instant.now());
        notifyComment(viewer, post, text, answer);
        return detail(membershipId, postId);
    }

    // 댓글 허용·알림. 값이 없으면 켬, 문의하기는 답변이 댓글이라 항상 켬.
    private static void applyCommentOptions(BoardPost post, Boolean allowComment, Boolean commentNotify) {
        boolean inquiry = post.getBoard().getType() == BoardType.INQUIRY;
        post.changeCommentOptions(inquiry || allowComment == null || allowComment,
                inquiry || commentNotify == null || commentNotify);
    }

    private static BoardRequest.SaveMode modeOf(BoardRequest.SaveMode mode) {
        return mode == null ? BoardRequest.SaveMode.PUBLISH : mode;
    }

    // 예약이면 예약 시각 검사(1분 뒤 ~ 90일 안). 예약이 아니면 null.
    private static Instant scheduleTime(BoardRequest.SaveMode mode, Instant publishAt) {
        if (mode != BoardRequest.SaveMode.SCHEDULE) return null;
        Instant now = Instant.now();
        if (publishAt == null || publishAt.isBefore(now.plusSeconds(60)) || publishAt.isAfter(now.plus(SCHEDULE_MAX))) {
            throw new BoardException("BOARD_SCHEDULE_INVALID", "예약 시각은 1분 뒤부터 90일 안으로 골라 주세요.");
        }
        return publishAt;
    }

    // 예약 시각이 지난 글을 공개하고 공지·필독·문의 알림을 보냄(BoardPublishScheduler가 1분마다 부름).
    // 조건부 변경이라 서버가 여러 대여도 글마다 한 번만 공개·알림. 돌려주는 값은 이번에 공개한 글 수.
    @Transactional
    public int publishDueScheduled() {
        Instant now = Instant.now();
        int published = 0;
        for (Long postId : postRepository.findDueScheduledIds(now)) {
            if (postRepository.publishScheduled(postId, now) != 1) continue;
            BoardPost post = postRepository.findDetailById(postId).orElseThrow();
            notifyNewPost(new Viewer(post.getAuthor(), post.getAuthor().getRole() == Role.ADMIN), post);
            published++;
        }
        return published;
    }

    // ---------- 알림(시스템 분류, 누르면 그 글로) ----------

    // 새 글 알림: 문의하기는 관리자에게, 공지사항·필독 글은 그 게시판을 볼 수 있는 구성원에게(글쓴이 제외). 일반 글은 알림 없음.
    private void notifyNewPost(Viewer viewer, BoardPost post) {
        Board board = post.getBoard();
        String link = postLink(post);
        String writer = viewer.me().getUserAccount().getDisplayName();
        if (board.getType() == BoardType.INQUIRY) {
            for (Membership admin : membershipRepository.findEnabledByRole(Role.ADMIN)) {
                if (admin.getId().equals(viewer.id())) continue;
                notificationService.create(admin.getId(), NotificationCategory.SYSTEM, "새 문의",
                        writer + "님이 문의를 남겼습니다: " + post.getTitle(), link);
            }
            return;
        }
        if (board.getType() != BoardType.NOTICE && !post.isMustRead()) return;
        String title = board.getType() == BoardType.NOTICE ? "새 공지사항" : "새 필독 글";
        for (Membership reader : readersOf(board)) {
            if (reader.getId().equals(viewer.id())) continue;
            notificationService.create(reader.getId(), NotificationCategory.SYSTEM, title,
                    "[" + board.getName() + "] " + post.getTitle() + " - " + writer, link);
        }
    }

    // 내 글에 남이 댓글을 달면 글쓴이에게. 문의하기에 관리자가 달면 "문의 답변 완료".
    private void notifyComment(Viewer viewer, BoardPost post, String text, boolean answer) {
        Long authorId = post.getAuthor().getId();
        if (authorId.equals(viewer.id()) || !post.isCommentNotify()) return;
        String writer = viewer.me().getUserAccount().getDisplayName();
        notificationService.create(authorId, NotificationCategory.SYSTEM, answer ? "문의 답변 완료" : "새 댓글",
                writer + "님 (" + post.getTitle() + "): " + text, postLink(post));
    }

    // 그 게시판을 볼 수 있는 사용 중인 구성원: 공통은 전체, 부서는 그 부서, 회사는 그 회사.
    private List<Membership> readersOf(Board board) {
        if (board.getType().common()) return membershipRepository.findAllEnabled();
        return board.getDepartment() != null
                ? membershipRepository.findEnabledByDepartmentId(board.getDepartment().getId())
                : membershipRepository.findEnabledByCompanyId(board.getCompany().getId());
    }

    private static String postLink(BoardPost post) {
        return "/board?postId=" + post.getId();
    }

    @Transactional
    public BoardResponse.PostDetail deleteComment(Long membershipId, Long commentId) {
        Viewer viewer = viewer(membershipId);
        BoardComment comment = commentRepository.findById(commentId)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new BoardException("BOARD_COMMENT_NOT_FOUND", "댓글을 찾을 수 없습니다."));
        Long postId = comment.getPost().getId();
        BoardPost post = readablePost(viewer, postId);
        if (post.isDeleted() || !(viewer.admin() || comment.getAuthor().getId().equals(viewer.id()))) {
            throw new BoardException("BOARD_COMMENT_AUTHOR_REQUIRED", "댓글 쓴 사람이나 관리자만 지울 수 있습니다.");
        }
        comment.delete(Instant.now());
        postRepository.changeCommentCount(postId, -1);
        return detail(membershipId, postId);
    }

    // ---------- 첨부 ----------

    // 글을 쓰는 중에 먼저 올림(아직 글에 붙지 않음). 1분에 30개까지.
    @Transactional
    public BoardResponse.Attachment upload(Long membershipId, MultipartFile file) {
        Viewer viewer = viewer(membershipId);
        if (attachmentRepository.countByUploaderIdAndCreatedAtAfter(viewer.id(),
                Instant.now().minus(Duration.ofMinutes(1))) >= UPLOADS_PER_MINUTE) {
            throw new BoardException("BOARD_ATTACHMENT_RATE_LIMITED", "파일을 너무 자주 올렸습니다. 잠시 후 다시 시도해 주세요.");
        }
        ChatAttachmentStore.StoredFile stored = attachmentStore.store(file);
        BoardAttachment attachment = attachmentRepository.save(new BoardAttachment(viewer.me(), stored.kind(),
                stored.name(), stored.contentType(), stored.size(), stored.key(), stored.thumbnailKey(),
                stored.width(), stored.height()));
        return attachment(attachment);
    }

    public record AttachmentFile(BoardAttachment attachment, Resource resource, long size) {
    }

    // 내려받기. 글에 붙은 첨부는 그 글을 볼 수 있는 사람, 아직 안 붙은 첨부는 올린 사람만.
    @Transactional(readOnly = true)
    public AttachmentFile attachmentFile(Long membershipId, Long attachmentId, boolean thumbnail) {
        Viewer viewer = viewer(membershipId);
        BoardAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(BoardService::attachmentNotFound);
        if (attachment.getPost() == null) {
            if (!attachment.getUploader().getId().equals(viewer.id())) throw attachmentNotFound();
        } else {
            readablePost(viewer, attachment.getPost().getId());
        }
        String key = thumbnail && attachment.getThumbnailKey() != null ? attachment.getThumbnailKey()
                : attachment.getStorageKey();
        Resource resource = attachmentStore.load(key);
        try {
            if (resource == null || !resource.exists()) throw attachmentNotFound();
            return new AttachmentFile(attachment, resource, resource.contentLength());
        } catch (IOException exception) {
            throw attachmentNotFound();
        }
    }

    // 글에 붙일 첨부를 정리. 아래 목록용 파일(fileIds)과 본문 속 사진(imageIds) 모두 내가 올렸고
    // 아직 다른 글에 붙지 않은 것이어야 함. 이번에 빠진 기존 첨부는 떼어 냄(파일은 청소 작업이 정리).
    private void attach(Viewer viewer, BoardPost post, List<Long> fileIds, Set<Long> imageIds) {
        Set<Long> wanted = new LinkedHashSet<>(fileIds == null ? List.of() : fileIds.stream().filter(Objects::nonNull)
                .toList());
        wanted.addAll(imageIds);
        if (wanted.size() > MAX_ATTACHMENTS) {
            throw new BoardException("BOARD_ATTACHMENT_TOO_MANY", "첨부는 글 하나에 " + MAX_ATTACHMENTS + "개까지입니다.");
        }
        Map<Long, BoardAttachment> found = attachmentRepository.findAllById(wanted).stream()
                .collect(Collectors.toMap(BoardAttachment::getId, Function.identity()));
        for (Long id : wanted) {
            BoardAttachment attachment = found.get(id);
            boolean usable = attachment != null && attachment.getUploader().getId().equals(viewer.id())
                    && (attachment.getPost() == null || attachment.getPost().getId().equals(post.getId()));
            if (!usable) {
                throw new BoardException("BOARD_ATTACHMENT_INVALID", "첨부 파일을 다시 올려 주세요.");
            }
            if (imageIds.contains(id) && attachment.getKind() != ChatAttachmentKind.IMAGE) {
                throw new BoardException("BOARD_ATTACHMENT_INVALID", "본문에는 사진만 넣을 수 있습니다.");
            }
        }
        if (post.getId() != null) {
            for (BoardAttachment old : attachmentRepository.findByPostIdOrderById(post.getId())) {
                if (!wanted.contains(old.getId())) old.attachTo(null, false);
            }
        }
        for (Long id : wanted) found.get(id).attachTo(post, imageIds.contains(id));
    }

    private static BoardResponse.Attachment attachment(BoardAttachment attachment) {
        return new BoardResponse.Attachment(attachment.getId(), attachment.getKind(), attachment.getOriginalName(),
                attachment.getContentType(), attachment.getSize(), attachment.getWidth(), attachment.getHeight(),
                attachment.isInlineImage());
    }

    private static BoardException attachmentNotFound() {
        return new BoardException("BOARD_ATTACHMENT_NOT_FOUND", "첨부 파일을 찾을 수 없습니다.");
    }

    // ---------- 권한 ----------

    private Viewer viewer(Long membershipId) {
        Membership me = membershipRepository.findWithUserAccountById(membershipId)
                .orElseThrow(() -> new BoardException("BOARD_MEMBER_NOT_FOUND", "구성원을 찾을 수 없습니다."));
        if (!me.getUserAccount().isEnabled()) {
            throw new BoardException("BOARD_MEMBER_INACTIVE", "비활성 구성원은 게시판을 사용할 수 없습니다.");
        }
        return new Viewer(me, me.getRole() == Role.ADMIN);
    }

    private List<Board> visibleBoards(Viewer viewer) {
        if (viewer.admin()) return boardRepository.findAllActive().stream().filter(board -> canView(viewer, board)).toList();
        // 일반: 내 회사 게시판(부서는 내 부서만) + 공통 게시판 전체(다른 회사에서 예전에 만든 것 포함)
        return boardRepository.findActiveForCompanyOrTypes(viewer.me().getCompany().getId(), COMMON_TYPES).stream()
                .filter(board -> canView(viewer, board)).toList();
    }

    // 공통 게시판은 누구에게나 "내 게시판"(회사 이름을 붙이지 않음)
    private static boolean isMyCompany(Viewer viewer, Board board) {
        return board.getType().common() || board.getCompany().getId().equals(viewer.me().getCompany().getId());
    }

    // 회사는 경로일 뿐이라 예전 회사 게시판(COMPANY)은 아무도 못 봄. 공통 게시판은 누구나.
    // 부서 게시판: 관리자는 전부, 일반은 내 부서 것만.
    private static boolean canView(Viewer viewer, Board board) {
        if (board.getType() == BoardType.COMPANY) return false;
        if (viewer.admin() || board.getType().common()) return true;
        if (!isMyCompany(viewer, board)) return false;
        if (board.getDepartment() == null) return false;
        Department mine = viewer.me().getDepartment();
        return mine != null && mine.getId().equals(board.getDepartment().getId());
    }

    private static boolean canWrite(Viewer viewer, Board board) {
        return canView(viewer, board) && (board.getType() != BoardType.NOTICE || viewer.admin());
    }

    // 볼 수 없는 게시판은 있는지 없는지도 알려 주지 않음(같은 "찾을 수 없음").
    private Board viewableBoard(Viewer viewer, Long boardId) {
        Board board = boardRepository.findWithScopeById(boardId)
                .filter(found -> found.getDepartment() == null || found.getDepartment().isEnabled())
                .filter(found -> canView(viewer, found))
                .orElseThrow(() -> new BoardException("BOARD_NOT_FOUND", "게시판을 찾을 수 없습니다."));
        return board;
    }

    // 읽을 수 있는 글. 휴지통 글은 복구할 수 있는 사람(옮긴 사람·관리자)만.
    private BoardPost readablePost(Viewer viewer, Long postId) {
        BoardPost post = postRepository.findDetailById(postId)
                .orElseThrow(BoardService::postNotFound);
        Board board = post.getBoard();
        boolean boardVisible = (board.getDepartment() == null || board.getDepartment().isEnabled())
                && canView(viewer, board);
        boolean inquiryHidden = board.getType() == BoardType.INQUIRY && !viewer.admin() && !isAuthor(viewer, post);
        boolean trashHidden = post.isDeleted() && !viewer.admin()
                && !post.getDeletedBy().getId().equals(viewer.id());
        // 임시저장·예약 글은 쓴 사람만(관리자도 못 봄)
        boolean unpublishedHidden = !post.isPublished() && !isAuthor(viewer, post);
        if (!boardVisible || inquiryHidden || trashHidden || unpublishedHidden) throw postNotFound();
        return post;
    }

    private static boolean isAuthor(Viewer viewer, BoardPost post) {
        return post.getAuthor().getId().equals(viewer.id());
    }

    private static BoardException postNotFound() {
        return new BoardException("BOARD_POST_NOT_FOUND", "글을 찾을 수 없습니다.");
    }

    private BoardResponse.BoardItem boardItem(Viewer viewer, Board board, boolean favorite) {
        return new BoardResponse.BoardItem(board.getId(), board.getType(), board.getName(), board.getCompany().getId(),
                board.getCompany().getName(), board.getDepartment() == null ? null : board.getDepartment().getId(),
                isMyCompany(viewer, board), favorite, canWrite(viewer, board),
                viewer.admin() && board.getType().renamable());
    }

    // 공통 게시판 한 종류의 대표(가장 먼저 만든 것). 새 글은 여기로 모이고 사이드바에도 이것만 보임.
    private static Board primary(List<Board> boards, BoardType type) {
        return boards.stream().filter(board -> board.getType() == type).min(Comparator.comparing(Board::getId))
                .orElse(null);
    }

    // 한 종류의 게시판 id 전부(공통 게시판은 예전 회사별 것까지 한 게시판으로 묶음)
    private static List<Long> ofType(List<Board> boards, BoardType type) {
        return boards.stream().filter(board -> board.getType() == type).map(Board::getId).toList();
    }

    private static List<Long> ids(List<Board> boards) {
        return boards.stream().map(Board::getId).toList();
    }

    // 임시저장은 제목을 비워 둘 수 있음(200자 제한만). 등록·예약은 1~200자.
    private static String title(String raw, BoardRequest.SaveMode mode) {
        String title = raw == null ? "" : raw.strip();
        if (mode == BoardRequest.SaveMode.DRAFT) {
            if (title.length() > 200) throw new BoardException("BOARD_TITLE_INVALID", "제목은 200자까지 입력할 수 있습니다.");
            return title;
        }
        if (title.isEmpty() || title.length() > 200) {
            throw new BoardException("BOARD_TITLE_INVALID", "제목은 1~200자로 입력해 주세요.");
        }
        return title;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
