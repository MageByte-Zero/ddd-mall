#!/usr/bin/env python3
"""Verify order-line identity through the running Java apps and a lesson-19 upgrade."""

import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid
from urllib.request import Request, urlopen


spec = importlib.util.spec_from_file_location("payment_loop", Path(__file__).with_name("payment-loop.py"))
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)
lab.PORTS.update(product=28081, inventory=28082, payment=28083, order=28084)
lab.SCHEMAS = {role: f"ddd20_{role}_{lab.RUN}" for role in lab.PORTS}
lab.TOPIC = f"payment-l20-{lab.RUN}"
ORDER_TOPIC = f"orders-l20-{lab.RUN}"
GROUP = f"ship-l20-{lab.RUN}"
RESULTS = {}


def eventually(probe, expected):
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        value = probe()
        if value == expected:
            return value
        time.sleep(0.3)
    raise AssertionError((value, expected, str(lab.LOGS)))


def initialize():
    for schema in lab.SCHEMAS.values():
        lab.sql(f"CREATE DATABASE {schema} CHARACTER SET utf8mb4")
    for role in ("order", "inventory"):
        migration = next((lab.ROOT / f"mall-{role}/src/main/resources/db/migration").glob("V1__*.sql"))
        lab.sql(migration.read_text(), role)
    for topic in (ORDER_TOPIC, lab.TOPIC):
        subprocess.run(["docker", "exec", "ddd-rocketmq-broker", "sh", "mqadmin", "updateTopic",
                        "-n", "rocketmq-namesrv:9876", "-c", "DefaultCluster", "-t", topic],
                       check=True, stdout=subprocess.DEVNULL)
    lab.start("product", lab.jar("product"))
    lab.ready("product", "/api/catalog/skus/999", 404)


def start_inventory(jar):
    lab.start("inventory", jar, f"--ddd.inventory.shipment-topic={ORDER_TOPIC}",
              f"--ddd.inventory.shipment-group={GROUP}")
    lab.ready("inventory", "/api/inventories/SKU-1001", 200)


def start_order(jar, delay=250, managed=False):
    lab.start("order", jar, "--spring.cloud.nacos.discovery.register-enabled=false",
              "--ddd.order.event-logger-enabled=false",
              f"--ddd.order.catalog-base-url=http://127.0.0.1:{lab.PORTS['product']}",
              f"--ddd.outbox.relay-initial-delay-ms={delay}", "--ddd.outbox.relay-interval-ms=250",
              "--ddd.seata.demo-failure-sleep-seconds=0", f"--ddd.payment.consumer-group=pay-l20-{lab.RUN}",
              f"--ddd.payment.new-orders-enabled={str(managed).lower()}",
              f"--ddd.payment.consumer-enabled={str(managed).lower()}", order_topic=ORDER_TOPIC)
    lab.ready("order", "/api/orders/lesson20-readiness", 404)


def seed(sku, total):
    lab.http("product", "/api/catalog/skus", {"productId": sku, "skuId": sku, "name": "教学数据线",
             "unitPrice": "50.00", "enabled": True}, 201)
    lab.sql(f"INSERT INTO t_inventory(sku_code,sku_name,total_stock,available_stock,reserved_stock) "
            f"VALUES('SKU-{sku}','Lesson20',{total},{total},0)", "inventory")


def order_input(user_id):
    return {"userId": user_id, "receiverName": "采购员", "receiverPhone": "13800000000", "receiverAddress": "合成地址"}


def create(sku, user_id, *, multi=False, expected=201, quantities=(1, 2)):
    payload = order_input(user_id)
    if multi:
        payload["items"] = [dict(skuId=sku, quantity=qty, purchaseRequestNo=ref)
                            for qty, ref in zip(quantities, ("PR-A", "PR-B"))]
    else:
        payload.update(skuId=sku, quantity=2, productId=sku, unitPrice="50.00")
    result = lab.http("order", "/api/orders", payload, expected)
    return result["data"] if expected == 201 else result


