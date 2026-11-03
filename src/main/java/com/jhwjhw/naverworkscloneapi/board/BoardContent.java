package com.jhwjhw.naverworkscloneapi.board;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

// 편집기(Tiptap)가 보낸 본문 JSON 검사. 받은 값을 그대로 저장하지 않고, 허용한 노드·서식·속성만 골라 새로 만듦.
// 그래서 스크립트·이벤트 속성·javascript: 링크 같은 것은 저장 단계에서 사라짐(화면도 HTML이 아니라 이 JSON을 그려서 보여 줌).
// 본문 속 사진은 주소 대신 첨부 ID(attachmentId)만 남기고, 그 ID가 이 글의 첨부인지는 서비스가 확인.
@Component
public class BoardContent {
    static final int MAX_JSON_LENGTH = 500_000;
    static final int MAX_TEXT_LENGTH = 50_000;
    private static final int MAX_NODES = 10_000;
    private static final int MAX_DEPTH = 30;
    private static final int MAX_HREF_LENGTH = 2048;

    // 블록 노드(앞뒤로 줄을 바꿔 글자를 뽑음)와 줄 안 노드
    private static final Set<String> BLOCKS = Set.of("paragraph", "heading", "bulletList", "orderedList", "listItem",
            "blockquote", "codeBlock", "horizontalRule", "image", "table", "tableRow");
    private static final Set<String> SIMPLE_MARKS = Set.of("bold", "italic", "strike", "code", "underline");
    // 표 칸 정렬과 글자 색 서식(tone)은 정해 둔 값만 남김(임의의 CSS 색·스타일은 받지 않음)
    private static final Set<String> CELL_ALIGNS = Set.of("left", "center", "right");
    private static final Set<String> TONES = Set.of("up", "down", "muted");

    private final ObjectMapper objectMapper;

