package com.centralArchiveSystem.Archive;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class ReferenceIntegrityIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    private UUID project() {
        UUID org=UUID.randomUUID(), id=UUID.randomUUID();
        jdbc.update("INSERT INTO core.organizations(id,name,code,type) VALUES (?,?,?,'COMPANY')",org,"Fixture",org.toString());
        jdbc.update("""
            INSERT INTO project.projects(id,organization_id,project_uid,project_number,name,year)
            VALUES (?,?,?,?,?,2026)
            """,id,org,UUID.randomUUID(),"P", "Fixture");
        return id;
    }
    @Test void v28IsAppliedSuccessfully() {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM public.flyway_schema_history WHERE version='28' AND success", Integer.class));
    }
    @Test void completionStatusIsProtectedEvenOutsideTheApi() {
        UUID id=project();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE project.projects SET data_completion_status='NOT_A_STATUS' WHERE id=?",id));
    }
    @Test void filesCannotPairATypeWithTheWrongCategory() {
        UUID project=project(), a=UUID.randomUUID(), b=UUID.randomUUID(), type=UUID.randomUUID();
        jdbc.update("INSERT INTO project.file_categories(id,name,code) VALUES (?,?,?),(?,?,?)",
                a,"A",a.toString(),b,"B",b.toString());
        jdbc.update("INSERT INTO project.file_types(id,category_id,name,code) VALUES (?,?,?,?)",type,a,"T",type.toString());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO project.files(project_id,category_id,type_id,original_name,stored_name,bucket_name,object_key)
                VALUES (?,?,?,'a.txt','a.txt','test','test/a')
                """,project,b,type));
    }
    @Test void theSettingsSingletonCannotAcquireAFalseSecondRow() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            INSERT INTO import.migration_settings(id,singleton,updated_at) VALUES (?,false,now())
            """,UUID.randomUUID()));
    }
}
