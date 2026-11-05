// 백엔드 README 그림 정의. 이름(키)은 README의 <!-- diagram: 이름 --> 과 같아야 한다.
// 그린 뒤 확인: node scripts/build-readme-art.mjs --png <폴더>

import {
  arrow,
  badgeIcon,
  card,
  chipRow,
  chipWrap,
  cylinder,
  diamond,
  icon,
  lines,
  MUTED,
  step,
  svg,
  text,
  TONES,
  zone,
} from "./kit.mjs";

const W = 960;

// 1. 한눈에 보기
const overview = () => {
  const left = [
    ["MessagesSquare", "violet", "메신저", ["1:1 · 그룹 · 내 메모", "답장 · 수정 · 전달 · 공지 · 파일"]],
    ["Contact", "teal", "주소록", ["조직도 · 전체 회사 검색", "즐겨찾기 · 내 상태 7종"]],
    ["ClipboardList", "amber", "게시판", ["공지 · 자유 · 문의 · 부서", "예약 게시 · 휴지통 · 일일 브리핑"]],
  ];
  const right = [
    ["Bell", "rose", "알림", ["멘션 · 답장 · 게시판 소식", "30일 보관 · 실시간 도착"]],
    ["Wifi", "sky", "접속 상태 · 프로필", ["온라인 표시 · 탭 여러 개 대응", "프로필 사진 실시간 반영"]],
    ["ChartLine", "green", "API 모니터링", ["요청 · 오류율 · 응답 시간", "서버 목록 · DB 연결 풀"]],
  ];
  const parts = [];
  left.forEach(([iconName, tone, title, sub], index) => {
    const y = 30 + index * 115;
    parts.push(card({ x: 30, y, w: 290, h: 90, tone, icon: iconName, title, sub }));
    parts.push(arrow([[320, y + 45], [372, 165 + index * 25]], { tone: "slate", dashed: true, width: 1.4 }));
  });
  right.forEach(([iconName, tone, title, sub], index) => {
    const y = 30 + index * 115;
    parts.push(card({ x: 640, y, w: 290, h: 90, tone, icon: iconName, title, sub }));
    parts.push(arrow([[640, y + 45], [588, 165 + index * 25]], { tone: "slate", dashed: true, width: 1.4 }));
  });
  parts.push(
    card({ x: 375, y: 30, w: 210, h: 90, tone: "slate", icon: "KeyRound", title: "인증 · 회사", sub: ["JWT 로그인", "가입 3가지 · 계정 찾기"] }),
    arrow([[480, 120], [480, 142]], { tone: "slate" }),
    card({ x: 375, y: 145, w: 210, h: 90, tone: "blue", icon: "Server", title: "Spring Boot API", sub: ["Java 21 · Boot 4.1", "REST + STOMP"], fill: "#dbeafe" }),
    arrow([[480, 235], [480, 262]], { tone: "green" }),
    cylinder({ x: 390, y: 265, w: 180, h: 86, tone: "green", title: "PostgreSQL", sub: ["Supabase · Flyway V47"] }),
  );
  return svg(W, 380, parts.join(""), { title: "백엔드 기능 한눈에 보기" });
};

// 2. 전체 구조
const architecture = () =>
  svg(
    W,
    340,
    [
      card({ x: 30, y: 125, w: 170, h: 90, tone: "blue", icon: "MonitorSmartphone", title: "브라우저", sub: ["Next.js 화면"] }),
      zone({ x: 240, y: 36, w: 470, h: 280, label: "Spring Boot API · 포트 9090", tone: "violet", iconName: "Server" }),
      card({ x: 262, y: 66, w: 200, h: 84, tone: "violet", icon: "ShieldCheck", title: "REST", sub: ["/api/v1/** · JWT 검사"] }),
      card({ x: 262, y: 206, w: 200, h: 84, tone: "violet", icon: "Radio", title: "STOMP /ws", sub: ["CONNECT 때 JWT 검사"] }),
      card({ x: 510, y: 128, w: 180, h: 90, tone: "violet", icon: "Workflow", title: "도메인 서비스", sub: ["권한 · 규칙 검사", "트랜잭션"] }),
      cylinder({ x: 758, y: 40, w: 172, h: 104, tone: "green", title: "PostgreSQL", sub: ["모든 데이터 원본", "Flyway 이력 관리"] }),
      cylinder({ x: 758, y: 196, w: 172, h: 104, tone: "rose", title: "Redis (선택)", sub: ["서버 2대 이상일 때", "접속 상태 · 지표 공유"] }),
      arrow([[200, 150], [262, 108]], { tone: "blue", label: "HTTPS" }),
      arrow([[200, 190], [262, 248]], { tone: "blue", dashed: true, both: true, label: "WebSocket" }),
      arrow([[462, 108], [510, 150]], { tone: "violet" }),
      arrow([[462, 248], [510, 198]], { tone: "violet", both: true }),
      arrow([[690, 150], [758, 92]], { tone: "green", label: "JPA" }),
      arrow([[690, 198], [758, 248]], { tone: "rose", dashed: true, label: "Pub/Sub" }),
    ].join(""),
    { title: "전체 구조" },
  );

