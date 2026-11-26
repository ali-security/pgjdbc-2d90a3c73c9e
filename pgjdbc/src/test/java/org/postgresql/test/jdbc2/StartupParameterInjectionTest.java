/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.GSSEncMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * A NUL character in a startup parameter would terminate the value early in the startup packet,
 * so the remainder would be read by the server as additional (attacker chosen) parameters. Such
 * values must be rejected before anything is sent to the server.
 */
public class StartupParameterInjectionTest {

  private interface ConnectionOpener {
    Connection open() throws SQLException;
  }

  private static void assertRejected(ConnectionOpener opener, String paramName) {
    PSQLException e = assertThrows(PSQLException.class, () -> {
      Connection con = opener.open();
      // Should never get here; close the connection to avoid leaking it on failure.
      TestUtil.closeDB(con);
    });
    assertEquals("SQLState", PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState());
    String message = String.valueOf(e.getMessage());
    assertTrue("Message should mention the parameter " + paramName + ": " + message,
        message.contains(paramName));
    assertTrue("Message should mention NUL: " + message, message.contains("NUL"));
  }

  private static void assertRejected(Properties props, String paramName) {
    assertRejected(() -> TestUtil.openDB(props), paramName);
  }

  /**
   * {@link TestUtil#openDB(Properties)} forces {@code ApplicationName} in the URL, and URL
   * parameters take precedence over the properties, so the application name is replaced in the
   * URL itself.
   */
  private static Connection openDBWithUrlApplicationName(String encodedApplicationName)
      throws SQLException {
    TestUtil.initDriver();
    String url = TestUtil.getURL().replace("ApplicationName=Driver Tests",
        "ApplicationName=" + encodedApplicationName);
    Properties props = new Properties();
    // application_name is sent in the startup packet only for 9.0+ servers
    PGProperty.ASSUME_MIN_SERVER_VERSION.set(props, "9.0");
    PGProperty.USER.set(props, TestUtil.getUser());
    String password = TestUtil.getPassword();
    PGProperty.PASSWORD.set(props, password != null ? password : "");
    PGProperty.GSS_ENC_MODE.set(props, TestUtil.getGSSEncMode().value);
    return DriverManager.getConnection(url, props);
  }

  @Test
  public void applicationNameWithNulIsRejected() {
    // %00 is decoded to NUL while parsing the connection URL
    assertRejected(
        () -> openDBWithUrlApplicationName("pgjdbc%00search_path%00injected"),
        "application_name");
  }

  @Test
  public void optionsWithNulIsRejected() {
    Properties props = new Properties();
    PGProperty.OPTIONS.set(props, "-c extra_float_digits=1\u0000application_name\u0000injected");
    assertRejected(props, "options");
  }

  @Test
  public void currentSchemaWithNulIsRejected() {
    Properties props = new Properties();
    PGProperty.CURRENT_SCHEMA.set(props, "public\u0000options\u0000-c statement_timeout=1");
    assertRejected(props, "search_path");
  }

  @Test
  public void userWithNulIsRejected() {
    Properties props = new Properties();
    PGProperty.USER.set(props, TestUtil.getUser() + "\u0000database\u0000postgres");
    // The user name is also used for GSS encryption negotiation, keep that out of the picture
    PGProperty.GSS_ENC_MODE.set(props, GSSEncMode.DISABLE.value);
    assertRejected(props, "user");
  }

  @Test
  public void databaseFromUrlWithNulIsRejected() {
    Properties props = new Properties();
    // %00 is decoded to NUL while parsing the connection URL
    props.setProperty(TestUtil.DATABASE_PROP,
        TestUtil.getDatabase() + "%00options%00-c%20statement_timeout%3D1");
    assertRejected(props, "database");
  }

  @Test
  public void applicationNameWithoutNulIsAccepted() throws Exception {
    try (Connection con = openDBWithUrlApplicationName("pgjdbc-startup-param");
         Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SHOW application_name")) {
      assertTrue(rs.next());
      assertEquals("pgjdbc-startup-param", rs.getString(1));
    }
  }
}
