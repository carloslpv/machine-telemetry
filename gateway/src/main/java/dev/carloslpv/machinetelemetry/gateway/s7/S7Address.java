package dev.carloslpv.machinetelemetry.gateway.s7;

import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An address inside a data block in STEP 7 notation: {@code DB1.DBX0.1} (bit), {@code DB1.DBB0} (byte),
 * {@code DB1.DBW2} (word), {@code DB1.DBD4} (double word). The type says how to read the bytes; it defaults to
 * BOOL, BYTE, INT and DINT, so a REAL has to be declared.
 */
public record S7Address(int db, int offset, int bit, String type) {

	private static final Pattern SYNTAX = Pattern.compile("DB(\\d+)\\.DB([XBWD])(\\d+)(?:\\.([0-7]))?", Pattern.CASE_INSENSITIVE);

	public static S7Address parse(String address, String declaredType) {
		Matcher m = SYNTAX.matcher(address.trim());
		if (!m.matches()) {
			throw new IllegalArgumentException("'" + address + "' is not an S7 address like DB1.DBX0.0, DB1.DBW2 or DB1.DBD4");
		}
		char width = Character.toUpperCase(m.group(2).charAt(0));
		if ((width == 'X') != (m.group(4) != null)) {
			throw new IllegalArgumentException("'" + address + "': only DBX addresses take a bit number");
		}
		String type = declaredType != null ? declaredType : switch (width) {
			case 'X' -> "BOOL";
			case 'B' -> "BYTE";
			case 'W' -> "INT";
			default -> "DINT";
		};
		boolean fits = switch (type) {
			case "BOOL" -> width == 'X';
			case "BYTE" -> width == 'B';
			case "INT", "WORD" -> width == 'W';
			case "DINT", "DWORD", "REAL" -> width == 'D';
			default -> throw new IllegalArgumentException("unsupported S7 type " + type);
		};
		if (!fits) {
			throw new IllegalArgumentException("'" + address + "' cannot hold a " + type);
		}
		int bit = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
		return new S7Address(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3)), bit, type);
	}

	public int size() {
		return switch (type) {
			case "BOOL", "BYTE" -> 1;
			case "INT", "WORD" -> 2;
			default -> 4;
		};
	}

	/** Decodes the value from a buffer that starts at byte {@code base} of the same data block (big-endian). */
	public Object decode(byte[] block, int base) {
		ByteBuffer buffer = ByteBuffer.wrap(block);
		int i = offset - base;
		return switch (type) {
			case "BOOL" -> ((block[i] >> bit) & 1) == 1;
			case "BYTE" -> (long) (block[i] & 0xFF);
			case "INT" -> (long) buffer.getShort(i);
			case "WORD" -> (long) (buffer.getShort(i) & 0xFFFF);
			case "DINT" -> (long) buffer.getInt(i);
			case "DWORD" -> buffer.getInt(i) & 0xFFFFFFFFL;
			case "REAL" -> (double) buffer.getFloat(i);
			default -> throw new IllegalStateException(type);
		};
	}
}