// 3. 로그인과 세션
const auth = () => {
  const x0 = 70;
  const x1 = 890;
  const hourX = (hour) => x0 + ((x1 - x0) * hour) / 24;
  const parts = [
    text(x0, 42, "한 번 로그인한 세션 = 최대 24시간", { size: 15, weight: 700 }),
    text(x1, 42, "Refresh는 갱신될 때마다 바뀌지만 끝나는 시각은 그대로", { size: 12.5, color: MUTED, anchor: "end" }),
  ];
  for (let index = 0; index < 8; index++) {
    const fill = index % 2 === 0 ? "#bfdbfe" : "#dbeafe";
    parts.push(`<rect x="${hourX(index * 3) + 1}" y="60" width="${hourX(3) - hourX(0) - 2}" height="30" rx="6" fill="${fill}"/>`);
  }
  parts.push(text(hourX(1.75), 80, "Access 3h", { size: 12, weight: 700, color: TONES.blue.ink, anchor: "middle" }));
  for (let hour = 0; hour <= 24; hour += 3) {
    parts.push(text(hourX(hour), 110, `${hour}h`, { size: 11.5, color: MUTED, anchor: "middle" }));
    if (hour > 0 && hour < 24) parts.push(icon("RefreshCw", hourX(hour) - 7, 118, 14, TONES.blue.solid));
  }
  parts.push(
    badgeIcon("LogIn", x0, 75, "green", 30),
    badgeIcon("Lock", x1, 75, "rose", 30),
    text(x0, 152, "로그인", { size: 12, weight: 700, color: TONES.green.ink, anchor: "middle" }),
    text(hourX(12), 152, "만료 1분 전 화면이 조용히 갱신(토큰 회전)", { size: 12, color: TONES.blue.ink, anchor: "middle" }),
    text(x1, 152, "다시 로그인", { size: 12, weight: 700, color: TONES.rose.ink, anchor: "middle" }),
    chipRow(70, 172, ["Refresh = HttpOnly · SameSite=Strict 쿠키", "DB에는 SHA-256 해시만", "로그아웃 = 즉시 폐기"], "slate"),
    zone({ x: 30, y: 232, w: 900, h: 122, label: "가입 3가지", tone: "violet", iconName: "UserPlus" }),
    card({ x: 50, y: 254, w: 280, h: 82, tone: "violet", icon: "Sparkles", title: "체험하기", sub: ["데모 회사에 바로 합류"] }),
    card({ x: 340, y: 254, w: 280, h: 82, tone: "violet", icon: "Building2", title: "새 회사 만들기", sub: ["만든 사람이 대표 · 관리자", "경영팀 · 직급 자동 생성"] }),
    card({ x: 630, y: 254, w: 280, h: 82, tone: "violet", icon: "Mail", title: "초대 코드", sub: ["8자리 코드로 그 회사에 합류"] }),
    card({ x: 30, y: 374, w: 900, h: 70, tone: "amber", icon: "KeyRound", title: "계정 찾기", sub: ["가입 때 복구 코드(16자리) 발급 → 이름·이메일·전화 또는 복구 코드로 확인 → 비밀번호 재설정 + 모든 세션 종료"] }),
  );
  return svg(W, 470, parts.join(""), { title: "로그인과 세션" });
};

