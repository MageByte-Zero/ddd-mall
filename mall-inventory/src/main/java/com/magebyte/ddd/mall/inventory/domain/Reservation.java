package com.magebyte.ddd.mall.inventory.domain;

/** 有稳定业务身份的预占单；不可变实体，本讲只允许整笔释放或整笔出库。 */
public final class Reservation {
    public enum State { RESERVED, RELEASED, CONFIRMED }

    private final String reservationNo;
    private final String skuCode;
    private final int quantity;
    private final State state;

    public Reservation(String reservationNo, String skuCode, int quantity, State state) {
        if (reservationNo == null || reservationNo.isBlank() || skuCode == null
                || skuCode.isBlank() || quantity <= 0 || state == null) {
            throw new InventoryDomainException("预占身份、SKU、数量和状态不能为空或非法");
        }
        this.reservationNo = reservationNo;
        this.skuCode = skuCode;
        this.quantity = quantity;
        this.state = state;
    }

    public String reservationNo() { return reservationNo; }
    public String skuCode() { return skuCode; }
    public int quantity() { return quantity; }
    public State state() { return state; }

    public void requireSame(String sku, int qty) {
        if (!skuCode.equals(sku) || quantity != qty) {
            throw new InventoryIdempotencyException("同一预占单不能改变 SKU 或数量");
        }
    }

    public Reservation finish(State target) {
        if (state != State.RESERVED || target == null || target == State.RESERVED) {
            throw new InventoryDomainException("预占单已结束，不能再次释放或出库: " + reservationNo);
        }
        return new Reservation(reservationNo, skuCode, quantity, target);
    }
}
