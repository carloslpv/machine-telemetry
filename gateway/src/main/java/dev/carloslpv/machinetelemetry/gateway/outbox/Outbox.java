package dev.carloslpv.machinetelemetry.gateway.outbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Messages waiting to be sent, kept in SQLite so they survive the network going down and the gateway restarting.
 * <p>
 * This is the TCC's INTEGRATION_QUEUE (rows pending until integrated), with two changes: a row is deleted once the
 * broker acknowledged it instead of being flagged, so the table stays small; and the queue has a limit. When it is
 * full, the oldest telemetry is dropped first, because a lost state change would corrupt the stop history while a
 * lost average only leaves a gap in a chart.
 * <p>
 * The row id is the message sequence number: increasing, never reused (AUTOINCREMENT), and stored with the
 * message, so it is the deduplication key on the platform side.
 */
public final class Outbox implements AutoCloseable {

	public enum Kind { STATE, TELEMETRY, STATUS }

	public record Entry(long seq, Kind kind, String topic, String payload) {
	}

	private final Connection connection;

	private final int limit;

	private int size;

	private long dropped;

	public Outbox(Path file, int limit) throws SQLException {
		this.limit = limit;
		try {
			Files.createDirectories(file.toAbsolutePath().getParent());
		}
		catch (java.io.IOException ex) {
			throw new SQLException("cannot create " + file.getParent(), ex);
		}
		connection = DriverManager.getConnection("jdbc:sqlite:" + file);
		try (Statement st = connection.createStatement()) {
			st.execute("PRAGMA journal_mode=WAL");
			// a TV box loses power without warning; FULL makes a committed row survive that
			st.execute("PRAGMA synchronous=FULL");
			st.execute("""
					CREATE TABLE IF NOT EXISTS outbox (
					  seq INTEGER PRIMARY KEY AUTOINCREMENT,
					  kind TEXT NOT NULL,
					  topic TEXT NOT NULL,
					  payload TEXT NOT NULL,
					  created_at TEXT NOT NULL
					)""");
			try (ResultSet rs = st.executeQuery("SELECT count(*) FROM outbox")) {
				rs.next();
				size = rs.getInt(1);
			}
		}
	}

	public synchronized long add(Kind kind, String topic, String payload) throws SQLException {
		long seq;
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO outbox (kind, topic, payload, created_at) VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
			ps.setString(1, kind.name());
			ps.setString(2, topic);
			ps.setString(3, payload);
			ps.setString(4, Instant.now().toString());
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys()) {
				keys.next();
				seq = keys.getLong(1);
			}
		}
		size++;
		if (size > limit) {
			trim(size - limit);
		}
		notifyAll();
		return seq;
	}

	private void trim(int excess) throws SQLException {
		int removed = deleteOldest("kind = 'TELEMETRY'", excess);
		if (removed < excess) {
			removed += deleteOldest("1 = 1", excess - removed);
		}
		size -= removed;
		dropped += removed;
	}

	private int deleteOldest(String condition, int count) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"DELETE FROM outbox WHERE seq IN (SELECT seq FROM outbox WHERE " + condition + " ORDER BY seq LIMIT ?)")) {
			ps.setInt(1, count);
			return ps.executeUpdate();
		}
	}

	/** The oldest messages, without removing them. */
	public synchronized List<Entry> peek(int max) throws SQLException {
		List<Entry> entries = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT seq, kind, topic, payload FROM outbox ORDER BY seq LIMIT ?")) {
			ps.setInt(1, max);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					entries.add(new Entry(rs.getLong(1), Kind.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4)));
				}
			}
		}
		return entries;
	}

	public synchronized void remove(long seq) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("DELETE FROM outbox WHERE seq = ?")) {
			ps.setLong(1, seq);
			size -= ps.executeUpdate();
		}
	}

	/** Waits until there is something to send or the timeout passes. */
	public synchronized void awaitMessages(long timeoutMillis) throws InterruptedException {
		if (size == 0) {
			wait(timeoutMillis);
		}
	}

	public synchronized int size() {
		return size;
	}

	public synchronized long dropped() {
		return dropped;
	}

	@Override
	public synchronized void close() throws SQLException {
		connection.close();
	}
}