    public BoardContent(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // 다시 만든 JSON, 검색·미리보기용 글자, 본문 속 사진의 첨부 ID들
    public record Sanitized(String json, String text, Set<Long> imageAttachmentIds) {
    }

    public Sanitized sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return build(Map.of("type", "doc", "content", List.of(Map.of("type", "paragraph"))), new State());
        }
        if (raw.length() > MAX_JSON_LENGTH) {
            throw new BoardException("BOARD_CONTENT_TOO_LONG", "본문이 너무 깁니다.");
        }
        Object parsed;
        try {
            parsed = objectMapper.readValue(raw, Object.class);
        } catch (JacksonException exception) {
            throw invalid();
        }
        if (!(parsed instanceof Map<?, ?> root) || !"doc".equals(root.get("type"))) {
            throw invalid();
        }
        State state = new State();
        Map<String, Object> doc = node(root, 0, state);
        return build(doc, state);
    }

    private Sanitized build(Map<String, Object> doc, State state) {
        String text = state.text.toString().replaceAll("\n{3,}", "\n\n").strip();
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new BoardException("BOARD_CONTENT_TOO_LONG", "본문은 " + MAX_TEXT_LENGTH + "자까지 쓸 수 있습니다.");
        }
        return new Sanitized(objectMapper.writeValueAsString(doc), text, state.images);
    }

    private static final class State {
        private int nodes;
        private final StringBuilder text = new StringBuilder();
        private final Set<Long> images = new LinkedHashSet<>();
    }

    private Map<String, Object> node(Map<?, ?> source, int depth, State state) {
        if (depth > MAX_DEPTH || ++state.nodes > MAX_NODES) {
            throw new BoardException("BOARD_CONTENT_TOO_LONG", "본문 구조가 너무 복잡합니다.");
        }
        if (!(source.get("type") instanceof String type)) throw invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        switch (type) {
            case "doc", "paragraph", "bulletList", "listItem", "blockquote", "table", "tableRow" -> {
            }
            case "tableHeader", "tableCell" -> {
                if (attrs(source).get("align") instanceof String align && CELL_ALIGNS.contains(align)) {
                    result.put("attrs", Map.of("align", align));
                }
            }
            case "heading" -> {
                int level = intAttr(source, "level", 1);
                result.put("attrs", Map.of("level", Math.min(3, Math.max(1, level))));
            }
            case "orderedList" -> result.put("attrs", Map.of("start", Math.max(1, intAttr(source, "start", 1))));
            case "codeBlock" -> {
                Object language = attrs(source).get("language");
                if (language instanceof String value && value.matches("[A-Za-z0-9+#-]{1,20}")) {
                    result.put("attrs", Map.of("language", value));
                }
            }
            case "hardBreak" -> {
                state.text.append('\n');
                return result;
            }
            case "horizontalRule" -> {
                state.text.append('\n');
                return result;
            }
            case "image" -> {
                Object id = attrs(source).get("attachmentId");
                if (!(id instanceof Number number) || number.longValue() <= 0) throw invalid();
                state.images.add(number.longValue());
                result.put("attrs", Map.of("attachmentId", number.longValue()));
                state.text.append('\n');
                return result;
            }
            case "text" -> {
                if (!(source.get("text") instanceof String value) || value.isEmpty()) throw invalid();
                result.put("text", value);
                List<Map<String, Object>> marks = marks(source.get("marks"));
                if (!marks.isEmpty()) result.put("marks", marks);
                state.text.append(value);
                return result;
            }
            default -> throw invalid();
        }
        Object content = source.get("content");
        if (content != null) {
            if (!(content instanceof List<?> children)) throw invalid();
            List<Map<String, Object>> sanitized = new ArrayList<>();
            for (Object child : children) {
                if (!(child instanceof Map<?, ?> childMap)) throw invalid();
                sanitized.add(node(childMap, depth + 1, state));
            }
            if (!sanitized.isEmpty()) result.put("content", sanitized);
        }
        if (BLOCKS.contains(type)) state.text.append('\n');
        // 표 칸 사이는 띄어쓰기 하나(검색·미리보기 글자에서 칸 내용이 붙지 않게)
        if (type.equals("tableHeader") || type.equals("tableCell")) state.text.append(' ');
        return result;
    }

    // 글자 서식. 모르는 서식은 버리고(오류 아님), 링크는 http·https·mailto 주소만 남김.
    private List<Map<String, Object>> marks(Object raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return result;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> mark) || !(mark.get("type") instanceof String type)) continue;
            if (SIMPLE_MARKS.contains(type)) {
                result.add(Map.of("type", type));
            } else if ("tone".equals(type)) {
                if (attrs(mark).get("tone") instanceof String tone && TONES.contains(tone)) {
                    result.add(Map.of("type", "tone", "attrs", Map.of("tone", tone)));
                }
            } else if ("link".equals(type)) {
                Object href = attrs(mark).get("href");
                if (href instanceof String value && safeHref(value)) {
                    result.add(Map.of("type", "link", "attrs", Map.of("href", value.strip())));
                }
            }
        }
        return result;
    }

    static boolean safeHref(String href) {
        String value = href.strip();
        if (value.isEmpty() || value.length() > MAX_HREF_LENGTH) return false;
        // 제어 문자·공백이 끼어 있으면 브라우저가 다르게 해석할 수 있어 거절
        if (value.chars().anyMatch(ch -> ch <= 0x20 || ch == 0x7F)) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:");
    }

    private static Map<?, ?> attrs(Map<?, ?> source) {
        return source.get("attrs") instanceof Map<?, ?> attrs ? attrs : Map.of();
    }

    private static int intAttr(Map<?, ?> source, String name, int fallback) {
        return attrs(source).get(name) instanceof Number number ? number.intValue() : fallback;
    }

    private static BoardException invalid() {
        return new BoardException("BOARD_CONTENT_INVALID", "본문 형식이 올바르지 않습니다.");
    }
}
