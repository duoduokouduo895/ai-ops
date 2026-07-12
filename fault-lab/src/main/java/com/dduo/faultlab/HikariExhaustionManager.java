package com.dduo.faultlab;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "faultlab", name = "role", havingValue = "payment")
public class HikariExhaustionManager {

    private final DataSource dataSource;
    private final FaultMetrics metrics;
    private final List<Connection> heldConnections = new ArrayList<>();

    public HikariExhaustionManager(DataSource dataSource, FaultMetrics metrics) {
        this.dataSource = dataSource;
        this.metrics = metrics;
    }

    public synchronized void enable() throws SQLException {
        if (!heldConnections.isEmpty()) {
            return;
        }
        try {
            for (var count = 0; count < 4; count++) {
                heldConnections.add(dataSource.getConnection());
                metrics.setHikariHolders(heldConnections.size());
            }
        } catch (SQLException exception) {
            releaseAll();
            throw exception;
        }
    }

    public synchronized void disable() {
        releaseAll();
    }

    public synchronized int holderCount() {
        return heldConnections.size();
    }

    @PreDestroy
    public synchronized void destroy() {
        releaseAll();
    }

    private void releaseAll() {
        for (var connection : heldConnections) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Closing a test-only held connection must not prevent cleanup of the remaining ones.
            }
        }
        heldConnections.clear();
        metrics.setHikariHolders(0);
    }
}

