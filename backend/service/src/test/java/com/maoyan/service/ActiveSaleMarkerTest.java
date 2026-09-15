package com.maoyan.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActiveSaleMarkerTest {

    @Test
    void h2AllowsOneActiveSaleAndMultipleReleasedHistoryRows() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:active_sale;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE order_seat (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        order_no VARCHAR(64) NOT NULL,
                        schedule_id BIGINT NOT NULL,
                        row_num INT NOT NULL,
                        col_num INT NOT NULL,
                        active_sale_marker TINYINT NULL DEFAULT 1,
                        UNIQUE(schedule_id, row_num, col_num, active_sale_marker)
                    )
                    """);
                statement.executeUpdate("INSERT INTO order_seat(order_no,schedule_id,row_num,col_num,active_sale_marker) VALUES('A',19,1,2,1)");
                assertThatThrownBy(() -> statement.executeUpdate(
                        "INSERT INTO order_seat(order_no,schedule_id,row_num,col_num,active_sale_marker) VALUES('B',19,1,2,1)"))
                        .isInstanceOf(SQLException.class);

                statement.executeUpdate("UPDATE order_seat SET active_sale_marker=NULL WHERE order_no='A'");
                statement.executeUpdate("INSERT INTO order_seat(order_no,schedule_id,row_num,col_num,active_sale_marker) VALUES('B',19,1,2,1)");
                statement.executeUpdate("INSERT INTO order_seat(order_no,schedule_id,row_num,col_num,active_sale_marker) VALUES('C',19,1,2,NULL)");

                var active = statement.executeQuery("SELECT COUNT(*) FROM order_seat WHERE active_sale_marker=1");
                active.next();
                assertThat(active.getInt(1)).isEqualTo(1);
                var history = statement.executeQuery("SELECT COUNT(*) FROM order_seat WHERE schedule_id=19 AND row_num=1 AND col_num=2");
                history.next();
                assertThat(history.getInt(1)).isEqualTo(3);
            }
        }
    }

    @Test
    void mysqlAndH2SchemasUseTheSameActiveSaleContract() throws Exception {
        String mysql = Files.readString(Path.of("..", "..", "docker", "mysql", "init", "00-init.sql"));
        String h2 = Files.readString(Path.of("..", "provider", "src", "main", "resources", "schema.sql"));

        for (String schema : new String[]{mysql, h2}) {
            assertThat(schema)
                    .contains("active_sale_marker TINYINT    NULL DEFAULT 1")
                    .contains("idx_order_seat_active_unique")
                    .contains("schedule_id, row_num, col_num, active_sale_marker")
                    .contains("refund_time")
                    .contains("refund_record");
        }
    }
}
