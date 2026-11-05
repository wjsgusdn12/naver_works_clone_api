// README 그림(SVG)을 docs/diagrams/ 에 뽑고, 앱의 "프로젝트 소개" 모달이 읽는 docs/guide.json 을 만든다.
// 그림 모양은 scripts/readme-art/diagrams.mjs, 제목·한 줄 설명은 README.md 가 원본이다.
//
// 사용법:  node scripts/build-readme-art.mjs            (SVG + guide.json)
//          node scripts/build-readme-art.mjs --png 폴더  (확인용 PNG도 함께, Edge 또는 Chrome 필요)
// README 표시:  <!-- diagram: 이름 --> 다음 줄에 ![제목](docs/diagrams/이름.svg), 그 아래 문단이 설명.

import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { DIAGRAMS } from "./readme-art/diagrams.mjs";

const root = process.cwd();
const outDir = join(root, "docs", "diagrams");
const lines = readFileSync(join(root, "README.md"), "utf8").split(/\r?\n/);

// README에서 그림 자리·제목·설명 찾기
const sections = [];
let heading = "";
for (let i = 0; i < lines.length; i++) {
  const headingMatch = lines[i].match(/^## (.+)$/);
  if (headingMatch) heading = headingMatch[1].trim();
  const marker = lines[i].match(/^<!-- diagram: ([a-z0-9-]+) -->$/);
  if (!marker) continue;
  const id = marker[1];
  if (!lines[i + 1]?.includes(`docs/diagrams/${id}.svg`)) {
    throw new Error(`${id}: 표시 다음 줄에 docs/diagrams/${id}.svg 그림이 없음`);
  }
  let j = i + 2;
  while (j < lines.length && lines[j].trim() === "") j++;
  const caption = [];
  while (j < lines.length && lines[j].trim() !== "" && !/^(#|---|<!--|<)/.test(lines[j])) {
    caption.push(lines[j++].trim());
  }
  sections.push({ id, title: heading, description: caption.join(" ").replace(/\*\*|`/g, "") });
}

const known = new Set(Object.keys(DIAGRAMS));
const used = new Set(sections.map((section) => section.id));
for (const id of used) if (!known.has(id)) throw new Error(`${id}: diagrams.mjs 에 그림 정의가 없음`);
for (const id of known) if (!used.has(id)) throw new Error(`${id}: README 에 자리가 없음`);

rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });

const pngIndex = process.argv.indexOf("--png");
const pngDir = pngIndex > 0 ? resolve(process.argv[pngIndex + 1]) : null;
const browser = [
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
].find((path) => existsSync(path));
if (pngDir) mkdirSync(pngDir, { recursive: true });

for (const section of sections) {
  const markup = DIAGRAMS[section.id]();
  const file = join(outDir, `${section.id}.svg`);
  writeFileSync(file, markup);
  if (pngDir && browser) {
    const [, width, height] = markup.match(/width="(\d+)" height="(\d+)"/);
    execFileSync(browser, [
      "--headless=new",
      "--disable-gpu",
      "--hide-scrollbars",
      // 열려 있는 브라우저와 프로필이 겹치면 캡처가 안 돼 따로 씀
      `--user-data-dir=${join(pngDir, `.browser-profile-${section.id}`)}`,
      `--window-size=${width},${height}`,
      `--screenshot=${join(pngDir, `${section.id}.png`)}`,
      pathToFileURL(file).href,
    ], { stdio: "ignore" });
  }
  console.log(`✓ ${section.id}`);
}

const guide = sections.map((section) => ({ ...section, svg: `docs/diagrams/${section.id}.svg` }));
writeFileSync(join(root, "docs", "guide.json"), JSON.stringify(guide, null, 2) + "\n");
console.log(`완료: ${guide.length}개 → docs/diagrams, docs/guide.json`);
