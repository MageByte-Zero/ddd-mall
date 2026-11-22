#!/usr/bin/env python3
"""Check the paid-order list against the running Java apps and isolated MySQL schemas."""

from contextlib import contextmanager
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import subprocess
from threading import Thread
import time
from urllib.request import Request, urlopen


spec = importlib.util.spec_from_file_location("payment_loop", Path(__file__).with_name("payment-loop.py"))
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)
lab.PORTS.update(product=29081, inventory=29082, payment=29083, order=29084)
lab.SCHEMAS = {role: f"ddd22_{role}_{lab.RUN}" for role in lab.PORTS}
lab.TOPIC = f"payment-l22-{lab.RUN}"
ORDER_TOPIC = f"orders-l22-{lab.RUN}"
USER_ID = 22001
SKU = 22101
LIST_PATH = f"/internal/order-list?userId={USER_ID}"
STATUS_PATH = "/internal/inventory-reservations/batch-query"
RESULTS = {}


@contextmanager
def inventory_status_proxy():
    """Only the list's read client uses this connection, so command traffic stays real."""
    calls = []
    behavior = {"timeout": False}

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            assert self.path == STATUS_PATH, self.path
            payload = self.rfile.read(int(self.headers["Content-Length"]))
            calls.append(json.loads(payload)["reservationNos"])
            if behavior["timeout"]:
                # The caller is waiting on the HTTP response past its configured deadline.
                time.sleep(1.6)
                return
            target = f"http://127.0.0.1:{lab.PORTS['inventory']}{self.path}"
            request = Request(target, data=payload, headers={"Content-Type": "application/json"})
            with urlopen(request, timeout=5) as response:
                content = response.read()
                self.send_response(response.status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(content)))
                self.end_headers()
                self.wfile.write(content)

        def log_message(self, *_):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield server.server_address[1], calls, behavior
        finally:
            server.shutdown()
            thread.join()


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
    lab.start("inventory", lab.jar("inventory"), f"--ddd.inventory.shipment-topic={ORDER_TOPIC}",
              f"--ddd.inventory.shipment-group=ship-l22-{lab.RUN}")
    lab.ready("inventory", "/api/inventories/SKU-1001", 200)
    lab.http("product", "/api/catalog/skus", {"productId": SKU, "skuId": SKU, "name": "采购数据线",
             "unitPrice": "50.00", "enabled": True}, 201)
    lab.sql(f"INSERT INTO t_inventory(sku_code,sku_name,total_stock,available_stock,reserved_stock) "
            f"VALUES('SKU-{SKU}','采购数据线',3,3,0)", "inventory")


def start_order(proxy_port, hold):
    lab.start("order", lab.jar("order"), "--spring.cloud.nacos.discovery.register-enabled=false",
              "--ddd.order.event-logger-enabled=false", "--ddd.payment.new-orders-enabled=true",
              "--ddd.payment.consumer-enabled=true", f"--ddd.payment.consumer-group=pay-l22-{lab.RUN}",
              f"--ddd.order.catalog-base-url=http://127.0.0.1:{lab.PORTS['product']}",
              f"--ddd.order.inventory-status-base-url=http://127.0.0.1:{proxy_port}",
              f"--ddd.outbox.relay-initial-delay-ms={3600000 if hold else 100}",
              "--ddd.outbox.relay-interval-ms=250", order_topic=ORDER_TOPIC)
    lab.ready("order", "/api/orders/list-readiness", 404)


def start_payment(hold):
    lab.start("payment", lab.jar("payment"), "--spring.cloud.nacos.discovery.register-enabled=false",
              f"--ddd.payment.order-base-url=http://127.0.0.1:{lab.PORTS['order']}",
              f"--ddd.payment.relay-initial-delay-ms={3600000 if hold else 100}",
              "--ddd.payment.relay-interval-ms=250")
    lab.ready("payment", "/api/payments/list-readiness", 401)


def list_page(expected=200, path=LIST_PATH):
    response = lab.http("order", path, expected=expected)
    return response["data"] if expected == 200 else response


def assert_checks(page, *checks):
    row, = page["orders"]
    assert [line["shipmentCheck"] for line in row["lines"]] == list(checks), row
    return row


def shipment_is_confirmed():
    until = time.monotonic() + 35
    while time.monotonic() < until:
        count = lab.sql(f"SELECT COUNT(*) FROM t_inventory_reservation WHERE sku_code='SKU-{SKU}' "
                        "AND state='CONFIRMED'", "inventory")
        if count == "2":
            return
        time.sleep(0.25)
    raise AssertionError(f"shipment did not reach inventory; logs: {lab.LOGS}")


def read_only_fingerprint(order_no):
    return (
        lab.sql(f"SELECT status,version,paid_amount FROM t_order WHERE order_no='{order_no}';"
                f"SELECT COUNT(*) FROM t_outbox_event WHERE aggregate_id='{order_no}';"
                "SELECT COUNT(*) FROM t_order_partial_refund", "order"),
        lab.sql("SELECT reservation_no,state FROM t_inventory_reservation ORDER BY id;"
                "SELECT COUNT(*) FROM t_inventory_operation; SELECT COUNT(*) FROM t_inventory_log", "inventory"),
    )


