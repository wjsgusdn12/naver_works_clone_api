package com.jhwjhw.naverworkscloneapi.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jhwjhw.naverworkscloneapi.board.domain.Board;
import com.jhwjhw.naverworkscloneapi.board.domain.BoardPost;
import com.jhwjhw.naverworkscloneapi.board.domain.BoardType;
import com.jhwjhw.naverworkscloneapi.board.domain.PostStatus;
import com.jhwjhw.naverworkscloneapi.board.repository.BoardPostRepository;
import com.jhwjhw.naverworkscloneapi.board.repository.BoardRepository;
import com.jhwjhw.naverworkscloneapi.identity.domain.Company;
import com.jhwjhw.naverworkscloneapi.identity.domain.Department;
import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import com.jhwjhw.naverworkscloneapi.identity.domain.Role;
import com.jhwjhw.naverworkscloneapi.identity.domain.UserAccount;
import com.jhwjhw.naverworkscloneapi.identity.repository.CompanyRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.DepartmentRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.MembershipRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.UserAccountRepository;
import com.jhwjhw.naverworkscloneapi.notification.NotificationResponse;
import com.jhwjhw.naverworkscloneapi.notification.NotificationService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:boardTest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@Transactional
// 게시판: 권한(공통 게시판·회사·부서·관리자·문의하기), 휴지통, 필독 읽음, 본문 검사, 첨부, 이름 변경. 모두 가상 데이터.
class BoardTests {
    private static final String PNG = "\u0089PNG\r\n\u001a\n";

    @Autowired private BoardService service;
    @Autowired private BoardContent content;
    @Autowired private CompanyRepository companies;
    @Autowired private DepartmentRepository departments;
    @Autowired private UserAccountRepository accounts;
    @Autowired private MembershipRepository memberships;
    @Autowired private NotificationService notifications;
    @Autowired private BoardRepository boards;
    @Autowired private BoardPostRepository posts;

    private Company company;
    private Department sales;
    private Department dev;
    private Membership me;
    private Membership colleague;
    private Membership developer;
    private Membership admin;
    private Membership outsider;

    @BeforeEach
    void setUp() {
        company = companies.save(new Company("가상 게시판 회사"));
        Company other = companies.save(new Company("가상 다른 회사"));
        sales = departments.save(new Department(company, "영업팀"));
        dev = departments.save(new Department(company, "개발팀"));
        Department otherTeam = departments.save(new Department(other, "다른 회사 팀"));
        me = member(company, sales, "board-me@example.com", "나", Role.USER);
        colleague = member(company, sales, "board-colleague@example.com", "같은 팀", Role.USER);
        developer = member(company, dev, "board-dev@example.com", "개발자", Role.USER);
        admin = member(company, null, "board-admin@example.com", "관리자", Role.ADMIN);
        outsider = member(other, otherTeam, "board-outsider@example.com", "다른 회사 사람", Role.USER);
        for (Membership member : List.of(me, colleague, developer, admin, outsider)) {
            service.ensureBoards(member.getId());
        }
    }

    private Membership member(Company company, Department department, String email, String name, Role role) {
        UserAccount account = accounts.save(new UserAccount(email, "test-hash", name));
        return memberships.save(new Membership(company, account, department, role));
    }

    private BoardResponse.BoardItem board(Membership viewer, BoardType type, String companyName) {
        return service.sidebar(viewer.getId()).boards().stream()
                // 공통 게시판(공지·자유·문의)은 회사와 상관없이 하나
                .filter(item -> item.type() == type && (type.common() || item.companyName().equals(companyName)))
                .findFirst().orElseThrow();
    }

    private BoardResponse.BoardItem department(Membership viewer, Department department) {
        return service.sidebar(viewer.getId()).boards().stream()
                .filter(item -> department.getId().equals(item.departmentId())).findFirst().orElseThrow();
    }

    private static String doc(String text) {
        return "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\""
                + text + "\"}]}]}";
    }

    private BoardResponse.PostDetail write(Membership author, Long boardId, String title) {
        return service.create(author.getId(),
                new BoardRequest.PostCreate(boardId, title, doc(title + " 본문"), false, false, List.of()));
    }

    private List<Long> ids(BoardResponse.PostPage page) {
        return page.items().stream().map(BoardResponse.PostSummary::id).toList();
    }

