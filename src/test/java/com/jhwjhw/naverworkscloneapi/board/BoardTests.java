package com.jhwjhw.naverworkscloneapi.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardType;
import com.jhwjhw.naverworkscloneapi.identity.domain.Company;
import com.jhwjhw.naverworkscloneapi.identity.domain.Department;
import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import com.jhwjhw.naverworkscloneapi.identity.domain.Role;
import com.jhwjhw.naverworkscloneapi.identity.domain.UserAccount;
import com.jhwjhw.naverworkscloneapi.identity.repository.CompanyRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.DepartmentRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.MembershipRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.UserAccountRepository;
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
// 게시판: 권한(회사·부서·관리자·문의하기), 휴지통, 필독 읽음, 본문 검사, 첨부, 이름 변경. 모두 가상 데이터.
class BoardTests {
    private static final String PNG = "\u0089PNG\r\n\u001a\n";

    @Autowired private BoardService service;
    @Autowired private BoardContent content;
    @Autowired private CompanyRepository companies;
    @Autowired private DepartmentRepository departments;
    @Autowired private UserAccountRepository accounts;
    @Autowired private MembershipRepository memberships;

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
                .filter(item -> item.type() == type && item.companyName().equals(companyName))
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
    void usersSeeOnlyTheirCompanyAndDepartmentBoardsWhileAdminSeesAll() {
        var mine = service.sidebar(me.getId());
        assertThat(mine.admin()).isFalse();
        assertThat(mine.boards()).extracting(BoardResponse.BoardItem::name)
                .containsExactlyInAnyOrder("공지사항", "자유게시판", "문의하기", "가상 게시판 회사", "영업팀");
        // 공지사항은 읽기만, 이름 변경은 관리자만
        assertThat(board(me, BoardType.NOTICE, company.getName()).canWrite()).isFalse();
        assertThat(mine.boards()).noneMatch(BoardResponse.BoardItem::canRename);

        var all = service.sidebar(admin.getId());
        assertThat(all.admin()).isTrue();
        assertThat(all.boards()).extracting(BoardResponse.BoardItem::name)
                .contains("영업팀", "개발팀", "다른 회사 팀", "가상 다른 회사");
        // 내 회사 게시판이 먼저
        assertThat(all.boards().getFirst().myCompany()).isTrue();

        // 다른 부서·다른 회사 게시판은 ID를 알아도 없는 것처럼
        Long devBoard = department(admin, dev).id();
        Long otherFree = board(admin, BoardType.FREE, "가상 다른 회사").id();
        assertThatThrownBy(() -> service.list(me.getId(), BoardService.View.BOARD, devBoard, null))
                .isInstanceOf(BoardException.class).hasMessage("게시판을 찾을 수 없습니다.");
        assertThatThrownBy(() -> write(me, otherFree, "남의 회사"))
                .isInstanceOf(BoardException.class).hasMessage("게시판을 찾을 수 없습니다.");
        assertThatThrownBy(() -> write(me, board(me, BoardType.NOTICE, company.getName()).id(), "공지"))
                .isInstanceOf(BoardException.class).hasMessage("이 게시판에는 글을 쓸 수 없습니다.");

        // 관리자는 어느 회사 게시판에도 씀
        var adminPost = write(admin, otherFree, "관리자 글");
        assertThat(service.list(outsider.getId(), BoardService.View.LATEST, null, null).items())
                .extracting(BoardResponse.PostSummary::id).containsExactly(adminPost.id());
        assertThat(service.list(me.getId(), BoardService.View.LATEST, null, null).items()).isEmpty();
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
        // 대상은 그 회사의 사용 중인 구성원(다른 회사 사람은 없음)
        assertThat(readers.unread()).extracting(BoardResponse.Reader::name).containsExactlyInAnyOrder("나", "관리자");
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
