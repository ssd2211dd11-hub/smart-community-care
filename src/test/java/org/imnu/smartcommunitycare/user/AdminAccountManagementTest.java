package org.imnu.smartcommunitycare.user;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AdminAccountManagementTest extends BaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TransactionTemplate transaction;
    private String admin() throws Exception {
        createAccount("it_v13_management_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        return loginAndGetToken(mvc, "it_v13_management_admin", ADMIN_PASSWORD);
    }
    @Test void adminCreatesElderAndStaffWithoutLeakingSecrets() throws Exception {
        String token=admin();
        mvc.perform(post("/api/users").header("scc-test-token","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"username":"it_v13_created_elder","password":"%s","realName":"登记老人","role":"ELDER"}
                """.formatted(ELDER_PASSWORD))).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM elder_profile ep JOIN sys_user u ON u.id=ep.user_id WHERE u.username='it_v13_created_elder'",Long.class)).isEqualTo(1);
        mvc.perform(post("/api/users").header("scc-test-token","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"username":"it_v13_created_staff","password":"%s","realName":"登记人员","role":"SERVICE_STAFF"}
                """.formatted(STAFF_PASSWORD))).andExpect(status().isOk());
        var response=mvc.perform(get("/api/users/paged").param("keyword","it_v13_created").param("pageSize","1")
                .header("scc-test-token","Bearer "+token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.records.length()").value(1)).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("password", "$2a$");
        mvc.perform(post("/api/users").header("scc-test-token","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"username":"it_v13_created_elder","password":"%s","realName":"重复老人","role":"ELDER"}
                """.formatted(ELDER_PASSWORD))).andExpect(status().isConflict());
    }
    @Test void roleCannotBeCreatedOrChangedThroughManagementFields() throws Exception {
        String token=admin(); Long staff=createAccount("it_v13_fixed_role", STAFF_PASSWORD, UserRole.SERVICE_STAFF,true);
        mvc.perform(post("/api/users").header("scc-test-token","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content("""
{"username":"it_v13_new_admin","password":"%s","realName":"管理员","role":"COMMUNITY_ADMIN"}""".formatted(ADMIN_PASSWORD)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/users/{id}/profile",staff).header("scc-test-token","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content("""
{"realName":"更新人员","role":"COMMUNITY_ADMIN","enabled":false}"""))
                .andExpect(status().isOk());
        assertThat(userMapper.selectById(staff).getRole()).isEqualTo("SERVICE_STAFF");
        assertThat(userMapper.selectById(staff).getEnabled()).isTrue();
        String staffToken=loginAndGetToken(mvc,"it_v13_fixed_role",STAFF_PASSWORD);
        mvc.perform(get("/api/users/paged").header("scc-test-token","Bearer "+staffToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/elder-profiles/paged").header("scc-test-token","Bearer "+staffToken)).andExpect(status().isForbidden());
    }
    @Test void elderMaintenanceIsSeparateFromAccountIdentity() throws Exception {
        String token=admin(); Long elder=createElder("it_v13_archive");
        mvc.perform(put("/api/elder-profiles/{id}",elder).header("scc-test-token","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content("""
{"birthDate":"1950-02-03","gender":"female","address":"登记地址","emergencyContact":"联系人","emergencyPhone":"13900001111","careNote":"照护备注","userId":"9999"}"""))
                .andExpect(status().isOk());
        mvc.perform(get("/api/elder-profiles/{id}",elder).header("scc-test-token","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.profile.careNote").value("照护备注"));
        assertThat(elderProfileMapper.selectById(elder).getUserId()).isNotEqualTo(9999L);
        mvc.perform(get("/api/elder-profiles/paged").param("keyword","it_v13_archive").header("scc-test-token","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].address").value("登记地址"));
    }
    @Test void passwordChangeRevokesAllOldSessionsOnlyAfterCommit() throws Exception {
        createAccount("it_v13_multisession", STAFF_PASSWORD,UserRole.SERVICE_STAFF,true);
        String one=loginAndGetToken(mvc,"it_v13_multisession",STAFF_PASSWORD), two=loginAndGetToken(mvc,"it_v13_multisession",STAFF_PASSWORD);
        assertThat(two).isNotEqualTo(one);
        String other=admin();
        mvc.perform(put("/api/auth/password").header("scc-test-token","Bearer "+one).contentType(MediaType.APPLICATION_JSON)
                .content("""
{"oldPassword":"%s","newPassword":"NewPass@2026"}""".formatted(STAFF_PASSWORD)))
                .andExpect(status().isOk());
        for(String old:List.of(one,two)) mvc.perform(get("/api/auth/me").header("scc-test-token","Bearer "+old)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("scc-test-token","Bearer "+other)).andExpect(status().isOk());
    }
    @Test void rolledBackPasswordChangeDoesNotRevokeSession() throws Exception {
        createAccount("it_v13_password_rollback", STAFF_PASSWORD,UserRole.SERVICE_STAFF,true);
        String token=loginAndGetToken(mvc,"it_v13_password_rollback",STAFF_PASSWORD);
        transaction.executeWithoutResult(state->{
            try {mvc.perform(put("/api/auth/password").header("scc-test-token","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                    .content("""
{"oldPassword":"%s","newPassword":"NewPass@2026"}""".formatted(STAFF_PASSWORD))).andExpect(status().isOk());}
            catch(Exception e){throw new IllegalStateException(e);}
            state.setRollbackOnly();
        });
        mvc.perform(get("/api/auth/me").header("scc-test-token","Bearer "+token)).andExpect(status().isOk());
    }
    @Test void twoAdminsCannotConcurrentlyDisableTheLastTwoAdmins() throws Exception {
        assertDatabaseIs(TEST_DATABASE);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user WHERE role='COMMUNITY_ADMIN' AND enabled=1",Long.class))
                .as("测试前不能停用或删除来源不明管理员").isZero();
        Long a=createAccount("it_v13_last_a",ADMIN_PASSWORD,UserRole.COMMUNITY_ADMIN,true);
        Long b=createAccount("it_v13_last_b",ADMIN_PASSWORD,UserRole.COMMUNITY_ADMIN,true);
        String ta=loginAndGetToken(mvc,"it_v13_last_a",ADMIN_PASSWORD),tb=loginAndGetToken(mvc,"it_v13_last_b",ADMIN_PASSWORD);
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->disable(a,ta,start)); var second=pool.submit(()->disable(b,tb,start)); start.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,400);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user WHERE role='COMMUNITY_ADMIN' AND enabled=1",Long.class)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    private int disable(Long id,String token,CountDownLatch start) throws Exception {
        start.await(); return mvc.perform(put("/api/users/{id}/disable",id).header("scc-test-token","Bearer "+token)).andReturn().getResponse().getStatus();
    }
}
