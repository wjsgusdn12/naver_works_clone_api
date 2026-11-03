package com.jhwjhw.naverworkscloneapi.board.market;

import com.jhwjhw.naverworkscloneapi.board.market.KoreaMarketClient.IndexClose;
import com.jhwjhw.naverworkscloneapi.board.market.KoreaMarketClient.IndexQuote;
import com.jhwjhw.naverworkscloneapi.board.market.KoreaMarketClient.StockQuote;
import com.jhwjhw.naverworkscloneapi.board.market.UsMarketClient.DailyBar;
import com.jhwjhw.naverworkscloneapi.board.market.UsMarketClient.Mover;
import com.jhwjhw.naverworkscloneapi.board.market.UsMarketClient.Movers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

// 받은 시세 숫자로 게시글 본문(편집기 문서 노드)을 만드는 규칙 모음. AI 없이 정해진 기준으로 문장을 고름.
// 화면 꾸밈은 증시 전용 블록(marketHero·marketBanner·callout·marketStats·marketBreadth·marketHeatmap·marketRanks)으로
// 내려보내고, 프론트 RichContent가 카드·추이 그래프·히트맵으로 그림. 오름 빨강·내림 파랑(한국 증시 표기).
public final class MarketReportBuilder {
    // 미국 지수는 지수 자체 대신 지수를 따라가는 대표 ETF 시세로 보여 줌(무료 API에 지수가 없음)
    public static final Map<String, String> US_INDEX_ETFS = ordered(
            "SPY", "S&P 500", "QQQ", "나스닥 100", "DIA", "다우존스", "IWM", "러셀 2000");
    public static final Map<String, String> US_BIG_TECH = ordered(
            "AAPL", "애플", "MSFT", "마이크로소프트", "NVDA", "엔비디아", "AMZN", "아마존", "GOOGL", "알파벳",
            "META", "메타", "TSLA", "테슬라");

    // 만든 부분의 형식 번호. 보고 모양을 바꾸면 올려서, 예전 형식으로 저장된 지난 글을 새 형식으로 다시 만들게 함.
    // (저장해 둔 부분 JSON에만 남고, 게시글 본문에서는 본문 검사가 떼어 냄)
    public static final int FORMAT = 5;
    private static final String FORMAT_MARK = "\"format\":" + FORMAT;

    public static boolean isCurrent(String sectionJson) {
        return sectionJson != null && sectionJson.contains(FORMAT_MARK);
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal EOK = new BigDecimal("100000000");
    // 추이 그래프에 넣는 최근 거래일 수
    private static final int SPARK_DAYS = 20;
    // 한국 급등·급락 목록에서 거래가 거의 없는 종목을 빼는 기준(거래대금 10억 원)
    private static final BigDecimal KR_MIN_TRADE_VALUE = new BigDecimal("1000000000");
    // 미국 급등·급락 목록에서 동전주·거래 적은 종목을 빼는 기준
    private static final BigDecimal US_MIN_PRICE = BigDecimal.valueOf(5);
    private static final long US_MIN_VOLUME = 1_000_000;

    private MarketReportBuilder() {
    }

    // ───────────── 글 전체 ─────────────

    // 히어로 + 인사 문구 + 미국 부분 + 한국 부분 + 안내. 부분이 null이면 기능이 켜진 경우에만 "준비 중" 안내를 넣음.
    // 둘 다 꺼져 있으면 예전처럼 인사 문구 한 줄만.
    public static Map<String, Object> document(LocalDate date, String greeting, List<Map<String, Object>> news,
            List<Map<String, Object>> us, List<Map<String, Object>> kr, boolean usEnabled, boolean krEnabled) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        boolean report = usEnabled || krEnabled;
        if (report) {
            blocks.add(Map.of("type", "marketHero", "attrs", Map.of("title", "Daily Market Report",
                    "date", date.getYear() + "년 " + date.getMonthValue() + "월 " + date.getDayOfMonth() + "일 ("
                            + weekday(date) + ")")));
        }
        blocks.add(paragraph(text(greeting)));
        if (news != null) blocks.addAll(news);
        if (us != null) blocks.addAll(us);
        else if (usEnabled) blocks.addAll(pending("us", "미국 증시", "간밤 미국 증시 분석을 불러오는 중입니다."));
        if (kr != null) blocks.addAll(kr);
        else if (krEnabled) {
            blocks.addAll(pending("kr", "한국 증시", "한국 증시 분석은 오후 2시 이후에 추가됩니다. "
                    + "(공공데이터 시세가 다음 영업일 오후 1시 이후에 올라옵니다)"));
        }
        if (report) {
            blocks.add(callout("muted", paragraph(text("※ 시세 숫자로 자동 작성한 요약이며 투자 권유가 아닙니다. "
                    + "미국 지수는 지수를 따라가는 대표 ETF 시세입니다. 출처: 금융위원회(공공데이터포털), Alpha Vantage"
                    + (news != null ? ", 뉴스 제목·링크: 연합뉴스·Google 뉴스(기사 저작권은 각 언론사)" : "")))));
        }
        return Map.of("type", "doc", "content", blocks);
    }

    private static List<Map<String, Object>> pending(String market, String title, String message) {
        return List.of(banner(market, title, "준비 중", null, null), callout("muted", paragraph(text(message))));
    }

    // 끝내 데이터를 받지 못한 날의 부분
    public static List<Map<String, Object>> unavailable(String market) {
        String code = market.equals("미국") ? "us" : "kr";
        return List.of(banner(code, market + " 증시", "데이터 없음", null, null),
                callout("muted", paragraph(text("이 날짜의 " + market + " 증시 데이터를 받지 못해 분석을 생략합니다."))));
    }

