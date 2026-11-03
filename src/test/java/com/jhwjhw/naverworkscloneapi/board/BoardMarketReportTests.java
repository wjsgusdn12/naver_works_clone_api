package com.jhwjhw.naverworkscloneapi.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardDailyGreeting;
import com.jhwjhw.naverworkscloneapi.board.domain.BoardPost;
import com.jhwjhw.naverworkscloneapi.board.market.KoreaMarketClient;
import com.jhwjhw.naverworkscloneapi.board.market.MarketHttp;
import com.jhwjhw.naverworkscloneapi.board.market.UsMarketClient;
import com.jhwjhw.naverworkscloneapi.board.repository.BoardDailyGreetingRepository;
import com.jhwjhw.naverworkscloneapi.board.repository.BoardPostRepository;
import com.jhwjhw.naverworkscloneapi.identity.domain.Company;
import com.jhwjhw.naverworkscloneapi.identity.domain.Membership;
import com.jhwjhw.naverworkscloneapi.identity.domain.Role;
import com.jhwjhw.naverworkscloneapi.identity.domain.UserAccount;
import com.jhwjhw.naverworkscloneapi.identity.repository.CompanyRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.MembershipRepository;
import com.jhwjhw.naverworkscloneapi.identity.repository.UserAccountRepository;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:boardMarketReportTest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.board.daily-greeting.login-id=market-admin@example.com"
})
@ActiveProfiles("test")
// 매일 인사 글의 증시 분석: 미국은 아침, 한국은 오후 2시 이후 채움, 본문 검사 통과, "(수정됨)" 안 붙음, 서버 한 대만 작업.
// 시세 API는 부르지 않고 가짜 응답을 씀(숫자·종목 성적은 모두 가상).
class BoardMarketReportTests {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired private BoardDailyGreetingService greetingService;
    @Autowired private BoardDailyGreetingRepository greetings;
    @Autowired private BoardPostRepository posts;
    @Autowired private BoardContent content;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private CompanyRepository companies;
    @Autowired private UserAccountRepository accounts;
    @Autowired private MembershipRepository memberships;

    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void admin() {
        if (accounts.findByLoginId("market-admin@example.com").isPresent()) return;
        Company company = companies.save(new Company("가상 증시 회사"));
        UserAccount account = accounts.save(new UserAccount("market-admin@example.com", "test-hash", "관리자"));
        memberships.save(new Membership(company, account, null, Role.ADMIN));
    }

    private BoardMarketReportService service(ZonedDateTime now) {
        MarketHttp http = uri -> {
            calls.incrementAndGet();
            String query = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
            if (uri.getHost().contains("alphavantage")) {
                return query.contains("TOP_GAINERS_LOSERS") ? MOVERS : daily(param(query, "symbol"));
            }
            if (uri.getPath().endsWith("getStockMarketIndex_V2")) {
                if (query.contains("beginBasDt")) return INDEX_RANGE;
                return "코스피".equals(param(query, "idxNm")) ? KOSPI : KOSDAQ;
            }
            return STOCKS;
        };
        return new BoardMarketReportService(greetings, posts, content, objectMapper, transactionManager,
                new KoreaMarketClient(http, objectMapper, "fake-key"),
                new UsMarketClient(http, objectMapper, "fake-key", Duration.ZERO), LocalTime.of(14, 0), SEOUL,
                Clock.fixed(now.toInstant(), SEOUL));
    }

