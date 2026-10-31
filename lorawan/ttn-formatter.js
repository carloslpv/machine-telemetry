// Uplink payload formatter for The Things Network (v3), payload version 1.
// Paste it in the application's Payload formatters > Uplink > Custom Javascript formatter.
// The platform decodes frm_payload itself; this is for reading uplinks in the TTN console.
// Layout: see PayloadCodec.java in the gateway. Tested against test-vectors.json (node ttn-formatter.test.js).

var STATES = ["IDLE", "RUNNING", "FAULT", "NO_DATA"];

function u16(bytes, i) {
  return (bytes[i] << 8) | bytes[i + 1];
}

function decodeUplink(input) {
  var bytes = input.bytes;
  if (bytes.length < 9 || (bytes.length - 9) % 2 !== 0) {
    return { errors: ["unexpected payload length " + bytes.length] };
  }
  var version = bytes[0] >> 4;
  if (version !== 1) {
    return { errors: ["unknown payload version " + version] };
  }
  var analogs = [];
  for (var i = 9; i < bytes.length; i += 2) {
    var raw = u16(bytes, i);
    if (raw === 0x8000) {
      analogs.push(null);
    } else {
      if (raw > 0x7fff) raw -= 0x10000;
      analogs.push(raw / 10);
    }
  }
  return {
    data: {
      version: version,
      state: STATES[bytes[0] & 0x0f] || "UNKNOWN",
      faultCode: u16(bytes, 1),
      goodCount16: u16(bytes, 3),
      rejectCount16: u16(bytes, 5),
      secondsInState: u16(bytes, 7),
      analogs: analogs,
    },
  };
}

if (typeof module !== "undefined") {
  module.exports = { decodeUplink: decodeUplink };
}
