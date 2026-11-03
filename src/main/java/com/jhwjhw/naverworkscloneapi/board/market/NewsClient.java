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
    // SBS 뉴스 편집 헤드라인(15건)·매일경제 헤드라인: 설명란에 본문 앞부분이 와서 요약으로 씀
    static final String SBS_HEADLINE = "https://news.sbs.co.kr/news/headlineRssFeed.do?plink=RSSREADER";
    static final String MK_HEADLINE = "https://www.mk.co.kr/rss/30100041/";
    // 사진 기사 설명 끝: "2026.11.3 [홍길동기자]"
    private static final java.util.regex.Pattern PHOTO_CREDIT = java.util.regex.Pattern.compile(
            "\\d{4}\\.\\d{1,2}\\.\\d{1,2}\\s*\\[[^\\]]{1,20}기자\\]\\s*$");
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
        // 요약(본문 앞부분)이 함께 오는 언론사 헤드라인 RSS를 먼저, 막히면 Google 뉴스(요약 없음)로 대신
        List<Article> top = fetch(SBS_HEADLINE, "주요", "SBS");
        pick(result, seen, top.isEmpty() ? fetch(GOOGLE_TOP, "주요", null) : top, since, 4, false);
        List<Article> economy = fetch(MK_HEADLINE, "경제", "매일경제");
        pick(result, seen, economy.isEmpty() ? fetch(GOOGLE_BUSINESS, "경제", null) : economy, since, 4, false);
        pick(result, seen, fetch(YNA_ECONOMY, "시장", "연합뉴스"), since, 2, true);
        return result.stream().map(this::withShareDescription).toList();
    }

    // 기사 페이지에서 읽어도 되는 언론사(공유용 설명만 읽음, 본문은 읽지 않음)
    private static final Set<String> SHARE_HOSTS = Set.of("news.sbs.co.kr", "www.mk.co.kr", "www.yna.co.kr");
    private static final java.util.regex.Pattern OG_DESCRIPTION = java.util.regex.Pattern.compile(
            "<meta[^>]+property=[\"']og:description[\"'][^>]+content=[\"']([^\"']*)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final int MERGED_MAX = 320;

    // RSS 요약 + 기사 페이지의 공유용 설명(og:description, 언론사가 SNS 미리보기용으로 내놓은 글)을 겹치지 않게 합쳐
    // 2~3줄로. 페이지를 못 읽으면 RSS 요약 그대로.
    private Article withShareDescription(Article article) {
        String share = null;
        try {
            URI uri = URI.create(article.link());
            if (SHARE_HOSTS.contains(uri.getHost()) && "https".equals(uri.getScheme())) {
                java.util.regex.Matcher matcher = OG_DESCRIPTION.matcher(http.get(uri));
                if (matcher.find()) share = summarize(matcher.group(1));
            }
        } catch (IOException | RuntimeException ignored) {
            // 공유용 설명 없이
        }
        String merged = merge(article.summary(), share);
        return new Article(article.category(), article.title(), article.source(), article.link(), article.publishedAt(),
                merged, article.related());
    }

    // 두 요약을 문장 단위로 합침(같은 문장·서로 포함되는 문장은 한 번만). 최대 320자.
    static String merge(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank()) return first;
        String a = first.replaceAll("…$", "").strip();
        String b = second.replaceAll("…$", "").strip();
        String plainA = a.replaceAll("\\s", "");
        String plainB = b.replaceAll("\\s", "");
        if (plainA.contains(plainB)) return first;
        if (plainB.contains(plainA)) return second;
        // 공유용 설명이 RSS 요약의 앞부분과 같게 시작하면(같은 문장의 긴 판) 긴 쪽만
        String head = plainA.substring(0, Math.min(20, plainA.length()));
        if (plainB.startsWith(head)) return b.length() >= a.length() ? second : first;
        // RSS 요약(본문 첫머리)이 먼저. 잘려 끝난 RSS 요약은 마지막 온전한 문장까지만 남기고 이어 붙임.
        if (first.strip().endsWith("…")) {
            int end = Math.max(Math.max(a.lastIndexOf("다."), a.lastIndexOf("요.")), a.lastIndexOf("음."));
            a = end > 0 ? a.substring(0, end + 2) : "";
        }
        if (a.isBlank()) return second;
        String joined = a + " " + b;
        return joined.length() <= MERGED_MAX ? joined : joined.substring(0, MERGED_MAX).strip() + "…";
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
                // 사진 한 장과 설명만 있는 기사는 메인 뉴스로 보기 어려워 뺌
                if (PHOTO_CREDIT.matcher(unescape(strip(description))).find()) continue;
                summary = summarize(description);
                if (summary == null || summary.equals(title)) summary = null;
            }
            result.add(new Article(category, title, source == null ? "" : source, link, date(child(item, "pubDate")),
                    summary, related));
        }
        return result;
    }

    private static final int SUMMARY_MAX = 240;

    // 설명란(본문 앞부분) → 요약: 태그·기자 이름·사진 설명을 떼고 앞 두세 문장(최대 240자)만
    static String summarize(String description) {
        String text = unescape(strip(description));
        text = BYLINE.matcher(text).replaceFirst("");
        // 문장 끝(다. 요. 음.) 뒤에 띄어쓰기가 빠진 경우를 띄워 줌
        text = text.replaceAll("(다|요|음)\\.(?=\\S)", "$1. ");
        if (text.startsWith("▲") || text.startsWith("△")) {
            // 맨 앞 사진 설명: "▲ 발언하는 모습이다." 처럼 짧게 끝나면 그 문장만, 마침표 없이 본문 첫 문장과
            // 붙어 있으면(경계를 알 수 없음) 첫 문장째 뺌
            java.util.regex.Matcher first = java.util.regex.Pattern.compile("^.*?[다요음]\\.\\s*").matcher(text);
            int photo = text.indexOf("자료사진");
            if (photo >= 0 && photo < 60) text = text.substring(photo + "자료사진".length());
            else if (first.find()) text = text.substring(first.end());
            // 설명과 본문의 경계를 알 수 없으면 엉킨 문장을 보여 주지 않고 요약 없이
            else return null;
        }
        text = text.strip();
        if (text.isEmpty()) return null;
        StringBuilder summary = new StringBuilder();
        for (String sentence : text.split("(?<=[다요음]\\.)\\s+")) {
            if (summary.length() + sentence.length() > SUMMARY_MAX) break;
            summary.append(sentence).append(' ');
            if (summary.length() >= 120) break;
        }
        String result = summary.toString().strip();
        if (result.isEmpty()) result = text.substring(0, Math.min(SUMMARY_MAX, text.length())).strip() + "…";
        return result.replaceAll("\\.{2,}$", "…");
    }

    private static String strip(String html) {
        return html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
    }

    private static final java.util.regex.Pattern NUMERIC_ENTITY = java.util.regex.Pattern.compile("&#(x?)([0-9a-fA-F]{1,6});");

    private static String unescape(String text) {
        String named = text.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&middot;", "·").replace("&hellip;", "…");
        // &#34; &#9650; 같은 숫자 문자 참조
        java.util.regex.Matcher matcher = NUMERIC_ENTITY.matcher(named);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            int code;
            try {
                code = Integer.parseInt(matcher.group(2), matcher.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException exception) {
                code = -1;
            }
            String replacement = code > 0 && Character.isValidCodePoint(code) && !Character.isISOControl(code)
                    ? new String(Character.toChars(code)) : " ";
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString().replace("&amp;", "&").replaceAll("\\s+", " ").strip();
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
        // "+09:00"처럼 시차에 콜론이 든 형식(매일경제)도 RFC 1123 꼴("+0900")로 맞춤
        String normalized = value.strip().replaceAll("([+-]\\d{2}):(\\d{2})$", "$1$2");
        try {
            return ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH));
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
