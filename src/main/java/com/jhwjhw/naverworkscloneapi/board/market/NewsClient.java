package com.jhwjhw.naverworkscloneapi.board.market;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

// 주요 뉴스: 연합뉴스 공식 RSS(경제·종합)와 Google 뉴스 RSS(한국어 경제 주요 기사). 키 없음.
// 기사 본문은 저작권 때문에 가져오지 않고 제목·언론사·시각·원문 링크만 씀.
// RSS는 최신 기사만 주므로 오늘 글에만 붙임(지난 날짜는 건너뜀).
public class NewsClient {
    // 연합뉴스 RSS는 최신순이라(중요도 순 피드 없음) 시장 관련 낱말이 든 기사만 고르고,
    // "주요"·"경제"는 중요도 순으로 정렬된 Google 뉴스 머리기사를 씀.
    static final String YNA_ECONOMY = "https://www.yna.co.kr/rss/economy.xml";
    static final String GOOGLE_TOP = "https://news.google.com/rss?hl=ko&gl=KR&ceid=KR:ko";
    static final String GOOGLE_BUSINESS =
            "https://news.google.com/rss/headlines/section/topic/BUSINESS?hl=ko&gl=KR&ceid=KR:ko";
    private static final java.util.regex.Pattern MARKET_WORDS = java.util.regex.Pattern.compile(
            "증시|코스피|코스닥|주가|환율|달러|금리|국채|채권|한은|연준|Fed|반도체|수출|물가|유가|실적|외국인|공매도|IPO|상장");

    // category: 화면에 붙는 분류(주요·경제·시장). summary: RSS가 준 첫 문장 요약(연합뉴스, 없으면 null).
    // related: 같은 소식을 다룬 다른 언론사 보도(Google 뉴스 묶음, 없으면 빈 목록)
    public record Article(String category, String title, String source, String link, ZonedDateTime publishedAt,
            String summary, List<Related> related) {
    }

    public record Related(String title, String source, String link) {
    }

    // Google 뉴스 설명란의 묶음 기사: <a href="주소">제목</a>&nbsp;&nbsp;<font ...>언론사</font>
    private static final java.util.regex.Pattern RELATED = java.util.regex.Pattern.compile(
            "<a href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>(?:&nbsp;|\\s)*<font[^>]*>(.*?)</font>");
    // 연합뉴스 요약 앞머리 "(서울=연합뉴스) 홍길동 기자 = "
    private static final java.util.regex.Pattern BYLINE = java.util.regex.Pattern.compile(
            "^\\([^)]{1,30}=[^)]{1,20}\\)\\s*(?:[^=]{1,40}=\\s*)?");

    // 사진·인사·부고처럼 "메인 뉴스"로 보기 어려운 기사 머리말
    private static final List<String> SKIP_PREFIXES = List.of("[포토]", "[그래픽]", "[사진]", "[인사]", "[부고]", "[게시판]",
            "[표]", "[날씨]", "[오늘의 운세]", "[영상]", "[연합뉴스 이 시각 헤드라인]", "[모멘트]");

    private final MarketHttp http;

    public NewsClient(MarketHttp http) {
        this.http = http;
    }

    // 주요 4 + 경제 4(Google 머리기사) + 시장 2(연합뉴스 경제 중 시장 낱말), 제목이 겹치는 기사는 하나만.
    // since보다 오래된 기사는 뺌.
    public List<Article> headlines(ZonedDateTime since) {
        List<Article> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        pick(result, seen, fetch(GOOGLE_TOP, "주요", null), since, 4, false);
        pick(result, seen, fetch(GOOGLE_BUSINESS, "경제", null), since, 4, false);
        pick(result, seen, fetch(YNA_ECONOMY, "시장", "연합뉴스"), since, 2, true);
        return result;
    }

    private static void pick(List<Article> result, Set<String> seen, List<Article> articles, ZonedDateTime since,
            int count, boolean marketOnly) {
        int added = 0;
        for (Article article : articles) {
            if (added >= count) return;
            if (article.publishedAt() != null && article.publishedAt().isBefore(since)) continue;
            if (marketOnly && !MARKET_WORDS.matcher(article.title()).find()) continue;
            if (SKIP_PREFIXES.stream().anyMatch(article.title()::startsWith)) continue;
            String key = article.title().replaceAll("[\\s\\p{Punct}·…‘’“”]", "");
            if (key.length() > 18) key = key.substring(0, 18);
            if (!seen.add(key)) continue;
            result.add(article);
            added++;
        }
    }

    // 한 RSS를 읽음. 실패하면 빈 목록(다른 RSS로 채움).
    private List<Article> fetch(String url, String category, String fixedSource) {
        try {
            return parse(http.get(URI.create(url)), category, fixedSource);
        } catch (IOException | RuntimeException exception) {
            return List.of();
        }
    }

    static List<Article> parse(String xml, String category, String fixedSource) throws IOException {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 외부 엔티티·DTD를 막아 XML 폭탄·파일 읽기(XXE)를 차단
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IOException("News RSS parse failed: " + exception.getClass().getSimpleName());
        }
        List<Article> result = new ArrayList<>();
        NodeList items = document.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            if (!(items.item(i) instanceof Element item)) continue;
            String title = child(item, "title");
            String link = child(item, "link");
            if (title == null || link == null || !(link.startsWith("https://") || link.startsWith("http://"))) continue;
            String source = fixedSource != null ? fixedSource : child(item, "source");
            // Google 뉴스 제목은 "제목 - 언론사" 꼴이라 뒤의 언론사를 뗌
            if (fixedSource == null && source != null && title.endsWith(" - " + source)) {
                title = title.substring(0, title.length() - source.length() - 3).strip();
            }
            String description = child(item, "description");
            String summary = null;
            List<Related> related = new ArrayList<>();
            if (description != null && description.contains("<a ")) {
                // Google 뉴스: 첫 줄은 이 기사 자신이라 빼고 나머지 묶음 기사
                java.util.regex.Matcher matcher = RELATED.matcher(description);
                while (matcher.find() && related.size() < 4) {
                    String relatedTitle = unescape(strip(matcher.group(2)));
                    String relatedSource = unescape(strip(matcher.group(3)));
                    if (relatedTitle.isEmpty() || relatedTitle.equals(title)) continue;
                    related.add(new Related(relatedTitle, relatedSource, matcher.group(1)));
                }
            } else if (description != null) {
                summary = BYLINE.matcher(unescape(strip(description))).replaceFirst("").strip();
                if (summary.isEmpty() || summary.equals(title)) summary = null;
            }
            result.add(new Article(category, title, source == null ? "" : source, link, date(child(item, "pubDate")),
                    summary, related));
        }
        return result;
    }

    private static String strip(String html) {
        return html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
    }

    private static String unescape(String text) {
        return text.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&").strip();
    }

    private static String child(Element item, String name) {
        NodeList nodes = item.getElementsByTagName(name);
        if (nodes.getLength() == 0) return null;
        Node node = nodes.item(0);
        String text = node.getTextContent();
        if (text == null) return null;
        text = text.replaceAll("\\s+", " ").strip();
        return text.isEmpty() ? null : text;
    }

    private static ZonedDateTime date(String value) {
        if (value == null) return null;
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH));
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