    // ───────────── 뉴스 ─────────────

    // 기사가 하나도 없으면 빈 값(뉴스 부분 없이)
    public static Optional<List<Map<String, Object>>> newsSection(List<NewsClient.Article> articles,
            java.time.ZoneId zone) {
        if (articles.isEmpty()) return Optional.empty();
        List<Map<String, Object>> items = new ArrayList<>();
        for (NewsClient.Article article : articles) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", article.category());
            item.put("title", article.title());
            if (!article.source().isBlank()) item.put("source", article.source());
            if (article.publishedAt() != null) {
                java.time.ZonedDateTime local = article.publishedAt().withZoneSameInstant(zone);
                item.put("time", local.getMonthValue() + "/" + local.getDayOfMonth() + " "
                        + String.format(Locale.ROOT, "%02d:%02d", local.getHour(), local.getMinute()));
            }
            item.put("href", article.link());
            if (article.summary() != null) item.put("summary", article.summary());
            item.put("related", article.related().stream().map(related -> Map.of("title", related.title(),
                    "source", related.source(), "href", related.link())).toList());
            items.add(item);
        }
        return Optional.of(List.of(Map.of("type", "marketNews", "attrs",
                Map.of("format", FORMAT, "title", "오늘의 주요 뉴스", "items", items))));
    }

    // ───────────── 추세(이야기용) ─────────────

    // 최근 종가(오래된 날짜부터)로 본 흐름. streak: +n 연속 상승, -n 연속 하락. 비교할 날이 모자라면 null.
    record Trend(Double fiveDay, Double twentyDay, int streak, Double fromHigh, boolean newHigh, boolean newLow) {
    }

    static Trend trend(List<Double> closes) {
        int size = closes.size();
        if (size < 2) return new Trend(null, null, 0, null, false, false);
        double last = closes.get(size - 1);
        Double five = size > 5 ? percent(closes.get(size - 6), last) : null;
        Double twenty = size >= 20 ? percent(closes.getFirst(), last) : null;
        int streak = 0;
        for (int i = size - 1; i > 0; i--) {
            int sign = Double.compare(closes.get(i), closes.get(i - 1));
            if (sign == 0 || (streak != 0 && Integer.signum(streak) != sign)) break;
            streak += sign;
        }
        double high = closes.stream().mapToDouble(Double::doubleValue).max().orElse(last);
        double low = closes.stream().mapToDouble(Double::doubleValue).min().orElse(last);
        boolean enough = size >= 10;
        return new Trend(five, twenty, streak, high == 0 ? null : percent(high, last), enough && last >= high,
                enough && last <= low);
    }

    private static double percent(double from, double to) {
        return from == 0 ? 0 : (to - from) / from * 100;
    }

    private static String pct(double value) {
        return String.format(Locale.ROOT, "%+.2f%%", value);
    }

    // "S&P 500은 최근 5거래일 +1.20%, 20거래일 +3.40%입니다. 3거래일 연속 올랐고, 최근 20거래일 최고치를 새로 썼습니다."
    private static String trendSentence(String subject, Trend trend) {
        List<String> spans = new ArrayList<>();
        if (trend.fiveDay() != null) spans.add("최근 5거래일 " + pct(trend.fiveDay()));
        if (trend.twentyDay() != null) spans.add("20거래일 " + pct(trend.twentyDay()));
        StringBuilder text = new StringBuilder();
        if (!spans.isEmpty()) text.append(subject).append(' ').append(String.join(", ", spans)).append("입니다.");
        List<String> notes = new ArrayList<>();
        if (trend.streak() >= 2) notes.add(trend.streak() + "거래일 연속 올랐");
        else if (trend.streak() <= -2) notes.add(-trend.streak() + "거래일 연속 내렸");
        if (trend.newHigh()) notes.add("최근 20거래일 최고치를 새로 썼");
        else if (trend.newLow()) notes.add("최근 20거래일 최저치까지 밀렸");
        else if (trend.fromHigh() != null && trend.fromHigh() <= -3) {
            notes.add("최근 고점보다 " + String.format(Locale.ROOT, "%.1f", -trend.fromHigh()) + "% 낮은 자리에 있");
        }
        if (!notes.isEmpty()) {
            if (text.isEmpty()) text.append(subject);
            text.append(' ').append(String.join("고, ", notes)).append("습니다.");
        }
        return text.toString().strip();
    }

    private static Map<String, Object> story(String lead, String body) {
        return paragraph(bold(lead + "  "), text(body));
    }

    private static Map<String, Object> checklist(List<String> points) {
        return Map.of("type", "bulletList", "content", points.stream()
                .map(point -> Map.of("type", "listItem", "content", List.of(paragraph(text(point))))).toList());
    }

    // ───────────── 미국 ─────────────

    // basis: 분석할 거래일. series: 종목별 일별 시세(최신부터). movers: 그 거래일 급등·급락(없으면 null)
    public static Optional<List<Map<String, Object>>> usSection(LocalDate basis,
            Map<String, List<DailyBar>> series, Movers movers) {
        Map<String, Change> changes = new LinkedHashMap<>();
        for (String symbol : concat(US_INDEX_ETFS, US_BIG_TECH)) {
            change(series.get(symbol), basis).ifPresent(change -> changes.put(symbol, change));
        }
        if (!changes.containsKey("SPY")) return Optional.empty();

        double average = List.of("SPY", "QQQ", "DIA").stream().map(changes::get).filter(Objects::nonNull)
                .mapToDouble(change -> change.rate().doubleValue()).average().orElse(0);
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(banner("us", "미국 증시", dayLabel(basis) + " 마감", mood(average), moodLabel(average)));
        blocks.add(callout(mood(average), paragraph(text(usSummary(changes)))));

        List<Map<String, Object>> stats = new ArrayList<>();
        US_INDEX_ETFS.forEach((symbol, label) -> {
            Change change = changes.get(symbol);
            if (change == null) return;
            stats.add(stat(label, usd(change.close()), arrow(change.diff(), usd(change.diff().abs())),
                    change.rate(), symbol + " · 대표 ETF", spark(series.get(symbol), basis)));
        });
        blocks.add(Map.of("type", "marketStats", "attrs", Map.of("items", stats)));

        List<String> tech = US_BIG_TECH.keySet().stream().filter(changes::containsKey)
                .sorted(Comparator.comparing((String symbol) -> changes.get(symbol).rate()).reversed()).toList();
        Trend spyTrend = trend(spark(series.get("SPY"), basis));
        Trend qqqTrend = trend(spark(series.get("QQQ"), basis));
        List<Mover> gainers = movers != null && basis.equals(movers.date()) ? filterMovers(movers.gainers()) : List.of();
        List<Mover> losers = movers != null && basis.equals(movers.date()) ? filterMovers(movers.losers()) : List.of();
        List<Mover> active = movers != null && basis.equals(movers.date()) ? filterMovers(movers.active()) : List.of();

        // 📖 시장 이야기: 추세 → 빅테크 → 특징주 순서로 풀어 씀
        blocks.add(heading(3, "미국 시장 이야기"));
        String spyStory = trendSentence("S&P 500은", spyTrend);
        String qqqStory = trendSentence("나스닥 100은", qqqTrend);
        if (!spyStory.isEmpty() || !qqqStory.isEmpty()) {
            blocks.add(story("흐름", (spyStory + " " + qqqStory).strip()));
        }
        if (!tech.isEmpty()) blocks.add(story("빅테크", techStory(tech, changes, series, basis)));
        if (!gainers.isEmpty() || !losers.isEmpty() || !active.isEmpty()) {
            List<String> parts = new ArrayList<>();
            if (!gainers.isEmpty()) {
                parts.add("급등 1위는 " + gainers.getFirst().ticker() + "(" + pct(gainers.getFirst().changePercent()
                        .doubleValue()) + ")");
            }
            if (!losers.isEmpty()) {
                parts.add("급락 1위는 " + losers.getFirst().ticker() + "(" + pct(losers.getFirst().changePercent()
                        .doubleValue()) + ")");
            }
            String text = String.join(", ", parts) + (parts.isEmpty() ? "" : "였습니다.");
            if (!active.isEmpty()) {
                text += " 거래가 가장 많이 몰린 종목은 " + active.getFirst().ticker() + "(거래량 "
                        + compact(active.getFirst().volume()) + ", " + pct(active.getFirst().changePercent().doubleValue())
                        + ")입니다.";
            }
            blocks.add(story("특징주", text.strip()));
        }

        if (!tech.isEmpty()) {
            List<Map<String, Object>> tiles = tech.stream().map(symbol -> tile(US_BIG_TECH.get(symbol),
                    symbol + " · " + usd(changes.get(symbol).close()), changes.get(symbol).rate())).toList();
            blocks.add(Map.of("type", "marketHeatmap", "attrs", Map.of("title", "빅테크 7", "items", tiles)));
            blocks.add(paragraph(text(bigTechSummary(tech, changes))));
        }

        // 한국처럼 순위 3칸: 상승률 · 하락률 · 거래량
        if (!gainers.isEmpty() || !losers.isEmpty() || !active.isEmpty()) {
            List<Map<String, Object>> groups = new ArrayList<>();
            if (!gainers.isEmpty()) groups.add(rankGroup("상승률 상위", "up", gainers.stream().map(MarketReportBuilder::moverRow).toList()));
            if (!losers.isEmpty()) groups.add(rankGroup("하락률 상위", "down", losers.stream().map(MarketReportBuilder::moverRow).toList()));
            if (!active.isEmpty()) groups.add(rankGroup("거래량 상위", "info", active.stream().map(MarketReportBuilder::moverRow).toList()));
            blocks.add(Map.of("type", "marketRanks", "attrs", Map.of("groups", groups)));
            blocks.add(paragraph(tone("순위는 주가 $5 이상, 거래량 100만 주 이상 종목만 모았습니다.", "muted")));
        }

        blocks.add(heading(3, "오늘 한국장 체크포인트"));
        blocks.add(checklist(usCheckpoints(changes, spyTrend)));
        return Optional.of(blocks);
    }

    // 빅테크 이야기: 몇 개 올랐는지 + 가장 강한·약한 종목의 하루·5거래일 흐름
    private static String techStory(List<String> tech, Map<String, Change> changes, Map<String, List<DailyBar>> series,
            LocalDate basis) {
        long up = tech.stream().filter(symbol -> changes.get(symbol).rate().signum() > 0).count();
        StringBuilder text = new StringBuilder();
        if (up == tech.size()) text.append("빅테크가 모두 올랐습니다.");
        else if (up == 0) text.append("빅테크가 모두 내렸습니다.");
        else text.append("빅테크 ").append(tech.size()).append("개 중 ").append(up).append("개가 올라 종목별로 엇갈렸습니다.");
        for (String symbol : tech.size() > 1 ? List.of(tech.getFirst(), tech.getLast()) : List.of(tech.getFirst())) {
            Change change = changes.get(symbol);
            Trend trend = trend(spark(series.get(symbol), basis));
            text.append(' ').append(topic(US_BIG_TECH.get(symbol))).append(" 하루 ").append(pct(change.rate().doubleValue()));
            if (trend.fiveDay() != null) {
                text.append(", 5거래일 ").append(pct(trend.fiveDay()));
                boolean sameWay = Math.signum(trend.fiveDay()) == change.rate().signum();
                text.append(sameWay ? "로 흐름이 이어졌습니다." : "로 최근 흐름과 반대로 움직였습니다.");
            } else {
                text.append("였습니다.");
            }
        }
        return text.toString();
    }

    // 미국장 결과로 본 한국장 체크포인트(정해 둔 규칙, 투자 권유 아님)
    private static List<String> usCheckpoints(Map<String, Change> changes, Trend spyTrend) {
        List<String> points = new ArrayList<>();
        Change qqq = changes.get("QQQ");
        if (qqq != null && qqq.rate().doubleValue() >= 1) {
            points.add("간밤 나스닥 강세(" + signed(qqq.rate()) + "%) — 한국 반도체·IT 대형주가 따라 움직이는지");
        } else if (qqq != null && qqq.rate().doubleValue() <= -1) {
            points.add("간밤 기술주 약세(" + signed(qqq.rate()) + "%) — 한국 반도체·성장주에 부담이 이어지는지");
        }
        Change iwm = changes.get("IWM");
        Change spy = changes.get("SPY");
        if (iwm != null && iwm.rate().doubleValue() - spy.rate().doubleValue() >= 1) {
            points.add("미국 중소형주 강세 — 코스닥 중소형주로 온기가 번지는지");
        }
        if (spyTrend.streak() >= 3) points.add("S&P 500 " + spyTrend.streak() + "거래일 연속 상승 — 단기 숨 고르기가 나오는지");
        else if (spyTrend.streak() <= -3) points.add("S&P 500 " + (-spyTrend.streak()) + "거래일 연속 하락 — 반등 시도가 나오는지");
        if (spyTrend.newHigh()) points.add("S&P 500 최근 20거래일 최고치 — 상승 추세가 이어지는지");
        if (points.isEmpty()) points.add("주요 지수 움직임이 크지 않음 — 개별 종목·실적 뉴스 위주의 장세가 될지");
        return points;
    }

    record Change(BigDecimal close, BigDecimal diff, BigDecimal rate, long volume) {
    }

    // basis 날짜 종가와 그 전 거래일 종가로 전일 대비·등락률을 셈
    static Optional<Change> change(List<DailyBar> bars, LocalDate basis) {
        if (bars == null) return Optional.empty();
        for (int i = 0; i < bars.size() - 1; i++) {
            if (!bars.get(i).date().equals(basis)) continue;
            DailyBar today = bars.get(i);
            BigDecimal previous = bars.get(i + 1).close();
            if (previous.signum() == 0) return Optional.empty();
            BigDecimal diff = today.close().subtract(previous);
            BigDecimal rate = diff.multiply(HUNDRED).divide(previous, 2, RoundingMode.HALF_UP);
            return Optional.of(new Change(today.close(), diff, rate, today.volume()));
        }
        return Optional.empty();
    }

    // basis까지 최근 SPARK_DAYS일 종가, 오래된 날짜부터
    private static List<Double> spark(List<DailyBar> bars, LocalDate basis) {
        if (bars == null) return List.of();
        List<Double> closes = new ArrayList<>(bars.stream().filter(bar -> !bar.date().isAfter(basis)).limit(SPARK_DAYS)
                .map(bar -> bar.close().doubleValue()).toList());
        Collections.reverse(closes);
        return closes;
    }

    private static String usSummary(Map<String, Change> changes) {
        List<BigDecimal> main = List.of("SPY", "QQQ", "DIA").stream().map(changes::get).filter(Objects::nonNull)
                .map(Change::rate).toList();
        double average = main.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        boolean mixed = main.stream().anyMatch(rate -> rate.signum() > 0)
                && main.stream().anyMatch(rate -> rate.signum() < 0);
        StringBuilder text = new StringBuilder("뉴욕 증시는 ");
        if (Math.abs(average) < 0.3) text.append(mixed ? "주요 지수가 엇갈리며 혼조세로" : "보합권에서");
        else if (average >= 1.5) text.append("주요 지수가 일제히 크게 오르며 강세로");
        else if (average > 0) text.append(mixed ? "대체로 오르며" : "주요 지수가 함께 오르며");
        else if (average <= -1.5) text.append("주요 지수가 일제히 크게 내리며 약세로");
        else text.append(mixed ? "대체로 내리며" : "주요 지수가 함께 내리며");
        text.append(" 마감했습니다.");

        Change qqq = changes.get("QQQ");
        Change dia = changes.get("DIA");
        if (qqq != null && dia != null) {
            double spread = qqq.rate().doubleValue() - dia.rate().doubleValue();
            if (spread >= 0.6) text.append(" 기술주 중심의 나스닥 100이 다우보다 강해, 기술주가 시장을 이끌었습니다.");
            else if (spread <= -0.6) text.append(" 기술주가 상대적으로 약했고, 전통 대형주 중심의 다우가 선방했습니다.");
        }
        Change iwm = changes.get("IWM");
        Change spy = changes.get("SPY");
        if (iwm != null) {
            double spread = iwm.rate().doubleValue() - spy.rate().doubleValue();
            if (spread >= 0.6) text.append(" 중소형주(러셀 2000)가 대형주보다 강했습니다.");
            else if (spread <= -0.6) text.append(" 중소형주(러셀 2000)는 대형주보다 부진했습니다.");
        }
        return text.toString();
    }

    private static String bigTechSummary(List<String> tech, Map<String, Change> changes) {
        long up = tech.stream().filter(symbol -> changes.get(symbol).rate().signum() > 0).count();
        String best = tech.getFirst();
        String worst = tech.getLast();
        String text = "빅테크 " + tech.size() + "개 중 " + up + "개가 올랐습니다. 가장 강했던 종목은 " + US_BIG_TECH.get(best)
                + "(" + signed(changes.get(best).rate()) + "%)";
        if (!best.equals(worst)) {
            text += ", 가장 약했던 종목은 " + US_BIG_TECH.get(worst) + "(" + signed(changes.get(worst).rate()) + "%)";
        }
        return text + "입니다.";
    }

    private static List<Mover> filterMovers(List<Mover> movers) {
        return movers.stream().filter(mover -> mover.price().compareTo(US_MIN_PRICE) >= 0)
                .filter(mover -> mover.volume() >= US_MIN_VOLUME).limit(5).toList();
    }

    private static Map<String, Object> moverRow(Mover mover) {
        // 이름 칸은 종목 기호, 그 옆 작은 글자는 회사 이름(있으면) · 거래량
        String sub = (mover.name() != null ? mover.name() + " · " : "") + "거래량 " + compact(mover.volume());
        return rankRow(mover.ticker(), usd(mover.price()), mover.changePercent(), sub);
    }

    // ───────────── 한국 ─────────────

    // histories: 지수 이름별 최근 종가(추이 그래프용, 없으면 빈 목록)
    public static Optional<List<Map<String, Object>>> krSection(LocalDate basis, List<IndexQuote> indices,
            List<StockQuote> stocks, Map<String, List<IndexClose>> histories) {
        Optional<IndexQuote> kospi = indices.stream().filter(index -> index.name().equals("코스피")).findFirst();
        if (kospi.isEmpty() || kospi.get().close() == null || kospi.get().rate() == null) return Optional.empty();
        Optional<IndexQuote> kosdaq = indices.stream().filter(index -> index.name().equals("코스닥"))
                .filter(index -> index.close() != null && index.rate() != null).findFirst();
        List<StockQuote> rated = stocks.stream().filter(stock -> stock.rate() != null && stock.name() != null).toList();

        double kospiRate = kospi.get().rate().doubleValue();
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(banner("kr", "한국 증시", dayLabel(basis) + " 마감", mood(kospiRate), moodLabel(kospiRate)));
        blocks.add(callout(mood(kospiRate), paragraph(text(krSummary(kospi.get(), kosdaq.orElse(null), rated)))));

        List<Map<String, Object>> stats = new ArrayList<>();
        for (IndexQuote index : kosdaq.isPresent() ? List.of(kospi.get(), kosdaq.get()) : List.of(kospi.get())) {
            List<Double> spark = histories.getOrDefault(index.name(), List.of()).stream()
                    .filter(close -> !close.date().isAfter(basis)).map(close -> close.close().doubleValue()).toList();
            if (spark.size() > SPARK_DAYS) spark = spark.subList(spark.size() - SPARK_DAYS, spark.size());
            stats.add(stat(index.name(), decimal2(index.close()),
                    index.change() == null ? "-" : arrow(index.change(), decimal2(index.change().abs())), index.rate(),
                    "거래대금 " + won(index.tradeValue()), spark));
        }
        blocks.add(Map.of("type", "marketStats", "attrs", Map.of("items", stats)));

        if (!rated.isEmpty()) {
            long up = rated.stream().filter(stock -> stock.rate().signum() > 0).count();
            long down = rated.stream().filter(stock -> stock.rate().signum() < 0).count();
            blocks.add(Map.of("type", "marketBreadth", "attrs", Map.of("up", up, "flat", rated.size() - up - down,
                    "down", down, "label", "코스피·코스닥 종목 등락")));

            List<StockQuote> caps = rated.stream().filter(stock -> stock.marketCap() != null)
                    .sorted(Comparator.comparing(StockQuote::marketCap).reversed()).limit(10).toList();
            blocks.add(heading(3, "한국 시장 이야기"));
            blocks.addAll(krStory(kospi.get(), kosdaq.orElse(null), rated, caps, histories, basis));
            if (!caps.isEmpty()) {
                List<Map<String, Object>> tiles = caps.stream().map(stock -> tile(stock.name(),
                        "시총 " + won(stock.marketCap()) + " · " + price(stock.close()), stock.rate())).toList();
                blocks.add(Map.of("type", "marketHeatmap", "attrs", Map.of("title", "시가총액 상위 10", "items", tiles)));
                StockQuote mover = caps.stream().max(Comparator.comparing(stock -> stock.rate().abs())).orElseThrow();
                blocks.add(paragraph(text("시가총액 상위 종목 중 가장 크게 움직인 종목은 " + mover.name() + "("
                        + signed(mover.rate()) + "%)입니다.")));
            }

            List<StockQuote> liquid = rated.stream().filter(stock -> stock.tradeValue() != null)
                    .filter(stock -> stock.tradeValue().compareTo(KR_MIN_TRADE_VALUE) >= 0).toList();
            List<StockQuote> gainers = liquid.stream().filter(stock -> stock.rate().signum() > 0)
                    .sorted(Comparator.comparing(StockQuote::rate).reversed()).limit(5).toList();
            List<StockQuote> losers = liquid.stream().filter(stock -> stock.rate().signum() < 0)
                    .sorted(Comparator.comparing(StockQuote::rate)).limit(5).toList();
            List<StockQuote> busiest = liquid.stream()
                    .sorted(Comparator.comparing(StockQuote::tradeValue).reversed()).limit(5).toList();
            List<Map<String, Object>> groups = new ArrayList<>();
            if (!gainers.isEmpty()) groups.add(rankGroup("상승률 상위", "up", gainers.stream().map(stock ->
                    rankRow(stock.name(), price(stock.close()), stock.rate(), marketName(stock))).toList()));
            if (!losers.isEmpty()) groups.add(rankGroup("하락률 상위", "down", losers.stream().map(stock ->
                    rankRow(stock.name(), price(stock.close()), stock.rate(), marketName(stock))).toList()));
            if (!busiest.isEmpty()) groups.add(rankGroup("거래대금 상위", "info", busiest.stream().map(stock ->
                    rankRow(stock.name(), won(stock.tradeValue()), stock.rate(), marketName(stock))).toList()));
            if (!groups.isEmpty()) {
                blocks.add(Map.of("type", "marketRanks", "attrs", Map.of("groups", groups)));
                blocks.add(paragraph(tone("상승률·하락률·거래대금 순위는 거래대금 10억 원 이상 종목만 모았습니다.", "muted")));
            }
        }
        blocks.add(heading(3, "다음 거래일 체크포인트"));
        blocks.add(checklist(krCheckpoints(kospi.get(), rated, histories, basis)));
        return Optional.of(blocks);
    }

    private static List<Double> closes(Map<String, List<IndexClose>> histories, String name, LocalDate basis) {
        List<Double> closes = histories.getOrDefault(name, List.of()).stream()
                .filter(close -> !close.date().isAfter(basis)).map(close -> close.close().doubleValue()).toList();
        return closes.size() > SPARK_DAYS ? closes.subList(closes.size() - SPARK_DAYS, closes.size()) : closes;
    }

    // 한국 이야기: 흐름 → 시장 폭 → 대형주 → 거래대금 쏠림 → 상·하한가
    private static List<Map<String, Object>> krStory(IndexQuote kospi, IndexQuote kosdaq, List<StockQuote> stocks,
            List<StockQuote> caps, Map<String, List<IndexClose>> histories, LocalDate basis) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        String flow = (trendSentence("코스피는", trend(closes(histories, "코스피", basis))) + " "
                + (kosdaq == null ? "" : trendSentence("코스닥은", trend(closes(histories, "코스닥", basis))))).strip();
        if (!flow.isEmpty()) blocks.add(story("흐름", flow));

        long up = stocks.stream().filter(stock -> stock.rate().signum() > 0).count();
        long down = stocks.stream().filter(stock -> stock.rate().signum() < 0).count();
        String breadth = String.format(Locale.ROOT, "코스피·코스닥 %,d개 종목 중 %,d개(%d%%)가 오르고 %,d개(%d%%)가 내렸습니다.",
                stocks.size(), up, Math.round(up * 100.0 / stocks.size()), down, Math.round(down * 100.0 / stocks.size()));
        double ratio = up + down == 0 ? 0.5 : (double) up / (up + down);
        if (kospi.rate().signum() > 0 && ratio < 0.45) breadth += " 지수는 올랐지만 오른 종목이 적어 '지수만 오른 장'에 가까웠습니다.";
        else if (kospi.rate().signum() < 0 && ratio > 0.55) breadth += " 지수는 내렸지만 오른 종목이 많아 개별 종목은 선방했습니다.";
        else if (ratio >= 0.65) breadth += " 대부분 종목이 함께 오른 '고른 강세'였습니다.";
        else if (ratio <= 0.35) breadth += " 대부분 종목이 함께 내린 '고른 약세'였습니다.";
        blocks.add(story("시장 체감", breadth));

        if (!caps.isEmpty()) {
            long capUp = caps.stream().filter(stock -> stock.rate().signum() > 0).count();
            StringBuilder big = new StringBuilder("시가총액 상위 " + caps.size() + "개 중 " + capUp + "개가 올랐습니다. ");
            for (int i = 0; i < Math.min(3, caps.size()); i++) {
                StockQuote stock = caps.get(i);
                big.append(i + 1).append("위 ").append(stock.name()).append('(').append(signed(stock.rate())).append("%)")
                        .append(i < Math.min(3, caps.size()) - 1 ? ", " : "");
            }
            big.append("의 움직임이 지수 방향을 좌우했습니다.");
            blocks.add(story("대형주", big.toString()));
        }

        BigDecimal total = stocks.stream().map(StockQuote::tradeValue).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<StockQuote> busiest = stocks.stream().filter(stock -> stock.tradeValue() != null)
                .sorted(Comparator.comparing(StockQuote::tradeValue).reversed()).limit(5).toList();
        if (total.signum() > 0 && !busiest.isEmpty()) {
            double topShare = busiest.getFirst().tradeValue().doubleValue() / total.doubleValue() * 100;
            double fiveShare = busiest.stream().mapToDouble(stock -> stock.tradeValue().doubleValue()).sum()
                    / total.doubleValue() * 100;
            blocks.add(story("돈의 흐름", String.format(Locale.ROOT,
                    "전체 거래대금은 %s입니다. 1위 %s에 %.1f%%, 상위 5종목에 %.1f%%가 몰렸습니다.", won(total),
                    busiest.getFirst().name(), topShare, fiveShare)
                    + (topShare >= 10 ? " 한 종목 쏠림이 강한 하루였습니다." : "")));
        }

        long upperLimit = stocks.stream().filter(stock -> stock.rate().doubleValue() >= 29.5).count();
        long lowerLimit = stocks.stream().filter(stock -> stock.rate().doubleValue() <= -29.5).count();
        if (upperLimit + lowerLimit > 0) {
            String names = stocks.stream().filter(stock -> stock.rate().doubleValue() >= 29.5).limit(3)
                    .map(StockQuote::name).reduce((a, b) -> a + ", " + b).map(list -> " (" + list + " 등)").orElse("");
            blocks.add(story("상·하한가", "상한가 " + upperLimit + "개" + names + ", 하한가 " + lowerLimit + "개가 나왔습니다."));
        }
        return blocks;
    }

    private static List<String> krCheckpoints(IndexQuote kospi, List<StockQuote> stocks,
            Map<String, List<IndexClose>> histories, LocalDate basis) {
        List<String> points = new ArrayList<>();
        double rate = kospi.rate().doubleValue();
        if (rate >= 1.5) points.add("코스피 강한 상승(" + signed(kospi.rate()) + "%) 다음 날 — 차익 실현 매물이 나오는지");
        else if (rate <= -1.5) points.add("코스피 급락(" + signed(kospi.rate()) + "%) 다음 날 — 저가 매수가 들어오는지");
        long up = stocks.stream().filter(stock -> stock.rate().signum() > 0).count();
        long down = stocks.stream().filter(stock -> stock.rate().signum() < 0).count();
        if (rate > 0 && up < down) points.add("오른 종목보다 내린 종목이 많음 — 대형주 쏠림이 이어지는지");
        if (kospi.yearHigh() != null) {
            double gap = (kospi.yearHigh().doubleValue() - kospi.close().doubleValue()) / kospi.yearHigh().doubleValue() * 100;
            if (gap <= 0) points.add("코스피 52주 최고치 경신 — 신고가 행진이 이어지는지");
            else if (gap <= 3) points.add(String.format(Locale.ROOT, "코스피 52주 최고치까지 %.1f%% — 돌파를 시도하는지", gap));
        }
        Trend trend = trend(closes(histories, "코스피", basis));
        if (trend.streak() >= 3) points.add("코스피 " + trend.streak() + "거래일 연속 상승 — 숨 고르기가 나오는지");
        else if (trend.streak() <= -3) points.add("코스피 " + (-trend.streak()) + "거래일 연속 하락 — 반등 계기가 생기는지");
        if (points.isEmpty()) points.add("뚜렷한 쏠림 없음 — 업종별 순환매 흐름을 확인");
        points.add("오늘 밤 미국장 — 내일 아침 브리핑에서 이어서 확인");
        return points;
    }

    private static String krSummary(IndexQuote kospi, IndexQuote kosdaq, List<StockQuote> stocks) {
        StringBuilder text = new StringBuilder(move("코스피는", kospi));
        if (kosdaq != null) {
            text.append(' ').append(move("코스닥은", kosdaq));
            double spread = kosdaq.rate().doubleValue() - kospi.rate().doubleValue();
            if (spread >= 0.7) text.append(" 코스닥이 더 강해 중소형주가 상대적으로 힘을 냈습니다.");
            else if (spread <= -0.7) text.append(" 코스피가 더 강해 대형주 중심의 장세였습니다.");
        }
        long up = stocks.stream().filter(stock -> stock.rate().signum() > 0).count();
        long down = stocks.stream().filter(stock -> stock.rate().signum() < 0).count();
        if (up + down > 0) {
            double ratio = (double) up / (up + down);
            int kospiSign = kospi.rate().signum();
            if (kospiSign > 0 && ratio < 0.45) {
                text.append(" 다만 오른 종목보다 내린 종목이 많아, 일부 대형주가 지수를 끌어올린 모습입니다.");
            } else if (kospiSign < 0 && ratio > 0.55) {
                text.append(" 지수는 내렸지만 오른 종목이 더 많아, 체감 분위기는 지수보다 나았습니다.");
            } else if (ratio >= 0.65) {
                text.append(" 대부분의 종목이 오른 고른 강세였습니다.");
            } else if (ratio <= 0.35) {
                text.append(" 대부분의 종목이 내린 고른 약세였습니다.");
            }
        }
        if (kospi.yearHigh() != null && kospi.close().compareTo(kospi.yearHigh()) >= 0) {
            text.append(" 코스피는 52주 최고치를 새로 썼습니다.");
        } else if (kospi.yearLow() != null && kospi.close().compareTo(kospi.yearLow()) <= 0) {
            text.append(" 코스피는 52주 최저치까지 내려왔습니다.");
        }
        if (kospi.tradeValue() != null) text.append(" 코스피 거래대금은 ").append(won(kospi.tradeValue())).append("입니다.");
        return text.toString();
    }

    // "코스피는 1.23% 올라 2,612.45에 마감했습니다." 같은 한 문장
    private static String move(String subject, IndexQuote index) {
        double rate = index.rate().doubleValue();
        String close = decimal2(index.close());
        if (Math.abs(rate) < 0.3) return subject + " " + signed(index.rate()) + "% 보합권에서 " + close + "에 마감했습니다.";
        String verb = rate >= 1.5 ? "크게 올라" : rate > 0 ? "올라" : rate <= -1.5 ? "크게 내려" : "내려";
        return subject + " " + index.rate().abs().setScale(2, RoundingMode.HALF_UP).toPlainString() + "% " + verb + " "
                + close + "에 마감했습니다.";
    }

    private static String marketName(StockQuote stock) {
        return "KOSDAQ".equals(stock.market()) ? "코스닥" : "코스피";
    }

    // ───────────── 분위기 ─────────────

    private static String mood(double rate) {
        return rate >= 0.3 ? "up" : rate <= -0.3 ? "down" : "flat";
    }

    private static String moodLabel(double rate) {
        if (rate >= 1.5) return "강한 상승";
        if (rate >= 0.3) return "상승";
        if (rate <= -1.5) return "강한 하락";
        if (rate <= -0.3) return "하락";
        return "보합";
    }

    // ───────────── 글자 모양 ─────────────

    static String dayLabel(LocalDate date) {
        return date.getMonthValue() + "월 " + date.getDayOfMonth() + "일(" + weekday(date) + ")";
    }

    private static String weekday(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
    }

    static String signed(BigDecimal rate) {
        BigDecimal value = rate.setScale(2, RoundingMode.HALF_UP);
        return (value.signum() > 0 ? "+" : "") + value.toPlainString();
    }

    private static String arrow(BigDecimal change, String absText) {
        if (change == null || change.signum() == 0) return "-";
        return (change.signum() > 0 ? "▲" : "▼") + absText;
    }

    static String usd(BigDecimal value) {
        return "$" + new DecimalFormat("#,##0.00").format(value);
    }

    static String decimal2(BigDecimal value) {
        return new DecimalFormat("#,##0.00").format(value);
    }

    static String price(BigDecimal value) {
        return new DecimalFormat("#,##0").format(value) + "원";
    }

    // 12345678 → 12.3M
    static String compact(long value) {
        if (value >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1fB", value / 1e9);
        if (value >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", value / 1e6);
        if (value >= 1_000L) return String.format(Locale.ROOT, "%.1fK", value / 1e3);
        return String.valueOf(value);
    }

    // 원 단위 금액 → "1조 2,345억원" / "345억원"
    static String won(BigDecimal value) {
        if (value == null) return "-";
        long eok = value.divide(EOK, 0, RoundingMode.HALF_UP).longValue();
        DecimalFormat format = new DecimalFormat("#,##0");
        if (eok >= 10_000) {
            long jo = eok / 10_000;
            long rest = eok % 10_000;
            return format.format(jo) + "조" + (rest > 0 ? " " + format.format(rest) + "억" : "") + "원";
        }
        if (eok > 0) return format.format(eok) + "억원";
        return format.format(value) + "원";
    }

    // ───────────── 편집기 문서 노드 ─────────────

    static Map<String, Object> text(String value) {
        return Map.of("type", "text", "text", value);
    }

    static Map<String, Object> heading(int level, String value) {
        return Map.of("type", "heading", "attrs", Map.of("level", level), "content", List.of(text(value)));
    }

    // 이름 뒤 "은/는": 마지막 글자에 받침이 있으면 은. 한글이 아니면 둘 다 적음.
    static String topic(String name) {
        char last = name.charAt(name.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) return name + "은(는)";
        return name + ((last - 0xAC00) % 28 == 0 ? "는" : "은");
    }

    static Map<String, Object> bold(String value) {
        return Map.of("type", "text", "text", value, "marks", List.of(Map.of("type", "bold")));
    }

    static Map<String, Object> tone(String value, String tone) {
        return Map.of("type", "text", "text", value, "marks",
                List.of(Map.of("type", "tone", "attrs", Map.of("tone", tone))));
    }

    @SafeVarargs
    static Map<String, Object> paragraph(Map<String, Object>... inline) {
        return Map.of("type", "paragraph", "content", List.of(inline));
    }

    @SafeVarargs
    private static Map<String, Object> callout(String tone, Map<String, Object>... blocks) {
        return Map.of("type", "callout", "attrs", Map.of("tone", tone), "content", List.of(blocks));
    }

    // mood·moodLabel이 null이면 배지 없이(준비 중·데이터 없음)
    private static Map<String, Object> banner(String market, String title, String date, String mood, String moodLabel) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("format", FORMAT);
        attrs.put("market", market);
        attrs.put("title", title);
        attrs.put("date", date);
        if (mood != null) attrs.put("mood", mood);
        if (moodLabel != null) attrs.put("moodLabel", moodLabel);
        return Map.of("type", "marketBanner", "attrs", attrs);
    }

    private static Map<String, Object> stat(String label, String value, String change, BigDecimal rate, String sub,
            List<Double> spark) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("value", value);
        item.put("change", change);
        if (rate != null) item.put("rate", rate.doubleValue());
        item.put("sub", sub);
        if (spark.size() >= 2) item.put("spark", spark);
        return item;
    }

    private static Map<String, Object> tile(String label, String sub, BigDecimal rate) {
        return Map.of("label", label, "sub", sub, "rate", rate.doubleValue());
    }

    private static Map<String, Object> rankGroup(String title, String tone, List<Map<String, Object>> items) {
        return Map.of("title", title, "tone", tone, "items", items);
    }

    private static Map<String, Object> rankRow(String name, String value, BigDecimal rate, String sub) {
        return Map.of("name", name, "value", value, "rate", rate.setScale(2, RoundingMode.HALF_UP).doubleValue(),
                "sub", sub);
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(map);
    }

    private static List<String> concat(Map<String, String> first, Map<String, String> second) {
        List<String> all = new ArrayList<>(first.keySet());
        all.addAll(second.keySet());
        return all;
    }
}