    @Test
    void usersSeeCommonBoardsAndOnlyTheirCompanyAndDepartmentBoardsWhileAdminSeesAll() {
        var mine = service.sidebar(me.getId());
        assertThat(mine.admin()).isFalse();
        assertThat(mine.boards()).extracting(BoardResponse.BoardItem::name)
                .containsExactlyInAnyOrder("공지사항", "자유게시판", "문의하기", "영업팀");
        // 공지사항은 읽기만, 이름 변경은 관리자만
        assertThat(board(me, BoardType.NOTICE, company.getName()).canWrite()).isFalse();
        assertThat(mine.boards()).noneMatch(BoardResponse.BoardItem::canRename);
        // 다른 회사 사람도 같은 공통 게시판 + 자기 부서 게시판. 회사는 경로일 뿐 게시판이 아님
        assertThat(service.sidebar(outsider.getId()).boards()).extracting(BoardResponse.BoardItem::name)
                .containsExactlyInAnyOrder("공지사항", "자유게시판", "문의하기", "다른 회사 팀");

        var all = service.sidebar(admin.getId());
        assertThat(all.admin()).isTrue();
        assertThat(all.boards()).extracting(BoardResponse.BoardItem::name)
                .contains("영업팀", "개발팀", "다른 회사 팀")
                .doesNotContain("가상 게시판 회사", "가상 다른 회사");
        assertThat(all.boards()).filteredOn(item -> item.type() == BoardType.NOTICE).hasSize(1);

        // 다른 부서·다른 회사 게시판은 ID를 알아도 없는 것처럼
        Long devBoard = department(admin, dev).id();
        Long otherCompanyBoard = department(admin, outsider.getDepartment()).id();
        assertThatThrownBy(() -> service.list(me.getId(), BoardService.View.BOARD, devBoard, null))
                .isInstanceOf(BoardException.class).hasMessage("게시판을 찾을 수 없습니다.");
        assertThatThrownBy(() -> write(me, otherCompanyBoard, "남의 회사"))
                .isInstanceOf(BoardException.class).hasMessage("게시판을 찾을 수 없습니다.");
        assertThatThrownBy(() -> write(me, board(me, BoardType.NOTICE, company.getName()).id(), "공지"))
                .isInstanceOf(BoardException.class).hasMessage("이 게시판에는 글을 쓸 수 없습니다.");

        // 관리자는 어느 회사 부서 게시판에도 씀
        var adminPost = write(admin, otherCompanyBoard, "관리자 글");
        assertThat(service.list(outsider.getId(), BoardService.View.LATEST, null, null).items())
                .extracting(BoardResponse.PostSummary::id).containsExactly(adminPost.id());
        assertThat(service.list(me.getId(), BoardService.View.LATEST, null, null).items()).isEmpty();
    }

    @Test
    void commonBoardsAreSharedByEveryoneAndOldPerCompanyBoardsAreMerged() {
        Long free = board(me, BoardType.FREE, company.getName()).id();
        var notice = service.create(admin.getId(), new BoardRequest.PostCreate(
                board(admin, BoardType.NOTICE, company.getName()).id(), "전체 공지", doc("x"), true, false,
                List.of()));
        var outsiderPost = write(outsider, free, "다른 회사 사람 글");

        // 다른 회사 사람도 같은 공지·자유게시판을 봄, 회사 이름은 붙이지 않음
        var outsiderNotice = service.list(outsider.getId(), BoardService.View.TYPE, null, BoardType.NOTICE, null);
        assertThat(outsiderNotice.pinned()).extracting(BoardResponse.PostSummary::id).containsExactly(notice.id());
        var myFree = service.list(me.getId(), BoardService.View.BOARD, free, null);
        assertThat(ids(myFree)).containsExactly(outsiderPost.id());
        assertThat(myFree.items().getFirst().companyName()).isEmpty();

        // 예전에 회사마다 만든 자유게시판의 글도 한 게시판으로 보이고, 그 게시판에 쓴 새 글은 대표 게시판으로
        Board legacy = boards.save(new Board(companies.findById(outsider.getCompany().getId()).orElseThrow(), null,
                BoardType.FREE, "자유게시판"));
        var legacyPost = posts.save(new BoardPost(legacy, outsider, "예전 글", doc("예전"), "예전", false, false));
        assertThat(ids(service.list(me.getId(), BoardService.View.BOARD, free, null)))
                .containsExactly(legacyPost.getId(), outsiderPost.id());
        var moved = write(outsider, legacy.getId(), "새 글");
        assertThat(moved.board().id()).isEqualTo(free);
        assertThat(service.sidebar(me.getId()).boards()).filteredOn(item -> item.type() == BoardType.FREE)
                .extracting(BoardResponse.BoardItem::id).containsExactly(free);
        assertThatThrownBy(() -> service.list(me.getId(), BoardService.View.TYPE, null, null, null))
                .isInstanceOf(BoardException.class);
    }

