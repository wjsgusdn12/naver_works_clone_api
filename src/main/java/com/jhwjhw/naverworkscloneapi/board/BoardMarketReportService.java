package com.jhwjhw.naverworkscloneapi.board;

import com.jhwjhw.naverworkscloneapi.board.domain.BoardDailyGreeting;
import com.jhwjhw.naverworkscloneapi.board.market.KoreaMarketClient;
import com.jhwjhw.naverworkscloneapi.board.market.MarketHttp;
import com.jhwjhw.naverworkscloneapi.board.market.MarketReportBuilder;
import com.jhwjhw.naverworkscloneapi.board.market.NewsClient;
import com.jhwjhw.naverworkscloneapi.board.market.YahooMarketClient;
import com.jhwjhw.naverworkscloneapi.board.market.UsMarketClient;
import com.jhwjhw.naverworkscloneapi.board.market.UsMarketClient.DailyBar;
import com.jhwjhw.naverworkscloneapi.board.market.UsMarketClient.Movers;
import com.jhwjhw.naverworkscloneapi.board.repository.BoardDailyGreetingRepository;
import com.jhwjhw.naverworkscloneapi.board.repository.BoardPostRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

// 매일 인사 글에 증시 분석을 채움. 미국은 글을 올린 뒤 바로(간밤 마감), 한국은 오후 2시 이후(공공데이터가 오후 1시 이후 올라옴).
// 이미 올라온 지난 인사 글도 아직 안 채운 부분이 있으면 같은 방식으로 채움(최근 약 100거래일까지 데이터가 있음).
// 시세 받기는 몇 분 걸릴 수 있어(미국 1분 5번 제한) 예약 작업 스레드를 막지 않게 별도 가상 스레드에서 함.
// 서버가 여러 대면 가장 오래된 남은 날짜 행을 잡은 서버 한 대만 작업함(하루 25번인 미국 호출 수를 나눠 쓰지 않게).
@Service
@Slf4j
public class BoardMarketReportService {
    // 한 번 작업은 3분쯤(미국 12번 × 13초 간격). 개발 중 자동 재시작으로 작업이 끊기면 이 시간이 지나야 다시 잡을 수 있어 짧게.
    private static final Duration LEASE = Duration.ofMinutes(6);
    // 이 날짜 수보다 오래 못 받은 부분은 "받지 못함"으로 끝냄
    private static final int GIVE_UP_DAYS = 7;
    // 예전 형식으로 채운 부분을 새 형식으로 다시 만드는 범위(미국 시세가 최근 약 100거래일까지라 그 안쪽)
    private static final int REGENERATE_DAYS = 120;
    private static final TypeReference<List<Map<String, Object>>> NODES = new TypeReference<>() {
    };

    private final BoardDailyGreetingRepository greetingRepository;
    private final BoardPostRepository postRepository;
    private final BoardContent content;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final KoreaMarketClient korea;
    private final UsMarketClient us;
    // 미국 일별 시세는 Yahoo가 먼저(한도 없음), 막히면 Alpha Vantage. 급등·급락은 Alpha Vantage만.
    private final YahooMarketClient yahoo;
    private final NewsClient news;
    private final ZoneId zone;
    private final LocalTime koreaAt;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    // 미국 시세는 하루 호출 수가 적어, 같은 날 받은 것은 이 서버에서 다시 받지 않음(날짜가 바뀌면 비움)
    private LocalDate cacheDay;
    private final Map<String, List<DailyBar>> seriesCache = new HashMap<>();
    private Movers moversCache;
    private boolean usLimitReached;