// 4. 데이터 모델
const dataModel = () =>
  svg(
    W,
    470,
    [
      zone({ x: 30, y: 36, w: 440, h: 196, label: "조직 · 인증", tone: "teal", iconName: "Building2" }),
      chipWrap(50, 62, 400, [{ text: "membership (구성원)", solid: true }, "company", "department", "job_grade", "user_account", "refresh_token", "member_favorite", "contact_group", "contact_group_member", "address_book_search_history"], "teal"),
      text(50, 216, "한 계정이 회사마다 membership 하나 → 모든 데이터의 주인", { size: 12, color: MUTED }),
      zone({ x: 490, y: 36, w: 440, h: 196, label: "메신저", tone: "violet", iconName: "MessagesSquare" }),
      chipWrap(510, 62, 400, ["chat_room", { text: "chat_room_member · 읽음 커서", solid: true }, "chat_message", "chat_message_mention", "chat_message_reaction", "chat_message_attachment", "chat_room_notice", "chat_link_preview"], "violet"),
      text(510, 216, "메시지 삭제는 논리 삭제 · version으로 동시 수정 감지", { size: 12, color: MUTED }),
      zone({ x: 30, y: 262, w: 440, h: 180, label: "게시판", tone: "amber", iconName: "ClipboardList" }),
      chipWrap(50, 288, 400, ["board", { text: "board_post", solid: true }, "board_comment", "board_attachment", "board_favorite", "board_post_read", "board_post_important", "board_post_reaction", "board_daily_greeting"], "amber"),
      text(50, 426, "글 상태: 임시저장 · 예약 · 게시 / 휴지통 30일", { size: 12, color: MUTED }),
      zone({ x: 490, y: 262, w: 210, h: 180, label: "알림 · 프로필", tone: "rose", iconName: "Bell" }),
      chipWrap(510, 288, 180, ["notification", "membership_profile_image"], "rose"),
      text(510, 408, "사진은 DB에 저장", { size: 12, color: MUTED }),
      text(510, 426, "(256px JPEG)", { size: 12, color: MUTED }),
      zone({ x: 720, y: 262, w: 210, h: 180, label: "모니터링", tone: "green", iconName: "ChartLine" }),
      chipWrap(740, 288, 180, ["api_metric_minute", "api_metric_bucket", "api_metric_hourly", "api_pool_bucket"], "green"),
      text(740, 426, "1분 · 5분 · 1시간 단위", { size: 12, color: MUTED }),
    ].join(""),
    { title: "데이터 모델" },
  );

// 5. 메시지가 전달되는 길
const messageFlow = () => {
  const items = [
    ["Send", "blue", "보낸 사람", ["POST /chatRoom", "/message"]],
    ["ShieldCheck", "violet", "서비스", ["참여자인지", "내용 · 파일 검사"]],
    ["Database", "green", "저장", ["메시지 + 멘션", "내 읽음 커서 전진"]],
    ["CircleCheck", "green", "commit", ["트랜잭션 확정", "AFTER_COMMIT"]],
    ["Radio", "violet", "STOMP 방송", ["/topic/chatRoom", "/{방 ID}"]],
    ["Users", "blue", "받는 화면들", ["방 목록 · 대화창", "바로 갱신"]],
  ];
  const parts = [];
  items.forEach(([iconName, tone, title, sub], index) => {
    const x = 30 + index * 153;
    parts.push(card({ x, y: 56, w: 135, h: 124, tone, icon: iconName, title, sub, align: "center", subSize: 11.5 }));
    parts.push(step(x + 16, 72, index + 1, tone));
    if (index < items.length - 1) parts.push(arrow([[x + 135, 118], [x + 153, 118]], { tone: "slate" }));
  });
  parts.push(
    arrow([[565, 180], [565, 236]], { tone: "rose", label: "멘션 · 답장만" }),
    card({ x: 440, y: 240, w: 250, h: 76, tone: "rose", icon: "Bell", title: "알림 만들기", sub: ["/user/queue/notification"] }),
    card({ x: 30, y: 240, w: 380, h: 76, tone: "amber", icon: "CircleAlert", title: "commit 전에는 방송하지 않음", sub: ["받은 쪽이 바로 조회해도 데이터가 이미 있음"] }),
    card({ x: 720, y: 240, w: 210, h: 76, tone: "slate", icon: "Keyboard", title: "입력 중 표시", sub: ["저장 없이 바로 방송"] }),
  );
  return svg(W, 340, parts.join(""), { title: "메시지가 전달되는 길" });
};

// 6. 읽음 수 계산
const readCursor = () => {
  const ids = [116, 117, 118, 119, 120, 121];
  const colX = (index) => 250 + index * 104;
  const rows = [
    ["A", 121, "blue"],
    ["B", 118, "amber"],
    ["C", 120, "violet"],
  ];
  const parts = [
    `<rect x="${colX(3) - 8}" y="40" width="96" height="230" rx="12" fill="${TONES.rose.bg}" stroke="${TONES.rose.line}" stroke-dasharray="5 4"/>`,
  ];
  ids.forEach((id, index) => {
    parts.push(card({ x: colX(index), y: 52, w: 80, h: 40, tone: "slate", title: `#${id}`, align: "center", titleSize: 13 }));
  });
  rows.forEach(([name, last, tone], rowIndex) => {
    const y = 120 + rowIndex * 50;
    parts.push(badgeIcon("UserRound", 60, y + 14, tone, 30), text(84, y + 19, `${name}의 커서`, { size: 13, weight: 700 }));
    const readUntil = ids.indexOf(last);
    parts.push(`<rect x="${colX(0)}" y="${y + 4}" width="${colX(readUntil) + 80 - colX(0)}" height="22" rx="11" fill="${TONES[tone].line}"/>`);
    if (readUntil < ids.length - 1) {
      parts.push(`<rect x="${colX(readUntil + 1)}" y="${y + 4}" width="${colX(ids.length - 1) + 80 - colX(readUntil + 1)}" height="22" rx="11" fill="#e2e8f0"/>`);
    }
    parts.push(text(colX(readUntil) + 72, y + 20, `${last}까지 읽음`, { size: 11.5, weight: 700, color: TONES[tone].ink, anchor: "end" }));
  });
  parts.push(
    text(colX(3) + 40, 290, "#119 안 읽음 = 1 (B)", { size: 13, weight: 700, color: TONES.rose.ink, anchor: "middle" }),
    text(30, 330, "메시지마다 '누가 읽었나'를 저장하지 않고, 참여자마다 마지막으로 읽은 메시지 번호 하나만 둠.", { size: 12.5, color: MUTED }),
    text(30, 350, "안 읽음 수 = 커서가 그 메시지보다 앞에 있는 사람 수(보낸 사람 제외). 커서는 앞으로만 움직임.", { size: 12.5, color: MUTED }),
  );
  return svg(W, 375, parts.join(""), { title: "읽음 수 계산" });
};

