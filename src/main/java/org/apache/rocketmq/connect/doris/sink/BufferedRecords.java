/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.connect.doris.sink;

import io.openmessaging.connector.api.data.ConnectRecord;
import io.openmessaging.connector.api.data.Schema;
import io.openmessaging.connector.api.errors.ConnectException;
import org.apache.rocketmq.connect.doris.exception.DorisException;
import org.apache.rocketmq.connect.doris.schema.table.TableId;
import org.apache.rocketmq.connect.doris.connector.DorisSinkConfig;
import org.apache.rocketmq.connect.doris.dialect.DatabaseDialect;
//import org.apache.rocketmq.connect.doris.dialect.impl.GenericDatabaseDialect;
import org.apache.rocketmq.connect.doris.schema.column.ColumnId;
import org.apache.rocketmq.connect.doris.schema.db.DbStructure;
import org.apache.rocketmq.connect.doris.schema.table.TableId;
//import org.apache.rocketmq.connect.doris.sink.metadata.FieldsMetadata;
//import org.apache.rocketmq.connect.doris.sink.metadata.SchemaPair;
import org.apache.rocketmq.connect.doris.sink.metadata.FieldsMetadata;
import org.apache.rocketmq.connect.doris.sink.metadata.SchemaPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * buffered records
 */
public class BufferedRecords {
    private static final Logger log = LoggerFactory.getLogger(BufferedRecords.class);

    private final TableId tableId;
    private final DorisSinkConfig config;
//    private final DatabaseDialect dbDialect;
    private final DbStructure dbStructure;
//    private final Connection connection;

    private List<ConnectRecord> records = new ArrayList<>();
    private Schema keySchema;
    private Schema schema;
    private FieldsMetadata fieldsMetadata;
    private RecordValidator recordValidator;
    private List<ConnectRecord> updatePreparedRecords = new ArrayList<>();
    private List<ConnectRecord> deletePreparedRecords = new ArrayList<>();
//    private DatabaseDialect.StatementBinder updateStatementBinder;
//    private DatabaseDialect.StatementBinder deleteStatementBinder;
    private boolean deletesInBatch = false;

    public BufferedRecords(
            DorisSinkConfig config,
            TableId tableId,
            DbStructure dbStructure
    ) {
        this.tableId = tableId;
        this.config = config;
//        this.dbDialect = dbDialect;
        this.dbStructure = dbStructure;
//        this.connection = connection;
        this.recordValidator = RecordValidator.create(config);
    }

    /**
     * add record
     *
     * @param record
     * @return
     * @throws SQLException
     */
    public List<ConnectRecord> add(ConnectRecord record) throws SQLException {
        recordValidator.validate(record);
        final List<ConnectRecord> flushed = new ArrayList<>();
        boolean schemaChanged = false;
        if (!Objects.equals(keySchema, record.getKeySchema())) {
            keySchema = record.getKeySchema();
            schemaChanged = true;
        }
        if (isNull(record.getSchema())) {
            // For deletes, value and optionally value schema come in as null.
            // We don't want to treat this as a schema change if key schemas is the same
            // otherwise we flush unnecessarily.
            if (config.isDeleteEnabled()) {
                deletesInBatch = true;
            }
        } else if (Objects.equals(schema, record.getSchema())) {
            if (config.isDeleteEnabled() && deletesInBatch) {
                // flush so an insert after a delete of same record isn't lost
                flushed.addAll(flush());
            }
        } else {
            // value schema is not null and has changed. This is a real schema change.
            schema = record.getSchema();
            schemaChanged = true;
        }

        if (schemaChanged) {
            // Each batch needs to have the same schemas, so get the buffered records out
            flushed.addAll(flush());
            // re-initialize everything that depends on the record schema
            final SchemaPair schemaPair = new SchemaPair(
                    record.getKeySchema(),
                    record.getSchema(),
                    record.getExtensions()
            );
            // extract field
            fieldsMetadata = FieldsMetadata.extract(
                    tableId.tableName(),
                    config.pkMode,
                    config.getPkFields(),
                    config.getFieldsWhitelist(),
                    schemaPair
            );
            // create or alter table
            dbStructure.createOrAmendIfNecessary(
                    config,
                    tableId,
                    fieldsMetadata
            );
//            final String insertSql = getInsertSql();
//            final String deleteSql = getDeleteSql();
//            log.debug(
//                    "{} sql: {} deleteSql: {} meta: {}",
//                    config.getInsertMode(),
//                    insertSql,
//                    deleteSql,
//                    fieldsMetadata
//            );
//            close();
//            updatePreparedRecords.add(DorisDialect.createPreparedRecord(record));
//            updateStatementBinder = dbDialect.statementBinder(
//                    updatePreparedStatement,
//                    config.pkMode,
//                    schemaPair,
//                    fieldsMetadata,
//                    dbStructure.tableDefinition(connection, tableId),
//                    config.getInsertMode()
//            );
//            if (config.isDeleteEnabled() && nonNull(deleteSql)) {
//                deletePreparedStatement = dbDialect.createPreparedStatement(connection, deleteSql);
//                deleteStatementBinder = dbDialect.statementBinder(
//                        deletePreparedStatement,
//                        config.pkMode,
//                        schemaPair,
//                        fieldsMetadata,
//                        dbStructure.tableDefinition(connection, tableId),
//                        config.getInsertMode()
//                );
//            }
        }

        // set deletesInBatch if schema value is not null
        if (isNull(record.getData()) && config.isDeleteEnabled()) {
            deletesInBatch = true;
        }

        records.add(record);
        if (records.size() >= config.getBatchSize()) {
            flushed.addAll(flush());
        }
        return flushed;
    }

