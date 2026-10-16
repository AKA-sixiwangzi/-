package com.xuri.training.config;

import java.sql.*;
import java.util.Calendar;
import java.util.TimeZone;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** datetime2 stores UTC wall time; always read/write it with an explicit UTC Calendar. */
public class UtcTimestampTypeHandler extends BaseTypeHandler<Timestamp> {
  private Calendar utc() {
    return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
  }

  @Override
  public void setNonNullParameter(PreparedStatement s, int i, Timestamp value, JdbcType type)
      throws SQLException {
    s.setTimestamp(i, value, utc());
  }

  @Override
  public Timestamp getNullableResult(ResultSet r, String name) throws SQLException {
    return r.getTimestamp(name, utc());
  }

  @Override
  public Timestamp getNullableResult(ResultSet r, int index) throws SQLException {
    return r.getTimestamp(index, utc());
  }

  @Override
  public Timestamp getNullableResult(CallableStatement s, int index) throws SQLException {
    return s.getTimestamp(index, utc());
  }
}
