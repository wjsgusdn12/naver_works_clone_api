package com.jhwjhw.naverworkscloneapi.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardPost;
import com.jhwjhw.naverworkscloneapi.board.domain.BoardType;
import com.jhwjhw.naverworkscloneapi.board.repository.BoardDailyGreetingRepository;
import com.jhwjhw.naverworkscloneapi.board.repository.BoardPostRepository;
import com.jhwjhw.naverworkscloneapi.identity.domain.Company;
import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import com.jhwjhw.naverworkscloneapi.identity.domain.Role;
import com.jhwjhw.naverworkscloneapi.identity.domain.UserAccount;
import com.jhwjhw.naverworkscloneapi.identity.repository.CompanyRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.MembershipRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.UserAccountRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:boardGreetingTest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.board.daily-greeting.login-id=greeting-admin@example.com"
})
@ActiveProfiles("test")
@Transactional
// 매일 자유게시판 인사 글: 제목·본문·글쓴이·게시판, 같은 날 두 번 안 올라감. 모두 가상 데이터.
class BoardDailyGreetingTests {
    @Autowired private BoardDailyGreetingService greetingService;
    @Autowired private BoardDailyGreetingRepository greetings;
    @Autowired private BoardPostRepository posts;
    @Autowired private CompanyRepository companies;
    @Autowired private UserAccountRepository accounts;
    @Autowired private MembershipRepository memberships;

    private Membership admin() {
        Company company = companies.save(new Company("가상 인사 회사"));
        UserAccount account = accounts.save(new UserAccount("greeting-admin@example.com", "test-hash", "관리자"));
        return memberships.save(new Membership(company, account, null, Role.ADMIN));
    }

    @Test
    void postsGreetingOncePerDay() {
        Membership admin = admin();
        LocalDate day = LocalDate.of(2026, 11, 3);

        assertThat(greetingService.postIfMissing(day)).isTrue();
        assertThat(greetingService.postIfMissing(day)).isFalse();
        // 다음 날은 새로 올라감
        assertThat(greetingService.postIfMissing(day.plusDays(1))).isTrue();

        BoardPost post = posts.findById(greetings.findById(day).orElseThrow().getPostId()).orElseThrow();
        assertThat(post.getTitle()).isEqualTo("2026년 11월 03일 입니다, 오늘도 좋은 하루 보내세요!");
        assertThat(post.getContentText()).contains(BoardDailyGreetingService.CONTENT_TEXT);
        assertThat(post.getAuthor().getId()).isEqualTo(admin.getId());
        assertThat(post.getBoard().getType()).isEqualTo(BoardType.FREE);
        assertThat(greetings.count()).isEqualTo(2);
    }

    @Test
    void skipsWhenWriterAccountMissing() {
        assertThat(greetingService.postIfMissing(LocalDate.of(2026, 11, 3))).isFalse();
        assertThat(greetings.count()).isZero();
        assertThat(posts.findAll()).isEqualTo(List.of());
    }
}