    public List<ConnectRecord> flush() throws SQLException {
        if (records.isEmpty()) {
            log.debug("Records is empty");
            return new ArrayList<>();
        }
        log.debug("Flushing {} buffered records", records.size());
        for (ConnectRecord record : records) {
            if (isNull(record.getData())) {
                deletePreparedRecords.add(record);
            } else {
                updatePreparedRecords.add(record);
            }
        }
        Optional<Long> totalUpdateCount = executeUpdates();
        long totalDeleteCount = executeDeletes();
        final long expectedCount = updateRecordCount();
        log.trace("{} records:{} resulting in totalUpdateCount:{} totalDeleteCount:{}",
                config.getInsertMode(),
                records.size(),
                totalUpdateCount,
                totalDeleteCount
        );
        if (totalUpdateCount.filter(total -> total != expectedCount).isPresent()
                && config.getInsertMode() == DorisSinkConfig.InsertMode.INSERT) {
//            if (dbDialect.name().equals(GenericDatabaseDialect.DialectName.generateDialectName(dbDialect.getDialectClass())) && totalUpdateCount.get() == 0) {
//                // openMLDB execute success result 0; do nothing
//            } else {
                throw new ConnectException(
                        String.format(
                                "Update count (%d) did not sum up to total number of records inserted (%d)",
                                totalUpdateCount.get(),
                                expectedCount
                        )
                );
//            }
        }
        if (!totalUpdateCount.isPresent()) {
            log.info(
                    "{} records:{} , but no count of the number of rows it affected is available",
                    config.getInsertMode(),
                    records.size()
            );
        }

        final List<ConnectRecord> flushedRecords = records;
        records = new ArrayList<>();
        return flushedRecords;
    }

    /**
     * @return an optional count of all updated rows or an empty optional if no info is available
     */
    private Optional<Long> executeUpdates() throws DorisException {
        Optional<Long> count = Optional.empty();
        if (updatePreparedRecords.isEmpty()) {
            return count;
        }
        StringBuilder updateJsonStringBuilder = new StringBuilder();
        for (ConnectRecord record: updatePreparedRecords) {
            updateJsonStringBuilder.append(DorisDialect.convertToUpdateJsonString(record, updateJsonStringBuilder.length() == 0));
        }
        if (updateJsonStringBuilder.length() != 0) {
            try {
                DorisStreamLoader loader = new DorisStreamLoader();
                loader.loadJson(updateJsonStringBuilder.toString());
            } catch (DorisException e) {
                log.error("executeUpdates failed");
                throw e;
            } catch (Exception e) {
                throw new DorisException("doris error");
            }
        }
        return count;
    }