def main(proxy_port, calls, behavior):
    initialize()
    start_order(proxy_port, hold=True)
    start_payment(hold=True)
    response = lab.http("order", "/api/orders", {
        "userId": USER_ID, "receiverName": "采购员", "receiverPhone": "13800000000", "receiverAddress": "教学地址",
        "items": [{"skuId": SKU, "quantity": 1, "purchaseRequestNo": "PR-A"},
                  {"skuId": SKU, "quantity": 2, "purchaseRequestNo": "PR-B"}],
    }, 201)["data"]
    order_no = response["orderNo"]
    refs = [line["reservationNo"] for line in response["items"]]
    assert len(set(refs)) == 2
    request = Request(f"http://127.0.0.1:{lab.PORTS['product']}/api/catalog/skus/{SKU}", method="PUT",
                      headers={"Content-Type": "application/json"},
                      data=json.dumps({"name": "新目录名称", "unitPrice": "60.00", "enabled": True}).encode())
    with urlopen(request, timeout=5) as changed:
        assert changed.status == 200
    current = lab.http("product", f"/api/catalog/skus/{SKU}")
    assert current["name"] == "新目录名称" and Decimal(str(current["unitPrice"])) == Decimal(60)

    payment_no = lab.create_payment(order_no)
    assert lab.callback(payment_no, order_no, "LESSON22-CAPTURED", amountMinor=15000)["result"] == "CONFIRMED"
    assert lab.payment(payment_no)["status"] == "SUCCEEDED"
    assert lab.payment_counts(payment_no, order_no) == {
        "paymentFacts": 1, "orderPaidFacts": 0, "orderAcceptanceRows": 0,
    }
    assert list_page()["orders"] == [] and not calls
    lab.stop("payment")
    start_payment(hold=False)
    lab.wait_status(payment_no, "ACCEPTED")
    paid = assert_checks(list_page(), "NOT_APPLICABLE", "NOT_APPLICABLE")
    assert paid["fulfillmentStatus"] == "PAID" and Decimal(str(paid["paidAmount"])) == Decimal(150)
    assert [line["lineId"] for line in paid["lines"]] == [line["lineId"] for line in response["items"]]
    assert all(line["productName"] == "采购数据线" and Decimal(str(line["unitPrice"])) == Decimal(50)
               for line in paid["lines"])
    assert not calls
    RESULTS["purchaseAndPayment"] = dict(catalogName=current["name"], catalogPrice="60.00", snapshotName="采购数据线",
                                         snapshotUnitPrice="50.00", paidAmount="150.00", oldLines=2,
                                         beforeAcceptanceOrders=0, afterAcceptanceOrders=1)

    assert lab.http("order", f"/api/orders/{order_no}/shipment", {"operatedBy": "lesson22"})["data"]["status"] == "SHIPPED"
    reserved = assert_checks(list_page(), "RESERVED", "RESERVED")
    assert reserved["fulfillmentStatus"] == "SHIPPED" and calls == [refs]
    lab.stop("order")
    start_order(proxy_port, hold=False)
    shipment_is_confirmed()
    confirmed = list_page()
    assert assert_checks(confirmed, "CONFIRMED", "CONFIRMED")["fulfillmentStatus"] == "SHIPPED"
    assert confirmed["inventoryObservedAt"] and calls == [refs, refs]
    RESULTS["shipment"] = dict(orderStatus="SHIPPED", beforeConsumption=["RESERVED", "RESERVED"],
                               afterConsumption=["CONFIRMED", "CONFIRMED"], singleBatchReferences=2)

    before = read_only_fingerprint(order_no)
    behavior["timeout"] = True
    timed_out = list_page()
    timeout_row = assert_checks(timed_out, "UNAVAILABLE", "UNAVAILABLE")
    assert timed_out["inventoryObservedAt"] is None
    assert timeout_row["refund"] == paid["refund"] and timeout_row["paidAmount"] == paid["paidAmount"]
    assert calls == [refs, refs, refs]
    behavior["timeout"] = False
    assert_checks(list_page(), "CONFIRMED", "CONFIRMED")
    assert calls == [refs, refs, refs, refs]
    assert before == read_only_fingerprint(order_no)
    RESULTS["timeoutRecovery"] = dict(timedOut=["UNAVAILABLE", "UNAVAILABLE"], orderRowRetained=True,
                                      nextRefresh=["CONFIRMED", "CONFIRMED"], callsPerPage=1, writesDuringReads=0)

    # Controlled discrepancy confined to the copy of the order DB used by this fixture.
    for replacement, expected in (("'missing-l22'", "NOT_FOUND"), ("NULL", "NOT_VERIFIED")):
        lab.sql(f"UPDATE t_order_item SET reservation_no={replacement} WHERE reservation_no='{refs[0]}'", "order")
        try:
            assert_checks(list_page(), expected, "CONFIRMED")
        finally:
            lab.sql(f"UPDATE t_order_item SET reservation_no='{refs[0]}' "
                    f"WHERE line_id='{response['items'][0]['lineId']}'", "order")
    RESULTS["missingReferences"] = dict(successWithoutRow="NOT_FOUND", absentStoredReference="NOT_VERIFIED")
    list_page(expected=400, path=LIST_PATH+"&limit=21")
    assert lab.http("inventory", STATUS_PATH, {"reservationNos": [""]}, expected=400) is not None


if __name__ == "__main__":
    try:
        with inventory_status_proxy() as proxy:
            main(*proxy)
        print(json.dumps(RESULTS, ensure_ascii=False, indent=2))
    finally:
        for role in list(lab.PROCESSES):
            lab.stop(role)
