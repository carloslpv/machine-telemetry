package dev.carloslpv.machinetelemetry.gateway.lorawan;

import java.io.IOException;
import java.time.Duration;

/** A line-oriented serial port: what talking AT commands needs, and easy to fake in tests. */
public interface SerialLine extends AutoCloseable {

	void writeLine(String line) throws IOException;

	/** The next line without its terminator, or null if none arrived in time. */
	String readLine(Duration timeout) throws IOException, InterruptedException;

	/** Throws away anything the modem said that nobody read, such as unsolicited status lines. */
	void discardInput();

	@Override
	void close();
}