    private long executeDeletes() throws SQLException {
        long totalDeleteCount = 0;
        if (deletePreparedRecords.isEmpty()) {
            return totalDeleteCount;
        }
        StringBuilder deleteJsonStringBuilder = new StringBuilder();
//        for (ConnectRecord record: deletePreparedRecords) {
//            deleteJsonStringBuilder.append(DorisDialect.convertToDeleteJsonString(record, updateJsonStringBuilder.length() == 0));
//        }
        if (deleteJsonStringBuilder.length() != 0) {
            try {
                DorisStreamLoader loader = new DorisStreamLoader();
                loader.loadJson(deleteJsonStringBuilder.toString());
            } catch (DorisException e) {
                log.error("executeUpdates failed");
                throw e;
            } catch (Exception e) {
                throw new DorisException("doris error");
            }
        }
        return totalDeleteCount;
    }

    private long updateRecordCount() {
        return records
                .stream()
                // ignore deletes
                .filter(record -> nonNull(record.getData()) || !config.isDeleteEnabled())
                .count();
    }

//    public void close() throws SQLException {
//        log.debug(
//                "Closing BufferedRecords with updatePreparedStatement: {} deletePreparedStatement: {}",
//                updatePreparedStatement,
//                deletePreparedStatement
//        );
//        if (nonNull(updatePreparedStatement)) {
//            updatePreparedStatement.close();
//            updatePreparedStatement = null;
//        }
//        if (nonNull(deletePreparedStatement)) {
//            deletePreparedStatement.close();
//            deletePreparedStatement = null;
//        }
//    }

//    private String getInsertSql() {
//        switch (config.getInsertMode()) {
//            case INSERT:
//                return dbDialect.buildInsertStatement(
//                        tableId,
//                        asColumns(fieldsMetadata.keyFieldNames),
//                        asColumns(fieldsMetadata.nonKeyFieldNames)
//                );
//            case UPSERT:
//                if (fieldsMetadata.keyFieldNames.isEmpty()) {
//                    throw new ConnectException(String.format(
//                            "Write to table '%s' in UPSERT mode requires key field names to be known, check the"
//                                    + " primary key configuration",
//                            tableId
//                    ));
//                }
//                try {
//                    return dbDialect.buildUpsertQueryStatement(
//                            tableId,
//                            asColumns(fieldsMetadata.keyFieldNames),
//                            asColumns(fieldsMetadata.nonKeyFieldNames)
//                    );
//                } catch (UnsupportedOperationException e) {
//                    throw new ConnectException(String.format(
//                            "Write to table '%s' in UPSERT mode is not supported with the %s dialect.",
//                            tableId,
//                            dbDialect.name()
//                    ));
//                }
//            case UPDATE:
//                return dbDialect.buildUpdateStatement(
//                        tableId,
//                        asColumns(fieldsMetadata.keyFieldNames),
//                        asColumns(fieldsMetadata.nonKeyFieldNames)
//                );
//            default:
//                throw new ConnectException("Invalid insert mode");
//        }
//    }
//
//    private String getDeleteSql() {
//        String sql = null;
//        if (config.isDeleteEnabled()) {
//            switch (config.pkMode) {
//                case RECORD_KEY:
//                    if (fieldsMetadata.keyFieldNames.isEmpty()) {
//                        throw new ConnectException("Require primary keys to support delete");
//                    }
//                    try {
//                        sql = dbDialect.buildDeleteStatement(
//                                tableId,
//                                asColumns(fieldsMetadata.keyFieldNames)
//                        );
//                    } catch (UnsupportedOperationException e) {
//                        throw new ConnectException(String.format(
//                                "Deletes to table '%s' are not supported with the %s dialect.",
//                                tableId,
//                                dbDialect.name()
//                        ));
//                    }
//                    break;
//                default:
//                    throw new ConnectException("Deletes are only supported for pk.mode record_key");
//            }
//        }
//        return sql;
//    }
//
//    private Collection<ColumnId> asColumns(Collection<String> names) {
//        return names.stream()
//                .map(name -> new ColumnId(tableId, name))
//                .collect(Collectors.toList());
//    }
}