package com.maoyan.provider;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DemoDataContractTest {

    @Test
    void h2FixtureUsesEventAndVenueSemantics() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/data.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("星河音乐现场", "城市篮球邀请赛", "光影城市艺术展", "朝阳城市体育馆")
                    .contains("/images/events/event-01.svg")
                    .doesNotContain("picsum.photos", "爆米花", "IMAX厅", "杜比影院", "CGV影城");
            assertThat(sql).doesNotContain("900001", "910001", "920001");
        }
    }
}
