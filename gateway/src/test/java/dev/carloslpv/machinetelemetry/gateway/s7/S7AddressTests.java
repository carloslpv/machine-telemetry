package dev.carloslpv.machinetelemetry.gateway.s7;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S7AddressTests {

	// DB_Telemetria from plc-sim: Running 0.0, Fault 0.1, FaultCode INT 2, GoodCount DINT 4, MotorCurrent REAL 12
	private static final byte[] BLOCK = { 0b10, 0, 0x00, 0x66, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFE, 0, 0, 0, 0,
			0x40, (byte) 0xF0, 0, 0 };

	@Test
	void parsesStep7Notation() {
		assertThat(S7Address.parse("DB1.DBX0.1", null)).isEqualTo(new S7Address(1, 0, 1, "BOOL"));
		assertThat(S7Address.parse("db12.dbw2", null)).isEqualTo(new S7Address(12, 2, 0, "INT"));
		assertThat(S7Address.parse("DB1.DBD12", "REAL")).isEqualTo(new S7Address(1, 12, 0, "REAL"));
	}

	@Test
	void decodesBigEndianValues() {
		assertThat(S7Address.parse("DB1.DBX0.0", null).decode(BLOCK, 0)).isEqualTo(false);
		assertThat(S7Address.parse("DB1.DBX0.1", null).decode(BLOCK, 0)).isEqualTo(true);
		assertThat(S7Address.parse("DB1.DBW2", null).decode(BLOCK, 0)).isEqualTo(102L);
		assertThat(S7Address.parse("DB1.DBD4", "DINT").decode(BLOCK, 0)).isEqualTo(-2L);
		assertThat(S7Address.parse("DB1.DBD4", "DWORD").decode(BLOCK, 0)).isEqualTo(4294967294L);
		assertThat(S7Address.parse("DB1.DBD12", "REAL").decode(BLOCK, 0)).isEqualTo(7.5);
	}

	@Test
	void decodesRelativeToWhereTheReadStarted() {
		byte[] fromByte12 = { 0x40, (byte) 0xF0, 0, 0 };
		assertThat(S7Address.parse("DB1.DBD12", "REAL").decode(fromByte12, 12)).isEqualTo(7.5);
	}

	@Test
	void refusesATypeThatDoesNotFitTheWidth() {
		assertThatThrownBy(() -> S7Address.parse("DB1.DBW2", "REAL")).hasMessageContaining("cannot hold a REAL");
		assertThatThrownBy(() -> S7Address.parse("DB1.DBW2.1", null)).hasMessageContaining("bit number");
		assertThatThrownBy(() -> S7Address.parse("MW10", null)).hasMessageContaining("not an S7 address");
	}
}