    @Autowired
    public BoardMarketReportService(BoardDailyGreetingRepository greetingRepository,
            BoardPostRepository postRepository, BoardContent content, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${app.market.enabled:true}") boolean enabled,
            @Value("${app.market.data-go-kr-key:}") String dataGoKrKey,
            @Value("${app.market.alpha-vantage-key:}") String alphaVantageKey,
            @Value("${app.market.news-enabled:true}") boolean newsEnabled,
            @Value("${app.market.yahoo-enabled:true}") boolean yahooEnabled,
            @Value("${app.market.korea-time:14:00}") String koreaAt,
            @Value("${app.monitoring.zone:Asia/Seoul}") String zone) {
        this(greetingRepository, postRepository, content, objectMapper, transactionManager,
                enabled && !dataGoKrKey.isBlank()
                        ? new KoreaMarketClient(MarketHttp.jdk(), objectMapper, dataGoKrKey.strip()) : null,
                enabled && !alphaVantageKey.isBlank()
                        ? new UsMarketClient(cached(MarketHttp.jdk(), "alphavantage", ZoneId.of(zone),
                                body -> !body.contains("\"Information\"") && !body.contains("\"Note\"")
                                        && !body.contains("\"Error Message\"")),
                                objectMapper, alphaVantageKey.strip(), Duration.ofSeconds(13))
                        : null,
                enabled && yahooEnabled
                        ? new YahooMarketClient(cached(MarketHttp.jdk(), "yahoo", ZoneId.of(zone),
                                body -> body.contains("\"timestamp\"")), objectMapper)
                        : null,
                enabled && newsEnabled ? new NewsClient(MarketHttp.jdk()) : null,
                LocalTime.parse(koreaAt), ZoneId.of(zone), Clock.systemUTC());
    }

