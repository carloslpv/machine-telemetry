package dev.carloslpv.machinetelemetry.gateway.lorawan;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;

import dev.carloslpv.machinetelemetry.gateway.config.GatewayConfig;

/**
 * Sends uplinks through a LoRaWAN module that takes AT commands, like the SMW-SX1276M0 of the TCC.
 * <p>
 * Modules differ in the send command and in what they answer, so both are configurable: {@code send-template}
 * builds the command, and the answer is matched line by line against {@code success-pattern} (which may have to
 * appear {@code success-count} times: the TCC's module only finished after printing RxConfig twice, once per receive
 * window), {@code busy-pattern} and {@code error-pattern}.
 */
public final class AtModem {

	public enum Result { SENT, BUSY, ERROR, TIMEOUT }

	private final SerialLine line;

	private final GatewayConfig.LoraWan config;

	private final Pattern success;

	private final Pattern busy;

	private final Pattern error;

	public AtModem(SerialLine line, GatewayConfig.LoraWan config) {
		this.line = line;
		this.config = config;
		this.success = Pattern.compile(config.successPattern());
		this.busy = Pattern.compile(config.busyPattern());
		this.error = Pattern.compile(config.errorPattern());
	}

	public static String command(String template, int port, byte[] payload) {
		return template.replace("{port}", Integer.toString(port)).replace("{hex}", HexFormat.of().withUpperCase().formatHex(payload));
	}

	public Result send(int port, byte[] payload) throws IOException, InterruptedException {
		line.discardInput();
		line.writeLine(command(config.sendTemplate(), port, payload));
		Instant deadline = Instant.now().plus(config.responseTimeout());
		int successes = 0;
		while (true) {
			Duration left = Duration.between(Instant.now(), deadline);
			if (left.isNegative() || left.isZero()) {
				return Result.TIMEOUT;
			}
			String answer = line.readLine(left);
			if (answer == null) {
				return Result.TIMEOUT;
			}
			// busy is checked first: some modules print "AT_BUSY_ERROR", which also matches the error pattern
			if (busy.matcher(answer).matches()) {
				return Result.BUSY;
			}
			if (error.matcher(answer).matches()) {
				return Result.ERROR;
			}
			if (success.matcher(answer).find() && ++successes >= config.successCount()) {
				return Result.SENT;
			}
		}
	}
}