    @Test
    void departmentPostsStayInsideTheDepartment() {
        var post = write(me, department(me, sales).id(), "영업팀 회의");
        assertThat(ids(service.list(colleague.getId(), BoardService.View.LATEST, null, null)))
                .containsExactly(post.id());
        assertThat(service.list(developer.getId(), BoardService.View.LATEST, null, null).items()).isEmpty();
        assertThatThrownBy(() -> service.detail(developer.getId(), post.id()))
                .isInstanceOf(BoardException.class).hasMessage("글을 찾을 수 없습니다.");
        assertThat(service.detail(admin.getId(), post.id()).title()).isEqualTo("영업팀 회의");
    }

    @Test
    void inquiriesAreVisibleOnlyToTheirAuthorAndAdminsAndAdminCommentAnswers() {
        Long inquiry = board(me, BoardType.INQUIRY, company.getName()).id();
        var question = service.create(me.getId(),
                new BoardRequest.PostCreate(inquiry, "비밀번호 문의", doc("문의"), true, true, List.of()));
        // 문의하기 글에는 중요·필독을 걸 수 없음
        assertThat(question.important()).isFalse();
        assertThat(question.mustRead()).isFalse();

        assertThat(service.list(colleague.getId(), BoardService.View.BOARD, inquiry, null).items()).isEmpty();
        assertThatThrownBy(() -> service.detail(colleague.getId(), question.id()))
                .isInstanceOf(BoardException.class).hasMessage("글을 찾을 수 없습니다.");
        assertThat(service.home(admin.getId()).inquiries()).extracting(BoardResponse.PostSummary::id)
                .containsExactly(question.id());

        // 본인 댓글은 답변이 아님, 관리자 댓글이 달리면 답변 완료
        service.addComment(me.getId(), question.id(), new BoardRequest.CommentCreate("추가 질문"));
        assertThat(service.home(me.getId()).inquiries().getFirst().answered()).isFalse();
        var answered = service.addComment(admin.getId(), question.id(), new BoardRequest.CommentCreate("답변입니다"));
        assertThat(answered.answeredAt()).isNotNull();
        assertThat(answered.comments()).extracting(BoardResponse.Comment::authorAdmin).containsExactly(false, true);
        var mine = service.home(me.getId()).inquiries().getFirst();
        assertThat(mine.answered()).isTrue();
        assertThat(mine.commentCount()).isEqualTo(2);
    }

    @Test
    void trashKeepsPostsAndOnlyTheDeleterOrAdminRestores() {
        Long free = board(me, BoardType.FREE, company.getName()).id();
        var post = write(me, free, "지울 글");
        assertThatThrownBy(() -> service.delete(colleague.getId(), post.id()))
                .isInstanceOf(BoardException.class).hasMessage("글쓴이나 관리자만 지울 수 있습니다.");
        service.delete(me.getId(), post.id());

        assertThat(service.list(me.getId(), BoardService.View.LATEST, null, null).items()).isEmpty();
        assertThat(ids(service.list(me.getId(), BoardService.View.TRASH, null, null))).containsExactly(post.id());
        assertThat(service.list(colleague.getId(), BoardService.View.TRASH, null, null).items()).isEmpty();
        assertThat(ids(service.list(admin.getId(), BoardService.View.TRASH, null, null))).containsExactly(post.id());
        // 휴지통 글은 옮긴 사람·관리자만 열 수 있고, 수정·댓글은 막힘
        assertThatThrownBy(() -> service.detail(colleague.getId(), post.id())).isInstanceOf(BoardException.class);
        var trashed = service.detail(me.getId(), post.id());
        assertThat(trashed.canRestore()).isTrue();
        assertThat(trashed.canEdit()).isFalse();
        assertThatThrownBy(() -> service.addComment(me.getId(), post.id(), new BoardRequest.CommentCreate("x")))
                .isInstanceOf(BoardException.class);

        service.restore(me.getId(), post.id());
        assertThat(ids(service.list(colleague.getId(), BoardService.View.LATEST, null, null)))
                .containsExactly(post.id());
        assertThat(service.list(me.getId(), BoardService.View.TRASH, null, null).items()).isEmpty();

        // 관리자는 남의 글도 휴지통으로
        service.delete(admin.getId(), post.id());
        assertThatThrownBy(() -> service.restore(me.getId(), post.id())).isInstanceOf(BoardException.class);
        service.restore(admin.getId(), post.id());
    }

