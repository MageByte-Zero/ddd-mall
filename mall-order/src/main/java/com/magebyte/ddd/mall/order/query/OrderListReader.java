package com.magebyte.ddd.mall.order.query;

public interface OrderListReader {
    OrderListPage read(long userId, Long beforeId, int limit);
}
