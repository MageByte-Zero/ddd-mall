package com.magebyte.ddd.mall.inventory.interfaces.rest;

import com.magebyte.ddd.mall.inventory.application.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InventoryLockTimeoutTest {
    @Test void global_lock_timeout_is_conflict_not_business_rejection() throws Exception {
        var identified=mock(IdempotentInventoryService.class);
        when(identified.reserve(any())).thenThrow(new QueryTimeoutException("global lock wait timeout"));
        var mvc=MockMvcBuilders.standaloneSetup(new InventoryController(mock(InventoryApplicationService.class),identified))
                .setControllerAdvice(new InventoryGlobalExceptionHandler()).build();
        mvc.perform(post("/api/inventories/reservations").contentType("application/json")
                .content("{\"requestKey\":\"RESERVE:A\",\"reservationNo\":\"A\",\"skuCode\":\"SKU-1001\",\"quantity\":2}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("库存锁等待超时，请稍后重试整个用例"));
    }
}
