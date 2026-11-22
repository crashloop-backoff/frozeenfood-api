package com.lezzat.b2b2c.frozenfood.dto.request;

import java.util.List;
import java.util.UUID;

public record DeliveryRouteRequest(
        List<UUID> idDeliveries
) {
}
