/*-
 * -\-\-
 * DBeam Core
 * --
 * Copyright (C) 2016 - 2018 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.dbeam.avro;

import com.spotify.dbeam.args.QueryBuilder;

import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.sql.Types.*;

public class JdbcAvroSchema {

  private static Logger LOGGER = LoggerFactory.getLogger(JdbcAvroSchema.class);

  public static Schema createSchemaByReadingOneRow(
          Connection connection, String tablename, String avroSchemaNamespace,
          String avroDoc, boolean useLogicalTypes)
      throws SQLException {
    return createSchemaByReadingOneRow(
            connection, QueryBuilder.fromTablename(tablename),
            avroSchemaNamespace, avroDoc, useLogicalTypes);
  }

  public static Schema createSchemaByReadingOneRow(
          Connection connection, QueryBuilder baseSqlQuery, String avroSchemaNamespace,
          String avroDoc, boolean useLogicalTypes)
      throws SQLException {
    LOGGER.debug("Creating Avro schema based on the first read row from the database");
    setSCWithColumnNames(connection, baseSqlQuery);
    try (Statement statement = connection.createStatement()) {
      final ResultSet
          resultSet =
          statement.executeQuery(baseSqlQuery.copy().withLimitOne().build());

      Schema schema = JdbcAvroSchema.createAvroSchema(
          resultSet, avroSchemaNamespace, connection.getMetaData().getURL(), avroDoc,
          useLogicalTypes);
      LOGGER.info("Schema created successfully. Generated schema: {}", schema.toString());
      return schema;
    }
  }

  public static Schema createAvroSchema(
      ResultSet resultSet, String avroSchemaNamespace, String connectionUrl,
      String avroDoc, boolean useLogicalTypes)
      throws SQLException {
    ResultSetMetaData meta = resultSet.getMetaData();
    String tableName = "no_table_name";

//    if (meta.getColumnCount() > 0) {
//      tableName = normalizeForAvro(meta.getTableName(1));
//    }
    SchemaBuilder.FieldAssembler<Schema> builder = SchemaBuilder.record(tableName)
        .namespace(avroSchemaNamespace)
        .doc(avroDoc)
        .prop("tableName", tableName)
        .prop("connectionUrl", connectionUrl)
        .fields();
    return createAvroFields(meta, builder, useLogicalTypes).endRecord();
  }

  private static SchemaBuilder.FieldAssembler<Schema> createAvroFields(
      ResultSetMetaData meta, SchemaBuilder.FieldAssembler<Schema> builder, boolean useLogicalTypes)
      throws SQLException {
    for (int i = 1; i <= meta.getColumnCount(); i++) {

      String columnName;
      if (meta.getColumnName(i).isEmpty()) {
        columnName = meta.getColumnLabel(i);
      } else {
        columnName = meta.getColumnName(i);
      }

      int columnType = meta.getColumnType(i);
      String typeName = JDBCType.valueOf(columnType).getName();
      SchemaBuilder.FieldBuilder<Schema> field = builder
          .name(normalizeForAvro(columnName))
          .doc(String.format("From sqlType %d %s", columnType, typeName))
          .prop("columnName", columnName)
          .prop("sqlCode", String.valueOf(columnType))
          .prop("typeName", typeName);
      fieldAvroType(columnType, meta.getPrecision(i), field, useLogicalTypes);
    }
    return builder;
  }

  private static void setSCWithColumnNames(Connection connection, QueryBuilder baseSqlQuery) throws SQLException
  {
    try(Statement statement = connection.createStatement()) {
      final List<String> names = new ArrayList<>();
      final ResultSet resultSet = statement.executeQuery(baseSqlQuery.copy().withLimitOne().build());
      final ResultSetMetaData meta = resultSet.getMetaData();
      for (int i = 1; i <= meta.getColumnCount(); i++) {

        String columnName;
        if (meta.getColumnName(i).isEmpty()) {
          columnName = meta.getColumnLabel(i);
        } else {
          columnName = meta.getColumnName(i);
        }
        names.add(columnName);
      }

      baseSqlQuery.getBase().setSelectClause("SELECT " + String.join(",", names));
    }
  }

  private static SchemaBuilder.FieldAssembler<Schema> fieldAvroType(
      int columnType, int precision, SchemaBuilder.FieldBuilder<Schema> fieldBuilder,
      boolean useLogicalTypes) {

    final SchemaBuilder.BaseTypeBuilder<
        SchemaBuilder.UnionAccumulator<SchemaBuilder.NullDefault<Schema>>>
        field =
        fieldBuilder.type().unionOf().nullBuilder().endNull().and();

    switch (columnType) {
      case VARCHAR:
      case CHAR:
      case CLOB:
      case LONGNVARCHAR:
      case LONGVARCHAR:
      case NCHAR:
        return field.stringType().endUnion().nullDefault();
      case BIGINT:
        if (precision > 0 && precision <= JdbcAvroRecord.MAX_DIGITS_BIGINT) {
          return field.longType().endUnion().nullDefault();
        } else {
          return field.stringType().endUnion().nullDefault();
        }
      case INTEGER:
      case SMALLINT:
      case TINYINT:
        return field.intType().endUnion().nullDefault();
//      case TIMESTAMP:
//      case DATE:
//      case TIME:
//      case TIME_WITH_TIMEZONE:
//        if (useLogicalTypes) {
//          return field.longBuilder().prop("logicalType", "timestamp-millis")
//              .endLong().endUnion().nullDefault();
//        } else {
//          return field.longType().endUnion().nullDefault();
//        }
      case BOOLEAN:
        return field.booleanType().endUnion().nullDefault();
      case BIT:
        if (precision <= 1) {
          return field.booleanType().endUnion().nullDefault();
        } else {
          return field.bytesType().endUnion().nullDefault();
        }
      case BINARY:
      case VARBINARY:
      case LONGVARBINARY:
      case ARRAY:
      case BLOB:
        return field.bytesType().endUnion().nullDefault();
      case DOUBLE:
        return field.doubleType().endUnion().nullDefault();
      case FLOAT:
      case REAL:
        return field.floatType().endUnion().nullDefault();
      default:
        return field.stringType().endUnion().nullDefault();
    }
  }

  private static String normalizeForAvro(String input) {
    return input.replaceAll("[^A-Za-z0-9_]", "_");
  }

}
