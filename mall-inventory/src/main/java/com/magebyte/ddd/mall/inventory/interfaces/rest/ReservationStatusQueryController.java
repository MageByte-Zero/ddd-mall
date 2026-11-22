package com.magebyte.ddd.mall.inventory.interfaces.rest;

import com.magebyte.ddd.mall.commons.response.Result;
import com.magebyte.ddd.mall.inventory.query.ReservationStatusQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ReservationStatusQueryController {
    private final ReservationStatusQuery query;

    public ReservationStatusQueryController(ReservationStatusQuery query) {
        this.query = query;
    }

    @PostMapping("/internal/inventory-reservations/batch-query")
    public Result<ReservationStatusQuery.Batch> read(@Valid @RequestBody Request request) {
        return Result.ok(query.read(request.reservationNos()));
    }

    public record Request(
            @NotEmpty @Size(max = ReservationStatusQuery.MAX_BATCH_SIZE)
            List<@NotBlank @Size(max = 128) String> reservationNos) {}
}
