#!/usr/bin/env python3
"""Run the real-process lesson 17 fixture against a local DDD Compose stack."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
PORTS = {"inventory": 18082, "payment": 18083, "order": 18084}
RUN = secrets.token_hex(4)
SCHEMAS = {name: f"ddd17_{name}_{RUN}" for name in PORTS}
TOPIC = f"payment-events-{RUN}"
HANDOFF = secrets.token_urlsafe()
CHANNEL = secrets.token_urlsafe()
LOGS = Path(tempfile.mkdtemp(prefix="ddd17-payment-"))
PROCESSES = {}
RESULTS = {}


def sql(statement, schema=None):
    args = ["docker", "exec", "ddd-mysql", "sh", "-c",
            'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot -N -B "$@"',
            "fixture", "-e", statement]
    if schema:
        args.append(SCHEMAS[schema])
    return subprocess.check_output(args, text=True).strip()


def http(service, path, body=None, expected=200, token=None, callback=False):
    data = None if body is None else json.dumps(body).encode()
    headers = {"Content-Type": "application/json"}
    if token is not None:
        headers["X-Simulated-Callback-Token" if callback else "X-Payment-Handoff-Token"] = token
    request = Request(f"http://127.0.0.1:{PORTS[service]}{path}", data=data, headers=headers)
    try:
        response = urlopen(request, timeout=20)
    except HTTPError as error:
        response = error
    with response:
        raw = response.read()
        assert response.code == expected, (service, path, response.code, expected, raw[:600])
        return json.loads(raw) if raw else None


def ready(service, path, expected):
    until = time.monotonic() + 45
    while time.monotonic() < until:
        status = PROCESSES[service].poll()
        if status is not None:
            raise RuntimeError(f"{service} exited {status}; logs in {LOGS}")
        try:
            return http(service, path, expected=expected)
        except URLError:
            time.sleep(0.3)
    raise TimeoutError(f"{service} did not start; logs in {LOGS}")


def jar(role):
    matches = list((ROOT / f"mall-{role}/target").glob(f"mall-{role}-*.jar"))
    assert len(matches) == 1, matches
    return matches[0]


def start(role, artifact, *settings, order_topic="order-events"):
    assert role not in PROCESSES
    log = LOGS / f"{role}-{time.time_ns()}.log"
    env = dict(os.environ, DDD_PAYMENT_HANDOFF_TOKEN=HANDOFF, DDD_PAYMENT_CALLBACK_TOKEN=CHANNEL)
    java = Path(os.getenv("JAVA_HOME", "")) / "bin/java" if os.getenv("JAVA_HOME") else "java"
    args = [str(java), "-Xmx384m", "-jar", str(artifact), f"--server.port={PORTS[role]}",
            "--server.address=127.0.0.1", "--spring.cloud.nacos.discovery.ip=127.0.0.1",
            f"--spring.cloud.nacos.discovery.group=DDD17_{RUN}",
            f"--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/{SCHEMAS[role]}"
            "?characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC",
            f"--ddd.order.events-topic={order_topic}",
            f"--ddd.payment.events-topic={TOPIC}", *settings]
    with log.open("w") as stream:
        PROCESSES[role] = subprocess.Popen(args, stdout=stream, stderr=subprocess.STDOUT, env=env)


def stop(role):
    process = PROCESSES.pop(role)
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=12)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def make_order(sku):
    sql(f"INSERT INTO t_inventory (sku_code,sku_name,total_stock,available_stock,reserved_stock)"
        f" VALUES ('SKU-{sku}','Lesson 17',10,10,0)", "inventory")
    payload = {"userId": 17, "productId": sku, "skuId": sku, "productName": "合成商品", "quantity": 2,
               "unitPrice": "50.00", "receiverName": "合成用户", "receiverPhone": "13800000000",
               "receiverAddress": "合成地址"}
    result = http("order", "/api/orders", payload, 201)["data"]
    assert result["status"] == "PENDING_PAY"
    return result["orderNo"]


def stock(sku):
    return list(map(int, sql("SELECT total_stock,available_stock,reserved_stock"
                             f" FROM t_inventory WHERE sku_code='SKU-{sku}'", "inventory").split()))


def cancel(order_no):
    return http("order", f"/api/orders/{order_no}/cancellation", {
        "reason": "合成用户取消", "operatedBy": "lesson17"})["data"]["status"]


def old_pay(order_no, expected=200):
    return http("order", f"/api/orders/{order_no}/payment",
                {"paidAmount": "100.00", "operatedBy": "lesson17"}, expected)


def create_payment(order_no):
    return http("payment", "/api/payments", {"orderNo": order_no}, token=HANDOFF)["paymentNo"]


def payment(payment_no):
    return http("payment", f"/api/payments/{payment_no}", token=HANDOFF)


def callback(payment_no, order_no, transaction, expected=200, key=CHANNEL, **overrides):
    payload = {"paymentNo": payment_no, "orderNo": order_no, "channelTxnNo": transaction,
               "amountMinor": 10000, "currency": "CNY", "result": "SUCCESS", **overrides}
    return http("payment", "/api/payments/callbacks/simulated", payload, expected, key, callback=True)


def wait_status(payment_no, expected):
    until = time.monotonic() + 35
    while time.monotonic() < until:
        try:
            return_value = http("order", f"/internal/payment-outcomes/{payment_no}", token=HANDOFF)
        except AssertionError as error:
            if error.args[0][2] != 404:
                raise
            time.sleep(0.35)
            continue
        assert return_value["outcome"] == expected, return_value
        return return_value
    raise TimeoutError(f"no outcome for {payment_no}; logs in {LOGS}")


def payment_counts(payment_no, order_no):
    return {
        "paymentFacts": int(sql(f"SELECT COUNT(*) FROM t_payment_outbox WHERE payment_no='{payment_no}'", "payment")),
        "orderPaidFacts": int(sql(f"SELECT COUNT(*) FROM t_outbox_event WHERE aggregate_id='{order_no}'"
                                  " AND event_type='OrderPaid'", "order")),
        "orderAcceptanceRows": int(sql(f"SELECT COUNT(*) FROM t_payment_acceptance WHERE payment_no='{payment_no}'", "order")),
    }


def run(baseline_order):
    for schema in SCHEMAS.values():
        sql(f"CREATE DATABASE {schema} CHARACTER SET utf8mb4")
    # The Seata datasource proxy checks undo_log before Flyway can run on an empty schema.
    for role in ("order", "inventory"):
        migration = next((ROOT / f"mall-{role}/src/main/resources/db/migration").glob("V1__*.sql"))
        sql(migration.read_text(), role)
    subprocess.run(["docker", "exec", "ddd-rocketmq-broker", "sh", "mqadmin", "updateTopic",
                    "-n", "rocketmq-namesrv:9876", "-c", "DefaultCluster", "-t", TOPIC],
                   check=True, stdout=subprocess.DEVNULL)
    order_ready = "/api/orders/lesson17-readiness"
    start("inventory", jar("inventory"))
    ready("inventory", "/api/inventories/SKU-1001", 200)
    start("order", baseline_order, "--spring.cloud.nacos.discovery.register-enabled=false")
    ready("order", order_ready, 404)
    legacy = make_order(17001)
    paid_legacy = make_order(17002)
    assert old_pay(paid_legacy)["data"]["status"] == "PAID"
    refunded_legacy = make_order(17009)
    assert old_pay(refunded_legacy)["data"]["status"] == "PAID"
    # Seed one valid L15 refund snapshot, since its application service has no HTTP endpoint.
    sql("UPDATE t_order SET status='REFUNDED', refund_no='RF-L17-MIGRATION',refund_amount=100,"
        "refund_reason='synthetic migration fixture',refund_from_status='PAID',refund_outcome='SUCCEEDED',"
        f"refund_reference='SIM-L15' WHERE order_no='{refunded_legacy}'", "order")
    for previous, target in (("PAID", "REFUND_REQUESTED"), ("REFUND_REQUESTED", "REFUNDED")):
        sql("INSERT INTO t_order_status_history (order_id,from_status,to_status,reason,operated_by)"
            f" SELECT id,'{previous}','{target}','synthetic migration fixture','fixture' FROM t_order"
            f" WHERE order_no='{refunded_legacy}'", "order")
    late_baseline = make_order(17003)
    assert cancel(late_baseline) == "CANCELLED"
    assert old_pay(late_baseline, 422)["code"] == 422 and stock(17003) == [10, 10, 0]
    RESULTS["baseline"] = {"statusAfterCancel": "CANCELLED", "legacyPaymentHttp": 422,
                           "stockAfterCancel": stock(17003), "paidOrderBeforeUpgrade": paid_legacy}
    stop("order")

    order_configuration = ("--spring.cloud.nacos.discovery.register-enabled=false",
                           "--ddd.payment.consumer-enabled=true", f"--ddd.payment.consumer-group=ddd17-{RUN}")
    start("order", jar("order"), *order_configuration, "--ddd.payment.new-orders-enabled=true")
    ready("order", order_ready, 404)
    assert old_pay(legacy)["data"]["status"] == "PAID"
    assert http("order", f"/api/orders/{paid_legacy}")["data"]["status"] == "PAID"
    assert http("order", f"/api/orders/{refunded_legacy}")["data"]["status"] == "REFUNDED"
    assert sql(f"SELECT refund_no,refund_outcome,refund_reference FROM t_order WHERE order_no='{refunded_legacy}'",
               "order").split() == ["RF-L17-MIGRATION", "SUCCEEDED", "SIM-L15"]
    assert sql("SELECT COUNT(*) FROM t_order WHERE payment_managed=0", "order") == "4"
    start("payment", jar("payment"), "--spring.cloud.nacos.discovery.register-enabled=false",
          "--ddd.payment.relay-initial-delay-ms=3600000",
          f"--ddd.payment.order-base-url=http://127.0.0.1:{PORTS['order']}")
    ready("payment", "/api/payments/lesson17-readiness", 401)

    normal = make_order(17004)
    with ThreadPoolExecutor(max_workers=4) as workers:
        numbers = set(workers.map(create_payment, [normal] * 4))
    assert len(numbers) == 1
    p = numbers.pop()
    assert old_pay(normal, 422)["code"] == 422
    assert callback(p, normal, "T-NORMAL", 401, key="wrong")["status"] == 401
    assert callback(p, normal, "T-NORMAL", 400, amountMinor=10000.5)["status"] == 400
    assert callback(p, normal, None, result="UNKNOWN")["paymentStatus"] == "PENDING"
    for override in ({"amountMinor": 9999}, {"currency": "USD"}, {"result": "VENDOR_NEW_STATUS"}):
        assert callback(p, normal, "T-NORMAL", 422, **override)["result"] == "REJECTED"
    assert callback(p, legacy, "T-NORMAL", 422)["reason"] == "ORDER_MISMATCH"
    assert payment(p)["deliveryStatus"] is None

    # Force only the future Outbox INSERT to fail and inspect the earlier write after rollback.
    sql(f"INSERT INTO t_payment_outbox (payment_no,event_id,payload,status)"
        f" VALUES ('{p}','fixture-occupied','{{}}','FAILED')", "payment")
    assert callback(p, normal, "T-NORMAL", 500)["status"] == 500
    assert payment(p)["status"] == "PENDING" and payment(p)["channelTxnNo"] is None
    assert sql("DELETE FROM t_payment_outbox WHERE event_id='fixture-occupied'", "payment") == ""
    assert callback(p, normal, "T-NORMAL")["result"] == "CONFIRMED"
    with ThreadPoolExecutor(max_workers=8) as workers:
        repeats = list(workers.map(lambda _: callback(p, normal, "T-NORMAL")["result"], range(100)))
    assert set(repeats) == {"ALREADY_CONFIRMED"}
    assert callback(p, normal, "T-NORMAL", 400, amountMinor=10000.5)["status"] == 400
    assert callback(p, normal, None, result="UNKNOWN")["paymentStatus"] == "SUCCEEDED"
    assert callback(p, normal, "T-DIFFERENT", 422)["reason"] == "CONFLICTING_SUCCESS"
    assert payment(p)["deliveryStatus"] == "PENDING"
    assert http("order", f"/api/orders/{normal}")["data"]["status"] == "PENDING_PAY"
    http("order", f"/internal/payment-outcomes/{p}", expected=404, token=HANDOFF)
    assert stock(17004) == [10, 8, 2]
    reviews = sql("SELECT reason FROM t_payment_callback_review ORDER BY id", "payment").splitlines()
    assert reviews == ["AMOUNT_MISMATCH", "AMOUNT_MISMATCH", "UNSUPPORTED_RESULT", "ORDER_MISMATCH",
                       "CONFLICTING_SUCCESS"], reviews
    RESULTS["normalBeforeRelay"] = {"paymentNo": p, "orderNo": normal, "paymentStatus": "SUCCEEDED",
                                    "deliveryStatus": "PENDING", "orderStatus": "PENDING_PAY",
                                    "stock": stock(17004), "replays": len(repeats), "reviewReasons": reviews,
                                    "injectedOutboxFailure": "payment and its transaction ID rolled back"}

    late = make_order(17005)
    late_payment = create_payment(late)
    assert cancel(late) == "CANCELLED"
    assert stock(17005) == [10, 10, 0]
    assert callback(late_payment, late, "T-LATE")["result"] == "CONFIRMED"

    competitors = [(no := make_order(sku), create_payment(no)) for sku in (17006, 17007)]
    def contested(candidate):
        no, pn = candidate
        try:
            return callback(pn, no, "T-CONTESTED")
        except AssertionError as error:
            if error.args[0][2] != 422:
                raise
            return json.loads(error.args[0][4])
    with ThreadPoolExecutor(max_workers=2) as workers:
        decisions = list(workers.map(contested, competitors))
    assert {value["result"] for value in decisions} == {"CONFIRMED", "REJECTED"}, decisions
    assert any(value["reason"] == "CHANNEL_TRANSACTION_IN_USE" for value in decisions)
    for (no, pn), decision in zip(competitors, decisions):
        if decision["result"] == "REJECTED":
            assert callback(pn, no, "T-INDEPENDENT")["result"] == "CONFIRMED"

    stop("order")
    start("order", jar("order"), *order_configuration, "--ddd.payment.new-orders-enabled=false")
    ready("order", order_ready, 404)
    after_disable = make_order(17008)
    http("payment", "/api/payments", {"orderNo": after_disable}, 422, HANDOFF)
    assert old_pay(after_disable)["data"]["status"] == "PAID"

    stop("payment")
    sql("UPDATE t_payment_outbox SET next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 HOUR)"
        f" WHERE payment_no<>'{p}'", "payment")
    sql(f"UPDATE t_payment_outbox SET attempts=4 WHERE payment_no='{p}'", "payment")
    start("payment", jar("payment"), "--spring.cloud.nacos.discovery.register-enabled=false",
          "--rocketmq.name-server=127.0.0.1:19876", "--rocketmq.producer.sendMessageTimeout=750",
          "--ddd.payment.relay-initial-delay-ms=100", "--ddd.payment.relay-interval-ms=200",
          f"--ddd.payment.order-base-url=http://127.0.0.1:{PORTS['order']}")
    ready("payment", "/api/payments/lesson17-readiness", 401)
    deadline = time.monotonic() + 20
    while payment(p)["deliveryStatus"] != "FAILED":
        assert time.monotonic() < deadline, "expected a persisted relay failure"
        time.sleep(0.3)
    assert payment(p)["status"] == "SUCCEEDED"
    assert sql(f"SELECT attempts FROM t_payment_outbox WHERE payment_no='{p}'", "payment") == "5"
    RESULTS["relayFailure"] = {"paymentStatus": "SUCCEEDED", "deliveryStatus": "FAILED", "attempts": 5,
                               "injected": "four prior attempts seeded, fifth attempted with unavailable name server"}
    stop("payment")
    sql("UPDATE t_payment_outbox SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE status='PENDING'", "payment")
    start("payment", jar("payment"), "--spring.cloud.nacos.discovery.register-enabled=false",
          "--ddd.payment.relay-initial-delay-ms=100", "--ddd.payment.relay-interval-ms=200",
          f"--ddd.payment.order-base-url=http://127.0.0.1:{PORTS['order']}")
    ready("payment", "/api/payments/lesson17-readiness", 401)
    http("payment", f"/api/payments/{p}/redelivery", {}, 202, HANDOFF)
    assert wait_status(p, "ACCEPTED")["orderNo"] == normal
    assert wait_status(late_payment, "REVIEW_CANCELLED_ORDER")["orderNo"] == late
    for no, pn in competitors:
        assert wait_status(pn, "ACCEPTED")["orderNo"] == no
    assert stock(17004) == [10, 8, 2] and stock(17005) == [10, 10, 0]
    assert http("order", f"/api/orders/{normal}")["data"]["status"] == "PAID"
    assert http("order", f"/api/orders/{late}")["data"]["status"] == "CANCELLED"

    http("payment", f"/api/payments/{p}/redelivery", {}, 202, HANDOFF)
    until = time.monotonic() + 15
    while payment(p)["deliveryStatus"] != "SENT":
        assert time.monotonic() < until
        time.sleep(0.2)
    assert wait_status(p, "ACCEPTED")
    normal_counts = payment_counts(p, normal)
    assert normal_counts == {"paymentFacts": 1, "orderPaidFacts": 1, "orderAcceptanceRows": 1}
    late_counts = payment_counts(late_payment, late)
    assert late_counts == {"paymentFacts": 1, "orderPaidFacts": 0, "orderAcceptanceRows": 1}
    assert sql(f"SELECT COUNT(*) FROM t_payment_inbox WHERE payment_no='{p}'", "order") == "1"
    published = sql("SELECT payload FROM t_outbox_event"
                    f" WHERE aggregate_id='{normal}' AND event_type='OrderPaid'", "order")
    assert json.loads(published)["eventName"] == "OrderPaid"
    RESULTS["normalAfterRestartAndReplay"] = dict(normal_counts, orderStatus="PAID", stock=stock(17004),
                                                  outcome="ACCEPTED", inboxRows=1)
    RESULTS["late"] = dict(late_counts, paymentNo=late_payment, orderNo=late, paymentStatus="SUCCEEDED",
                           orderStatus="CANCELLED", outcome="REVIEW_CANCELLED_ORDER", stock=stock(17005))
    RESULTS["compatibility"] = {"historicalOrderCountAtMigration": 4, "refundFieldsPreserved": True, "oldEntryForLegacy": 200,
                               "oldEntryForNewPath": 422, "inFlightAfterDisablingNewOrders": "ACCEPTED"}
    RESULTS["channelIdentityRace"] = decisions


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-order-jar", required=True, type=Path)
    args = parser.parse_args()
    try:
        run(args.baseline_order_jar.resolve())
        print(json.dumps(RESULTS, indent=2, ensure_ascii=False))
    finally:
        for role in list(PROCESSES):
            stop(role)
        print(f"Dedicated DBs: {', '.join(SCHEMAS.values())}; process logs: {LOGS}", file=sys.stderr)
