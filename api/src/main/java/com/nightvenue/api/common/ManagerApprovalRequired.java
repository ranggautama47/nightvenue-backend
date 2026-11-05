package com.nightvenue.api.common;

public class ManagerApprovalRequired extends RuntimeException {
    public ManagerApprovalRequired() {
        super("MANAGER_APPROVAL_REQUIRED");
    }
}
