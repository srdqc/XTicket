package com.maoyan.service;

import com.baomidou.mybatisplus.annotation.TableName;
import com.maoyan.dao.mapper.ActivitySessionMapper;
import com.maoyan.dao.mapper.VenueHallMapper;
import com.maoyan.dao.provider.CinemaSqlProvider;
import com.maoyan.domain.model.dto.CinemaQueryDTO;
import com.maoyan.domain.model.po.ActivityFollowPO;
import com.maoyan.domain.model.po.ActivityPO;
import com.maoyan.domain.model.po.ActivitySessionPO;
import com.maoyan.domain.model.po.VenueHallPO;
import com.maoyan.domain.model.po.VenuePO;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityDomainPersistenceTest {

    @Test
    void corePersistenceModelsUseActivityDomainTables() {
        assertThat(ActivityPO.class.getAnnotation(TableName.class).value()).isEqualTo("activity");
        assertThat(VenuePO.class.getAnnotation(TableName.class).value()).isEqualTo("venue");
        assertThat(VenueHallPO.class.getAnnotation(TableName.class).value()).isEqualTo("venue_hall");
        assertThat(ActivitySessionPO.class.getAnnotation(TableName.class).value()).isEqualTo("activity_session");
        assertThat(ActivityFollowPO.class.getAnnotation(TableName.class).value()).isEqualTo("activity_follow");
    }

    @Test
    void newDomainForeignKeysExistOnPersistenceModels() {
        assertThat(hasField(ActivitySessionPO.class, "activityId")).isTrue();
        assertThat(hasField(ActivitySessionPO.class, "venueId")).isTrue();
        assertThat(hasField(ActivitySessionPO.class, "movieId")).isFalse();
        assertThat(hasField(ActivitySessionPO.class, "cinemaId")).isFalse();
        assertThat(hasField(VenueHallPO.class, "venueId")).isTrue();
        assertThat(hasField(VenueHallPO.class, "cinemaId")).isFalse();
        assertThat(hasField(ActivityFollowPO.class, "activityId")).isTrue();
        assertThat(hasField(ActivityFollowPO.class, "movieId")).isFalse();
    }

    @Test
    void orderSnapshotQueryReadsNewTablesAndKeepsLegacyAliases() throws Exception {
        Select select = ActivitySessionMapper.class
                .getMethod("selectOrderSnapshotSource", Long.class)
                .getAnnotation(Select.class);

        String sql = normalizeSql(select.value());

        assertThat(sql).contains("from activity_session ms");
        assertThat(sql).contains("left join activity m");
        assertThat(sql).contains("left join venue c");
        assertThat(sql).contains("ms.activity_id as movie_id");
        assertThat(sql).contains("ms.venue_id as cinema_id");
        assertThat(sql).doesNotContain("from movie_schedule");
        assertThat(sql).doesNotContain("join movie ");
        assertThat(sql).doesNotContain("join cinema ");
    }

    @Test
    void cinemaListProviderReadsVenueRelationTables() {
        CinemaQueryDTO query = new CinemaQueryDTO();
        query.setCityId(1L);
        query.setServiceId(1L);
        query.setHallType(1L);

        String sql = normalizeSql(new CinemaSqlProvider().selectCinemaList(query));

        assertThat(sql).contains("from venue c");
        assertThat(sql).contains("join venue_service_rel csr on c.id = csr.venue_id");
        assertThat(sql).contains("join venue_hall_type_rel chr on c.id = chr.venue_id");
        assertThat(sql).doesNotContain("cinema_service_rel");
        assertThat(sql).doesNotContain("cinema_hall_type_rel");
        assertThat(sql).doesNotContain("csr.cinema_id");
        assertThat(sql).doesNotContain("chr.cinema_id");
    }

    @Test
    void venueHallMapperUsesVenueHallAndVenueId() throws Exception {
        Select byVenueAndHall = VenueHallMapper.class
                .getMethod("selectByVenueAndHall", Long.class, String.class)
                .getAnnotation(Select.class);
        Select byVenueId = VenueHallMapper.class
                .getMethod("selectByVenueId", Long.class)
                .getAnnotation(Select.class);

        assertThat(normalizeSql(byVenueAndHall.value()))
                .contains("from venue_hall")
                .contains("venue_id = #{venueid}");
        assertThat(normalizeSql(byVenueId.value()))
                .contains("from venue_hall")
                .contains("venue_id = #{venueid}");
    }

    @Test
    void newEnvironmentSchemasUseActivityDomainTables() throws Exception {
        String h2Schema = Files.readString(Path.of("..", "provider", "src", "main", "resources", "schema.sql"))
                .toLowerCase(Locale.ROOT);
        String mysqlSchema = Files.readString(Path.of("..", "..", "docker", "mysql", "init", "00-init.sql"))
                .toLowerCase(Locale.ROOT);

        assertThat(h2Schema).contains("create table if not exists activity");
        assertThat(h2Schema).contains("create table if not exists venue");
        assertThat(h2Schema).contains("create table if not exists venue_hall");
        assertThat(h2Schema).contains("create table if not exists venue_service_rel");
        assertThat(h2Schema).contains("create table if not exists venue_hall_type_rel");
        assertThat(h2Schema).contains("create table if not exists activity_session");
        assertThat(h2Schema).contains("create table if not exists activity_follow");
        assertThat(h2Schema).doesNotContain("create table if not exists movie (");
        assertThat(h2Schema).doesNotContain("create table if not exists cinema (");
        assertThat(h2Schema).doesNotContain("create table if not exists cinema_hall (");
        assertThat(h2Schema).doesNotContain("create table if not exists movie_schedule (");
        assertThat(h2Schema).doesNotContain("create table if not exists user_wish (");
        assertThat(h2Schema).doesNotContain("create table if not exists cinema_service_rel");
        assertThat(h2Schema).doesNotContain("create table if not exists cinema_hall_type_rel");

        assertThat(mysqlSchema).contains("create table if not exists activity");
        assertThat(mysqlSchema).contains("create table if not exists venue");
        assertThat(mysqlSchema).contains("create table if not exists venue_hall");
        assertThat(mysqlSchema).contains("create table if not exists venue_service_rel");
        assertThat(mysqlSchema).contains("create table if not exists venue_hall_type_rel");
        assertThat(mysqlSchema).contains("create table if not exists activity_session");
        assertThat(mysqlSchema).contains("create table if not exists activity_follow");
        assertThat(mysqlSchema).doesNotContain("create table if not exists movie (");
        assertThat(mysqlSchema).doesNotContain("create table if not exists cinema (");
        assertThat(mysqlSchema).doesNotContain("create table if not exists cinema_hall (");
        assertThat(mysqlSchema).doesNotContain("create table if not exists movie_schedule (");
        assertThat(mysqlSchema).doesNotContain("create table if not exists user_wish (");
        assertThat(mysqlSchema).doesNotContain("create table if not exists cinema_service_rel");
        assertThat(mysqlSchema).doesNotContain("create table if not exists cinema_hall_type_rel");
    }

    private static boolean hasField(Class<?> type, String fieldName) {
        return Arrays.stream(type.getDeclaredFields()).anyMatch(field -> field.getName().equals(fieldName));
    }

    private static String normalizeSql(String[] values) {
        return normalizeSql(String.join(" ", values));
    }

    private static String normalizeSql(String value) {
        return value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
