/**
 * png-to-ico.cjs — Convert one or more PNG files into a valid Windows .ico
 * container.  Modern Windows (and rcedit) accept PNG data directly inside an
 * ICO wrapper as long as the ICONDIR / ICONDIRENTRY headers are correct.
 *
 * Usage:
 *   node scripts/png-to-ico.cjs <input.png> [input2.png ...] <output.ico>
 *
 * If a single PNG is given it is wrapped as a 256x256 icon entry.
 */
'use strict';

const fs = require('fs');
const path = require('path');

/**
 * Build a valid ICO buffer from a list of PNG buffers.
 * @param {Array<{data:Buffer,width:number,height:number,bpp:number}>} images
 * @returns {Buffer}
 */
function buildIco(images) {
  // Each ICONDIRENTRY is 16 bytes.  ICONDIR header is 6 bytes.
  const headerSize = 6 + 16 * images.length;
  let dataOffset = headerSize;

  const entries = [];
  const chunks = [];

  for (const img of images) {
    const { data, width, height, bpp } = img;
    const size = data.length;

    // ICONDIRENTRY — width/height of 0 means 256px
    const entry = Buffer.alloc(16);
    entry.writeUInt8(width  >= 256 ? 0 : width,  0);
    entry.writeUInt8(height >= 256 ? 0 : height, 1);
    entry.writeUInt8(0, 2);                        // color count (0 = truecolor)
    entry.writeUInt8(0, 3);                        // reserved
    entry.writeUInt16LE(1, 4);                     // color planes
    entry.writeUInt16LE(bpp || 32, 6);             // bits per pixel
    entry.writeUInt32LE(size, 8);                  // size of image data
    entry.writeUInt32LE(dataOffset, 12);           // offset from start of file

    entries.push(entry);
    chunks.push(data);
    dataOffset += size;
  }

  // ICONDIR header
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);              // reserved, must be 0
  header.writeUInt16LE(1, 2);              // type: 1 = icon
  header.writeUInt16LE(images.length, 4);  // number of images

  return Buffer.concat([header, ...entries, ...chunks]);
}

/**
 * Extract dimensions + bpp from a PNG IHDR chunk.
 * @param {Buffer} png
 */
function readPngInfo(png) {
  // PNG signature (8 bytes) then 4-byte length, 4-byte 'IHDR', then:
  //   width (4) height (4) bit depth (1) color type (1) ...
  if (png.readUInt32BE(0) !== 0x89504E47 || png.readUInt32BE(4) !== 0x0D0A1A0A) {
    throw new Error('Not a valid PNG (bad signature)');
  }
  const ihdrStart = 8;
  const length = png.readUInt32BE(ihdrStart);
  const type   = png.readUInt32BE(ihdrStart + 4);
  if (type !== 0x49484452) { // 'IHDR'
    throw new Error('PNG does not start with IHDR');
  }
  const dataStart = ihdrStart + 8;
  const width    = png.readUInt32BE(dataStart);
  const height   = png.readUInt32BE(dataStart + 4);
  const bitDepth = png[dataStart + 8];
  const colorType = png[dataStart + 9];
  // Color type -> bytes per pixel (approx for bpp field in icon entry)
  // 0=grayscale 2=rgb 3=indexed 4=grayscale+alpha 6=rgba
  const samplesPerPixel = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[colorType] || 4;
  const bpp = samplesPerPixel * bitDepth;
  return { width, height, bpp };
}

function main(argv) {
  const args = argv.slice(2);
  if (args.length < 2) {
    console.error('Usage: node png-to-ico.cjs <input.png> [input2.png ...] <output.ico>');
    process.exit(1);
  }

  const output = args.pop();
  const inputs = args;

  const images = inputs.map((file) => {
    const data = fs.readFileSync(file);
    const info = readPngInfo(data);
    console.log(`  -> ${path.basename(file)}: ${info.width}x${info.height} @ ${info.bpp}bpp  (${(data.length/1024).toFixed(1)} KB)`);
    return { data, ...info };
  });

  const ico = buildIco(images);
  fs.writeFileSync(output, ico);
  console.log(`Wrote ${output}  (${(ico.length / 1024).toFixed(1)} KB, ${images.length} image(s))`);
}

if (require.main === module) {
  main(process.argv);
}

module.exports = { buildIco, readPngInfo };
