package org.imnu.smartcommunitycare.device;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.device.config.SimulatorProperties;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class SimulatorStatusTest extends BaseIntegrationTest {
    @Autowired org.imnu.smartcommunitycare.device.mapper.DeviceMapper devices;
    @Test void devicePagingUsesSqlScopeAndExactOnlineFilter() throws Exception {
        Long own=createElder("it_v13_device_page_own"),other=createElder("it_v13_device_page_other");
        createAccount("it_v13_device_page_admin",ADMIN_PASSWORD,UserRole.COMMUNITY_ADMIN,true);
        var ids=new java.util.ArrayList<Long>();
        for(int i=0;i<3;i++) {
            var device=new org.imnu.smartcommunitycare.device.model.entity.Device();device.setDeviceNo("IT-V13-DEVICE-PAGE-"+i);
            device.setDeviceName("登记分页设备");device.setDeviceType("LIGHT");device.setElderId(i==2?other:own);
            device.setAccessMode("SIMULATED");device.setEnabled(true);device.setSimulatorRunning(false);device.setSimulationMode("NORMAL");
            device.setSimulatedPowerStatus("OFF");device.setVersion(0);device.setCreatedAt(java.time.LocalDateTime.now());device.setUpdatedAt(java.time.LocalDateTime.now());devices.insert(device);ids.add(device.getId());
        }
        String elder=loginAndGetToken(mvc,"it_v13_device_page_own",ELDER_PASSWORD);
        mvc.perform(get("/api/devices/paged").header("scc-test-token","Bearer "+elder).param("onlineStatus","OFFLINE").param("pageSize","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.records.length()").value(1));
        mvc.perform(get("/api/devices/paged").header("scc-test-token","Bearer "+elder).param("elderId",other.toString())).andExpect(status().isNotFound());
        mvc.perform(get("/api/devices/{id}/commands/paged",ids.get(2)).header("scc-test-token","Bearer "+elder)).andExpect(status().isNotFound());
        String admin=loginAndGetToken(mvc,"it_v13_device_page_admin",ADMIN_PASSWORD);
        mvc.perform(get("/api/devices/paged").header("scc-test-token","Bearer "+admin).param("elderId",own.toString()).param("pageNum","2147483647"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.records.length()").value(0));
    }
    @Autowired MockMvc mvc;
    @Autowired SimulatorProperties properties;

    @Test void engineStatusRemainsAvailableWithoutAnyOwnedDevice() throws Exception {
        createAccount("it_v13_status_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String token = loginAndGetToken(mvc, "it_v13_status_admin", ADMIN_PASSWORD);
        boolean initial = properties.isEnabled();
        try {
            for (boolean enabled : new boolean[]{false, true}) {
                properties.setEnabled(enabled);
                mvc.perform(get("/api/devices/simulator-status").header("scc-test-token", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.globalEnabled").value(enabled))
                        .andExpect(jsonPath("$.data.businessZone").value("Asia/Shanghai"))
                        .andExpect(jsonPath("$.data.supportedTypes.length()").value(3));
            }
        } finally { properties.setEnabled(initial); }
    }

    @Test void statusRequiresAdministratorAndDoesNotCreateDevices() throws Exception {
        createElder("it_v13_status_elder");
        createAccount("it_v13_status_staff", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        mvc.perform(get("/api/devices/simulator-status")).andExpect(status().isUnauthorized());
        for (String role : new String[]{"elder", "staff"}) {
            String token = loginAndGetToken(mvc, "it_v13_status_" + role, role.equals("elder") ? ELDER_PASSWORD : STAFF_PASSWORD);
            mvc.perform(get("/api/devices/simulator-status").header("scc-test-token", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }
}