    // 하루 보관 폴더: 시스템 임시 폴더 아래(서버가 다시 켜져도 남음, git·uploads와 무관)
    private static MarketHttp cached(MarketHttp inner, String name, ZoneId zone,
            java.util.function.Predicate<String> keep) {
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                "naverworks-market-cache", name);
        return MarketHttp.dailyFileCache(inner, root, () -> LocalDate.now(zone), keep);
    }

    BoardMarketReportService(BoardDailyGreetingRepository greetingRepository, BoardPostRepository postRepository,
            BoardContent content, ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
            KoreaMarketClient korea, UsMarketClient us, YahooMarketClient yahoo, NewsClient news,
            LocalTime koreaAt, ZoneId zone, Clock clock) {
        this.yahoo = yahoo;
        this.news = news;
        this.greetingRepository = greetingRepository;
        this.postRepository = postRepository;
        this.content = content;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.korea = korea;
        this.us = us;
        this.koreaAt = koreaAt;
        this.zone = zone;
        this.clock = clock;
    }

    public boolean usEnabled() {
        return us != null || yahoo != null;
    }

    public boolean krEnabled() {
        return korea != null;
    }

    public boolean newsEnabled() {
        return news != null;
    }

    // 인사 글 본문(분석 부분이 없으면 "준비 중" 안내). 처음 올릴 때와 분석을 채울 때 함께 씀.
    String documentJson(LocalDate date, String greeting, String newsSection, String usSection, String krSection) {
        return objectMapper.writeValueAsString(MarketReportBuilder.document(date, greeting, nodes(newsSection),
                nodes(usSection), nodes(krSection), usEnabled(), krEnabled()));
    }

    // 예약 작업이 5분마다 부름. 이미 작업 중이면 그냥 돌아감.
    public void triggerAsync() {
        if (!usEnabled() && !krEnabled() && !newsEnabled()) return;
        if (!running.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("market-report").start(() -> {
            try {
                fillPending();
            } catch (RuntimeException exception) {
                log.warn("Market report run failed.", exception);
            } finally {
                running.set(false);
            }
        });
    }

    // 할 일이 있는 날짜들을 채움(테스트는 이것을 바로 부름)
    void fillPending() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        // 아직 안 채운 부분 + 예전 형식으로 채워 둔 부분(보고 모양을 바꾼 뒤 새 형식으로 다시 만듦). 최근 120일만.
        List<BoardDailyGreeting> due = tx.execute(status -> greetingRepository
                .findByGreetingDateGreaterThanEqualOrderByGreetingDate(now.toLocalDate().minusDays(REGENERATE_DAYS)))
                .stream().filter(greeting -> needsNews(greeting) || needsUs(greeting) || krDue(greeting, now)).toList();
        if (due.isEmpty()) return;
        log.info("Market report run started. due={}", due.stream().map(BoardDailyGreeting::getGreetingDate).toList());
        // 가장 오래된 날짜 행을 이번 작업의 자물쇠로 씀. 다른 서버가 잡고 있으면 이번에는 쉼.
        List<LocalDate> claimed = new ArrayList<>();
        try {
            for (BoardDailyGreeting greeting : due) {
                Instant at = clock.instant();
                Integer won = tx.execute(status -> greetingRepository.claim(greeting.getGreetingDate(), at,
                        at.plus(LEASE)));
                if (won == null || won == 0) {
                    log.info("Market report waiting: {} is held by another run (until lease expires, max {} min).",
                            greeting.getGreetingDate(), LEASE.toMinutes());
                    if (claimed.isEmpty()) return;
                    continue;
                }
                claimed.add(greeting.getGreetingDate());
                fill(greeting, now);
            }
        } finally {
            for (LocalDate date : claimed) tx.executeWithoutResult(status -> greetingRepository.release(date));
        }
    }

    // 채운 적이 없거나, 채웠지만 예전 형식이면 할 일
    private static boolean stale(Instant doneAt, String section) {
        return doneAt == null || (section != null && !MarketReportBuilder.isCurrent(section));
    }

    private boolean needsNews(BoardDailyGreeting greeting) {
        return newsEnabled() && stale(greeting.getNewsDoneAt(), greeting.getNewsSection());
    }

    private boolean needsUs(BoardDailyGreeting greeting) {
        return usEnabled() && stale(greeting.getUsDoneAt(), greeting.getUsSection());
    }

    private boolean krDue(BoardDailyGreeting greeting, ZonedDateTime now) {
        return krEnabled() && stale(greeting.getKrDoneAt(), greeting.getKrSection())
                && (greeting.getGreetingDate().isBefore(now.toLocalDate()) || !now.toLocalTime().isBefore(koreaAt));
    }

    // 부분 하나의 결과. done=false면 아직(다음 차례에 다시), done=true인데 section이 null이면 "없이 끝냄"(지난 날짜 뉴스).
    private record Part(boolean done, String section) {
        static final Part NOT_YET = new Part(false, null);
    }

    private void fill(BoardDailyGreeting greeting, ZonedDateTime now) {
        LocalDate date = greeting.getGreetingDate();
        boolean giveUp = date.isBefore(now.toLocalDate().minusDays(GIVE_UP_DAYS));
        Part newsPart = Part.NOT_YET;
        Part usPart = Part.NOT_YET;
        Part krPart = Part.NOT_YET;
        if (needsNews(greeting)) {
            if (date.isBefore(now.toLocalDate())) {
                // RSS는 최신 기사만 있어 지난 날짜에는 뉴스를 붙이지 않음
                newsPart = new Part(true, null);
            } else {
                String section = attempt("NEWS", date, () -> MarketReportBuilder.newsSection(
                        news.headlines(date.minusDays(1).atTime(9, 0).atZone(zone)), zone));
                if (section != null) newsPart = new Part(true, section);
            }
        }
        if (needsUs(greeting)) {
            String section = attempt("US", date, () -> buildUs(date, now));
            if (section == null && giveUp) section = json(MarketReportBuilder.unavailable("미국"));
            if (section != null) usPart = new Part(true, section);
        }
        if (krDue(greeting, now)) {
            String section = attempt("KR", date, () -> buildKr(date, now));
            if (section == null && giveUp) section = json(MarketReportBuilder.unavailable("한국"));
            if (section != null) krPart = new Part(true, section);
        }
        if (!newsPart.done() && !usPart.done() && !krPart.done()) {
            log.info("Market report not ready yet. date={} (will retry in 5 min)", date);
            return;
        }
        Part finalNews = newsPart;
        Part finalUs = usPart;
        Part finalKr = krPart;
        tx.executeWithoutResult(status -> save(date, finalNews, finalUs, finalKr));
    }

    private interface Build {
        Optional<List<Map<String, Object>>> run() throws IOException;
    }

    // 만들면 JSON, 아직 데이터가 없거나 실패하면 null(다음 차례에 다시)
    private String attempt(String market, LocalDate date, Build build) {
        try {
            return build.run().map(this::json).orElse(null);
        } catch (UsMarketClient.LimitReachedException exception) {
            usLimitReached = true;
            log.warn("Market report {} skipped: daily API limit reached. date={}", market, date);
        } catch (IOException | RuntimeException exception) {
            log.warn("Market report {} failed. date={} reason={}", market, date, exception.getMessage());
        }
        return null;
    }

    private void save(LocalDate date, Part newsPart, Part usPart, Part krPart) {
        BoardDailyGreeting greeting = greetingRepository.findById(date).orElseThrow();
        Instant at = clock.instant();
        if (newsPart.done()) greeting.finishNews(newsPart.section(), at);
        if (usPart.done()) greeting.finishUs(usPart.section(), at);
        if (krPart.done()) greeting.finishKr(krPart.section(), at);
        // 지난 날짜 뉴스를 "없이 끝냄"만 한 경우는 글이 그대로라 고치지 않음("(수정됨)"이 괜히 붙지 않게)
        boolean visible = newsPart.section() != null || usPart.done() || krPart.done();
        if (visible) {
            postRepository.findById(greeting.getPostId()).filter(post -> !post.isDeleted()).ifPresent(post -> {
                BoardContent.Sanitized body = content.sanitize(documentJson(date,
                        BoardDailyGreetingService.CONTENT_TEXT, greeting.getNewsSection(), greeting.getUsSection(),
                        greeting.getKrSection()));
                post.refreshContentBySystem(body.json(), body.text());
            });
        }
        log.info("Market report saved. date={} news={} us={} kr={}", date, newsPart.section() != null, usPart.done(),
                krPart.done());
    }

    // ───────────── 미국 ─────────────

    private Optional<List<Map<String, Object>>> buildUs(LocalDate date, ZonedDateTime now) throws IOException {
        resetCacheIfNewDay(now.toLocalDate());
        List<DailyBar> spy = series("SPY");
        Optional<LocalDate> basis = spy.stream().map(DailyBar::date).filter(day -> day.isBefore(date)).findFirst();
        if (basis.isEmpty()) return Optional.empty();
        // 기대한 거래일(전 평일)이 아니면 아직 안 올라왔을 수 있어, 오늘 글이면 낮 12시까지 기다렸다가 휴장일로 봄
        if (!basis.get().equals(previousWeekday(date)) && !date.isBefore(now.toLocalDate()) && now.getHour() < 12) {
            return Optional.empty();
        }
        Map<String, List<DailyBar>> all = new LinkedHashMap<>();
        all.put("SPY", spy);
        List<String> symbols = new ArrayList<>(MarketReportBuilder.US_INDEX_ETFS.keySet());
        symbols.addAll(MarketReportBuilder.US_BIG_TECH.keySet());
        for (String symbol : symbols) {
            if (all.containsKey(symbol)) continue;
            try {
                all.put(symbol, series(symbol));
            } catch (UsMarketClient.LimitReachedException exception) {
                throw exception;
            } catch (IOException exception) {
                // 한 종목이 실패해도 나머지로 만듦
                log.warn("Market report US symbol skipped. symbol={} reason={}", symbol, exception.getMessage());
            }
        }
        Movers movers = null;
        // 급등·급락은 Alpha Vantage에만 있고 최신 거래일 것만 있음. 못 받아도 나머지로 만듦(급등·급락 카드만 빠짐).
        if (us != null && !usLimitReached && !spy.isEmpty() && spy.getFirst().date().equals(basis.get())) {
            try {
                if (moversCache == null) moversCache = us.movers();
                movers = moversCache;
            } catch (UsMarketClient.LimitReachedException exception) {
                usLimitReached = true;
                log.warn("Market report US movers skipped: Alpha Vantage daily limit reached.");
            } catch (IOException exception) {
                log.warn("Market report US movers skipped. reason={}", exception.getMessage());
            }
        }
        return MarketReportBuilder.usSection(basis.get(), all, movers);
    }

    // 일별 시세: Yahoo 먼저, 안 되면 Alpha Vantage(하루 한도가 남아 있을 때)
    private List<DailyBar> series(String symbol) throws IOException {
        List<DailyBar> cached = seriesCache.get(symbol);
        if (cached != null) return cached;
        List<DailyBar> bars = null;
        IOException failure = null;
        if (yahoo != null) {
            try {
                bars = yahoo.daily(symbol);
            } catch (IOException exception) {
                failure = exception;
                log.warn("Market report Yahoo failed, trying Alpha Vantage. symbol={} reason={}", symbol,
                        exception.getMessage());
            }
        }
        if (bars == null && us != null && !usLimitReached) {
            try {
                bars = us.daily(symbol);
            } catch (UsMarketClient.LimitReachedException exception) {
                usLimitReached = true;
                throw exception;
            }
        }
        if (bars == null) throw failure != null ? failure : new IOException("No US price source for " + symbol);
        seriesCache.put(symbol, bars);
        return bars;
    }

    private void resetCacheIfNewDay(LocalDate today) {
        if (today.equals(cacheDay)) return;
        cacheDay = today;
        seriesCache.clear();
        moversCache = null;
        usLimitReached = false;
    }

    // ───────────── 한국 ─────────────

    private Optional<List<Map<String, Object>>> buildKr(LocalDate date, ZonedDateTime now) throws IOException {
        // 코스피 추이로 마지막 거래일도 함께 알아냄(호출 한 번 아낌)
        List<KoreaMarketClient.IndexClose> kospiHistory = korea.history("코스피", date);
        Optional<LocalDate> latest = kospiHistory.stream().map(KoreaMarketClient.IndexClose::date)
                .max(LocalDate::compareTo);
        if (latest.isEmpty()) return Optional.empty();
        LocalDate expected = previousWeekday(date);
        if (!latest.get().equals(expected)) {
            // 기대한 날짜가 아직 안 올라왔거나 휴장일. 올라올 시각(다음 평일 오후 1시)에서 하루가 더 지났으면 휴장일로 봄.
            ZonedDateTime surely = nextWeekday(nextWeekday(expected)).atTime(koreaAt).atZone(zone);
            if (now.isBefore(surely)) return Optional.empty();
        }
        List<KoreaMarketClient.IndexQuote> indices = korea.indices(latest.get());
        if (indices.isEmpty()) return Optional.empty();
        Map<String, List<KoreaMarketClient.IndexClose>> histories = new HashMap<>();
        histories.put("코스피", kospiHistory);
        try {
            histories.put("코스닥", korea.history("코스닥", date));
        } catch (IOException exception) {
            // 추이 그래프만 빠짐
            log.warn("Market report KR kosdaq history skipped. reason={}", exception.getMessage());
        }
        return MarketReportBuilder.krSection(latest.get(), indices, korea.stocks(latest.get()), histories);
    }

    // ───────────── 도움 ─────────────

    static LocalDate previousWeekday(LocalDate date) {
        LocalDate day = date.minusDays(1);
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.minusDays(1);
        }
        return day;
    }

    static LocalDate nextWeekday(LocalDate date) {
        LocalDate day = date.plusDays(1);
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.plusDays(1);
        }
        return day;
    }

    private String json(List<Map<String, Object>> nodes) {
        return objectMapper.writeValueAsString(nodes);
    }

    private List<Map<String, Object>> nodes(String json) {
        return json == null ? null : objectMapper.readValue(json, NODES);
    }
}
