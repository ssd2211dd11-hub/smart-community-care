package org.imnu.smartcommunitycare;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 显式执行的只读物理核查，不继承有夹具清理的基类、不执行DDL。 */
@SpringBootTest @ActiveProfiles("test")
class SchemaV13ReadOnlyIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Test void recordExactIncrementPrerequisites() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("smart_community_care_test");
        Map<String,Object> results=new LinkedHashMap<>();
        for(String database:List.of("smart_community_care","smart_community_care_test")) {
            Map<String,Object> schema=new LinkedHashMap<>();
            schema.put("columns",jdbc.queryForList("""
                SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT
                FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME IN('health_record','device','health_device_config')
                ORDER BY TABLE_NAME,ORDINAL_POSITION
                """,database));
            schema.put("indexes",jdbc.queryForList("""
                SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,COLUMN_NAME,SEQ_IN_INDEX
                FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=? AND TABLE_NAME='health_record'
                ORDER BY INDEX_NAME,SEQ_IN_INDEX
                """,database));
            schema.put("checks",jdbc.queryForList("""
                SELECT tc.TABLE_NAME,cc.CONSTRAINT_NAME,cc.CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS cc
                JOIN information_schema.TABLE_CONSTRAINTS tc ON tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME
                WHERE tc.CONSTRAINT_SCHEMA=? AND tc.TABLE_NAME IN('health_record','device')
                """,database));
            schema.put("duplicateDeviceRequests",jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT source_device_id,request_id FROM "+database+
                    ".health_record WHERE source_device_id IS NOT NULL AND request_id IS NOT NULL GROUP BY source_device_id,request_id HAVING COUNT(*)>1) duplicates",Long.class));
            schema.put("healthRecordCount",jdbc.queryForObject("SELECT COUNT(*) FROM "+database+".health_record",Long.class));
            schema.put("deviceCount",jdbc.queryForObject("SELECT COUNT(*) FROM "+database+".device",Long.class));
            results.put(database,schema);
        }
        Path work=Path.of(System.getProperty("care.schema.work", "../开发资料/work/CP-P3-V1.3-control"));Files.createDirectories(work);
        json.writeValue(work.resolve("schema-readonly.json").toFile(),results);
    }
}