    @Test
    void viewsAreCountedOncePerPersonAndMustReadShowsReaders() {
        Long free = board(me, BoardType.FREE, company.getName()).id();
        var post = service.create(me.getId(),
                new BoardRequest.PostCreate(free, "필독 공지", doc("꼭 읽기"), true, true, List.of()));
        service.markRead(colleague.getId(), post.id());
        service.markRead(colleague.getId(), post.id());
        service.markRead(developer.getId(), post.id());
        assertThat(service.detail(me.getId(), post.id()).viewCount()).isEqualTo(2);

        var readers = service.readers(me.getId(), post.id());
        assertThat(readers.read()).extracting(BoardResponse.Reader::name).containsExactlyInAnyOrder("같은 팀", "개발자");
        // 자유게시판은 프로젝트 전체 게시판이라 대상은 회사와 상관없이 사용 중인 구성원 전체(회사 이름 함께)
        assertThat(readers.unread()).extracting(BoardResponse.Reader::name)
                .containsExactlyInAnyOrder("나", "관리자", "다른 회사 사람");
        assertThat(readers.unread()).filteredOn(reader -> reader.name().equals("다른 회사 사람"))
                .extracting(BoardResponse.Reader::companyName).containsExactly("가상 다른 회사");
        assertThatThrownBy(() -> service.readers(colleague.getId(), post.id())).isInstanceOf(BoardException.class);

        // 목록: 필독·중요 모아보기, 게시판에서는 중요 글이 맨 위 고정(아래 목록에는 중복 안 됨)
        var normal = write(colleague, free, "일반 글");
        assertThat(ids(service.list(developer.getId(), BoardService.View.MUST_READ, null, null)))
                .containsExactly(post.id());
        assertThat(ids(service.list(developer.getId(), BoardService.View.IMPORTANT, null, null)))
                .containsExactly(post.id());
        var boardPage = service.list(developer.getId(), BoardService.View.BOARD, free, null);
        assertThat(boardPage.pinned()).extracting(BoardResponse.PostSummary::id).containsExactly(post.id());
        assertThat(ids(boardPage)).containsExactly(normal.id());
        assertThat(boardPage.pinned().getFirst().read()).isTrue();
        assertThat(ids(service.list(colleague.getId(), BoardService.View.MINE, null, null)))
                .containsExactly(normal.id());
    }

    @Test
    void noticesMustReadInquiriesAndCommentsNotifyTheRightPeople() {
        // 공지사항: 전체 사용자(글쓴이 제외)
        Long notice = board(admin, BoardType.NOTICE, company.getName()).id();
        write(admin, notice, "가상 공지");
        assertThat(titles(me)).containsExactly("새 공지사항");
        assertThat(titles(developer)).containsExactly("새 공지사항");
        assertThat(titles(admin)).isEmpty();
        // 공지사항은 프로젝트 전체 게시판이라 다른 회사 사람도 받음
        assertThat(titles(outsider)).containsExactly("새 공지사항");

        // 일반 글은 알림 없음, 부서 게시판 필독 글은 그 부서 사람에게만
        Long free = board(me, BoardType.FREE, company.getName()).id();
        var normal = write(me, free, "일반 글");
        service.create(me.getId(), new BoardRequest.PostCreate(department(me, sales).id(), "영업팀 필독",
                doc("필독"), false, true, List.of()));
        assertThat(titles(colleague)).containsExactly("새 필독 글", "새 공지사항");
        assertThat(titles(developer)).containsExactly("새 공지사항");

        // 내 글에 남이 댓글 → 글쓴이에게, 내 댓글은 알림 없음
        service.addComment(colleague.getId(), normal.id(), new BoardRequest.CommentCreate("좋아요"));
        service.addComment(me.getId(), normal.id(), new BoardRequest.CommentCreate("감사합니다"));
        assertThat(titles(me)).containsExactly("새 댓글", "새 공지사항");
        assertThat(titles(colleague)).containsExactly("새 필독 글", "새 공지사항");

        // 새 문의 → 관리자, 관리자 답변 → 문의한 사람에게 "문의 답변 완료"
        Long inquiry = board(me, BoardType.INQUIRY, company.getName()).id();
        var question = write(me, inquiry, "가상 문의");
        assertThat(titles(admin)).containsExactly("새 문의");
        service.addComment(admin.getId(), question.id(), new BoardRequest.CommentCreate("답변입니다"));
        assertThat(titles(me).getFirst()).isEqualTo("문의 답변 완료");
        assertThat(notifications.list(me.getId(), null).getFirst().linkPath())
                .isEqualTo("/board?postId=" + question.id());
    }

