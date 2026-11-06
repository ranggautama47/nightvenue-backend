package com.nightvenue.api.shift;

import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ShiftService {
    private final ShiftRepository shifts;

    public ShiftService(ShiftRepository shifts) {
        this.shifts = shifts;
    }

    public Optional<CashierShift> findOpenShiftByCashier(long cashierId) {
        return shifts.findOpenShiftByCashier(cashierId);
    }
}
