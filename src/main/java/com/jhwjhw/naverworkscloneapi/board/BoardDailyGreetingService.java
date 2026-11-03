package com.jhwjhw.naverworkscloneapi.board;

import com.jhwjhw.naverworkscloneapi.board.domain.Board;
import com.jhwjhw.naverworkscloneapi.board.domain.BoardDailyGreeting;
import com.jhwjhw.naverworkscloneapi.board.domain.BoardType;
import com.jhwjhw.naverworkscloneapi.board.repository.BoardDailyGreetingRepository;
import com.jhwjhw.naverworkscloneapi.board.repository.BoardRepository;
import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import com.jhwjhw.naverworkscloneapi.identity.repository.MembershipRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.UserAccountRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 매일 자유게시판에 관리자 계정으로 인사 글을 올림(BoardDailyGreetingScheduler가 부름).
// 글과 날짜 기록을 한 트랜잭션에 저장하므로, 다른 서버가 같은 날 먼저 올렸으면 날짜 기본 키 충돌로 이쪽 글까지 함께 취소됨.
@Service
@Slf4j
public class BoardDailyGreetingService {
    static final String CONTENT_TEXT = "오늘 하루도 힘내시고 화이팅 하세요 !";
    private static final DateTimeFormatter TITLE_DATE = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일");

    private final BoardService boardService;
    private final BoardRepository boardRepository;
    private final BoardDailyGreetingRepository greetingRepository;
    private final UserAccountRepository accountRepository;
    private final MembershipRepository membershipRepository;
    private final BoardMarketReportService reportService;
    // 글쓴이 계정 아이디. 기본은 처음 만든 관리자 계정.
    private final String loginId;

    public BoardDailyGreetingService(BoardService boardService, BoardRepository boardRepository,
            BoardDailyGreetingRepository greetingRepository, UserAccountRepository accountRepository,
            MembershipRepository membershipRepository, BoardMarketReportService reportService,
            @Value("${app.board.daily-greeting.login-id:admin@naverworks.local}") String loginId) {
        this.boardService = boardService;
        this.boardRepository = boardRepository;
        this.greetingRepository = greetingRepository;
        this.accountRepository = accountRepository;
        this.membershipRepository = membershipRepository;
        this.reportService = reportService;
        this.loginId = loginId;
    }

    static String titleOf(LocalDate date) {
        return date.format(TITLE_DATE) + " 입니다, 오늘도 좋은 하루 보내세요!";
    }

    // 그 날짜 인사 글이 없으면 올림. 올렸으면 true, 이미 있거나 글쓴이 계정이 없으면 false.
    @Transactional
    public boolean postIfMissing(LocalDate date) {
        if (greetingRepository.existsById(date)) return false;
        Optional<Membership> writer = accountRepository.findByLoginId(loginId)
                .flatMap(account -> membershipRepository.findByUserAccountId(account.getId()));
        if (writer.isEmpty()) {
            log.warn("Daily greeting skipped: writer account not found. loginId={}", loginId);
            return false;
        }
        Long membershipId = writer.get().getId();
        Long boardId = freeBoardId().orElseGet(() -> {
            // 아무도 게시판에 들어간 적이 없으면 공통 게시판이 아직 없을 수 있어 먼저 만듦
            boardService.ensureBoards(membershipId);
            return freeBoardId().orElseThrow();
        });
        BoardResponse.PostDetail post = boardService.create(membershipId, new BoardRequest.PostCreate(boardId,
                titleOf(date), contentJson(date), false, false, List.of()));
        greetingRepository.saveAndFlush(new BoardDailyGreeting(date, post.id()));
        return true;
    }

    private Optional<Long> freeBoardId() {
        return boardRepository.findByTypes(List.of(BoardType.FREE)).stream().findFirst().map(Board::getId);
    }

    // 편집기 문서 형식. 인사 문구 + (증시 분석이 켜져 있으면) "불러오는 중" 안내 — 분석은 BoardMarketReportService가 곧 채움.
    private String contentJson(LocalDate date) {
        return reportService.documentJson(date, CONTENT_TEXT, null, null);
    }
}
