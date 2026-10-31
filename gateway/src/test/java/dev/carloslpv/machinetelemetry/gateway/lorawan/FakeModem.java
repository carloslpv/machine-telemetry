package dev.carloslpv.machinetelemetry.gateway.lorawan;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** A serial line whose far end is a scripted modem: each command written gets the lines the script returns. */
public final class FakeModem implements SerialLine {

	private final Function<String, List<String>> script;

	private final BlockingQueue<String> output = new LinkedBlockingQueue<>();

	public final List<String> commands = java.util.Collections.synchronizedList(new ArrayList<>());

	public FakeModem(Function<String, List<String>> script) {
		this.script = script;
	}

	@Override
	public void writeLine(String line) {
		commands.add(line);
		output.addAll(script.apply(line));
	}

	@Override
	public String readLine(Duration timeout) throws InterruptedException {
		return output.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

	@Override
	public void discardInput() {
		output.clear();
	}

	@Override
	public void close() {
	}
}
