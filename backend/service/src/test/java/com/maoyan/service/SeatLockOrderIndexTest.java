package com.maoyan.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SeatLockOrderIndexTest {

    @Test
    void mysqlAndH2SchemasDefineOrderOnlyIndexForSeatLocks() throws Exception {
        String mysql = Files.readString(Path.of("..", "..", "docker", "mysql", "init", "00-init.sql"));
        String h2 = Files.readString(Path.of("..", "provider", "src", "main", "resources", "schema.sql"));

        for (String schema : new String[]{mysql, h2}) {
            assertThat(schema)
                    .contains("idx_seat_lock_order")
                    .contains("(order_no)")
                    .doesNotContain("idx_seat_lock_order_status")
                    .doesNotContain("(order_no, status)");
        }
    }
}