// 7. 메시지 기능
const messageFeatures = () => {
  const features = [
    [30, 36, "Reply", "답장", "원문을 그대로 인용 보존"],
    [30, 126, "Pencil", "수정", "24시간 안 · 충돌 감지"],
    [30, 216, "Trash2", "삭제", "24시간 안 · 논리 삭제"],
    [730, 36, "Forward", "전달", "여러 방에 한 번에"],
    [730, 126, "Megaphone", "공지", "방마다 최대 20개"],
    [730, 216, "SmilePlus", "리액션", "6종 · 1인 1개"],
    [30, 316, "AtSign", "멘션", "@이름 · @All → 알림"],
    [265, 316, "Paperclip", "파일", "20MB · 이미지 썸네일"],
    [500, 316, "Link", "링크 정보", "제목 · 그림 미리 수집"],
    [735, 316, "Search", "검색", "메시지 · 파일 · 링크"],
  ];
  const parts = features.map(([x, y, iconName, title, sub]) =>
    card({ x, y, w: x === 30 || x === 730 ? (y === 316 ? 195 : 200) : 195, h: 74, tone: "violet", icon: iconName, title, sub: [sub], subSize: 12 }),
  );
  // 가운데 말풍선 모형
  const bx = 280;
  const by = 50;
  parts.push(
    `<rect x="${bx}" y="${by}" width="400" height="236" rx="18" fill="#ffffff" stroke="${TONES.violet.line}" stroke-width="1.5"/>`,
    badgeIcon("UserRound", bx + 36, by + 44, "teal", 36),
    text(bx + 64, by + 38, "김하늘", { size: 13, weight: 700 }),
    text(bx + 112, by + 38, "오후 2:14", { size: 11.5, color: MUTED }),
    `<rect x="${bx + 64}" y="${by + 50}" width="300" height="40" rx="8" fill="${TONES.slate.bg}" stroke="${TONES.slate.line}"/>`,
    `<rect x="${bx + 64}" y="${by + 50}" width="4" height="40" rx="2" fill="${TONES.violet.solid}"/>`,
    text(bx + 78, by + 67, "이서준님에게 답장", { size: 11, weight: 700, color: TONES.violet.ink }),
    text(bx + 78, by + 83, "회의 자료 어디 있나요?", { size: 11.5, color: MUTED }),
    `<rect x="${bx + 64}" y="${by + 100}" width="300" height="44" rx="14" fill="${TONES.violet.bg}" stroke="${TONES.violet.line}"/>`,
    text(bx + 80, by + 127, "@이서준 여기 올려 뒀어요!", { size: 13 }),
    `<rect x="${bx + 64}" y="${by + 154}" width="190" height="40" rx="10" fill="#ffffff" stroke="${TONES.slate.line}"/>`,
    icon("FileText", bx + 76, by + 163, 22, TONES.blue.solid),
    text(bx + 106, by + 172, "회의자료.pdf", { size: 12, weight: 700 }),
    text(bx + 106, by + 187, "2.4MB", { size: 11, color: MUTED }),
    chipRow(bx + 64, by + 204, ["좋아요 2", "감사 1"], "violet", { size: 11, height: 22 }),
    text(bx + 364, by + 190, "읽음 3", { size: 11.5, weight: 700, color: TONES.amber.solid, anchor: "end" }),
  );
  return svg(W, 410, parts.join(""), { title: "메시지 기능" });
};

