package com.jhwjhw.naverworkscloneapi.chat;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

// 링크 미리보기용으로 웹사이트에 접속해 제목·대표 이미지 주소를 읽고, 대표 이미지를 받아 옴.
// 서버가 사용자 대신 외부 주소에 접속하므로 내부망을 찌르는 공격(SSRF)을 막는 것이 핵심:
// - http/https, 기본 포트(80·443)만. 주소에 사용자 정보(user@) 금지.
// - 이름을 IP로 바꿔 사설망(10.x·192.168.x 등)·루프백(127.x)·링크 로컬(169.254.x, 클라우드 메타데이터)·
//   통신사 공유(100.64.x)·IPv6 내부 주소면 거절.
// - 다른 주소로 넘기기(리다이렉트)는 자동으로 따라가지 않고 최대 3번까지 매번 같은 검사 후 따라감.
// - 연결 3초·응답 5초 제한, HTML은 512KB·이미지는 5MB까지만 읽음.
// (한계: 검사 뒤 실제 접속 때 이름을 다시 풀기 때문에 DNS를 짧은 순간에 바꾸는 공격은 완전히 막지 못함.
//  배포 때 서버의 외부 접속 방화벽으로 내부망을 한 번 더 막음)
@Component
public class ChatLinkPreviewFetcher {
    static final int MAX_HTML_BYTES = 512 * 1024;
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_TITLE_LENGTH = 300;
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; NaverWorksCloneLinkPreview/1.0)";

    private static final Pattern META = Pattern.compile("<meta\\s[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([a-zA-Z_:.-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*[\"']?([a-zA-Z0-9_.:-]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    // 주소 이름 → IP 목록. 테스트에서 실제 DNS 없이 바꿔 끼우기 위해 분리.
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    // 웹 페이지에서 읽은 제목·대표 이미지 주소(없으면 null)
    record Page(String title, URI imageUrl) {
    }

    private final HttpClient client;
    private final Resolver resolver;

    public ChatLinkPreviewFetcher() {
        this(InetAddress::getAllByName);
    }

    ChatLinkPreviewFetcher(Resolver resolver) {
        this.resolver = resolver;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    // 웹 페이지를 받아 제목·대표 이미지 주소를 읽음. HTML이 아니면 제목·이미지 없이 돌려줌.
    Page fetchPage(URI url) throws IOException {
        Body body = get(url, "text/html,application/xhtml+xml", MAX_HTML_BYTES, false);
        String type = body.contentType().toLowerCase(Locale.ROOT);
        if (!type.contains("html")) return new Page(null, null);
        return parse(decode(body.bytes(), body.contentType()), body.finalUrl());
    }

    // 대표 이미지를 받음. 5MB를 넘으면 null(작은 사본을 만들 수 없는 큰 이미지는 쓰지 않음).
    byte[] fetchImage(URI url) throws IOException {
        Body body = get(url, "image/*", MAX_IMAGE_BYTES, true);
        return body.bytes();
    }

    private record Body(URI finalUrl, String contentType, byte[] bytes) {
    }

    // 주소를 검사하고 받음. 리다이렉트는 매번 다시 검사하며 최대 3번. rejectOversize면 상한을 넘을 때 null 본문.
    private Body get(URI start, String accept, int maxBytes, boolean rejectOversize) throws IOException {
        URI url = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            requirePublic(url, resolver);
            HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5))
                    .header("User-Agent", USER_AGENT).header("Accept", accept)
                    .header("Accept-Language", "ko,en;q=0.8").GET().build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("링크를 받는 중 중단되었습니다.", exception);
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                response.body().close();
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("이동할 주소가 없습니다."));
                url = url.resolve(location.trim());
                continue;
            }
            try (InputStream in = response.body()) {
                if (status != 200) throw new IOException("응답 코드 " + status);
                byte[] bytes = in.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    if (rejectOversize) return new Body(url, "", null);
                    bytes = Arrays.copyOf(bytes, maxBytes);
                }
                return new Body(url, response.headers().firstValue("Content-Type").orElse(""), bytes);
            }
        }
        throw new IOException("다른 주소로 너무 많이 넘어갑니다.");
    }

    // 외부 공개 주소인지 검사. 아니면 IOException(그 링크는 미리보기 없이 "제목 없는 웹사이트").
    static void requirePublic(URI url, Resolver resolver) throws IOException {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new IOException("http/https만 허용합니다.");
        if (url.getHost() == null || url.getRawUserInfo() != null) throw new IOException("주소 형식이 올바르지 않습니다.");
        int port = url.getPort();
        if (port != -1 && port != 80 && port != 443) throw new IOException("기본 포트만 허용합니다.");
        String host = url.getHost();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        InetAddress[] addresses = resolver.resolve(host);
        if (addresses.length == 0) throw new IOException("주소를 찾을 수 없습니다.");
        for (InetAddress address : addresses) {
            if (!isPublic(address)) throw new IOException("내부 주소는 열 수 없습니다.");
        }
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address) return isPublicV4(raw);
        if (address instanceof Inet6Address) {
            // ::ffff:a.b.c.d(IPv4를 감싼 주소)는 안의 IPv4로 다시 검사
            boolean mapped = true;
            for (int i = 0; i < 10; i++) mapped &= raw[i] == 0;
            if (mapped && (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF) {
                return isPublicV4(Arrays.copyOfRange(raw, 12, 16));
            }
            // fc00::/7 (사설 IPv6)
            return (raw[0] & 0xFE) != 0xFC;
        }
        return false;
    }

    private static boolean isPublicV4(byte[] ip) {
        int a = ip[0] & 0xFF;
        int b = ip[1] & 0xFF;
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false; // 0.x·사설·루프백·멀티캐스트·예약
        if (a == 100 && b >= 64 && b <= 127) return false; // 통신사 공유 주소(CGNAT)
        if (a == 169 && b == 254) return false; // 링크 로컬·클라우드 메타데이터
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && b == 168) return false;
        if (a == 192 && b == 0 && (ip[2] & 0xFF) == 0) return false;
        if (a == 198 && (b == 18 || b == 19)) return false; // 성능 시험용
        return true;
    }

    // 응답 헤더나 HTML 앞부분의 charset으로 글자를 풂(없으면 UTF-8). 한국 옛 사이트의 EUC-KR도 처리.
    static String decode(byte[] bytes, String contentType) {
        Charset charset = charsetOf(contentType);
        if (charset == null) {
            String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
            Matcher meta = Pattern.compile("<meta[^>]+charset\\s*=\\s*[\"']?([a-zA-Z0-9_.:-]+)",
                    Pattern.CASE_INSENSITIVE).matcher(head);
            if (meta.find()) charset = charsetNamed(meta.group(1));
        }
        return new String(bytes, charset == null ? StandardCharsets.UTF_8 : charset);
    }

    private static Charset charsetOf(String contentType) {
        Matcher matcher = CHARSET.matcher(contentType == null ? "" : contentType);
        return matcher.find() ? charsetNamed(matcher.group(1)) : null;
    }

    private static Charset charsetNamed(String name) {
        try {
            return Charset.forName(name);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    // HTML에서 제목(og:title → twitter:title → <title>)과 대표 이미지(og:image → twitter:image)를 읽음.
    static Page parse(String html, URI base) {
        Map<String, String> meta = new HashMap<>();
        Matcher tags = META.matcher(html);
        while (tags.find()) {
            Map<String, String> attributes = new HashMap<>();
            Matcher attribute = ATTRIBUTE.matcher(tags.group());
            while (attribute.find()) {
                String value = attribute.group(2) != null ? attribute.group(2)
                        : attribute.group(3) != null ? attribute.group(3) : attribute.group(4);
                attributes.put(attribute.group(1).toLowerCase(Locale.ROOT), value);
            }
            String key = attributes.getOrDefault("property", attributes.get("name"));
            String content = attributes.get("content");
            if (key != null && content != null) meta.putIfAbsent(key.toLowerCase(Locale.ROOT), content);
        }
        String title = firstNonBlank(meta.get("og:title"), meta.get("twitter:title"), titleTag(html));
        String image = firstNonBlank(meta.get("og:image"), meta.get("og:image:url"), meta.get("twitter:image"),
                meta.get("twitter:image:src"));
        return new Page(clean(title), imageUrl(image, base));
    }

    private static String titleTag(String html) {
        Matcher matcher = TITLE.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    // HTML 문자 표기(&amp; 등)를 풀고 공백을 한 칸으로, 너무 길면 자름.
    static String clean(String raw) {
        if (raw == null) return null;
        String text = raw.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'");
        Matcher numeric = NUMERIC_ENTITY.matcher(text);
        StringBuilder decoded = new StringBuilder();
        while (numeric.find()) {
            String replacement;
            try {
                int codePoint = Integer.parseInt(numeric.group(2), numeric.group(1).isEmpty() ? 10 : 16);
                replacement = new String(Character.toChars(codePoint));
            } catch (IllegalArgumentException exception) {
                replacement = "";
            }
            numeric.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
        }
        numeric.appendTail(decoded);
        text = decoded.toString().replace("&amp;", "&").replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) return null;
        return text.length() > MAX_TITLE_LENGTH ? text.substring(0, MAX_TITLE_LENGTH) : text;
    }

    // 상대 주소(/img/a.png)는 페이지 주소 기준으로 풂. http/https가 아니면 버림.
    private static URI imageUrl(String raw, URI base) {
        if (raw == null) return null;
        try {
            URI resolved = base.resolve(new URI(clean(raw).replace(" ", "%20")));
            String scheme = resolved.getScheme() == null ? "" : resolved.getScheme().toLowerCase(Locale.ROOT);
            return scheme.equals("http") || scheme.equals("https") ? resolved : null;
        } catch (URISyntaxException | IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }
}
