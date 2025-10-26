package com.achen.shelf.crawl.cluster;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The coordinator's leader election: a Postgres session-level advisory lock.
 *
 * <p>{@code pg_try_advisory_lock(KEY)} either succeeds at once or returns false; there is no queue
 * and no waiting. The lock belongs to the <em>session</em> and is released the instant that session
 * ends — which is what makes failover simple: a coordinator that is killed, crashes or loses its
 * network takes its session with it, and the next poller to try acquires the lock. Nothing here
 * ever releases the lock explicitly, because an explicit release could race the automatic one.
 *
 * <p>Because the lock is tied to a session, it is held on one dedicated JDBC connection that is
 * never handed out by a pool. A pooled connection might be swapped on retry, and the lock would
 * silently stay with a session nobody is using. The same connection is where the leader does all of
 * its work: if the session has died, so has the lock, and the work fails with it rather than
 * proceeding as a second, un-elected leader.
 */
public final class LeaderLock implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(LeaderLock.class);

  /**
   * The one lock key. Arbitrary but fixed: every coordinator, in every process, on every host,
   * competes for the same 64-bit key in the same database, and that is the whole election.
   */
  public static final long KEY = 0x5348454c465f4c44L; // "SHELF_LD"

  private final String jdbcUrl;
  private final String user;
  private final String password;
  private final String applicationName;

  private Connection conn;
  private boolean held;

  public LeaderLock(String jdbcUrl, String user, String password, String applicationName) {
    this.jdbcUrl = jdbcUrl;
    this.user = user;
    this.password = password;
    this.applicationName = applicationName;
  }

  /**
   * Tries once to become (or confirm we still are) the leader.
   *
   * <p>A held lock is re-verified by pinging the session it lives on: if the ping fails, the
   * session is gone, the lock went with it, and we are a standby again — even though this process
   * is perfectly alive. That is the honest answer, and the next call will reconnect and try again.
   */
  public synchronized boolean tryAcquire() {
    if (held && sessionAlive()) {
      return true;
    }
    held = false;
    try {
      if (conn == null || conn.isClosed()) {
        connect();
      }
      try (PreparedStatement ps = conn.prepareStatement("select pg_try_advisory_lock(?)")) {
        ps.setLong(1, KEY);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          held = rs.getBoolean(1);
        }
      }
    } catch (SQLException e) {
      log.warn("{}: could not try for the leader lock: {}", applicationName, e.getMessage());
      dropConnection();
    }
    return held;
  }

  /** True if the lock is held on a session that was alive at the last check. */
  public synchronized boolean isHeld() {
    return held;
  }

  /** The session the lock is held on. Only valid while {@link #isHeld()}. */
  public synchronized Connection connection() {
    if (!held) {
      throw new IllegalStateException(applicationName + " is not the leader");
    }
    return conn;
  }

  /**
   * The {@code application_name} of the session currently holding the lock, from any connection.
   *
   * <p>A 64-bit advisory key shows up in {@code pg_locks} split across {@code classid} (high 32
   * bits) and {@code objid} (low 32 bits), with {@code objsubid = 1}. Used by the failover test to
   * watch leadership move, and handy at a psql prompt.
   */
  public static Optional<String> currentHolder(Connection c) throws SQLException {
    String sql =
        """
        select a.application_name
        from pg_locks l join pg_stat_activity a on a.pid = l.pid
        where l.locktype = 'advisory' and l.granted
          and l.classid = ? and l.objid = ? and l.objsubid = 1
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, (KEY >>> 32) & 0xffffffffL);
      ps.setLong(2, KEY & 0xffffffffL);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
      }
    }
  }

  /** Called by the leader when work on the session failed: the lock is presumed lost. */
  synchronized void sessionFailed() {
    held = false;
    dropConnection();
  }

  private boolean sessionAlive() {
    try {
      return conn != null && conn.isValid(2);
    } catch (SQLException e) {
      return false;
    }
  }

  private void connect() throws SQLException {
    Properties props = new Properties();
    props.setProperty("user", user);
    props.setProperty("password", password);
    // Visible in pg_stat_activity, so an operator (and the failover test) can see who leads.
    props.setProperty("ApplicationName", applicationName);
    conn = DriverManager.getConnection(jdbcUrl, props);
    conn.setAutoCommit(true);
    try (Statement s = conn.createStatement()) {
      s.execute("set time zone 'UTC'");
    }
  }

  private void dropConnection() {
    if (conn != null) {
      try {
        conn.close();
      } catch (SQLException ignored) {
        // Closing a dead connection may itself fail; either way it is gone.
      }
      conn = null;
    }
  }

  /** Ends the session, which releases the lock. */
  @Override
  public synchronized void close() {
    held = false;
    dropConnection();
  }
}