// 8. 알림 규칙
const notificationRules = () =>
  svg(
    W,
    480,
    [
      card({ x: 30, y: 40, w: 250, h: 80, tone: "violet", icon: "MessagesSquare", title: "채팅 메시지", sub: ["방마다 알림: 전체 · 멘션만 · 끔"] }),
      diamond(450, 80, 120, 50, ["나를 @멘션 or", "내 글에 답장?"], "amber"),
      arrow([[280, 80], [330, 80]], { tone: "violet" }),
      arrow([[450, 30], [450, 18], [690, 18], [690, 30]], { tone: "slate", dashed: true, label: "아니오" }),
      card({ x: 640, y: 30, w: 290, h: 70, tone: "slate", icon: "MessageSquare", title: "토스트 + 안 읽음 배지만", sub: ["알림 목록에는 쌓지 않음"] }),
      arrow([[450, 130], [450, 195], [690, 195]], { tone: "rose", label: "예", labelAt: 0.3 }),
      card({ x: 30, y: 145, w: 250, h: 148, tone: "amber", icon: "ClipboardList", title: "게시판", sub: [] }),
      chipWrap(48, 203, 220, ["새 문의 → 관리자", "공지 · 필독", "댓글 · 답글", "문의 답변 완료", "일일 브리핑"], "amber", { size: 11, height: 22, rowGap: 6, gap: 6 }),
      arrow([[280, 230], [690, 230]], { tone: "amber" }),
      card({ x: 30, y: 310, w: 250, h: 148, tone: "slate", icon: "UserRound", title: "계정 · 주소록", sub: [] }),
      chipWrap(48, 368, 220, ["새 브라우저 로그인", "환영", "회사 이동", "비밀번호 변경", "새 동료 합류"], "slate", { size: 11, height: 22, rowGap: 6, gap: 6 }),
      arrow([[280, 384], [600, 384], [690, 272]], { tone: "slate" }),
      card({ x: 690, y: 150, w: 240, h: 140, tone: "rose", icon: "Bell", title: "알림 목록", sub: ["커밋 뒤 실시간 도착", "/user/queue/notification", "최근 30일만 보여 줌", "읽음 · 모두 읽음"], fill: "#ffe4e6" }),
    ].join(""),
    { title: "알림 규칙" },
  );

// 9. 접속 상태
const presence = () => {
  const x0 = 110;
  const x1 = 900;
  const at = (second) => x0 + ((x1 - x0) * second) / 150;
  const parts = [
    text(30, 40, "브라우저 하나의 접속(세션) 수명", { size: 15, weight: 700 }),
    text(30, 88, "세션", { size: 12.5, weight: 700, color: MUTED }),
    `<rect x="${at(0)}" y="70" width="${at(70) - at(0)}" height="28" rx="14" fill="${TONES.green.line}"/>`,
    text(at(35), 89, "온라인 · 20초마다 만료 60초 뒤로 연장", { size: 12, weight: 700, color: TONES.green.ink, anchor: "middle" }),
    `<rect x="${at(70)}" y="70" width="${at(120) - at(70)}" height="28" rx="14" fill="#f1f5f9" stroke="#cbd5e1" stroke-dasharray="5 4"/>`,
    text(at(95), 89, "응답 없음 · 남은 60초", { size: 12, color: MUTED, anchor: "middle" }),
    `<rect x="${at(120)}" y="70" width="${at(150) - at(120)}" height="28" rx="14" fill="${TONES.rose.bg}" stroke="${TONES.rose.line}"/>`,
    text(at(135), 89, "오프라인", { size: 12, weight: 700, color: TONES.rose.ink, anchor: "middle" }),
  ];
  [0, 20, 40, 60].forEach((second) => parts.push(icon("RefreshCw", at(second) + (second === 0 ? 4 : -7), 104, 14, TONES.green.solid)));
  parts.push(
    badgeIcon("Wifi", at(0), 136, "green", 28),
    text(at(0) + 22, 141, "연결", { size: 12, weight: 700 }),
    badgeIcon("WifiOff", at(70), 136, "rose", 28),
    text(at(70) + 22, 141, "브라우저 강제 종료", { size: 12, weight: 700 }),
    badgeIcon("Trash2", at(122), 136, "slate", 28),
    text(at(122) + 22, 141, "15초마다 청소", { size: 12, weight: 700 }),
    card({ x: 30, y: 180, w: 440, h: 96, tone: "sky", icon: "Layers", title: "탭을 여러 개 열어도", sub: ["첫 연결 때만 '온라인', 마지막 탭이 닫힐 때만", "'오프라인'을 /topic/presence 로 방송"] }),
    card({ x: 490, y: 180, w: 440, h: 96, tone: "sky", icon: "Server", title: "서버가 여러 대여도", sub: ["Redis에 세션 목록 공유 · 서버마다 자기 세션만 연장", "Redis가 없으면 서버 메모리로 같은 규칙"] }),
    text(30, 310, "내 상태", { size: 13, weight: 700 }),
    chipRow(92, 294, ["바쁨", "자리 비움", "퇴근", "부재", "휴가", "출장", "병가"], "amber"),
    text(92, 340, "고를 때 자동 해제 시각(최대 90일 뒤)을 함께 정할 수 있음", { size: 12, color: MUTED }),
  );
  return svg(W, 365, parts.join(""), { title: "접속 상태" });
};

