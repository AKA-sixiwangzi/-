package com.xuri.training;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xuri.training.config.UtcTimestampTypeHandler;
import java.sql.*;
import java.util.Calendar;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UtcTimestampTest {
  @Test
  void sqlServerDatetime2UsesUtcRatherThanMachineTimezone() throws Exception {
    ResultSet result = mock(ResultSet.class);
    new UtcTimestampTypeHandler().getNullableResult(result, "start_at");
    ArgumentCaptor<Calendar> calendar = ArgumentCaptor.forClass(Calendar.class);
    verify(result).getTimestamp(eq("start_at"), calendar.capture());
    assertEquals("UTC", calendar.getValue().getTimeZone().getID());
  }
}
