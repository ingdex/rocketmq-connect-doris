package org.apache.rocketmq.connect.doris.connector;

import io.openmessaging.KeyValue;
import io.openmessaging.connector.api.component.task.Task;
import io.openmessaging.connector.api.component.task.sink.SinkConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class DorisSinkConnector extends SinkConnector {

    private static final Logger log = LoggerFactory.getLogger(DorisSinkConnector.class);
    private KeyValue connectConfig;

    public void start(KeyValue config) {
        this.connectConfig = config;
    }

    /**
     * Should invoke before start the connector.
     *
     * @param config
     * @return error message
     */
    @Override
    public void validate(KeyValue config) {
        // do validate config
    }

    @Override
    public void stop() {
        this.connectConfig = null;
    }

    /**
     * Returns a set of configurations for Tasks based on the current configuration,
     * producing at most count configurations.
     *
     * @param maxTasks maximum number of configurations to generate
     * @return configurations for Tasks
     */
    @Override
    public List<KeyValue> taskConfigs(int maxTasks) {
        log.info("Starting task config !!! ");
        List<KeyValue> configs = new ArrayList<>();
        for (int i = 0; i < maxTasks; i++) {
            configs.add(this.connectConfig);
        }
        return configs;
    }

    @Override
    public Class<? extends Task> taskClass() {
        return DorisSinkTask.class;
    }
}