// README 그림을 그리는 작은 도구 모음(외부 라이브러리 없음).
// 모든 그림이 같은 카드·화살표·색·글꼴을 쓰도록 여기 한곳에 모았다.
// 좌표는 px, 그림 하나의 폭은 기본 960.

import { ICONS } from "./icons.mjs";

export const FONT =
  "Pretendard, 'Apple SD Gothic Neo', 'Malgun Gothic', 'Noto Sans KR', 'Segoe UI', sans-serif";

// 색 묶음: 선(line)·글(ink)·바탕(bg)·진한 강조(solid)
export const TONES = {
  blue: { solid: "#2563eb", line: "#93c5fd", bg: "#eff6ff", ink: "#1e3a8a" },
  violet: { solid: "#7c3aed", line: "#c4b5fd", bg: "#f5f3ff", ink: "#4c1d95" },
  green: { solid: "#059669", line: "#6ee7b7", bg: "#ecfdf5", ink: "#064e3b" },
  amber: { solid: "#d97706", line: "#fcd34d", bg: "#fffbeb", ink: "#78350f" },
  rose: { solid: "#e11d48", line: "#fda4af", bg: "#fff1f2", ink: "#881337" },
  teal: { solid: "#0d9488", line: "#5eead4", bg: "#f0fdfa", ink: "#134e4a" },
  sky: { solid: "#0284c7", line: "#7dd3fc", bg: "#f0f9ff", ink: "#0c4a6e" },
  slate: { solid: "#475569", line: "#cbd5e1", bg: "#f8fafc", ink: "#0f172a" },
};

const INK = "#0f172a";
const MUTED = "#64748b";

const esc = (value) =>
  String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

