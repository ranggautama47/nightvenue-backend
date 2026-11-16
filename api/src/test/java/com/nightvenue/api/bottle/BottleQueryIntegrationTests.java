package com.nightvenue.api.bottle;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BottleQueryIntegrationTests extends BottleQueryTestSupport {
    @Test
    void staffSeesOnlyRequestedVenue() throws Exception {
        long inVenue = insertVault(venueA, customerId, productA, "ACTIVE");
        long outsideVenue = insertVault(venueB, customerId, productB, "ACTIVE");
        String inLabel = label(inVenue);
        String outsideLabel = label(outsideVenue);

        mvc.perform(staffRequest(bearer(managerId, "MANAGER"), venueA))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(inLabel)))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(outsideLabel))));
    }

    @Test
    void staffNoAccessVenue404() throws Exception {
        mvc.perform(staffRequest(bearer(managerId, "MANAGER"), venueB)).andExpect(status().isNotFound());
    }

    @Test
    void staffUnknownVenue404() throws Exception {
        mvc.perform(staffRequest(bearer(cashierId, "CASHIER"), Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void statusFilterWorks() throws Exception {
        insertVault(venueA, customerId, productA, "ACTIVE");
        long emptyVault = insertVault(venueA, customerId, productA, "EMPTY");

        mvc.perform(staffRequest(bearer(cashierId, "CASHIER"), venueA).param("status", "EMPTY"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].vaultId").value(emptyVault))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void invalidStatus400() throws Exception {
        mvc.perform(staffRequest(bearer(cashierId, "CASHIER"), venueA).param("status", "BROKEN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerIdFilterWorks() throws Exception {
        insertVault(venueA, customerId, productA, "ACTIVE");
        long expected = insertVault(venueA, otherCustomerId, productA, "ACTIVE");

        mvc.perform(staffRequest(bearer(managerId, "MANAGER"), venueA)
                        .param("customerId", Long.toString(otherCustomerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].vaultId").value(expected))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void pageBoundariesAndDistinctPages() throws Exception {
        long firstId = insertVault(venueA, customerId, productA, "ACTIVE", Instant.parse("2026-01-01T00:00:00Z"));
        insertVault(venueA, customerId, productA, "ACTIVE", Instant.parse("2026-01-02T00:00:00Z"));
        insertVault(venueA, customerId, productA, "ACTIVE", Instant.parse("2026-01-03T00:00:00Z"));

        String first = mvc.perform(staffRequest(bearer(cashierId, "CASHIER"), venueA)
                        .param("size", "2").param("page", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)).andReturn()
                .getResponse().getContentAsString();
        String second = mvc.perform(staffRequest(bearer(cashierId, "CASHIER"), venueA)
                        .param("size", "2").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andReturn()
                .getResponse().getContentAsString();
        assertThat(first).doesNotContain(label(firstId));
        assertThat(second).contains(label(firstId));
    }

    @Test
    void negativePage400() throws Exception {
        mvc.perform(staffRequest(bearer(cashierId, "CASHIER"), venueA).param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sizeZero400() throws Exception {
        mvc.perform(staffRequest(bearer(cashierId, "CASHIER"), venueA).param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sizeOver100Returns400() throws Exception {
        mvc.perform(staffRequest(bearer(cashierId, "CASHIER"), venueA).param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void defaultSize20() throws Exception {
        for (int i = 0; i < 21; i++) {
            insertVault(venueA, customerId, productA, "ACTIVE");
        }
        mvc.perform(staffRequest(bearer(cashierId, "CASHIER"), venueA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(20));
        mvc.perform(customerRequest(bearer(customerUserId, "CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(20));
    }

    @Test
    void orderingStable() throws Exception {
        insertVault(venueA, customerId, productA, "ACTIVE", Instant.parse("2026-01-01T00:00:00Z"));
        long newest = insertVault(venueA, customerId, productA, "ACTIVE", Instant.parse("2026-01-02T00:00:00Z"));
        mvc.perform(staffRequest(bearer(cashierId, "CASHIER"), venueA))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].vaultId").value(newest));
    }

    @Test
    void equalStoredAtOrderedByIdDesc() throws Exception {
        Instant sameTime = Instant.parse("2026-01-01T00:00:00Z");
        insertVault(venueA, customerId, productA, "ACTIVE", sameTime);
        long newestId = insertVault(venueA, customerId, productA, "ACTIVE", sameTime);
        mvc.perform(staffRequest(bearer(cashierId, "CASHIER"), venueA))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].vaultId").value(newestId));
    }

    @Test
    void customerRoleOnStaffEndpointForbidden() throws Exception {
        mvc.perform(staffRequest(bearer(customerUserId, "CUSTOMER"), venueA)).andExpect(status().isForbidden());
    }

    @Test
    void adminOnCustomerEndpointForbidden() throws Exception {
        mvc.perform(customerRequest(bearer(adminId, "ADMIN"))).andExpect(status().isForbidden());
    }

    @Test
    void managerOnCustomerEndpointForbidden() throws Exception {
        mvc.perform(customerRequest(bearer(managerId, "MANAGER"))).andExpect(status().isForbidden());
    }

    @Test
    void cashierOnCustomerEndpointForbidden() throws Exception {
        mvc.perform(customerRequest(bearer(cashierId, "CASHIER"))).andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedStaffEndpoint401() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/bottles")
                        .param("venueId", Long.toString(venueA)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedCustomerEndpoint401() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/customers/me/bottles"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanReadStaffEndpoint() throws Exception {
        mvc.perform(staffRequest(bearer(adminId, "ADMIN"), venueA)).andExpect(status().isOk());
    }

    @Test
    void managerCanReadStaffEndpoint() throws Exception {
        mvc.perform(staffRequest(bearer(managerId, "MANAGER"), venueA)).andExpect(status().isOk());
    }

    @Test
    void cashierCanReadStaffEndpoint() throws Exception {
        mvc.perform(staffRequest(bearer(cashierId, "CASHIER"), venueA)).andExpect(status().isOk());
    }

    @Test
    void customerSeesOnlyOwnBottles() throws Exception {
        long own = insertVault(venueA, customerId, productA, "ACTIVE");
        long other = insertVault(venueB, otherCustomerId, productB, "ACTIVE");
        String ownLabel = label(own);
        String otherLabel = label(other);
        mvc.perform(customerRequest(bearer(customerUserId, "CUSTOMER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(ownLabel)))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(otherLabel))));
    }

    @Test
    void customerIdParamHasNoEffect() throws Exception {
        long own = insertVault(venueA, customerId, productA, "ACTIVE");
        insertVault(venueA, otherCustomerId, productA, "ACTIVE");
        mvc.perform(customerRequest(bearer(customerUserId, "CUSTOMER"))
                        .param("customerId", Long.toString(otherCustomerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].vaultId").value(own))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void customerWithoutCustomerRow404() throws Exception {
        mvc.perform(customerRequest(bearer(noCustomerUserId, "CUSTOMER"))).andExpect(status().isNotFound());
    }

    @Test
    void noSecretLeak() throws Exception {
        long vault = insertVault(venueA, customerId, productA, "ACTIVE");
        String hash = jdbc.queryForObject("SELECT qr_token_hash FROM bottle_vaults WHERE id=?", String.class, vault);
        String label = label(vault);
        String staffBody = mvc.perform(staffRequest(bearer(cashierId, "CASHIER"), venueA))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String customerBody = mvc.perform(customerRequest(bearer(customerUserId, "CUSTOMER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(staffBody).isNotEmpty().contains(label).doesNotContain("qrToken", "qr_token_hash", "tokenHash", hash);
        assertThat(customerBody).isNotEmpty().contains(label).doesNotContain("qrToken", "qr_token_hash", "tokenHash", hash);
    }

    @Test
    void readOnlyNoSideEffects() throws Exception {
        insertVault(venueA, customerId, productA, "ACTIVE");
        long auditsBefore = auditCount();
        long idempotencyBefore = idempotencyCount();

        mvc.perform(staffRequest(bearer(cashierId, "CASHIER"), venueA)).andExpect(status().isOk());
        mvc.perform(customerRequest(bearer(customerUserId, "CUSTOMER"))).andExpect(status().isOk());

        assertThat(auditCount()).isEqualTo(auditsBefore);
        assertThat(idempotencyCount()).isEqualTo(idempotencyBefore);
    }

    private String label(long vaultId) {
        return jdbc.queryForObject("SELECT label_code FROM bottle_vaults WHERE id=?", String.class, vaultId);
    }
}
