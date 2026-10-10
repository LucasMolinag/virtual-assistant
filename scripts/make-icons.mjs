import { mkdirSync, writeFileSync } from 'node:fs';
import { PNG } from 'pngjs';

function fill(png, color) {
  for (let i = 0; i < png.data.length; i += 4) {
    png.data[i] = color[0];
    png.data[i + 1] = color[1];
    png.data[i + 2] = color[2];
    png.data[i + 3] = 255;
  }
}

function set(png, x, y, color) {
  const px = Math.round(x);
  const py = Math.round(y);
  if (px < 0 || py < 0 || px >= png.width || py >= png.height) return;
  const i = (png.width * py + px) << 2;
  png.data[i] = color[0];
  png.data[i + 1] = color[1];
  png.data[i + 2] = color[2];
  png.data[i + 3] = 255;
}

function roundRect(png, x, y, w, h, r, color) {
  for (let yy = 0; yy < h; yy += 1) {
    for (let xx = 0; xx < w; xx += 1) {
      const left = xx < r && yy < r && (xx - r) ** 2 + (yy - r) ** 2 > r ** 2;
      const right = xx >= w - r && yy < r && (xx - (w - r)) ** 2 + (yy - r) ** 2 > r ** 2;
      const bottomLeft = xx < r && yy >= h - r && (xx - r) ** 2 + (yy - (h - r)) ** 2 > r ** 2;
      const bottomRight = xx >= w - r && yy >= h - r && (xx - (w - r)) ** 2 + (yy - (h - r)) ** 2 > r ** 2;
      if (left || right || bottomLeft || bottomRight) continue;
      set(png, x + xx, y + yy, color);
    }
  }
}

function circle(png, cx, cy, radius, color) {
  for (let y = -radius; y <= radius; y += 1) {
    for (let x = -radius; x <= radius; x += 1) {
      if (x * x + y * y <= radius * radius) set(png, cx + x, cy + y, color);
    }
  }
}

function icon(size, padded) {
  const png = new PNG({ width: size, height: size });
  fill(png, [18, 22, 16]);
  const margin = padded ? size * 0.18 : size * 0.16;
  const cardW = size - margin * 2;
  const cardH = cardW * 0.92;
  const x = margin;
  const y = (size - cardH) / 2 + size * 0.03;
  const radius = size * 0.08;
  roundRect(png, x, y, cardW, cardH, radius, [244, 239, 228]);
  roundRect(png, x, y, cardW, cardH * 0.28, radius, [239, 106, 50]);
  roundRect(png, x, y + cardH * 0.18, cardW, cardH * 0.12, 0, [239, 106, 50]);
  const ringY = y - size * 0.035;
  circle(png, x + cardW * 0.28, ringY, size * 0.035, [244, 239, 228]);
  circle(png, x + cardW * 0.72, ringY, size * 0.035, [244, 239, 228]);
  circle(png, x + cardW * 0.28, ringY, size * 0.018, [18, 22, 16]);
  circle(png, x + cardW * 0.72, ringY, size * 0.018, [18, 22, 16]);
  return PNG.sync.write(png);
}

mkdirSync('public/icons', { recursive: true });
writeFileSync('public/icons/icon-192.png', icon(192, false));
writeFileSync('public/icons/icon-512.png', icon(512, false));
writeFileSync('public/icons/icon-maskable-512.png', icon(512, true));
