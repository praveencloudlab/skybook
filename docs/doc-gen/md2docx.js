// SkyBook governed-docs → corporate Word. One style, twelve documents.
const fs = require('fs');
const path = require('path');
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  HeadingLevel, AlignmentType, WidthType, BorderStyle, ShadingType,
  PageNumber, Header, Footer, TableOfContents, LevelFormat, PageBreak,
} = require('docx');

const SRC = 'D:/projects/skybook/docs/enterprise';
const OUT = 'D:/projects/skybook/docs/enterprise/docx';
fs.mkdirSync(OUT, { recursive: true });

const NAVY = '1F3864', ACCENT = '2E5395', GREY = '595959', LIGHT = 'D9E2F3',
      CODEBG = 'F2F2F2', BORDER = 'BFBFBF';
const PAGE_W = 11906, MARG = 1134; // A4, 2cm margins
const CONTENT_W = PAGE_W - 2 * MARG;

// ---------- inline markdown -> TextRuns ----------
function inline(text, base = {}) {
  const runs = [];
  // tokens: **bold**, `code`, [text](url); italics via *..* not used in the set
  const re = /(\*\*([^*]+)\*\*)|(`([^`]+)`)|(\[([^\]]+)\]\(([^)]+)\))|(\*([^*\s][^*]*)\*)/g;
  let last = 0, m;
  const push = (t, extra = {}) => {
    if (!t) return;
    runs.push(new TextRun({ text: t, font: 'Calibri', size: 22, ...base, ...extra }));
  };
  while ((m = re.exec(text)) !== null) {
    push(text.slice(last, m.index));
    if (m[2] !== undefined) push(m[2], { bold: true });
    else if (m[4] !== undefined) push(m[4], { font: 'Consolas', size: 20, shading: { type: ShadingType.CLEAR, fill: CODEBG } });
    else if (m[6] !== undefined) push(m[6], { color: ACCENT, underline: {} });
    else if (m[9] !== undefined) push(m[9], { italics: true });
    last = m.index + m[0].length;
  }
  push(text.slice(last));
  return runs.length ? runs : [new TextRun({ text: '', font: 'Calibri', size: 22 })];
}

const border = { style: BorderStyle.SINGLE, size: 4, color: BORDER };
const allBorders = { top: border, bottom: border, left: border, right: border,
  insideHorizontal: border, insideVertical: border };

function cell(text, { header = false, width, fill } = {}) {
  return new TableCell({
    width: { size: width, type: WidthType.DXA },
    shading: fill ? { type: ShadingType.CLEAR, fill } : undefined,
    margins: { top: 60, bottom: 60, left: 100, right: 100 },
    children: [new Paragraph({
      spacing: { before: 0, after: 0 },
      children: inline(text, header ? { bold: true, color: 'FFFFFF' } : {}),
    })],
  });
}

function mdTable(rows) {
  const cols = rows[0].length;
  const widths = Array(cols).fill(Math.floor(CONTENT_W / cols));
  // first column narrower for 2-col key/value tables
  if (cols === 2) { widths[0] = Math.floor(CONTENT_W * 0.3); widths[1] = CONTENT_W - widths[0]; }
  return new Table({
    width: { size: CONTENT_W, type: WidthType.DXA },
    columnWidths: widths,
    borders: allBorders,
    rows: rows.map((r, i) => new TableRow({
      tableHeader: i === 0,
      children: r.map((c, j) => cell(c, {
        header: i === 0, width: widths[j],
        fill: i === 0 ? NAVY : (i % 2 === 0 ? 'F7F9FC' : undefined),
      })),
    })),
  });
}

function codeBlock(lines) {
  return lines.map((l, i) => new Paragraph({
    shading: { type: ShadingType.CLEAR, fill: CODEBG },
    spacing: { before: i === 0 ? 120 : 0, after: i === lines.length - 1 ? 120 : 0, line: 240 },
    indent: { left: 200, right: 200 },
    children: [new TextRun({ text: l.length ? l : ' ', font: 'Consolas', size: 18 })],
  }));
}

// ---------- block parser ----------
function parse(md) {
  const lines = md.split(/\r?\n/);
  const blocks = [];
  let i = 0;
  const isTableLine = (l) => /^\s*\|.*\|\s*$/.test(l);
  const splitRow = (l) => l.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());

  while (i < lines.length) {
    const l = lines[i];
    if (/^\s*$/.test(l)) { i++; continue; }
    if (/^---\s*$/.test(l)) { blocks.push({ t: 'hr' }); i++; continue; }
    if (l.startsWith('```')) {
      const buf = []; i++;
      while (i < lines.length && !lines[i].startsWith('```')) buf.push(lines[i++]);
      i++; blocks.push({ t: 'code', lines: buf }); continue;
    }
    const h = /^(#{1,4})\s+(.*)$/.exec(l);
    if (h) { blocks.push({ t: 'h' + h[1].length, text: h[2] }); i++; continue; }
    if (isTableLine(l)) {
      const rows = [];
      while (i < lines.length && isTableLine(lines[i])) {
        const r = splitRow(lines[i]);
        if (!r.every((c) => /^:?-{3,}:?$/.test(c))) rows.push(r);
        i++;
      }
      blocks.push({ t: 'table', rows }); continue;
    }
    if (/^>\s?/.test(l)) {
      let buf = [];
      while (i < lines.length && /^>\s?/.test(lines[i])) buf.push(lines[i++].replace(/^>\s?/, ''));
      blocks.push({ t: 'quote', text: buf.join(' ') }); continue;
    }
    const li = /^(\s*)([-*]|\d+\.)\s+(.*)$/.exec(l);
    if (li) {
      const ordered = /\d+\./.test(li[2]);
      let text = li[3]; i++;
      while (i < lines.length && /^\s{2,}\S/.test(lines[i]) && !/^\s*([-*]|\d+\.)\s/.test(lines[i]) && !isTableLine(lines[i])) {
        text += ' ' + lines[i++].trim();
      }
      blocks.push({ t: 'li', ordered, text, level: li[1].length >= 2 ? 1 : 0 });
      continue;
    }
    // paragraph: join hard-wrapped lines
    let buf = [l.trim()]; i++;
    while (i < lines.length && !/^\s*$/.test(lines[i]) && !/^(#{1,4})\s/.test(lines[i])
      && !lines[i].startsWith('```') && !isTableLine(lines[i]) && !/^>\s?/.test(lines[i])
      && !/^(\s*)([-*]|\d+\.)\s+/.test(lines[i]) && !/^---\s*$/.test(lines[i])) {
      buf.push(lines[i++].trim());
    }
    blocks.push({ t: 'p', text: buf.join(' ') });
  }
  return blocks;
}