// 10. 게시판
const board = () => {
  const parts = [
    zone({ x: 30, y: 36, w: 420, h: 250, label: "모든 회사가 함께 쓰는 게시판", tone: "blue", iconName: "Globe" }),
    card({ x: 50, y: 62, w: 380, h: 64, tone: "blue", icon: "Megaphone", title: "공지사항", sub: ["관리자만 작성 · 모두 읽기"] }),
    card({ x: 50, y: 136, w: 380, h: 64, tone: "green", icon: "MessagesSquare", title: "자유게시판", sub: ["누구나 작성 · 매일 아침 브리핑"] }),
    card({ x: 50, y: 210, w: 380, h: 64, tone: "amber", icon: "MessageCircleQuestion", title: "문의하기", sub: ["작성자 · 관리자만 보임 · 관리자 댓글 = 답변 완료"] }),
    zone({ x: 30, y: 312, w: 420, h: 96, label: "회사별", tone: "violet", iconName: "Building2" }),
    card({ x: 50, y: 334, w: 380, h: 60, tone: "violet", icon: "Users", title: "부서 게시판", sub: ["내 부서만 · 관리자는 전체 · 이름 변경은 관리자"] }),
    text(480, 54, "글 한 편의 일생", { size: 14, weight: 700 }),
  ];
  const states = [
    ["FileClock", "slate", "임시저장", "나만 보임"],
    ["CalendarClock", "sky", "예약", "1분~90일 뒤"],
    ["CircleCheck", "green", "게시", "목록에 보임"],
    ["Trash2", "rose", "휴지통", "30일 안 복원"],
  ];
  states.forEach(([iconName, tone, title, sub], index) => {
    const x = 480 + index * 115;
    parts.push(card({ x, y: 70, w: 100, h: 112, tone, icon: iconName, title, sub: [sub], align: "center", subSize: 11, titleSize: 14 }));
    if (index < states.length - 1) parts.push(arrow([[x + 100, 126], [x + 115, 126]], { tone: "slate" }));
  });
  parts.push(
    arrow([[530, 182], [530, 200], [710, 200], [710, 184]], { tone: "sky", dashed: true, label: "1분마다 확인 → 자동 게시", labelAt: 0.5 }),
    text(480, 248, "글에 붙는 것", { size: 14, weight: 700 }),
    chipWrap(480, 262, 450, ["필독 + 읽은 사람 확인", "중요(나만의 별)", "댓글 · 답글 1단계", "리액션 6종", "첨부 최대 30개", "본문 사진", "게시판 즐겨찾기", "안 읽은 글만 보기", "작성 순 · 활동 순"], "amber"),
    text(480, 390, "게시판 홈: 최신글 · 공지 · 자유 · 문의 각 10개", { size: 12, color: MUTED }),
  );
  return svg(W, 430, parts.join(""), { title: "게시판" });
};

// 11. 일일 브리핑
const dailyBriefing = () => {
  const x0 = 70;
  const x1 = 890;
  const hourX = (hour) => x0 + ((x1 - x0) * (hour - 6)) / 12;
  const parts = [
    text(30, 40, "자유게시판 아침 인사 글 = 하루 한 번 자동 게시 + 시장 정보는 나오는 대로 채움", { size: 14, weight: 700 }),
    `<rect x="${x0}" y="70" width="${x1 - x0}" height="10" rx="5" fill="#e2e8f0"/>`,
  ];
  for (let hour = 6; hour <= 18; hour += 2) parts.push(text(hourX(hour), 102, `${String(hour).padStart(2, "0")}:00`, { size: 11.5, color: MUTED, anchor: "middle" }));
  parts.push(
    badgeIcon("Sun", hourX(9), 75, "amber", 32),
    badgeIcon("TrendingUp", hourX(14), 75, "rose", 32),
    card({ x: 60, y: 126, w: 410, h: 140, tone: "amber", icon: "Newspaper", title: "09:00 · 인사 글 게시", sub: ["\"2026년 11월 05일 입니다, 오늘도 좋은 하루 보내세요!\"", "+ 미국 시장(전날 종가) · 오늘 뉴스", "5분마다 확인 · 날짜가 기본키라 하루 한 번만"] }),
    card({ x: 490, y: 126, w: 410, h: 140, tone: "rose", icon: "ChartLine", title: "14:00 이후 · 한국 시장 채움", sub: ["공공데이터포털 지수 · 시세", "전 영업일 자료가 다음 날 13시 이후 공개돼서", "같은 글에 나중에 붙임(안내 문구 표시)"] }),
    arrow([[hourX(9), 92], [hourX(9), 126]], { tone: "amber" }),
    arrow([[hourX(14), 92], [hourX(14), 126]], { tone: "rose" }),
    text(30, 300, "어디서 가져오나", { size: 13, weight: 700 }),
    chipRow(30, 312, ["Yahoo Finance (먼저)", "Alpha Vantage (대체)", "공공데이터포털 · 금융위원회", "연합뉴스 · SBS · 매일경제 · Google 뉴스 RSS"], "amber"),
    text(30, 366, "AI 없이 정해진 규칙으로 문장을 만듦 · 빠진 부분은 최근 120일 글까지 나중에 채움", { size: 12, color: MUTED }),
  );
  return svg(W, 390, parts.join(""), { title: "일일 브리핑" });
};

