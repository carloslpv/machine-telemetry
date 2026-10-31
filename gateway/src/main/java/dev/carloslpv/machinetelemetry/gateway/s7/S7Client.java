package dev.carloslpv.machinetelemetry.gateway.s7;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Minimal S7 communication client: the PUT/GET access a Siemens CPU allows to its data blocks, the same thing
 * node-red-contrib-s7 (and Snap7 under it) does.
 * <p>
 * The protocol stack is three layers deep:
 * <ol>
 * <li>TPKT (RFC 1006): 4-byte header with the packet length, over TCP port 102;</li>
 * <li>COTP (ISO 8073 class 0): a connection request carrying the TSAPs that select rack and slot, then data
 * TPDUs;</li>
 * <li>S7 PDUs: "setup communication" to agree on the PDU size, then "read var" requests.</li>
 * </ol>
 * Only reading data blocks is implemented; the gateway never writes to the PLC. On the CPU, PUT/GET must be
 * allowed ("Permit access with PUT/GET communication") and the data block must not use optimized access, otherwise
 * it has no byte offsets to read.
 */
public final class S7Client implements Closeable {

	private static final int TPKT_VERSION = 3;

	private static final byte COTP_CONNECTION_REQUEST = (byte) 0xE0;

	private static final byte COTP_CONNECTION_CONFIRM = (byte) 0xD0;

	private static final byte COTP_DATA = (byte) 0xF0;

	private static final byte S7_PROTOCOL_ID = 0x32;

	private static final byte ROSCTR_JOB = 0x01;

	private static final byte ROSCTR_ACK_DATA = 0x03;

	private static final byte FUNCTION_SETUP_COMMUNICATION = (byte) 0xF0;

	private static final byte FUNCTION_READ_VAR = 0x04;

	private static final byte AREA_DB = (byte) 0x84;

	private static final int REQUESTED_PDU_SIZE = 480;

	/** S7 ack header (12) + read var parameters (2) + data item header (4). */
	private static final int READ_OVERHEAD = 18;

	private Socket socket;

	private DataInputStream in;

	private OutputStream out;

	private int pduSize;

	private int pduReference;

	public void connect(String host, int port, int rack, int slot, Duration timeout) throws IOException {
		int millis = Math.toIntExact(timeout.toMillis());
		socket = new Socket();
		try {
			socket.connect(new InetSocketAddress(host, port), millis);
			socket.setSoTimeout(millis);
			socket.setTcpNoDelay(true);
			in = new DataInputStream(socket.getInputStream());
			out = socket.getOutputStream();

			writeTpkt(connectionRequest(0x0100, remoteTsap(rack, slot)));
			byte[] confirm = readTpkt();
			if (confirm.length < 2 || confirm[1] != COTP_CONNECTION_CONFIRM) {
				throw new S7Exception("the PLC refused the ISO connection; check rack " + rack + " and slot " + slot);
			}

			byte[] reply = exchange(setupCommunication(nextReference(), REQUESTED_PDU_SIZE));
			pduSize = parseNegotiatedPduSize(reply);
		}
		catch (IOException ex) {
			close();
			throw ex;
		}
	}

	public int pduSize() {
		return pduSize;
	}

	/** Reads {@code length} bytes of a data block, split into as many requests as the PDU size requires. */
	public byte[] readDb(int db, int start, int length) throws IOException {
		if (socket == null) {
			throw new IOException("not connected");
		}
		int chunk = pduSize - READ_OVERHEAD;
		byte[] result = new byte[length];
		for (int offset = 0; offset < length; offset += chunk) {
			int size = Math.min(chunk, length - offset);
			byte[] reply = exchange(readVarRequest(nextReference(), db, start + offset, size));
			byte[] data = parseReadVarReply(reply, size);
			System.arraycopy(data, 0, result, offset, size);
		}
		return result;
	}

	@Override
	public void close() {
		if (socket != null) {
			try {
				socket.close();
			}
			catch (IOException ignored) {
				// nothing left to do with a socket that will not close
			}
			socket = null;
		}
	}

	private int nextReference() {
		pduReference = (pduReference + 1) & 0xFFFF;
		return pduReference;
	}

	private byte[] exchange(byte[] s7Pdu) throws IOException {
		ByteArrayOutputStream tpdu = new ByteArrayOutputStream();
		// COTP data TPDU: length 2, type DT, last data unit
		tpdu.write(0x02);
		tpdu.write(COTP_DATA);
		tpdu.write(0x80);
		tpdu.writeBytes(s7Pdu);
		writeTpkt(tpdu.toByteArray());
		byte[] reply = readTpkt();
		if (reply.length < 3 || reply[1] != COTP_DATA) {
			throw new S7Exception("expected a COTP data TPDU, got " + HexFormat.of().formatHex(reply));
		}
		byte[] pdu = new byte[reply.length - 3];
		System.arraycopy(reply, 3, pdu, 0, pdu.length);
		return pdu;
	}

	private void writeTpkt(byte[] payload) throws IOException {
		int length = payload.length + 4;
		out.write(new byte[] { TPKT_VERSION, 0, (byte) (length >> 8), (byte) length });
		out.write(payload);
		out.flush();
	}