    @Test
    void fillsUsInMorningAndKoreaAfterTwo() {
        LocalDate day = LocalDate.of(2026, 11, 3);
        assertThat(greetingService.postIfMissing(day)).isTrue();

        // 오전 9시 5분: 미국만(한국은 오후 2시 이후)
        service(day.atTime(9, 5).atZone(SEOUL)).fillPending();
        BoardDailyGreeting morning = greetings.findById(day).orElseThrow();
        assertThat(morning.getUsDoneAt()).isNotNull();
        assertThat(morning.getKrDoneAt()).isNull();
        BoardPost post = posts.findById(morning.getPostId()).orElseThrow();
        assertThat(post.getContentText()).contains(BoardDailyGreetingService.CONTENT_TEXT)
                .contains("미국 증시 · 11월 2일(월) 마감").contains("S&P 500 (SPY)").contains("+1.00%")
                .contains("기술주").contains("빅테크 7개 중").contains("FAKEUP").doesNotContain("PENNY")
                .contains("한국 증시 분석은 오후 2시 이후");
        assertThat(post.getContentJson()).contains("\"tone\":\"up\"").contains("\"type\":\"table\"");

        // 오후 2시 10분: 한국 채움(미국은 다시 받지 않음)
        int before = calls.get();
        service(day.atTime(14, 10).atZone(SEOUL)).fillPending();
        BoardDailyGreeting afternoon = greetings.findById(day).orElseThrow();
        assertThat(afternoon.getKrDoneAt()).isNotNull();
        assertThat(afternoon.getLeaseUntil()).isNull();
        post = posts.findById(afternoon.getPostId()).orElseThrow();
        assertThat(post.getContentText()).contains("한국 증시 · 11월 2일(월) 마감").contains("코스피는 1.20% 올라 2,612.45")
                .contains("시가총액 상위 10").contains("가상전자").contains("거래대금 상위")
                .doesNotContain("한국 증시 분석은 오후 2시 이후").contains("미국 증시 · 11월 2일(월) 마감");
        // 거래대금 10억 원 미만 종목은 급등 목록에서 빠짐
        assertThat(post.getContentText()).doesNotContain("가상소형");
        // 시스템이 채운 것이라 "(수정됨)" 시각은 그대로
        assertThat(post.getUpdatedAt()).isNull();
        // 한국: 지수 범위 1 + 지수 2 + 종목 1
        assertThat(calls.get() - before).isEqualTo(4);
    }

    @Test
    void anotherServerHoldingLeaseSkips() {
        LocalDate day = LocalDate.of(2026, 11, 4);
        assertThat(greetingService.postIfMissing(day)).isTrue();
        ZonedDateTime now = day.atTime(9, 5).atZone(SEOUL);
        // 다른 서버가 작업 자리를 잡은 상태
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                greetings.claim(day, now.toInstant(), now.plusMinutes(20).toInstant()));