def detail(order_no):
    return lab.http("order", f"/api/orders/{order_no}")["data"]


def ship(order_no, expected=200):
    return lab.http("order", f"/api/orders/{order_no}/shipment", {"operatedBy": "lesson20"}, expected)


def fact(order_no):
    return json.loads(lab.sql("SELECT payload FROM t_outbox_event WHERE event_type='OrderShipped'"
                              f" AND aggregate_id='{order_no}'", "order"))


def count(table, criteria, role):
    return int(lab.sql(f"SELECT COUNT(*) FROM {table} WHERE {criteria}", role))


def get_action_count(sku, action):
    return count("t_inventory_log", f"sku_code='SKU-{sku}' AND change_type='{action}'", "inventory")


def reservations(sku):
    rows = lab.sql("SELECT reservation_no, quantity, state FROM t_inventory_reservation "
                   f"WHERE sku_code='SKU-{sku}' ORDER BY quantity, reservation_no", "inventory")
    return [line.split("\t") for line in rows.splitlines()]


def inbox(event_id):
    return lab.sql("SELECT fingerprint FROM t_consumed_event WHERE subscription='inventory-shipment-v2'"
                   f" AND event_id='{event_id}'", "inventory")


def republish(event_id):
    # Inject duplicate delivery through the real relay; never reset Inventory Inbox.
    lab.sql("UPDATE t_outbox_event SET status='PENDING',next_retry_at=NULL"
            f" WHERE event_id='{event_id}'", "order")


def copy_fact(event_id):
    new_id = str(uuid.uuid4())
    lab.sql("INSERT INTO t_outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload,status) "
            f"SELECT '{new_id}',aggregate_type,aggregate_id,event_type,"
            f"JSON_SET(payload,'$.eventId','{new_id}'),'PENDING' FROM t_outbox_event WHERE event_id='{event_id}'", "order")
    return new_id


def wait_duplicate(event_id, expected_count):
    def observed():
        return sum(path.read_text(errors="replace").count(f"eventId={event_id} applied=false")
                   for path in lab.LOGS.glob("inventory-*.log"))
    eventually(observed, expected_count)


def legacy(baseline):
    baseline_jar = lambda role: baseline / f"mall-{role}/target/mall-{role}-0.1.0-SNAPSHOT.jar"
    start_inventory(baseline_jar("inventory"))
    start_order(baseline_jar("order"))
    for sku in range(20901, 20907):
        seed(sku, 3)
    old = [create(sku, sku)["orderNo"] for sku in range(20901, 20907)]
    for index in (0, 1, 3, 4, 5):
        lab.old_pay(old[index])
    ship(old[0])
    consumed = fact(old[0]); assert consumed["schemaVersion"] == 2
    eventually(lambda: get_action_count(20901, "CONFIRM"), 1)
    fingerprint = inbox(consumed["eventId"]); assert len(fingerprint) == 64

    lab.stop("order")
    start_order(baseline_jar("order"), delay=3600000)
    ship(old[1])
    pending = fact(old[1]); assert pending["schemaVersion"] == 2
    assert inbox(pending["eventId"]) == ""
    lab.stop("order")
    lab.stop("inventory")

    # Simulate two kinds of historical discrepancy using only fixture data.
    lab.sql(f"DELETE FROM t_inventory_reservation WHERE reservation_no='{old[3]}:SKU-20904'", "inventory")
    lab.sql("INSERT INTO t_order_item (order_id,product_id,sku_id,product_name,quantity,unit_price,subtotal) "
            "SELECT i.order_id,i.product_id,i.sku_id,i.product_name,i.quantity,i.unit_price,i.subtotal "
            f"FROM t_order_item i JOIN t_order o ON o.id=i.order_id WHERE o.order_no='{old[4]}'", "order")
    lab.sql(f"UPDATE t_order SET total_amount=200,paid_amount=200 WHERE order_no='{old[4]}'", "order")

    start_inventory(lab.jar("inventory"))
    start_order(lab.jar("order"), managed=True)
    legacy_line = detail(old[2])["items"][0]
    assert legacy_line["lineId"].startswith("legacy-")
    assert lab.cancel(old[2]) == "CANCELLED"
    assert reservations(20903) == [[f"{old[2]}:SKU-20903", "2", "RELEASED"]]
    assert detail(old[2])["items"][0]["lineId"] == legacy_line["lineId"]
    eventually(lambda: get_action_count(20902, "CONFIRM"), 1)
    ship(old[5])
    fresh_v2 = fact(old[5])
    assert fresh_v2["schemaVersion"] == 2
    assert set(fresh_v2["lines"][0]) == {"reservationNo", "skuCode", "quantity"}
    eventually(lambda: get_action_count(20906, "CONFIRM"), 1)
    republish(consumed["eventId"])
    wait_duplicate(consumed["eventId"], 1)
    assert inbox(consumed["eventId"]) == fingerprint
    assert get_action_count(20901, "CONFIRM") == 1
    for index in (3, 4):
        ship(old[index], 422)
        assert detail(old[index])["status"] == "PAID"
        assert count("t_outbox_event", f"aggregate_id='{old[index]}' AND event_type='OrderShipped'", "order") == 0
    RESULTS["legacy"] = dict(originalKey=reservations(20903)[0][0], lineStable=True,
                             delayedV2Confirms=1, freshV2Confirms=1, handledV2ReplayConfirms=1,
                             originalInboxFingerprintPreserved=True, missingAndAmbiguousRejected=True)


