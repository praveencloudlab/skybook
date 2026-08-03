import qrcode from 'qrcode-generator';

/**
 * A scannable QR code as a self-contained, scalable SVG string.
 *
 * <p>Rendered from the boarding pass's signed token so an airport scanner reads
 * the exact value checkin-service issued. The SVG carries a viewBox and no fixed
 * pixel size, so the same markup scales on screen (via width/height on the host
 * element) and embeds cleanly into the printable download - no external script,
 * works offline.
 *
 * <p>Auto-sizing (type 0) picks the smallest version that fits, and error level
 * M survives the full 255-char token. The try/catch is a belt-and-braces guard:
 * a code that somehow overflows degrades to level L rather than throwing inside
 * a render path.
 */
export function qrSvg(text: string, opts: { margin?: number; color?: string } = {}): string {
  const margin = opts.margin ?? 2;
  const color = opts.color ?? '#0f172a';

  let qr: ReturnType<typeof qrcode> | null = null;
  for (const ec of ['M', 'L'] as const) {
    try {
      const candidate = qrcode(0, ec);
      candidate.addData(text);
      candidate.make();
      qr = candidate;
      break;
    } catch {
      qr = null;
    }
  }
  if (!qr) {
    return '';
  }

  const count = qr.getModuleCount();
  const size = count + margin * 2;
  let d = '';
  for (let row = 0; row < count; row++) {
    for (let col = 0; col < count; col++) {
      if (qr.isDark(row, col)) {
        d += `M${col + margin} ${row + margin}h1v1h-1z`;
      }
    }
  }

  return (
    `<svg viewBox="0 0 ${size} ${size}" xmlns="http://www.w3.org/2000/svg" ` +
    `width="100%" height="100%" style="display:block" ` +
    `shape-rendering="crispEdges" role="img" aria-label="Boarding pass QR code">` +
    `<rect width="${size}" height="${size}" fill="#ffffff"/>` +
    `<path d="${d}" fill="${color}"/></svg>`
  );
}
