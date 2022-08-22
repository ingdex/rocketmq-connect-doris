package org.apache.rocketmq.connect.doris.util;

import io.openmessaging.connector.api.data.ConnectRecord;
import org.apache.rocketmq.connect.doris.schema.table.TableId;

import java.util.List;

public class TableUtil {

    public static TableId parseToTableId(String fqn) {
        return new TableId(null, null, fqn);
    }
    public static TableId destinationTable(ConnectRecord record) {
        // todo table from header
        return new TableId(null, null, record.getSchema().getName());
    }
}
