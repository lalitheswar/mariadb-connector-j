/*
 * MariaDB Client for Java
 *
 * Copyright (c) 2012-2014 Monty Program Ab.
 * Copyright (c) 2015-2020 MariaDB Corporation Ab.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to Monty Program Ab info@montyprogram.com.
 *
 */

package org.mariadb.jdbc.codec.list;

import java.io.IOException;
import java.sql.SQLDataException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Calendar;
import java.util.EnumSet;
import java.util.TimeZone;
import org.mariadb.jdbc.client.ReadableByteBuf;
import org.mariadb.jdbc.client.context.Context;
import org.mariadb.jdbc.client.socket.PacketWriter;
import org.mariadb.jdbc.codec.Codec;
import org.mariadb.jdbc.codec.DataType;
import org.mariadb.jdbc.message.server.ColumnDefinitionPacket;

public class LocalTimeCodec implements Codec<LocalTime> {

  public static final LocalTimeCodec INSTANCE = new LocalTimeCodec();

  private static final EnumSet<DataType> COMPATIBLE_TYPES =
      EnumSet.of(
          DataType.TIME,
          DataType.DATETIME,
          DataType.TIMESTAMP,
          DataType.VARSTRING,
          DataType.VARCHAR,
          DataType.STRING,
          DataType.BLOB,
          DataType.TINYBLOB,
          DataType.MEDIUMBLOB,
          DataType.LONGBLOB);

  public static int[] parseTime(ReadableByteBuf buf, int length, ColumnDefinitionPacket column)
      throws SQLDataException {
    int initialPos = buf.pos();
    int[] parts = new int[5];
    parts[0] = 1;
    int idx = 1;
    int partLength = 0;
    byte b;
    int i = 0;
    if (length > 0 && buf.getByte() == '-') {
      buf.skip();
      i++;
      parts[0] = -1;
    }

    for (; i < length; i++) {
      b = buf.readByte();
      if (b == ':' || b == '.') {
        idx++;
        partLength = 0;
        continue;
      }
      if (b < '0' || b > '9') {
        buf.pos(initialPos);
        String val = buf.readString(length);
        throw new SQLDataException(
            String.format("%s value '%s' cannot be decoded as Time", column.getType(), val));
      }
      partLength++;
      parts[idx] = parts[idx] * 10 + (b - '0');
    }

    if (idx < 2) {
      buf.pos(initialPos);
      String val = buf.readString(length);
      throw new SQLDataException(
          String.format("%s value '%s' cannot be decoded as Time", column.getType(), val));
    }

    // set nano real value
    if (idx == 4) {
      for (i = 0; i < 9 - partLength; i++) {
        parts[4] = parts[4] * 10;
      }
    }
    return parts;
  }

  public String className() {
    return LocalTime.class.getName();
  }

  public boolean canDecode(ColumnDefinitionPacket column, Class<?> type) {
    return COMPATIBLE_TYPES.contains(column.getType()) && type.isAssignableFrom(LocalTime.class);
  }

  public boolean canEncode(Object value) {
    return value instanceof LocalTime;
  }

  @Override
  @SuppressWarnings("fallthrough")
  public LocalTime decodeText(
      ReadableByteBuf buf, int length, ColumnDefinitionPacket column, Calendar cal)
      throws SQLDataException {

    int[] parts;
    switch (column.getType()) {
      case TIMESTAMP:
      case DATETIME:
        parts = LocalDateTimeCodec.parseTimestamp(buf.readString(length));
        if (parts == null) return null;
        return LocalTime.of(parts[3], parts[4], parts[5], parts[6]);

      case TIME:
        parts = parseTime(buf, length, column);
        parts[1] = parts[1] % 24;
        if (parts[0] == -1) {
          // negative
          long seconds = (24 * 60 * 60 - (parts[1] * 3600 + parts[2] * 60L + parts[3]));
          return LocalTime.ofNanoOfDay(seconds * 1_000_000_000 - parts[4]);
        }
        return LocalTime.of(parts[1] % 24, parts[2], parts[3], parts[4]);

      case BLOB:
      case TINYBLOB:
      case MEDIUMBLOB:
      case LONGBLOB:
        if (column.isBinary()) {
          buf.skip(length);
          throw new SQLDataException(
              String.format("Data type %s cannot be decoded as LocalTime", column.getType()));
        }
        // expected fallthrough
        // BLOB is considered as String if has a collation (this is TEXT column)

      case VARSTRING:
      case VARCHAR:
      case STRING:
        String val = buf.readString(length);
        try {
          if (val.contains(" ")) {
            ZoneId tz =
                cal != null ? cal.getTimeZone().toZoneId() : TimeZone.getDefault().toZoneId();
            return LocalDateTime.parse(val, LocalDateTimeCodec.MARIADB_LOCAL_DATE_TIME.withZone(tz))
                .toLocalTime();
          } else {
            return LocalTime.parse(val);
          }
        } catch (DateTimeParseException e) {
          throw new SQLDataException(
              String.format(
                  "value '%s' (%s) cannot be decoded as LocalTime", val, column.getType()));
        }

      default:
        buf.skip(length);
        throw new SQLDataException(
            String.format("Data type %s cannot be decoded as LocalTime", column.getType()));
    }
  }