    private List<String> titles(Membership member) {
        return notifications.list(member.getId(), null).stream().map(NotificationResponse.Item::title).toList();
    }

    @Test
    void draftsAndScheduledPostsStayWithTheAuthorUntilPublished() {
        // 임시저장: 제목 없이도 저장, 목록·남(관리자 포함)에게는 안 보이고 내 임시저장함에만
        Long free = board(me, BoardType.FREE, company.getName()).id();
        var draft = service.create(me.getId(), new BoardRequest.PostCreate(free, "", doc("쓰는 중"), false, false,
                List.of(), BoardRequest.SaveMode.DRAFT, null));
        assertThat(draft.status()).isEqualTo(PostStatus.DRAFT);
        assertThat(service.list(colleague.getId(), BoardService.View.LATEST, null, null).items()).isEmpty();
        assertThatThrownBy(() -> service.detail(admin.getId(), draft.id())).isInstanceOf(BoardException.class);
        assertThat(ids(service.list(me.getId(), BoardService.View.DRAFTS, null, null))).containsExactly(draft.id());
        assertThat(service.list(colleague.getId(), BoardService.View.DRAFTS, null, null).items()).isEmpty();

        // 이어 써서 등록(등록에는 제목이 필요)
        assertThatThrownBy(() -> service.update(me.getId(), draft.id(), new BoardRequest.PostUpdate("", doc("완성"),
                false, false, List.of(), draft.version(), BoardRequest.SaveMode.PUBLISH, null)))
                .isInstanceOf(BoardException.class);
        var published = service.update(me.getId(), draft.id(), new BoardRequest.PostUpdate("이어 쓴 글", doc("완성"),
                false, false, List.of(), draft.version(), BoardRequest.SaveMode.PUBLISH, null));
        assertThat(published.status()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(ids(service.list(colleague.getId(), BoardService.View.LATEST, null, null)))
                .containsExactly(draft.id());
        assertThat(service.list(me.getId(), BoardService.View.DRAFTS, null, null).items()).isEmpty();

        // 등록예약: 시각 검사, 예약함에만, 때가 되기 전에는 공개·알림 없음
        Long notice = board(admin, BoardType.NOTICE, company.getName()).id();
        assertThatThrownBy(() -> service.create(admin.getId(), new BoardRequest.PostCreate(notice, "예약 공지",
                doc("x"), false, false, List.of(), BoardRequest.SaveMode.SCHEDULE, Instant.now())))
                .isInstanceOf(BoardException.class);
        var scheduled = service.create(admin.getId(), new BoardRequest.PostCreate(notice, "예약 공지", doc("x"),
                false, false, List.of(), BoardRequest.SaveMode.SCHEDULE, Instant.now().plus(Duration.ofHours(1))));
        assertThat(scheduled.status()).isEqualTo(PostStatus.SCHEDULED);
        assertThat(ids(service.list(admin.getId(), BoardService.View.SCHEDULED, null, null)))
                .containsExactly(scheduled.id());
        assertThat(service.list(me.getId(), BoardService.View.TYPE, null, BoardType.NOTICE, null).items()).isEmpty();
        assertThat(titles(me)).doesNotContain("새 공지사항");
        assertThat(service.publishDueScheduled()).isZero();

        // 예약 시각이 지나면 한 번만 공개되고 그때 알림
        posts.findById(scheduled.id()).orElseThrow()
                .keepUnpublished(PostStatus.SCHEDULED, Instant.now().minusSeconds(1));
        posts.flush();
        assertThat(service.publishDueScheduled()).isEqualTo(1);
        assertThat(service.publishDueScheduled()).isZero();
        assertThat(ids(service.list(me.getId(), BoardService.View.TYPE, null, BoardType.NOTICE, null)))
                .containsExactly(scheduled.id());
        assertThat(titles(me)).contains("새 공지사항");
    }

    @Test
    void commentsCanBeTurnedOffAndCommentNotificationsSilenced() {
        Long free = board(me, BoardType.FREE, company.getName()).id();
        var closed = service.create(me.getId(), new BoardRequest.PostCreate(free, "댓글 막은 글", doc("x"), false,
                false, List.of(), null, null, false, true));
        assertThat(closed.allowComment()).isFalse();
        assertThat(closed.canComment()).isFalse();
        assertThatThrownBy(() -> service.addComment(colleague.getId(), closed.id(),
                new BoardRequest.CommentCreate("댓글"))).isInstanceOf(BoardException.class);

        // 댓글 알림을 끄면 댓글은 달리지만 글쓴이에게 알림 없음
        var quiet = service.create(me.getId(), new BoardRequest.PostCreate(free, "조용한 글", doc("x"), false, false,
                List.of(), null, null, true, false));
        service.addComment(colleague.getId(), quiet.id(), new BoardRequest.CommentCreate("좋아요"));
        assertThat(titles(me)).doesNotContain("새 댓글");

        // 문의하기는 설정과 상관없이 댓글·알림이 켜짐(관리자 답변이 댓글)
        Long inquiry = board(me, BoardType.INQUIRY, company.getName()).id();
        var question = service.create(me.getId(), new BoardRequest.PostCreate(inquiry, "문의", doc("x"), false, false,
                List.of(), null, null, false, false));
        assertThat(question.allowComment()).isTrue();
        service.addComment(admin.getId(), question.id(), new BoardRequest.CommentCreate("답변"));
        assertThat(titles(me)).contains("문의 답변 완료");
    }

    @Test
    void checkedPostsCanBeMarkedReadTogether() {
        Long free = board(me, BoardType.FREE, company.getName()).id();
        var first = write(me, free, "첫 글");
        var second = write(me, free, "둘째 글");
        service.markRead(colleague.getId(), first.id());

        // 이미 읽은 글은 건너뛰고, 중복 id도 한 번만
        assertThat(service.markReadAll(colleague.getId(), List.of(first.id(), second.id(), second.id())))
                .isEqualTo(1);
        assertThat(service.list(colleague.getId(), BoardService.View.BOARD, free, null).items())
                .allMatch(BoardResponse.PostSummary::read);
        assertThat(service.detail(me.getId(), second.id()).viewCount()).isEqualTo(1);

        // 볼 수 없는 글이 섞이면 아무것도 처리하지 않음
        Long inquiry = board(me, BoardType.INQUIRY, company.getName()).id();
        var secret = write(me, inquiry, "내 문의");
        var third = write(me, free, "셋째 글");
        assertThatThrownBy(() -> service.markReadAll(colleague.getId(), List.of(third.id(), secret.id())))
                .isInstanceOf(BoardException.class);
        assertThat(service.detail(me.getId(), third.id()).viewCount()).isZero();
    }

    @Test
    void listCanChangePageSizeSortByActivityAndShowOnlyUnread() throws InterruptedException {
        Long free = board(me, BoardType.FREE, company.getName()).id();
        var old = write(me, free, "오래된 글");
        Thread.sleep(5);
        var recent = write(me, free, "새 글");
        Thread.sleep(5);
        // 오래된 글에 댓글이 달리면 업데이트 순에서는 맨 위
        service.addComment(colleague.getId(), old.id(), new BoardRequest.CommentCreate("댓글"));

        var created = new BoardService.ListOptions(20, BoardService.Sort.CREATED, false);
        var activity = new BoardService.ListOptions(20, BoardService.Sort.ACTIVITY, false);
        assertThat(ids(service.list(developer.getId(), BoardService.View.BOARD, free, null, 0, null, created)))
                .containsExactly(recent.id(), old.id());
        assertThat(ids(service.list(developer.getId(), BoardService.View.BOARD, free, null, 0, null, activity)))
                .containsExactly(old.id(), recent.id());

        // 안 읽은 글만
        service.markRead(developer.getId(), recent.id());
        var unread = new BoardService.ListOptions(20, BoardService.Sort.CREATED, true);
        assertThat(ids(service.list(developer.getId(), BoardService.View.LATEST, null, null, 0, null, unread)))
                .containsExactly(old.id());

        // 쪽 크기: 10 단위만, 아니면 20
        for (int index = 0; index < 10; index++) write(me, free, "채우기 " + index);
        var ten = new BoardService.ListOptions(10, BoardService.Sort.CREATED, false);
        var page = service.list(developer.getId(), BoardService.View.LATEST, null, null, 0, null, ten);
        assertThat(page.items()).hasSize(10);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(new BoardService.ListOptions(15, null, false).size()).isEqualTo(BoardService.PAGE_SIZE);
    }

    @Test
    void searchMatchesTitleBodyOrAuthorAndPagesAreNumbered() {
        Long free = board(me, BoardType.FREE, company.getName()).id();
        var important = service.create(me.getId(),
                new BoardRequest.PostCreate(free, "회식 안내", doc("금요일 저녁"), true, false, List.of()));
        var byBody = service.create(colleague.getId(),
                new BoardRequest.PostCreate(free, "공지", doc("다음 주 회식 장소"), false, false, List.of()));
        var other = write(colleague, free, "100% 할인_쿠폰");

        // 검색 중에는 중요 글도 고정 없이 한 목록에, 대소문자·앞뒤 공백 무시
        var found = service.list(developer.getId(), BoardService.View.BOARD, free, null, 0, "  회식 ");
        assertThat(found.pinned()).isEmpty();
        assertThat(ids(found)).containsExactly(byBody.id(), important.id());
        // 글쓴이 이름으로도 찾음
        assertThat(ids(service.list(developer.getId(), BoardService.View.LATEST, null, null, 0, "같은 팀")))
                .containsExactly(other.id(), byBody.id());
        // %·_는 글자 그대로
        assertThat(ids(service.list(developer.getId(), BoardService.View.LATEST, null, null, 0, "0% 할인_")))
                .containsExactly(other.id());
        // 이스케이프하지 않으면 "%회"가 "회식" 글들과 맞아 버림
        assertThat(service.list(developer.getId(), BoardService.View.LATEST, null, null, 0, "%회").items()).isEmpty();

        // 쪽 번호: 20개씩
        for (int index = 0; index < BoardService.PAGE_SIZE; index++) write(me, free, "채우기 " + index);
        var first = service.list(developer.getId(), BoardService.View.LATEST, null, null, 0, null);
        var second = service.list(developer.getId(), BoardService.View.LATEST, null, null, 1, null);
        assertThat(first.totalElements()).isEqualTo(BoardService.PAGE_SIZE + 3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.items()).hasSize(BoardService.PAGE_SIZE);
        assertThat(second.page()).isEqualTo(1);
        assertThat(ids(second)).containsExactly(other.id(), byBody.id(), important.id());
    }

    @Test
    void editingNeedsTheLatestVersionButCommentsDoNotChangeIt() {
        Long free = board(me, BoardType.FREE, company.getName()).id();
        var post = write(me, free, "처음 제목");
        service.addComment(colleague.getId(), post.id(), new BoardRequest.CommentCreate("댓글"));
        var edited = service.update(me.getId(), post.id(),
                new BoardRequest.PostUpdate("바꾼 제목", doc("바꾼 본문"), false, false, List.of(), post.version()));
        assertThat(edited.title()).isEqualTo("바꾼 제목");
        assertThat(edited.updatedAt()).isNotNull();
        assertThatThrownBy(() -> service.update(me.getId(), post.id(),
                new BoardRequest.PostUpdate("또", doc("또"), false, false, List.of(), post.version())))
                .isInstanceOf(BoardException.class).extracting("code").isEqualTo("BOARD_POST_CONFLICT");
        assertThatThrownBy(() -> service.update(colleague.getId(), post.id(),
                new BoardRequest.PostUpdate("남의 글", doc("x"), false, false, List.of(), edited.version())))
                .isInstanceOf(BoardException.class).hasMessage("글쓴이만 수정할 수 있습니다.");
    }

    @Test
    void contentKeepsOnlyAllowedNodesMarksAndSafeLinks() {
        String raw = "{\"type\":\"doc\",\"content\":[{\"type\":\"heading\",\"attrs\":{\"level\":9,\"onclick\":\"x\"},"
                + "\"content\":[{\"type\":\"text\",\"text\":\"제목\",\"marks\":[{\"type\":\"bold\"},{\"type\":\"evil\"}]}]},"
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"나쁜 링크\",\"marks\":[{\"type\":\"link\","
                + "\"attrs\":{\"href\":\"javascript:alert(1)\"}}]},{\"type\":\"text\",\"text\":\" 좋은 링크\",\"marks\":"
                + "[{\"type\":\"link\",\"attrs\":{\"href\":\"https://example.com\",\"target\":\"_self\"}}]}]}]}";
        var result = content.sanitize(raw);
        assertThat(result.json()).doesNotContain("onclick", "evil", "javascript", "_self")
                .contains("\"level\":3", "\"bold\"", "https://example.com");
        assertThat(result.text()).isEqualTo("제목\n나쁜 링크 좋은 링크");

        assertThatThrownBy(() -> content.sanitize("{\"type\":\"doc\",\"content\":[{\"type\":\"script\"}]}"))
                .isInstanceOf(BoardException.class).hasMessage("본문 형식이 올바르지 않습니다.");
        assertThatThrownBy(() -> content.sanitize("not json")).isInstanceOf(BoardException.class);
        assertThat(BoardContent.safeHref("java\tscript:alert(1)")).isFalse();
        assertThat(BoardContent.safeHref("mailto:someone@example.com")).isTrue();
    }

    @Test
    void attachmentsMustBeMineAndInlineImagesAreSeparatedFromFiles() {
        Long free = board(me, BoardType.FREE, company.getName()).id();
        var image = service.upload(me.getId(), new MockMultipartFile("file", "사진.png", "image/png",
                (PNG + "fake").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        var file = service.upload(me.getId(),
                new MockMultipartFile("file", "자료.txt", "text/plain", "가상 자료".getBytes()));
        var othersFile = service.upload(colleague.getId(),
                new MockMultipartFile("file", "남의 자료.txt", "text/plain", "x".getBytes()));
        // 아직 글에 안 붙은 첨부는 올린 사람만 받음
        assertThatThrownBy(() -> service.attachmentFile(me.getId(), othersFile.id(), false))
                .isInstanceOf(BoardException.class);

        String body = "{\"type\":\"doc\",\"content\":[{\"type\":\"image\",\"attrs\":{\"attachmentId\":" + image.id()
                + ",\"src\":\"https://evil.example/x.png\"}}]}";
        var post = service.create(me.getId(),
                new BoardRequest.PostCreate(free, "사진 글", body, false, false, List.of(file.id())));
        assertThat(post.content()).doesNotContain("evil");
        assertThat(post.attachments()).extracting(BoardResponse.Attachment::id, BoardResponse.Attachment::inline)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(image.id(), true),
                        org.assertj.core.groups.Tuple.tuple(file.id(), false));
        assertThat(service.list(colleague.getId(), BoardService.View.LATEST, null, null).items().getFirst().hasFile())
                .isTrue();
        // 글에 붙으면 그 글을 볼 수 있는 사람이 받음
        assertThat(service.attachmentFile(colleague.getId(), file.id(), false).size()).isPositive();

        // 남이 올린 첨부, 다른 글에 이미 붙은 첨부는 못 붙임
        assertThatThrownBy(() -> service.create(me.getId(),
                new BoardRequest.PostCreate(free, "남의 첨부", doc("x"), false, false, List.of(othersFile.id()))))
                .isInstanceOf(BoardException.class).hasMessage("첨부 파일을 다시 올려 주세요.");
        assertThatThrownBy(() -> service.create(me.getId(),
                new BoardRequest.PostCreate(free, "재사용", doc("x"), false, false, List.of(file.id()))))
                .isInstanceOf(BoardException.class);

        // 수정에서 뺀 첨부는 떨어져 나감
        var edited = service.update(me.getId(), post.id(),
                new BoardRequest.PostUpdate("사진 글", body, false, false, List.of(), post.version()));
        assertThat(edited.attachments()).extracting(BoardResponse.Attachment::id).containsExactly(image.id());
    }

    @Test
    void adminRenamesCompanyAndDepartmentBoardsAndFavoritesAreToggled() {
        Long departmentBoard = department(admin, sales).id();
        var renamed = service.rename(admin.getId(), departmentBoard, new BoardRequest.Rename("  영업 소통방 "));
        assertThat(renamed.name()).isEqualTo("영업 소통방");
        assertThatThrownBy(() -> service.rename(me.getId(), departmentBoard, new BoardRequest.Rename("x")))
                .isInstanceOf(BoardException.class).hasMessage("관리자만 게시판 이름을 바꿀 수 있습니다.");
        Long notice = board(admin, BoardType.NOTICE, company.getName()).id();
        assertThatThrownBy(() -> service.rename(admin.getId(), notice, new BoardRequest.Rename("x")))
                .isInstanceOf(BoardException.class);

        assertThat(service.toggleFavorite(me.getId(), departmentBoard)).isTrue();
        assertThat(department(me, sales).favorite()).isTrue();
        assertThat(service.toggleFavorite(me.getId(), departmentBoard)).isFalse();
        assertThat(department(me, sales).favorite()).isFalse();
    }

    @Test
    void newDepartmentGetsItsBoardOnNextVisit() {
        Department newTeam = departments.save(new Department(company, "새 팀"));
        Membership newcomer = member(company, newTeam, "board-new@example.com", "새 사람", Role.USER);
        service.ensureBoards(newcomer.getId());
        assertThat(department(newcomer, newTeam).name()).isEqualTo("새 팀");
        // 한 번 더 불러도 중복으로 만들지 않음
        service.ensureBoards(newcomer.getId());
        assertThat(service.sidebar(admin.getId()).boards())
                .filteredOn(item -> newTeam.getId().equals(item.departmentId())).hasSize(1);
    }
}
