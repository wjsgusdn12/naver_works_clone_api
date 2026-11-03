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
    public static Map<String, Object> document(LocalDate date, String greeting, List<Map<String, Object>> us,
            List<Map<String, Object>> kr, boolean usEnabled, boolean krEnabled) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        boolean report = usEnabled || krEnabled;
        if (report) {
            blocks.add(Map.of("type", "marketHero", "attrs", Map.of("title", "Daily Market Report",
                    "date", date.getYear() + "년 " + date.getMonthValue() + "월 " + date.getDayOfMonth() + "일 ("
                            + weekday(date) + ")")));
        }
        blocks.add(paragraph(text(greeting)));
        if (us != null) blocks.addAll(us);
        else if (usEnabled) blocks.addAll(pending("us", "미국 증시", "⏳ 간밤 미국 증시 분석을 불러오는 중입니다."));
        if (kr != null) blocks.addAll(kr);
        else if (krEnabled) {
            blocks.addAll(pending("kr", "한국 증시", "⏳ 한국 증시 분석은 오후 2시 이후에 추가됩니다. "
                    + "(공공데이터 시세가 다음 영업일 오후 1시 이후에 올라옵니다)"));
        }
        if (report) {
            blocks.add(callout("muted", paragraph(text("※ 시세 숫자로 자동 작성한 요약이며 투자 권유가 아닙니다. "
                    + "미국 지수는 지수를 따라가는 대표 ETF 시세입니다. 출처: 금융위원회(공공데이터포털), Alpha Vantage"))));
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
        if (!tech.isEmpty()) {
            List<Map<String, Object>> tiles = tech.stream().map(symbol -> tile(US_BIG_TECH.get(symbol),
                    symbol + " · " + usd(changes.get(symbol).close()), changes.get(symbol).rate())).toList();
            blocks.add(Map.of("type", "marketHeatmap", "attrs", Map.of("title", "빅테크 7", "items", tiles)));
            blocks.add(paragraph(text(bigTechSummary(tech, changes))));
        }

        if (movers != null && basis.equals(movers.date())) {
            List<Mover> gainers = filterMovers(movers.gainers());
            List<Mover> losers = filterMovers(movers.losers());
            List<Map<String, Object>> groups = new ArrayList<>();
            if (!gainers.isEmpty()) groups.add(rankGroup("🚀 급등 TOP", "up", gainers.stream().map(MarketReportBuilder::moverRow).toList()));
            if (!losers.isEmpty()) groups.add(rankGroup("🧊 급락 TOP", "down", losers.stream().map(MarketReportBuilder::moverRow).toList()));
            if (!groups.isEmpty()) {
                blocks.add(Map.of("type", "marketRanks", "attrs", Map.of("groups", groups)));
                blocks.add(paragraph(tone("급등·급락은 주가 $5 이상, 거래량 100만 주 이상 종목만 모았습니다.", "muted")));
            }
        }
        return Optional.of(blocks);
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
        return rankRow(mover.ticker(), usd(mover.price()), mover.changePercent(), "거래량 " + compact(mover.volume()));
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
            if (!caps.isEmpty()) {
                List<Map<String, Object>> tiles = caps.stream().map(stock -> tile(stock.name(),
                        "시총 " + won(stock.marketCap()) + " · " + price(stock.close()), stock.rate())).toList();
                blocks.add(Map.of("type", "marketHeatmap", "attrs", Map.of("title", "시가총액 TOP 10", "items", tiles)));
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
            if (!gainers.isEmpty()) groups.add(rankGroup("🚀 상승률 TOP", "up", gainers.stream().map(stock ->
                    rankRow(stock.name(), price(stock.close()), stock.rate(), marketName(stock))).toList()));
            if (!losers.isEmpty()) groups.add(rankGroup("🧊 하락률 TOP", "down", losers.stream().map(stock ->
                    rankRow(stock.name(), price(stock.close()), stock.rate(), marketName(stock))).toList()));
            if (!busiest.isEmpty()) groups.add(rankGroup("💰 거래대금 TOP", "info", busiest.stream().map(stock ->
                    rankRow(stock.name(), won(stock.tradeValue()), stock.rate(), marketName(stock))).toList()));
            if (!groups.isEmpty()) {
                blocks.add(Map.of("type", "marketRanks", "attrs", Map.of("groups", groups)));
                blocks.add(paragraph(tone("상승률·하락률·거래대금 순위는 거래대금 10억 원 이상 종목만 모았습니다.", "muted")));
            }
        }
        return Optional.of(blocks);
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