        service(now).fillPending();
        assertThat(greetings.findById(day).orElseThrow().getUsDoneAt()).isNull();
    }

    @Test
    void givesUpOnOldDayWithoutData() {
        LocalDate day = LocalDate.of(2026, 1, 5);
        assertThat(greetingService.postIfMissing(day)).isTrue();
        // 가짜 미국 시세는 2026-10-30부터라 1월 5일 앞 거래일이 없음 → 오래된 날짜라 "받지 못함"으로 끝냄
        service(LocalDate.of(2026, 11, 3).atTime(15, 0).atZone(SEOUL)).fillPending();
        BoardDailyGreeting greeting = greetings.findById(day).orElseThrow();
        assertThat(greeting.getUsDoneAt()).isNotNull();
        assertThat(posts.findById(greeting.getPostId()).orElseThrow().getContentText())
                .contains("미국 증시 데이터를 받지 못해");
    }

    private static String param(String query, String name) {
        return Arrays.stream(query.split("&")).filter(part -> part.startsWith(name + "="))
                .map(part -> part.substring(name.length() + 1)).findFirst().orElse("");
    }

    // 종목별 10/30 → 11/2 종가(가상)
    private static final Map<String, double[]> PRICES = Map.ofEntries(
            Map.entry("SPY", new double[] {500, 505}), Map.entry("QQQ", new double[] {400, 408}),
            Map.entry("DIA", new double[] {300, 300.3}), Map.entry("IWM", new double[] {200, 196}),
            Map.entry("AAPL", new double[] {200, 202}), Map.entry("MSFT", new double[] {400, 396}),
            Map.entry("NVDA", new double[] {100, 105}), Map.entry("AMZN", new double[] {100, 100.5}),
            Map.entry("GOOGL", new double[] {100, 100.5}), Map.entry("META", new double[] {100, 100.5}),
            Map.entry("TSLA", new double[] {100, 100.5}));

    private static String daily(String symbol) {
        double[] price = PRICES.get(symbol);
        return """
                {"Meta Data": {"2. Symbol": "%s"},
                 "Time Series (Daily)": {
                   "2026-11-02": {"1. open": "1", "2. high": "1", "3. low": "1", "4. close": "%s", "5. volume": "12345678"},
                   "2026-10-30": {"1. open": "1", "2. high": "1", "3. low": "1", "4. close": "%s", "5. volume": "11111111"}
                 }}
                """.formatted(symbol, price[1], price[0]);
    }

    private static final String MOVERS = """
            {"metadata": "Top gainers, losers, and most actively traded US tickers",
             "last_updated": "2026-11-02 16:15:59 US/Eastern",
             "top_gainers": [
               {"ticker": "PENNY", "price": "0.42", "change_amount": "0.2", "change_percentage": "90.5%", "volume": "99000000"},
               {"ticker": "FAKEUP", "price": "25.10", "change_amount": "5.0", "change_percentage": "24.87%", "volume": "3200000"}
             ],
             "top_losers": [
               {"ticker": "FAKEDN", "price": "12.00", "change_amount": "-4.0", "change_percentage": "-25.0%", "volume": "2100000"}
             ],
             "most_actively_traded": []}
            """;

    private static final String INDEX_RANGE = """
            {"response": {"header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
             "body": {"numOfRows": 30, "pageNo": 1, "totalCount": 2, "items": {"item": [
               {"basDt": "20261102", "idxNm": "코스피", "clpr": "2612.45"},
               {"basDt": "20261030", "idxNm": "코스피", "clpr": "2581.47"}
             ]}}}}
            """;

    private static final String KOSPI = """
            {"response": {"header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
             "body": {"items": {"item": {"basDt": "20261102", "idxNm": "코스피", "clpr": "2612.45", "vs": "30.98",
               "fltRt": "1.20", "trPrc": "9876543210000", "yrWRcrdHgst": "2900.00", "yrWRcrdLwst": "2300.00"}}}}}
            """;

    private static final String KOSDAQ = """
            {"response": {"header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
             "body": {"items": {"item": [{"basDt": "20261102", "idxNm": "코스닥", "clpr": "745.10", "vs": "-2.25",
               "fltRt": "-.30", "trPrc": "6543210000000"}]}}}}
            """;

    private static final String STOCKS = """
            {"response": {"header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
             "body": {"items": {"item": [
               {"basDt": "20261102", "itmsNm": "가상전자", "mrktCtg": "KOSPI", "clpr": "71500", "vs": "1500",
                "fltRt": "2.14", "trqu": "15000000", "trPrc": "1070000000000", "mrktTotAmt": "426000000000000"},
               {"basDt": "20261102", "itmsNm": "가상바이오", "mrktCtg": "KOSDAQ", "clpr": "52000", "vs": "-2600",
                "fltRt": "-4.76", "trqu": "900000", "trPrc": "46800000000", "mrktTotAmt": "5200000000000"},
               {"basDt": "20261102", "itmsNm": "가상소형", "mrktCtg": "KOSDAQ", "clpr": "1200", "vs": "360",
                "fltRt": "30.00", "trqu": "100000", "trPrc": "120000000"},
               {"basDt": "20261102", "itmsNm": "가상코넥스", "mrktCtg": "KONEX", "clpr": "5000", "vs": "0",
                "fltRt": "0", "trqu": "10", "trPrc": "50000", "mrktTotAmt": "1000000000"}
             ]}}}}
            """;
}
