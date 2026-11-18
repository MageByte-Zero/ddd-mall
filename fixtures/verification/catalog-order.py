#!/usr/bin/env python3
"""Repeat the lesson 18 baseline and catalogue integration on local Compose."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
from threading import Event, Thread
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
PORTS = {"product": 18181, "inventory": 18182, "payment": 18183, "order": 18184}
RUN = secrets.token_hex(4)
SCHEMAS = {role: f"ddd18_{role}_{RUN}" for role in PORTS}
HANDOFF = secrets.token_urlsafe()
LOGS = Path(tempfile.mkdtemp(prefix="ddd18-catalog-"))
PROCESSES = {}
CATALOG = "/api/catalog/skus"


def sql(statement, role=None):
    args = ["docker", "exec", "ddd-mysql", "sh", "-c",
            'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot -N -B "$@"',
            "fixture", "-e", statement]
    if role:
        args.append(SCHEMAS[role])
    return subprocess.check_output(args, text=True).strip()


def http(role, path, body=None, status=200, method=None, token=None):
    data = None if body is None else json.dumps(body).encode()
    request = Request(f"http://127.0.0.1:{PORTS[role]}{path}", data=data, method=method,
                      headers={"Content-Type": "application/json"})
    if token is not None:
        request.add_header("X-Payment-Handoff-Token", token)
    try:
        response = urlopen(request, timeout=18)
    except HTTPError as error:
        response = error
    with response:
        raw = response.read()
        actual = response.code
        assert actual in (status if isinstance(status, tuple) else (status,)), (role, path, actual, status, raw[:400])
        return actual, json.loads(raw) if raw else None


def ready(role, path, status):
    deadline = time.monotonic() + 55
    while time.monotonic() < deadline:
        if PROCESSES[role].poll() is not None:
            raise RuntimeError(f"{role} failed to start; see {LOGS}")
        try:
            http(role, path, status=status)
            return
        except URLError:
            time.sleep(.25)
    raise TimeoutError(f"{role} did not start; see {LOGS}")


def jar(role):
    matches = list((ROOT / f"mall-{role}/target").glob(f"mall-{role}-*.jar"))
    assert len(matches) == 1, matches
    return matches[0]


def start(role, artifact=None, *settings):
    assert role not in PROCESSES
    java = Path(os.environ["JAVA_HOME"]) / "bin/java" if "JAVA_HOME" in os.environ else "java"
    url = f"jdbc:mysql://127.0.0.1:3306/{SCHEMAS[role]}?useSSL=false&allowPublicKeyRetrieval=true"
    options = [str(java), "-Xmx384m", "-jar", str(artifact or jar(role)), f"--server.port={PORTS[role]}",
               "--server.address=127.0.0.1", f"--spring.datasource.url={url}",
               "--spring.cloud.nacos.discovery.ip=127.0.0.1", f"--spring.cloud.nacos.discovery.group=DDD18_{RUN}",
               "--ddd.order.event-logger-enabled=false", "--ddd.outbox.relay-initial-delay-ms=3600000",
               f"--ddd.payment.order-base-url=http://127.0.0.1:{PORTS['order']}", *settings]
    with (LOGS / f"{role}-{time.time_ns()}.log").open("w") as stream:
        PROCESSES[role] = subprocess.Popen(options, env=dict(os.environ, DDD_PAYMENT_HANDOFF_TOKEN=HANDOFF),
                                           stdout=stream, stderr=subprocess.STDOUT)


def stop(role):
    process = PROCESSES.pop(role)
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=12)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=4)


def lookup_stock():
    return list(map(int, sql("SELECT total_stock,available_stock,reserved_stock FROM t_inventory "
                             "WHERE sku_code='SKU-1001'", "inventory").split()))


def writes():
    return {
        "orders": int(sql("SELECT COUNT(*) FROM t_order", "order")),
        "items": int(sql("SELECT COUNT(*) FROM t_order_item", "order")),
        "outbox": int(sql("SELECT COUNT(*) FROM t_outbox_event", "order")),
        "stock": lookup_stock(),
        "inventoryLog": int(sql("SELECT COUNT(*) FROM t_inventory_log", "inventory")),
    }


def order_payload(**fields):
    return {"skuId": 1001, "quantity": 2, "userId": 18, "receiverName": "合成顾客",
            "receiverPhone": "13800000000", "receiverAddress": "合成地址", **fields}


def order(body, status=201):
    return http("order", "/api/orders", body, status)[1]


def show_order(order_no):
    return http("order", f"/api/orders/{order_no}")[1]["data"]


def order_values(order):
    item = order["items"][0]
    return {"name": item["productName"], "unitPrice": item["unitPrice"],
            "total": order["totalAmount"], "productId": item["productId"], "skuId": item["skuId"]}


def edit(sku_id, name, price, enabled, status=200):
    return http("product", f"{CATALOG}/{sku_id}", {"name": name, "unitPrice": price, "enabled": enabled},
                status, "PUT")[1]


def add(sku_id, product_id, status=201):
    return http("product", CATALOG, {"skuId": sku_id, "productId": product_id,
                "name": "滤杯", "unitPrice": "50.00", "enabled": True}, status)[0]


def get_sku(sku_id):
    return http("product", f"{CATALOG}/{sku_id}")[1]


class CatalogGate:
    """Forward actual catalogue reads and pause one response after its DB snapshot is read."""

    def __init__(self):
        self.read = Event()
        self.release = Event()
        self.hold = False
        self.observed = None
        gate = self

        class Proxy(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_GET(self):
                status, result = http("product", self.path, status=(200, 404))
                if gate.hold:
                    gate.hold = False
                    gate.observed = (result, self.headers.get("TX_XID"))
                    gate.read.set()
                    gate.release.wait(10)
                body = json.dumps(result, ensure_ascii=False).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Proxy)
        self.thread = Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_args):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def arm(self):
        self.read.clear()
        self.release.clear()
        self.hold = True


def run(baseline_jar):
    for role, schema in SCHEMAS.items():
        sql(f"CREATE DATABASE {schema} CHARACTER SET utf8mb4")
        if role in ("inventory", "order"):
            # Seata's datasource checks undo_log before its application can run Flyway.
            migration = next((ROOT / f"mall-{role}/src/main/resources/db/migration").glob("V1__*.sql"))
            sql(migration.read_text(), role)
    start("inventory")
    start("product")
    start("payment", None, "--spring.cloud.nacos.discovery.register-enabled=false")
    start("order", baseline_jar, "--spring.cloud.nacos.discovery.register-enabled=false")
    ready("inventory", "/api/inventories/SKU-1001", 200)
    ready("product", f"{CATALOG}/1001", 404)
    ready("payment", "/api/payments/readiness", 401)
    ready("order", "/api/orders/readiness", 404)
    sql("UPDATE t_inventory SET total_stock=10,available_stock=10,reserved_stock=0 WHERE sku_code='SKU-1001'",
        "inventory")
    legacy = order(order_payload(productId=501, productName="请求名称", unitPrice="0.01"))["data"]
    assert order_values(legacy)["total"] == "0.02" and lookup_stock() == [10, 8, 2]
    baseline_stock = lookup_stock()
    http("order", f"/api/orders/{legacy['orderNo']}/cancellation",
         {"reason": "基线分支结束", "operatedBy": "fixture"})
    assert lookup_stock() == [10, 10, 0]
    stop("order")

    add(1001, 501)
    with ThreadPoolExecutor(max_workers=2) as workers:
        competing = sorted(workers.map(lambda _: add(1002, 502, (201, 409)), range(2)))
    assert competing == [201, 409]
    assert add(1003, 502, 409) == 409 and add(1003, 503) == 201
    for name, value in (("   ", "50"), ("滤杯", "0"), ("滤杯", "-1"),
                        ("滤杯", "0.001"), ("滤杯", "10000000000"), ("滤杯", None)):
        edit(1001, name, value, True, 422)
    assert get_sku(1001)["unitPrice"] == 50

    def catalog_updates():
        for _ in range(6):
            edit(1002, "版本乙", "60", False)
            edit(1002, "滤杯", "50", True)

    with ThreadPoolExecutor(max_workers=2) as workers:
        edits = workers.submit(catalog_updates)
        snapshots = [get_sku(1002) for _ in range(18)]
        edits.result()
    assert {(v["name"], v["unitPrice"], v["enabled"]) for v in snapshots} <= {
        ("滤杯", 50, True), ("版本乙", 60, False)}

    settings = ("--spring.cloud.nacos.discovery.register-enabled=false", "--ddd.payment.new-orders-enabled=true")
    start("order", None, *settings)
    ready("order", "/api/orders/readiness", 404)
    assert order_values(show_order(legacy["orderNo"])) == order_values(legacy)
    before = writes()
    assert "单价" in order(order_payload(productId=501, productName="旧名称", unitPrice="0.01"), 422)["message"]
    assert "不属于" in order(order_payload(productId=502), 422)["message"]
    assert "不存在" in order(order_payload(skuId=1999), 422)["message"]
    assert writes() == before

    a = order(order_payload(productId=501, productName="旧请求名称", unitPrice="50"))["data"]
    assert order_values(a) == {"name": "滤杯", "unitPrice": "50.00", "total": "100.00", "skuId": 1001,
                                "productId": 501}
    assert lookup_stock() == [10, 8, 2]
    edit(1001, "玻璃滤杯", "60", True)
    b = order(order_payload())["data"]
    assert order_values(b) == {"name": "玻璃滤杯", "unitPrice": "60.00", "total": "120.00", "skuId": 1001,
                                "productId": 501}
    assert lookup_stock() == [10, 6, 4]
    stop("product")
    start("product")
    ready("product", f"{CATALOG}/1001", 200)
    assert get_sku(1001) == {"skuId": 1001, "productId": 501, "name": "玻璃滤杯", "unitPrice": 60, "enabled": True}
    assert order_values(show_order(a["orderNo"])) == order_values(a)
    assert order_values(show_order(b["orderNo"])) == order_values(b)
    amounts = [http("payment", "/api/payments", {"orderNo": item["orderNo"]}, token=HANDOFF)[1]["amountMinor"]
               for item in (a, b)]
    assert amounts == [10000, 12000]
    at_start = writes()
    assert order(order_payload(quantity=7), 422)
    assert writes() == at_start

    stop("order")
    with CatalogGate() as gate:
        start("order", None, *settings, f"--ddd.order.catalog-base-url=http://127.0.0.1:{gate.server.server_port}")
        ready("order", "/api/orders/readiness", 404)
        at_start = writes()
        with ThreadPoolExecutor(max_workers=1) as workers:
            gate.arm()
            future = workers.submit(order, order_payload(), 503)
            try:
                assert gate.read.wait(8)
                assert "请稍后重试" in future.result(timeout=8)["message"]
            finally:
                gate.release.set()
        assert writes() == at_start

        with ThreadPoolExecutor(max_workers=1) as workers:
            gate.arm()
            future = workers.submit(order, order_payload())
            try:
                assert gate.read.wait(8)
                assert gate.observed == ({"skuId": 1001, "productId": 501, "name": "玻璃滤杯",
                                          "unitPrice": 60, "enabled": True}, None)
                assert writes() == at_start
                edit(1001, "停用样品", "70", False)
            finally:
                gate.release.set()
            during_edit = future.result(timeout=15)["data"]
        assert order_values(during_edit) == order_values(b) and lookup_stock() == [10, 4, 6]
        after = writes()
        assert "停用" in order(order_payload(), 422)["message"]
        assert writes() == after and get_sku(1001)["unitPrice"] == 70
        assert order_values(show_order(a["orderNo"])) == order_values(a)

    return {"baseline": {"http": 201, "order": order_values(legacy), "stock": baseline_stock,
                         "stockAfterCancel": [10, 10, 0], "historicalSnapshotPreserved": True},
            "cataloguePersistence": {"afterRestart": {"name": "玻璃滤杯", "unitPrice": 60},
                                     "duplicateHttp": competing, "atomicReads": len(snapshots)},
            "ordersBeforeAndAfterUpdate": {"A": order_values(a), "B": order_values(b),
                                            "paymentAmountMinor": amounts, "stock": [10, 6, 4]},
            "compatibility": {"matchingOldShape": 201, "skuOnly": 201, "badPrice": 422,
                              "wrongProduct": 422, "unknownSku": 422},
            "failureBoundary": {"insufficientStock": 422, "catalogueTimeout": 503,
                                "ordersItemsOutboxAndInventoryUnchanged": True},
            "inFlightSnapshot": {"catalogueRequestXid": gate.observed[1],
                                 "order": order_values(during_edit), "directoryAfterUpdate": get_sku(1001),
                                 "followingRequest": 422, "stock": [10, 4, 6]}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-order-jar", required=True, type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(run(args.baseline_order_jar.resolve()), indent=2, ensure_ascii=False))
    finally:
        for role in list(PROCESSES):
            stop(role)
        print(f"Dedicated DBs: {', '.join(SCHEMAS.values())}; process logs: {LOGS}", file=sys.stderr)