// ---------- build one document ----------
function build(mdPath) {
  const md = fs.readFileSync(mdPath, 'utf8');
  const blocks = parse(md);

  // Title: first h1 "SKB-DOC-XX — Title"
  const h1 = blocks.find((b) => b.t === 'h1');
  const m = /^(SKB-DOC-\d+)\s+—\s+(.*)$/.exec(h1.text) || [null, 'SKB-DOC', h1.text];
  const docId = m[1], title = m[2];

  // Control table: first table after h1
  const ctrlIdx = blocks.findIndex((b) => b.t === 'table');
  const ctrl = blocks[ctrlIdx];
  const ctrlRows = ctrl.rows.filter((r) => r.some((c) => c.length));

  const children = [];
  // Cover block
  children.push(new Paragraph({ spacing: { before: 1200, after: 0 }, children: [
    new TextRun({ text: 'SkyBook Airline Reservation Platform', font: 'Calibri Light', size: 28, color: GREY }),
  ]}));
  children.push(new Paragraph({ spacing: { before: 120, after: 60 }, children: [
    new TextRun({ text: docId, font: 'Calibri Light', size: 32, color: ACCENT, bold: true }),
  ]}));
  children.push(new Paragraph({ spacing: { before: 0, after: 300 },
    border: { bottom: { style: BorderStyle.SINGLE, size: 12, color: NAVY } },
    children: [new TextRun({ text: title, font: 'Calibri Light', size: 56, color: NAVY, bold: true })],
  }));
  // control table (skip the empty header row)
  children.push(mdTable([['Field', 'Value'], ...ctrlRows.map((r) => [r[0].replace(/\*\*/g, ''), r[1]])]));
  children.push(new Paragraph({ spacing: { before: 300, after: 120 }, children: [
    new TextRun({ text: 'Contents', font: 'Calibri Light', size: 32, color: NAVY, bold: true }),
  ]}));
  children.push(new TableOfContents('Contents', { hyperlink: true, headingStyleRange: '1-3' }));
  children.push(new Paragraph({ children: [new PageBreak()] }));

  // Body: everything after the control table
  let bulletActive = false;
  for (let bi = ctrlIdx + 1; bi < blocks.length; bi++) {
    const b = blocks[bi];
    switch (b.t) {
      case 'h1': break; // no second h1s
      case 'h2':
        children.push(new Paragraph({ heading: HeadingLevel.HEADING_1, spacing: { before: 360, after: 160 }, children: inline(b.text, { bold: true, color: NAVY, size: 30, font: 'Calibri Light' }) }));
        break;
      case 'h3':
        children.push(new Paragraph({ heading: HeadingLevel.HEADING_2, spacing: { before: 280, after: 120 }, children: inline(b.text, { bold: true, color: ACCENT, size: 26, font: 'Calibri Light' }) }));
        break;
      case 'h4':
        children.push(new Paragraph({ heading: HeadingLevel.HEADING_3, spacing: { before: 220, after: 100 }, children: inline(b.text, { bold: true, color: GREY, size: 23 }) }));
        break;
      case 'p':
        children.push(new Paragraph({ spacing: { before: 60, after: 120, line: 276 }, alignment: AlignmentType.JUSTIFIED, children: inline(b.text) }));
        break;
      case 'quote':
        children.push(new Paragraph({
          spacing: { before: 120, after: 120 }, indent: { left: 400 },
          border: { left: { style: BorderStyle.SINGLE, size: 18, color: ACCENT } },
          shading: { type: ShadingType.CLEAR, fill: 'F7F9FC' },
          children: inline(b.text, { italics: true, color: GREY }),
        }));
        break;
      case 'li':
        children.push(new Paragraph({
          numbering: { reference: b.ordered ? 'ol' : 'ul', level: b.level, instance: b.ordered ? olInstance : 0 },
          spacing: { before: 40, after: 40, line: 264 },
          children: inline(b.text),
        }));
        break;
      case 'table': children.push(mdTable(b.rows)); children.push(new Paragraph({ spacing: { after: 120 }, children: [] })); break;
      case 'code': children.push(...codeBlock(b.lines)); break;
      case 'hr':
        children.push(new Paragraph({ spacing: { before: 160, after: 160 }, border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: BORDER } }, children: [] }));
        break;
    }
    // restart ordered lists when a non-li block interrupts
    if (b.t !== 'li') { if (bulletActive) olInstance++; bulletActive = false; } else if (b.ordered) bulletActive = true;
  }

  const doc = new Document({
    creator: 'SkyBook Platform Engineering',
    title: `${docId} — ${title}`,
    description: 'SkyBook governed engineering documentation',
    styles: {
      default: { document: { run: { font: 'Calibri', size: 22 } } },
      paragraphStyles: [
        { id: 'Heading1', name: 'Heading 1', basedOn: 'Normal', next: 'Normal', quickFormat: true,
          run: { font: 'Calibri Light', size: 30, bold: true, color: NAVY },
          paragraph: { spacing: { before: 360, after: 160 }, outlineLevel: 0 } },
        { id: 'Heading2', name: 'Heading 2', basedOn: 'Normal', next: 'Normal', quickFormat: true,
          run: { font: 'Calibri Light', size: 26, bold: true, color: ACCENT },
          paragraph: { spacing: { before: 280, after: 120 }, outlineLevel: 1 } },
        { id: 'Heading3', name: 'Heading 3', basedOn: 'Normal', next: 'Normal', quickFormat: true,
          run: { size: 23, bold: true, color: GREY },
          paragraph: { spacing: { before: 220, after: 100 }, outlineLevel: 2 } },
      ],
    },
    numbering: {
      config: [
        { reference: 'ul', levels: [0, 1].map((lvl) => ({
            level: lvl, format: LevelFormat.BULLET, text: lvl === 0 ? '•' : '–', alignment: AlignmentType.LEFT,
            style: { paragraph: { indent: { left: 480 + lvl * 360, hanging: 240 } } } })) },
        ...Array.from({ length: 40 }, (_, k) => ({
          reference: `ol${k}`, levels: [{ level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.LEFT,
            style: { paragraph: { indent: { left: 480, hanging: 300 } } } }] })),
        { reference: 'ol', levels: [{ level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.LEFT,
            style: { paragraph: { indent: { left: 480, hanging: 300 } } } }] },
      ],
    },
    features: { updateFields: true },
    sections: [{
      properties: { page: { margin: { top: MARG, bottom: MARG, left: MARG, right: MARG } } },
      headers: { default: new Header({ children: [new Paragraph({
        tabStops: [{ type: 'right', position: CONTENT_W }],
        border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: BORDER } },
        children: [
          new TextRun({ text: 'SkyBook Engineering Documentation', font: 'Calibri', size: 16, color: GREY }),
          new TextRun({ text: `\t${docId}`, font: 'Calibri', size: 16, color: GREY, bold: true }),
        ] })] }) },
      footers: { default: new Footer({ children: [new Paragraph({
        tabStops: [{ type: 'right', position: CONTENT_W }],
        border: { top: { style: BorderStyle.SINGLE, size: 6, color: BORDER } },
        children: [
          new TextRun({ text: 'Internal — Engineering · Uncontrolled when printed', font: 'Calibri', size: 16, color: GREY }),
          new TextRun({ text: '\tPage ', font: 'Calibri', size: 16, color: GREY }),
          new TextRun({ children: [PageNumber.CURRENT], font: 'Calibri', size: 16, color: GREY }),
          new TextRun({ text: ' of ', font: 'Calibri', size: 16, color: GREY }),
          new TextRun({ children: [PageNumber.TOTAL_PAGES], font: 'Calibri', size: 16, color: GREY }),
        ] })] }) },
      children,
    }],
  });
  return { doc, docId, title };
}

let olInstance = 0;

(async () => {
  const files = fs.readdirSync(SRC).filter((f) => f.endsWith('.md')).sort();
  for (const f of files) {
    olInstance = 0;
    const { doc, docId, title } = build(path.join(SRC, f));
    const outName = `${docId}_${title.replace(/[^A-Za-z0-9]+/g, '_').replace(/^_+|_+$/g, '')}.docx`;
    const buf = await Packer.toBuffer(doc);
    fs.writeFileSync(path.join(OUT, outName), buf);
    console.log('wrote', outName, `${Math.round(buf.length / 1024)}kB`);
  }
})();