def new_lines():
    lab.start("payment", lab.jar("payment"), "--spring.cloud.nacos.discovery.register-enabled=false",
              f"--ddd.payment.order-base-url=http://127.0.0.1:{lab.PORTS['order']}",
              "--ddd.payment.relay-initial-delay-ms=200", "--ddd.payment.relay-interval-ms=250")
    lab.ready("payment", "/api/payments/lesson20-readiness", 401)
    for sku, stock in ((20101, 3), (20102, 2), (20103, 3), (20104, 3), (20105, 3)):
        seed(sku, stock)

    created = create(20101, 20101, multi=True)
    order = created["orderNo"]
    items = created["items"]
    assert created["totalAmount"] == "150.00"
    assert [(item["purchaseRequestNo"], item["skuId"], item["quantity"], item["unitPrice"]) for item in items] == [
        ("PR-A", 20101, 1, "50.00"), ("PR-B", 20101, 2, "50.00")]
    assert len({item["lineId"] for item in items}) == 2
    assert all(item["reservationNo"] == f"{order}:LINE:{item['lineId']}" for item in items)
    assert lab.stock(20101) == [3, 0, 3]
    assert reservations(20101) == [[item["reservationNo"], str(item["quantity"]), "RESERVED"] for item in items]

    # Compare by business identity even when read order differs from initial response order.
    assert {item["lineId"]: item for item in reversed(detail(order)["items"])} == {item["lineId"]: item for item in items}
    price_change = Request(f"http://127.0.0.1:{lab.PORTS['product']}/api/catalog/skus/20101",
                           data=json.dumps(dict(name="教学数据线", unitPrice="60.00", enabled=True)).encode(),
                           headers={"Content-Type": "application/json"}, method="PUT")
    with urlopen(price_change, timeout=10) as response:
        assert response.status == 200
    assert detail(order)["totalAmount"] == "150.00" and detail(order)["items"] == items
    RESULTS["reload"] = dict(lineCount=2, sameIdentityAfterReordering=True, orderAmount="150.00",
                             currentUnitPrice="60.00", snapshotUnitPrice="50.00")
    equal = create(20104, 20104, multi=True, quantities=(1, 1))["items"]
    assert equal[0]["lineId"] != equal[1]["lineId"] and len(reservations(20104)) == 2

    # Only the second line exceeds the shared available quantity.
    before = {table: count(table, "1=1", "order")
              for table in ("t_order_item", "t_order_status_history", "t_outbox_event")}
    rejected = create(20102, 20102, multi=True, expected=422)
    assert "库存不足" in rejected["message"], rejected
    assert lab.stock(20102) == [2, 2, 0]
    assert not reservations(20102)
    assert get_action_count(20102, "RESERVE") == 0
    assert count("t_inventory_operation", "sku_code='SKU-20102'", "inventory") == 0
    assert count("t_order", "user_id=20102", "order") == 0
    assert {table: count(table, "1=1", "order") for table in before} == before
    RESULTS["secondLineRollsBack"] = dict(before=[2, 2, 0], after=lab.stock(20102),
                                         orderRows=0, outboxRows=0, reservations=0, receipts=0, reserveLogs=0)

    cancel_no = create(20103, 20103, multi=True)["orderNo"]
    lab.http("order", f"/api/orders/{cancel_no}/cancellation", dict(reason="教学故障", operatedBy="lesson20",
             simulateReleaseFailure=True), 422)
    assert detail(cancel_no)["status"] == "PENDING_PAY" and lab.stock(20103) == [3, 0, 3]
    assert {row[2] for row in reservations(20103)} == {"RESERVED"}
    assert get_action_count(20103, "RELEASE") == 0
    assert count("t_outbox_event", f"aggregate_id='{cancel_no}' AND event_type='OrderCancelled'", "order") == 0
    assert lab.cancel(cancel_no) == "CANCELLED" and lab.stock(20103) == [3, 3, 0]
    for reservation, qty, state in reservations(20103):
        assert state == "RELEASED"
        lab.http("inventory", "/api/inventories/releases", dict(requestKey=f"RELEASE:{reservation}",
                 reservationNo=reservation, skuCode="SKU-20103", quantity=int(qty)))
    assert get_action_count(20103, "RELEASE") == 2
    RESULTS["cancel"] = dict(rollbacksPreserveBoth=True, releasesAfterRetry=2, stock=lab.stock(20103))

    payment_no = lab.create_payment(order)
    assert lab.callback(payment_no, order, "LESSON20-PAID", amountMinor=15000)["result"] == "CONFIRMED"
    lab.wait_status(payment_no, "ACCEPTED")
    assert detail(order)["items"] == items and lab.stock(20101) == [3, 0, 3]
    assert ship(order)["data"]["status"] == "SHIPPED"
    event = fact(order)
    assert event["schemaVersion"] == 3
    assert event["lines"] == [{"skuCode": "SKU-20101", **{name: item[name] for name in
                            ("lineId", "reservationNo", "quantity")}} for item in items]
    eventually(lambda: get_action_count(20101, "CONFIRM"), 2)
    republish(event["eventId"])
    wait_duplicate(event["eventId"], 1)
    copied = copy_fact(event["eventId"])
    eventually(lambda: len(inbox(copied)), 64)
    assert get_action_count(20101, "CONFIRM") == 2
    assert lab.stock(20101) == [0, 0, 0]
    assert {row[2] for row in reservations(20101)} == {"CONFIRMED"}
    RESULTS["shipment"] = dict(lines=event["lines"], schemaVersion=3, stockBefore=[3, 0, 3],
                               confirmsAfterBothReplays=2, stock=lab.stock(20101), paymentAccepted=True)

    single = create(20105, 20105)
    assert len(single["items"]) == 1 and ":LINE:" in single["items"][0]["reservationNo"]
    malformed = order_input(20105)
    malformed.update(skuId=20105, quantity=1, items=[dict(skuId=20105, quantity=1, purchaseRequestNo="PR-A")])
    lab.http("order", "/api/orders", malformed, 400)
    RESULTS["compatibility"] = dict(oldRequestAccepted=True, mixedShapeRejected=True, equalQuantityLines=2)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-root", type=Path, required=True, help="Built source tree at lesson-19")
    args = parser.parse_args()
    try:
        initialize()
        legacy(args.baseline_root.resolve())
        new_lines()
        print(json.dumps(RESULTS, ensure_ascii=False, indent=2))
    finally:
        for role in list(lab.PROCESSES):
            lab.stop(role)
        print(f"Process logs: {lab.LOGS}", file=sys.stderr)