// 글자 폭 어림(한글은 거의 정사각형, 영문은 그 절반쯤). 상자 크기를 글에 맞출 때 씀.
export const textWidth = (value, size = 14, weight = 400) => {
  let units = 0;
  for (const ch of String(value)) {
    const code = ch.codePointAt(0);
    if (code >= 0x1100 && code <= 0xffdc && !(code >= 0x2000 && code <= 0x206f)) units += 0.94;
    else if (ch === " ") units += 0.28;
    else if (/[A-Z0-9#@%&]/.test(ch)) units += 0.62;
    else if (/[a-z]/.test(ch)) units += 0.52;
    else units += 0.36;
  }
  return units * size * (weight >= 600 ? 1.04 : 1);
};

// 글 한 줄
export const text = (x, y, value, { size = 13, weight = 400, color = INK, anchor = "start" } = {}) =>
  `<text x="${x}" y="${y}" font-size="${size}" font-weight="${weight}" fill="${color}" text-anchor="${anchor}">${esc(value)}</text>`;

// 여러 줄(위에서부터 lineHeight 간격)
export const lines = (x, y, values, options = {}) => {
  const step = options.lineHeight ?? (options.size ?? 13) * 1.55;
  return values.map((value, index) => text(x, y + index * step, value, options)).join("");
};

// lucide 아이콘(24×24 선 그림)을 원하는 크기로
export const icon = (name, x, y, size = 20, color = INK, strokeWidth = 2) => {
  const node = ICONS[name];
  if (!node) throw new Error(`아이콘 없음: ${name}`);
  const scale = size / 24;
  const parts = node
    .map(
      ([tag, attrs]) =>
        `<${tag} ${Object.entries(attrs)
          .map(([key, value]) => `${key}="${esc(value)}"`)
          .join(" ")}/>`,
    )
    .join("");
  return `<g transform="translate(${x} ${y}) scale(${scale})" fill="none" stroke="${color}" stroke-width="${strokeWidth / scale > 3 ? 3 : strokeWidth}" stroke-linecap="round" stroke-linejoin="round">${parts}</g>`;
};

// 동그란 바탕 위 아이콘
export const badgeIcon = (name, cx, cy, tone = "blue", size = 34) => {
  const t = TONES[tone];
  const inner = Math.round(size * 0.56);
  return `<circle cx="${cx}" cy="${cy}" r="${size / 2}" fill="${t.solid}"/>${icon(name, cx - inner / 2, cy - inner / 2, inner, "#ffffff", 2.2)}`;
};

// 카드: 아이콘 + 굵은 제목 + 설명 줄들. align="center"면 세로로 가운데 정렬된 작은 카드.
export const card = ({
  x,
  y,
  w,
  h,
  tone = "slate",
  icon: iconName,
  title,
  sub = [],
  align = "left",
  titleSize = 15,
  subSize = 12.5,
  fill,
}) => {
  const t = TONES[tone];
  const parts = [
    `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="14" fill="${fill ?? t.bg}" stroke="${t.line}" stroke-width="1.5"/>`,
  ];
  const subLines = Array.isArray(sub) ? sub : [sub];
  if (align === "center") {
    const blockHeight = (iconName ? 44 : 0) + 20 + subLines.length * 19;
    let cy = y + (h - blockHeight) / 2;
    if (iconName) {
      parts.push(badgeIcon(iconName, x + w / 2, cy + 17, tone));
      cy += 44;
    }
    parts.push(text(x + w / 2, cy + 15, title, { size: titleSize, weight: 700, color: t.ink, anchor: "middle" }));
    subLines.forEach((line, index) =>
      parts.push(text(x + w / 2, cy + 36 + index * 19, line, { size: subSize, color: MUTED, anchor: "middle" })),
    );
  } else {
    const left = iconName ? x + 60 : x + 18;
    if (iconName) parts.push(badgeIcon(iconName, x + 34, y + 34, tone));
    parts.push(text(left, y + 31, title, { size: titleSize, weight: 700, color: t.ink }));
    subLines.forEach((line, index) =>
      parts.push(text(left, y + 53 + index * 19, line, { size: subSize, color: MUTED })),
    );
  }
  return parts.join("");
};

// 저장소(원기둥)
export const cylinder = ({ x, y, w, h, tone = "green", title, sub = [] }) => {
  const t = TONES[tone];
  const ry = 9;
  const subLines = Array.isArray(sub) ? sub : [sub];
  return [
    `<path d="M${x} ${y + ry} v${h - ry * 2} a${w / 2} ${ry} 0 0 0 ${w} 0 v${-(h - ry * 2)}" fill="${t.bg}" stroke="${t.line}" stroke-width="1.5"/>`,
    `<ellipse cx="${x + w / 2}" cy="${y + ry}" rx="${w / 2}" ry="${ry}" fill="#ffffff" stroke="${t.line}" stroke-width="1.5"/>`,
    text(x + w / 2, y + h / 2 + 6 - subLines.length * 9, title, { size: 14, weight: 700, color: t.ink, anchor: "middle" }),
    ...subLines.map((line, index) =>
      text(x + w / 2, y + h / 2 + 26 + index * 18 - subLines.length * 9, line, { size: 12, color: MUTED, anchor: "middle" }),
    ),
  ].join("");
};

// 점선 묶음 상자 + 왼쪽 위 이름표
export const zone = ({ x, y, w, h, label, tone = "slate", iconName }) => {
  const t = TONES[tone];
  const labelWidth = textWidth(label, 12, 700) + (iconName ? 40 : 24);
  return [
    `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="18" fill="${t.bg}" fill-opacity="0.45" stroke="${t.line}" stroke-width="1.5" stroke-dasharray="6 5"/>`,
    `<rect x="${x + 16}" y="${y - 12}" width="${labelWidth}" height="24" rx="12" fill="#ffffff" stroke="${t.line}" stroke-width="1.5"/>`,
    iconName ? icon(iconName, x + 26, y - 7, 14, t.solid, 2.2) : "",
    text(x + 16 + (iconName ? 32 : 12), y + 4, label, { size: 12, weight: 700, color: t.ink }),
  ].join("");
};

// 알약 모양 딱지. 폭을 돌려줘서 이어 붙일 수 있음.
export const chip = (x, y, value, tone = "slate", { size = 12, solid = false, height = 24 } = {}) => {
  const t = TONES[tone];
  const w = textWidth(value, size, 600) + 20;
  return {
    w,
    svg: `<rect x="${x}" y="${y}" width="${w}" height="${height}" rx="${height / 2}" fill="${solid ? t.solid : "#ffffff"}" stroke="${solid ? t.solid : t.line}" stroke-width="1.2"/>${text(x + w / 2, y + height / 2 + size * 0.36, value, { size, weight: 600, color: solid ? "#ffffff" : t.ink, anchor: "middle" })}`,
  };
};

// 딱지 여러 개를 한 줄로(gap 간격)
export const chipRow = (x, y, values, tone = "slate", options = {}) => {
  let cursor = x;
  const parts = [];
  for (const value of values) {
    const item = chip(cursor, y, value, tone, options);
    parts.push(item.svg);
    cursor += item.w + (options.gap ?? 8);
  }
  return parts.join("");
};

// 딱지 여러 개를 폭(maxWidth) 안에서 줄을 바꿔 가며
export const chipWrap = (x, y, maxWidth, values, tone = "slate", options = {}) => {
  const gap = options.gap ?? 8;
  const rowGap = options.rowGap ?? 8;
  const height = options.height ?? 24;
  let cx = x;
  let cy = y;
  const parts = [];
  for (const value of values) {
    const solid = typeof value === "object" ? value.solid : false;
    const label = typeof value === "object" ? value.text : value;
    const width = textWidth(label, options.size ?? 12, 600) + 20;
    if (cx > x && cx + width > x + maxWidth) {
      cx = x;
      cy += height + rowGap;
    }
    parts.push(chip(cx, cy, label, tone, { ...options, solid }).svg);
    cx += width + gap;
  }
  return parts.join("");
};

// 판단(마름모): 가운데 (cx, cy), 글은 여러 줄 가능
export const diamond = (cx, cy, halfWidth, halfHeight, label, tone = "amber") => {
  const t = TONES[tone];
  const labelLines = Array.isArray(label) ? label : [label];
  return [
    `<path d="M${cx} ${cy - halfHeight} L${cx + halfWidth} ${cy} L${cx} ${cy + halfHeight} L${cx - halfWidth} ${cy} Z" fill="${t.bg}" stroke="${t.line}" stroke-width="1.5" stroke-linejoin="round"/>`,
    ...labelLines.map((line, index) =>
      text(cx, cy + 5 + (index - (labelLines.length - 1) / 2) * 18, line, { size: 13, weight: 700, color: t.ink, anchor: "middle" }),
    ),
  ].join("");
};

// 화살표: 꺾이는 점 목록을 받아 끝에 화살촉. label은 가운데 구간 위에 흰 바탕 글.
export const arrow = (
  points,
  { tone = "slate", dashed = false, label, labelAt = 0.5, both = false, width = 1.8 } = {},
) => {
  const t = TONES[tone];
  const d = points.map(([px, py], index) => `${index === 0 ? "M" : "L"}${px} ${py}`).join(" ");
  const parts = [
    `<path d="${d}" fill="none" stroke="${t.solid}" stroke-width="${width}" ${dashed ? 'stroke-dasharray="6 5"' : ""} marker-end="url(#head-${tone})" ${both ? `marker-start="url(#tail-${tone})"` : ""} stroke-linecap="round" stroke-linejoin="round"/>`,
  ];
  if (label) {
    // 전체 길이 기준 labelAt 위치
    const segments = points.slice(1).map((point, index) => {
      const [ax, ay] = points[index];
      return { ax, ay, bx: point[0], by: point[1], len: Math.hypot(point[0] - ax, point[1] - ay) };
    });
    const total = segments.reduce((sum, segment) => sum + segment.len, 0);
    let remain = total * labelAt;
    let lx = points[0][0];
    let ly = points[0][1];
    for (const segment of segments) {
      if (remain <= segment.len) {
        const ratio = segment.len === 0 ? 0 : remain / segment.len;
        lx = segment.ax + (segment.bx - segment.ax) * ratio;
        ly = segment.ay + (segment.by - segment.ay) * ratio;
        break;
      }
      remain -= segment.len;
    }
    const labelLines = Array.isArray(label) ? label : [label];
    const w = Math.max(...labelLines.map((line) => textWidth(line, 11.5, 600))) + 14;
    const h = labelLines.length * 16 + 6;
    parts.push(
      `<rect x="${lx - w / 2}" y="${ly - h / 2}" width="${w}" height="${h}" rx="6" fill="#ffffff" stroke="${t.line}" stroke-width="1"/>`,
      ...labelLines.map((line, index) =>
        text(lx, ly - h / 2 + 15 + index * 16, line, { size: 11.5, weight: 600, color: t.ink, anchor: "middle" }),
      ),
    );
  }
  return parts.join("");
};

// 번호 동그라미(순서 표시)
export const step = (cx, cy, number, tone = "blue") =>
  `<circle cx="${cx}" cy="${cy}" r="12" fill="${TONES[tone].solid}"/>${text(cx, cy + 4.5, number, { size: 12.5, weight: 700, color: "#ffffff", anchor: "middle" })}`;

// 그림 한 장: 흰 바탕 카드(다크 모드 README에서도 그대로 보이게) + 화살촉 정의
export const svg = (width, height, body, { title } = {}) => {
  const markers = Object.entries(TONES)
    .map(
      ([name, t]) =>
        `<marker id="head-${name}" viewBox="0 0 10 10" refX="8.5" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 z" fill="${t.solid}"/></marker>` +
        `<marker id="tail-${name}" viewBox="0 0 10 10" refX="1.5" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 z" fill="${t.solid}"/></marker>`,
    )
    .join("");
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" font-family="${FONT}" role="img"${title ? ` aria-label="${esc(title)}"` : ""}>
<defs>${markers}</defs>
<rect x="0.75" y="0.75" width="${width - 1.5}" height="${height - 1.5}" rx="20" fill="#ffffff" stroke="#e2e8f0" stroke-width="1.5"/>
${body}
</svg>
`;
};

export { INK, MUTED };