  @Override
  @SuppressWarnings("fallthrough")
  public LocalTime decodeBinary(
      ReadableByteBuf buf, int length, ColumnDefinitionPacket column, Calendar cal)
      throws SQLDataException {

    int hour = 0;
    int minutes = 0;
    int seconds = 0;
    long microseconds = 0;
    switch (column.getType()) {
      case TIMESTAMP:
      case DATETIME:
        if (length == 0) return null;
        buf.skip(4); // skip year, month and day
        if (length > 4) {
          hour = buf.readByte();
          minutes = buf.readByte();
          seconds = buf.readByte();

          if (length > 7) {
            microseconds = buf.readInt();
          }
        }
        return LocalTime.of(hour, minutes, seconds).plusNanos(microseconds * 1000);

      case TIME:
        boolean negate = buf.readByte() == 1;
        if (length > 4) {
          buf.skip(4); // skip days
          if (length > 7) {
            hour = buf.readByte();
            minutes = buf.readByte();
            seconds = buf.readByte();
            if (length > 8) {
              microseconds = buf.readInt();
            }
          }
        }
        if (negate) {
          // negative
          long nanos = (24 * 60 * 60 - (hour * 3600 + minutes * 60 + seconds));
          return LocalTime.ofNanoOfDay(nanos * 1_000_000_000 - microseconds * 1000);
        }
        return LocalTime.of(hour % 24, minutes, seconds, (int) microseconds * 1000);

      case BLOB:
      case TINYBLOB:
      case MEDIUMBLOB:
      case LONGBLOB:
        if (column.isBinary()) {
          buf.skip(length);
          throw new SQLDataException(
              String.format("Data type %s cannot be decoded as LocalTime", column.getType()));
        }
        // expected fallthrough
        // BLOB is considered as String if has a collation (this is TEXT column)

      case VARSTRING:
      case VARCHAR:
      case STRING:
        String val = buf.readString(length);
        try {
          if (val.contains(" ")) {
            ZoneId tz =
                cal != null ? cal.getTimeZone().toZoneId() : TimeZone.getDefault().toZoneId();
            return LocalDateTime.parse(val, LocalDateTimeCodec.MARIADB_LOCAL_DATE_TIME.withZone(tz))
                .toLocalTime();
          } else {
            return LocalTime.parse(val);
          }
        } catch (DateTimeParseException e) {
          throw new SQLDataException(
              String.format(
                  "value '%s' (%s) cannot be decoded as LocalTime", val, column.getType()));
        }

      default:
        buf.skip(length);
        throw new SQLDataException(
            String.format("Data type %s cannot be decoded as LocalTime", column.getType()));
    }
  }

  @Override
  public void encodeText(
      PacketWriter encoder, Context context, Object value, Calendar cal, Long maxLen)
      throws IOException {
    LocalTime val = (LocalTime) value;
    StringBuilder dateString = new StringBuilder(15);
    dateString
        .append(val.getHour() < 10 ? "0" : "")
        .append(val.getHour())
        .append(val.getMinute() < 10 ? ":0" : ":")
        .append(val.getMinute())
        .append(val.getSecond() < 10 ? ":0" : ":")
        .append(val.getSecond());

    int microseconds = val.getNano() / 1000;
    if (microseconds > 0) {
      dateString.append(".");
      if (microseconds % 1000 == 0) {
        dateString.append(Integer.toString(microseconds / 1000 + 1000).substring(1));
      } else {
        dateString.append(Integer.toString(microseconds + 1000000).substring(1));
      }
    }

    encoder.writeByte('\'');
    encoder.writeAscii(dateString.toString());
    encoder.writeByte('\'');
  }

  @Override
  public void encodeBinary(
      PacketWriter encoder, Context context, Object value, Calendar cal, Long maxLength)
      throws IOException {
    LocalTime val = (LocalTime) value;
    int nano = val.getNano();
    if (nano > 0) {
      encoder.writeByte((byte) 12);
      encoder.writeByte((byte) 0);
      encoder.writeInt(0);
      encoder.writeByte((byte) val.get(ChronoField.HOUR_OF_DAY));
      encoder.writeByte((byte) val.get(ChronoField.MINUTE_OF_HOUR));
      encoder.writeByte((byte) val.get(ChronoField.SECOND_OF_MINUTE));
      encoder.writeInt(nano / 1000);
    } else {
      encoder.writeByte((byte) 8);
      encoder.writeByte((byte) 0);
      encoder.writeInt(0);
      encoder.writeByte((byte) val.get(ChronoField.HOUR_OF_DAY));
      encoder.writeByte((byte) val.get(ChronoField.MINUTE_OF_HOUR));
      encoder.writeByte((byte) val.get(ChronoField.SECOND_OF_MINUTE));
    }
  }

  public int getBinaryEncodeType() {
    return DataType.TIME.get();
  }
}