// 12. API 모니터링
const monitoring = () =>
  svg(
    W,
    450,
    [
      card({ x: 30, y: 42, w: 170, h: 116, tone: "blue", icon: "Globe", title: "모든 /api 요청", sub: ["대시보드 자신 제외"], align: "center" }),
      card({ x: 230, y: 42, w: 200, h: 116, tone: "green", icon: "Activity", title: "기록기", sub: ["횟수 · 4xx · 5xx", "응답 시간 분포(p95)"], align: "center" }),
      arrow([[200, 100], [230, 100]], { tone: "slate" }),
      cylinder({ x: 480, y: 30, w: 200, h: 70, tone: "green", title: "1분 × 70칸", sub: ["약 70분 · 5초마다 저장"] }),
      cylinder({ x: 480, y: 110, w: 200, h: 70, tone: "green", title: "5분 × 300칸", sub: ["약 25시간"] }),
      cylinder({ x: 480, y: 190, w: 200, h: 70, tone: "green", title: "1시간 누적", sub: ["7일 · 30일 보기용"] }),
      arrow([[430, 90], [480, 65]], { tone: "green" }),
      arrow([[430, 100], [480, 145]], { tone: "green" }),
      arrow([[430, 115], [480, 225]], { tone: "green" }),
      card({ x: 730, y: 30, w: 200, h: 230, tone: "violet", icon: "LayoutDashboard", title: "관리자 대시보드", sub: [] }),
      lines(752, 108, ["최근 60분 · 1분 단위", "24시간 · 5분 단위", "7일 · 1시간 단위", "30일 · 3시간 단위"], { size: 12.5, color: TONES.violet.ink, lineHeight: 26 }),
      text(752, 228, "같은 길이 이전 기간과 비교", { size: 11.5, color: MUTED }),
      arrow([[680, 145], [730, 145]], { tone: "violet" }),
      text(30, 300, "요청을 영역으로 묶음", { size: 13, weight: 700 }),
      chipRow(30, 312, ["메시지", "주소록", "게시판", "알림", "기타"], "slate"),
      card({ x: 30, y: 352, w: 440, h: 74, tone: "sky", icon: "Server", title: "서버 목록", sub: ["10초마다 보고 · 30초 조용하면 목록에서 빠짐"] }),
      card({ x: 490, y: 352, w: 440, h: 74, tone: "sky", icon: "Database", title: "DB 연결 풀", sub: ["10초마다 사용 · 대기 · 최대 기록"] }),
    ].join(""),
    { title: "API 모니터링" },
  );

// 13. 서버 여러 대 (Redis 스위치)
const scaleOut = () =>
  svg(
    W,
    380,
    [
      zone({ x: 30, y: 40, w: 330, h: 310, label: "서버 1대 (기본)", tone: "slate", iconName: "Server" }),
      card({ x: 55, y: 70, w: 280, h: 160, tone: "slate", icon: "Server", title: "API", sub: ["접속 상태 · 지표 · 방송", "모두 서버 메모리에서", "", "app.redis.enabled = false"] }),
      text(55, 270, "개발할 때 · 한 대로 돌릴 때", { size: 12.5, color: MUTED }),
      text(55, 292, "Redis 없이 그대로 동작", { size: 12.5, color: MUTED }),
      arrow([[370, 195], [420, 195]], { tone: "rose", width: 2.4 }),
      zone({ x: 430, y: 40, w: 500, h: 310, label: "서버 2대 이상 · app.redis.enabled = true", tone: "rose", iconName: "Network" }),
      card({ x: 455, y: 72, w: 140, h: 70, tone: "violet", icon: "Server", title: "api-1", sub: ["A의 연결"] }),
      card({ x: 765, y: 72, w: 140, h: 70, tone: "violet", icon: "Server", title: "api-2", sub: ["B의 연결"] }),
      cylinder({ x: 600, y: 160, w: 160, h: 80, tone: "rose", title: "Redis", sub: ["Pub/Sub · 정렬 집합"] }),
      arrow([[525, 142], [615, 180]], { tone: "rose", both: true }),
      arrow([[835, 142], [745, 180]], { tone: "rose", both: true }),
      chipWrap(455, 262, 460, ["realtime:presence (접속 상태)", "realtime:profile-image (사진)", "apiMetrics:servers (서버 목록)"], "rose"),
      text(455, 336, "채팅 메시지 방송은 아직 서버 안에서만 → 다음 계획", { size: 12, color: MUTED }),
    ].join(""),
    { title: "서버 여러 대" },
  );

