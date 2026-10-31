package dev.carloslpv.machinetelemetry.gateway.lorawan;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig;
import dev.carloslpv.machinetelemetry.gateway.lorawan.AtModem.Result;

import static org.assertj.core.api.Assertions.assertThat;

class AtModemTests {

	static GatewayConfig.LoraWan config(String success, int count) {
		return new GatewayConfig.LoraWan("/dev/null", null, false, null, success, count, null, null, Duration.ofMillis(50),
				Duration.ofSeconds(1), Duration.ofMillis(300), List.of("motor-current", "motor-temp"));
	}

	@Test
	void buildsTheCommandFromTheTemplate() {
		assertThat(AtModem.command("AT+SENDB={port}:{hex}", 2, new byte[] { 0x11, (byte) 0xAB })).isEqualTo("AT+SENDB=2:11AB");
		assertThat(AtModem.command("AT+SEND={port},{hex}", 1, new byte[] { 0x01 })).isEqualTo("AT+SEND=1,01");
	}

	@Test
	void sentWhenTheModemSaysOk() throws Exception {
		FakeModem modem = new FakeModem(cmd -> List.of("OK"));
		assertThat(new AtModem(modem, config("^OK$", 1)).send(1, new byte[] { 1 })).isEqualTo(Result.SENT);
		assertThat(modem.commands).containsExactly("AT+SENDB=1:01");
	}

	@Test
	void waitsForEveryReceiveWindowLikeTheTccModule() throws Exception {
		// the TCC's module only finished a send after printing RxConfig twice, one per receive window
		FakeModem modem = new FakeModem(cmd -> List.of("OK", "TxDone", "RxConfig: RX1", "RxConfig: RX2"));
		assertThat(new AtModem(modem, config("RxConfig", 2)).send(1, new byte[] { 1 })).isEqualTo(Result.SENT);

		FakeModem stuck = new FakeModem(cmd -> List.of("OK", "RxConfig: RX1"));
		assertThat(new AtModem(stuck, config("RxConfig", 2)).send(1, new byte[] { 1 })).isEqualTo(Result.TIMEOUT);
	}

	@Test
	void tellsBusyApartFromError() throws Exception {
		assertThat(new AtModem(new FakeModem(cmd -> List.of("AT_BUSY_ERROR")), config("^OK$", 1)).send(1, new byte[] { 1 }))
			.isEqualTo(Result.BUSY);
		assertThat(new AtModem(new FakeModem(cmd -> List.of("AT_NO_NETWORK_JOINED", "ERROR")), config("^OK$", 1)).send(1, new byte[] { 1 }))
			.isEqualTo(Result.ERROR);
	}

	@Test
	void ignoresWhatTheModemSaidBeforeTheCommand() throws Exception {
		FakeModem modem = new FakeModem(cmd -> cmd.equals("AT") ? List.of("OK") : List.of());
		modem.writeLine("AT"); // an "OK" from an earlier exchange is still in the buffer
		assertThat(new AtModem(modem, config("^OK$", 1)).send(1, new byte[] { 1 })).isEqualTo(Result.TIMEOUT);
	}
}
