package dev.carloslpv.machinetelemetry.gateway.lorawan;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A serial port opened as a file ({@code /dev/ttyUSB0}), configured with {@code stty}. No native library, so the
 * same jar runs on a Raspberry Pi, a TV box with an ARM build of Debian, or a PC.
 */
public final class FileSerialLine implements SerialLine {

	private static final Logger log = LoggerFactory.getLogger(FileSerialLine.class);

	private final String device;

	private final InputStream in;

	private final OutputStream out;

	private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

	private final Thread reader;

	private volatile boolean open = true;

	public FileSerialLine(String device, int baud, boolean configure) throws IOException, InterruptedException {
		this.device = device;
		if (configure) {
			Process stty = new ProcessBuilder("stty", "-F", device, Integer.toString(baud), "raw", "-echo", "-hupcl")
				.redirectErrorStream(true)
				.start();
			String output = new String(stty.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			if (stty.waitFor() != 0) {
				throw new IOException("stty could not configure " + device + ": " + output);
			}
		}
		in = new FileInputStream(device);
		out = new FileOutputStream(device);
		reader = Thread.ofVirtual().name("serial-" + device).start(this::readLoop);
	}

	private void readLoop() {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		try {
			int b;
			while (open && (b = in.read()) >= 0) {
				if (b == '\n') {
					String text = line.toString(StandardCharsets.US_ASCII).strip();
					line.reset();
					if (!text.isEmpty()) {
						log.debug("{} < {}", device, text);
						lines.add(text);
					}
				}
				else if (b != '\r') {
					line.write(b);
				}
			}
		}
		catch (IOException ex) {
			if (open) {
				log.warn("{}: {}", device, ex.getMessage());
			}
		}
	}

	@Override
	public void writeLine(String line) throws IOException {
		log.debug("{} > {}", device, line);
		out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
		out.flush();
	}

	@Override
	public String readLine(Duration timeout) throws InterruptedException {
		return lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

	@Override
	public void discardInput() {
		lines.clear();
	}

	@Override
	public void close() {
		open = false;
		try {
			in.close();
			out.close();
		}
		catch (IOException ignored) {
			// closing anyway
		}
		reader.interrupt();
	}
}