// 14. 패키지 구조
const packages = () => {
  const items = [
    ["config", "Cpu", "slate", "보안 · WebSocket 설정"],
    ["common", "Boxes", "slate", "공통 응답 · 예외 · 파일 저장"],
    ["auth", "KeyRound", "slate", "로그인 · 가입 · 계정 찾기"],
    ["identity", "Building2", "teal", "회사 · 부서 · 구성원"],
    ["addressbook", "Contact", "teal", "조직도 · 검색 · 상태"],
    ["chat", "MessagesSquare", "violet", "방 · 메시지 · 파일 · 실시간"],
    ["notification", "Bell", "rose", "알림 저장 · 실시간 전달"],
    ["presence", "Wifi", "sky", "접속 상태 · 세션 연장"],
    ["profile", "Image", "sky", "프로필 사진 · 변경 방송"],
    ["board", "ClipboardList", "amber", "게시판 · 댓글 · 브리핑"],
    ["monitoring", "ChartLine", "green", "API 지표 · 서버 · DB 풀"],
    ["controller", "House", "slate", "/ 안내 페이지"],
  ];
  const parts = items.map(([name, iconName, tone, sub], index) => {
    const x = 30 + (index % 4) * 228;
    const y = 30 + Math.floor(index / 4) * 96;
    return card({ x, y, w: 212, h: 80, tone, icon: iconName, title: name, sub: [sub], subSize: 11.5 });
  });
  parts.push(
    text(30, 336, "패키지마다 controller · service · domain · repository 를 함께 둠.", { size: 12.5, color: MUTED }),
    text(30, 356, "서로 직접 부르지 않고 '커밋 뒤 이벤트'로 이어 붙임(예: 채팅 → 알림 · 실시간 방송).", { size: 12.5, color: MUTED }),
  );
  return svg(W, 380, parts.join(""), { title: "패키지 구조" });
};

// 15. 다음 계획
const roadmap = () => {
  const parts = [
    zone({ x: 30, y: 36, w: 420, h: 330, label: "만든 것", tone: "green", iconName: "CircleCheck" }),
    chipWrap(50, 62, 390, ["JWT 로그인 · 24시간 세션", "가입 3가지 · 계정 찾기", "회사 이동 · 초대 코드", "관리자 모드", "조직도 · 전체 검색", "즐겨찾기 · 내 상태", "1:1 · 그룹 · 내 메모", "답장 · 수정 · 삭제", "전달 · 공지 · 리액션", "멘션 · 파일 · 링크", "읽음 커서", "입력 중 표시", "알림 규칙", "접속 상태 TTL", "프로필 사진", "게시판 4종", "예약 게시 · 휴지통", "댓글 · 필독", "일일 브리핑", "API 대시보드", "서버 · DB 풀 감시"], "green"),
    zone({ x: 480, y: 36, w: 450, h: 330, label: "다음 계획", tone: "slate", iconName: "CircleDashed" }),
  ];
  const plans = [
    ["Rocket", "무료 배포", "Oracle VM · nginx · api 2대 · Vercel · Supabase"],
    ["Network", "채팅 방송도 Redis로", "서버 2대에서 메시지가 모두에게 가도록"],
    ["CalendarClock", "일정 · 회의실 예약", "겹치는 예약 막기(동시성 제어)"],
    ["Gauge", "관측 · 부하 시험", "Prometheus · Grafana · k6"],
    ["Bot", "AI 대화 요약", "실패해도 채팅은 그대로"],
  ];
  plans.forEach(([iconName, title, sub], index) => {
    const y = 60 + index * 60;
    parts.push(
      `<rect x="500" y="${y}" width="410" height="52" rx="12" fill="#ffffff" stroke="${TONES.slate.line}" stroke-width="1.5" stroke-dasharray="5 4"/>`,
      badgeIcon(iconName, 528, y + 26, "slate", 32),
      text(556, y + 22, `${index + 1}. ${title}`, { size: 13.5, weight: 700 }),
      text(556, y + 41, sub, { size: 11.5, color: MUTED }),
    );
  });
  return svg(W, 390, parts.join(""), { title: "다음 계획" });
};

export const DIAGRAMS = {
  overview,
  architecture,
  auth,
  "data-model": dataModel,
  "message-flow": messageFlow,
  "read-cursor": readCursor,
  "message-features": messageFeatures,
  "notification-rules": notificationRules,
  presence,
  board,
  "daily-briefing": dailyBriefing,
  monitoring,
  "scale-out": scaleOut,
  packages,
  roadmap,
};
