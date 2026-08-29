package com.magebyte.ddd.mall.order.interfaces.rest;

import com.magebyte.ddd.mall.order.domain.OrderDomainException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口层异常翻译：领域异常经 @RestControllerAdvice 翻译成 HTTP 422。
 *
 * <p>用 standalone MockMvc 挂一个只存在于测试里的探针控制器，
 * 不拉 Spring 容器、不连数据库，直接验证"领域抛异常 → 接口层翻译"这一段。
 * 控制器第 11 讲进场后，同一 advice 对真实端点自动生效。
 */
class GlobalExceptionHandlerTest {

    /** 探针控制器：模拟业务规则拒绝（如非法状态迁移）。 */
    @RestController
    static class ProbeController {

        @GetMapping("/probe/illegal-transition")
        public void illegalTransition() {
            throw new OrderDomainException("非法状态迁移: PAID -> CANCELLED");
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void domain_exception_translates_to_422() throws Exception {
        mockMvc.perform(get("/probe/illegal-transition"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value("非法状态迁移: PAID -> CANCELLED"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }
}
