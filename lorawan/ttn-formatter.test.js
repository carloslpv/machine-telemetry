// node --test ttn-formatter.test.js
const test = require("node:test");
const assert = require("node:assert");
const { decodeUplink } = require("./ttn-formatter.js");
const { vectors } = require("./test-vectors.json");

for (const v of vectors) {
  test(v.name, () => {
    const bytes = Buffer.from(v.hex, "hex");
    const result = decodeUplink({ bytes: [...bytes], fPort: 1 });
    assert.deepStrictEqual(result.data, v.decoded);
  });
}

test("rejects a truncated payload", () => {
  assert.ok(decodeUplink({ bytes: [0x11, 0x00], fPort: 1 }).errors);
});

test("rejects an unknown version", () => {
  assert.ok(decodeUplink({ bytes: [0x21, 0, 0, 0, 0, 0, 0, 0, 0], fPort: 1 }).errors);
});
