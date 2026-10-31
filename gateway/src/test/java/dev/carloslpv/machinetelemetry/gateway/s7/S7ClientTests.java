package dev.carloslpv.machinetelemetry.gateway.s7;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Frame-level tests. The expected bytes are the ones Snap7 sends to an S7-1200, which is also what Wireshark's S7comm
 * dissector shows; the client was additionally run against python-snap7's server (plc-sim) in the end-to-end test.
 */
class S7ClientTests {

	private static final HexFormat HEX = HexFormat.of();

	@Test
	void connectionRequestSelectsRackAndSlotThroughTheRemoteTsap() {
		assertThat(HEX.formatHex(S7Client.connectionRequest(0x0100, S7Client.remoteTsap(0, 1))))
			.isEqualTo("11e00000000100c0010ac1020100c2020101");
		assertThat(S7Client.remoteTsap(0, 2)).isEqualTo(0x0102); // S7-300 CPU in slot 2
	}

	@Test
	void setupCommunicationAsksForA480BytePdu() {
		assertThat(HEX.formatHex(S7Client.setupCommunication(1, 480)))
			.isEqualTo("32010000000100080000" + "f0000001000101e0");
	}

	@Test
	void readVarAddressesTheDataBlockInBits() {
		// DB1, 26 bytes from byte 4: bit address 32 = 0x000020
		assertThat(HEX.formatHex(S7Client.readVarRequest(2, 1, 4, 26)))
			.isEqualTo("320100000002000e0000" + "0401" + "120a1002001a000184000020");
	}

	@Test
	void parsesTheNegotiatedPduSize() throws Exception {
		byte[] reply = HEX.parseHex("320300000001000800000000" + "f0000001000100f0");
		assertThat(S7Client.parseNegotiatedPduSize(reply)).isEqualTo(240);
	}

	@Test
	void parsesReadDataReportedInBits() throws Exception {
		byte[] reply = HEX.parseHex("320300000002000200070000" + "0401" + "ff040018" + "010203");
		assertThat(S7Client.parseReadVarReply(reply, 3)).containsExactly(1, 2, 3);
	}

	@Test
	void explainsTheReturnCodesPeopleHitWhenCommissioning() {
		byte[] optimized = HEX.parseHex("320300000002000200040000" + "0401" + "05000000");
		assertThatThrownBy(() -> S7Client.parseReadVarReply(optimized, 26)).hasMessageContaining("optimized");
		byte[] denied = HEX.parseHex("320300000002000200040000" + "0401" + "03000000");
		assertThatThrownBy(() -> S7Client.parseReadVarReply(denied, 26)).hasMessageContaining("PUT/GET");
		byte[] headerError = HEX.parseHex("320300000002000200040085" + "0401" + "ff040008");
		assertThatThrownBy(() -> S7Client.parseReadVarReply(headerError, 1)).hasMessageContaining("0x85");
	}

	@Test
	void splitsAReadLargerThanThePdu() throws Exception {
		// a fake CPU that negotiates a 240-byte PDU and answers every read with bytes equal to their offset
		try (ServerSocket server = new ServerSocket(0)) {
			CompletableFuture<Integer> reads = CompletableFuture.supplyAsync(() -> fakeCpu(server));
			S7Client client = new S7Client();
			client.connect("127.0.0.1", server.getLocalPort(), 0, 1, Duration.ofSeconds(5));
			assertThat(client.pduSize()).isEqualTo(240);
			byte[] data = client.readDb(1, 0, 500);
			client.close();
			for (int i = 0; i < data.length; i++) {
				assertThat(data[i]).isEqualTo((byte) i);
			}
			assertThat(reads.get()).isEqualTo(3); // 222 + 222 + 56
		}
	}

	private static int fakeCpu(ServerSocket server) {
		int reads = 0;
		try (Socket socket = server.accept()) {
			DataInputStream in = new DataInputStream(socket.getInputStream());
			OutputStream out = socket.getOutputStream();
			readTpkt(in); // connection request
			writeTpkt(out, HEX.parseHex("11d00001000100c0010ac1020100c2020101"));
			readTpkt(in); // setup communication
			writeTpkt(out, HEX.parseHex("02f080" + "320300000001000800000000" + "f0000001000100f0"));
			while (true) {
				byte[] request = readTpkt(in);
				if (request == null) {
					return reads;
				}
				reads++;
				int p = 3 + 10 + 2; // COTP, S7 header, function and item count
				int length = ((request[p + 4] & 0xFF) << 8) | (request[p + 5] & 0xFF);
				int bitAddress = ((request[p + 9] & 0xFF) << 16) | ((request[p + 10] & 0xFF) << 8) | (request[p + 11] & 0xFF);
				int start = bitAddress / 8;
				byte[] reply = new byte[3 + 12 + 2 + 4 + length];
				System.arraycopy(HEX.parseHex("02f080320300000002000200000000"), 0, reply, 0, 15);
				int dataLength = 4 + length;
				reply[11] = (byte) (dataLength >> 8);
				reply[12] = (byte) dataLength;
				reply[15] = 0x04;
				reply[16] = 0x01;
				reply[17] = (byte) 0xFF;
				reply[18] = 0x04;
				reply[19] = (byte) ((length * 8) >> 8);
				reply[20] = (byte) (length * 8);
				for (int i = 0; i < length; i++) {
					reply[21 + i] = (byte) (start + i);
				}
				writeTpkt(out, reply);
			}
		}
		catch (IOException ex) {
			return reads;
		}
	}

	private static byte[] readTpkt(DataInputStream in) throws IOException {
		byte[] header = new byte[4];
		try {
			in.readFully(header);
		}
		catch (java.io.EOFException ex) {
			return null;
		}
		byte[] body = new byte[(((header[2] & 0xFF) << 8) | (header[3] & 0xFF)) - 4];
		in.readFully(body);
		return body;
	}

	private static void writeTpkt(OutputStream out, byte[] body) throws IOException {
		int length = body.length + 4;
		out.write(new byte[] { 3, 0, (byte) (length >> 8), (byte) length });
		out.write(body);
		out.flush();
	}
}