	private byte[] readTpkt() throws IOException {
		byte[] header = new byte[4];
		in.readFully(header);
		if (header[0] != TPKT_VERSION) {
			throw new S7Exception("not a TPKT packet (version " + header[0] + ")");
		}
		int length = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
		if (length < 7) {
			throw new S7Exception("TPKT packet too short: " + length);
		}
		byte[] payload = new byte[length - 4];
		in.readFully(payload);
		return payload;
	}

	/** Remote TSAP for a PG connection: high byte 1 (PG), low byte rack * 32 + slot. */
	static int remoteTsap(int rack, int slot) {
		return 0x0100 | (rack * 0x20 + slot);
	}

	static byte[] connectionRequest(int localTsap, int remoteTsap) {
		return new byte[] {
				0x11, // length of the rest of the header
				COTP_CONNECTION_REQUEST, 0x00, 0x00, // destination reference
				0x00, 0x01, // source reference
				0x00, // class 0
				(byte) 0xC0, 0x01, 0x0A, // TPDU size 1024
				(byte) 0xC1, 0x02, (byte) (localTsap >> 8), (byte) localTsap,
				(byte) 0xC2, 0x02, (byte) (remoteTsap >> 8), (byte) remoteTsap };
	}

	static byte[] setupCommunication(int reference, int pduSize) {
		byte[] params = { FUNCTION_SETUP_COMMUNICATION, 0x00, 0x00, 0x01, 0x00, 0x01, (byte) (pduSize >> 8), (byte) pduSize };
		return job(reference, params);
	}

	static byte[] readVarRequest(int reference, int db, int start, int length) {
		int bitAddress = start * 8;
		byte[] params = { FUNCTION_READ_VAR, 0x01, // one item
				0x12, 0x0A, 0x10, // variable specification, 10 bytes follow, S7ANY addressing
				0x02, // transport size BYTE
				(byte) (length >> 8), (byte) length, (byte) (db >> 8), (byte) db, AREA_DB,
				(byte) (bitAddress >> 16), (byte) (bitAddress >> 8), (byte) bitAddress };
		return job(reference, params);
	}

	private static byte[] job(int reference, byte[] params) {
		byte[] pdu = new byte[10 + params.length];
		pdu[0] = S7_PROTOCOL_ID;
		pdu[1] = ROSCTR_JOB;
		pdu[4] = (byte) (reference >> 8);
		pdu[5] = (byte) reference;
		pdu[6] = (byte) (params.length >> 8);
		pdu[7] = (byte) params.length;
		System.arraycopy(params, 0, pdu, 10, params.length);
		return pdu;
	}

	/** Checks the 12-byte ack-data header and returns where the parameters start. */
	private static int checkAckHeader(byte[] pdu) throws S7Exception {
		if (pdu.length < 12 || pdu[0] != S7_PROTOCOL_ID || pdu[1] != ROSCTR_ACK_DATA) {
			throw new S7Exception("unexpected S7 reply " + HexFormat.of().formatHex(pdu));
		}
		int errorClass = pdu[10] & 0xFF;
		int errorCode = pdu[11] & 0xFF;
		if (errorClass != 0 || errorCode != 0) {
			throw new S7Exception(String.format("the PLC rejected the request (error class 0x%02X, code 0x%02X)", errorClass, errorCode));
		}
		return 12;
	}

	static int parseNegotiatedPduSize(byte[] pdu) throws S7Exception {
		int p = checkAckHeader(pdu);
		if (pdu.length < p + 8 || pdu[p] != FUNCTION_SETUP_COMMUNICATION) {
			throw new S7Exception("unexpected setup communication reply " + HexFormat.of().formatHex(pdu));
		}
		return ((pdu[p + 6] & 0xFF) << 8) | (pdu[p + 7] & 0xFF);
	}

	static byte[] parseReadVarReply(byte[] pdu, int expected) throws S7Exception {
		int p = checkAckHeader(pdu);
		int paramLength = ((pdu[6] & 0xFF) << 8) | (pdu[7] & 0xFF);
		if (pdu.length < p + paramLength + 4 || pdu[p] != FUNCTION_READ_VAR) {
			throw new S7Exception("unexpected read var reply " + HexFormat.of().formatHex(pdu));
		}
		int item = p + paramLength;
		int returnCode = pdu[item] & 0xFF;
		if (returnCode != 0xFF) {
			throw new S7Exception(describeReturnCode(returnCode));
		}
		int transportSize = pdu[item + 1] & 0xFF;
		int length = ((pdu[item + 2] & 0xFF) << 8) | (pdu[item + 3] & 0xFF);
		// BYTE/WORD/DWORD (0x04) and INTEGER (0x05) report the length in bits, the others in bytes
		if (transportSize == 0x04 || transportSize == 0x05) {
			length /= 8;
		}
		if (length != expected || pdu.length < item + 4 + length) {
			throw new S7Exception("asked for " + expected + " bytes, the PLC returned " + length);
		}
		byte[] data = new byte[length];
		System.arraycopy(pdu, item + 4, data, 0, length);
		return data;
	}

	static String describeReturnCode(int code) {
		return switch (code) {
			case 0x03 -> "access denied (0x03): is PUT/GET allowed in the CPU's protection settings?";
			case 0x05 -> "address out of range (0x05): is the data block shorter than configured, or optimized?";
			case 0x06 -> "data type not supported (0x06)";
			case 0x0A -> "object does not exist (0x0A): the data block is not in the CPU";
			default -> String.format("the PLC could not read the item (return code 0x%02X)", code);
		};
	}
}
