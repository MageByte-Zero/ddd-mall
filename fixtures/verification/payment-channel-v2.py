#!/usr/bin/env python3
"""Exercise simulated V2 and the L17 payment loop with the current order/catalog modules."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import secrets
import subprocess
import sys
from threading import Event, Thread
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen


spec = importlib.util.spec_from_file_location("payment_loop", Path(__file__).with_name("payment-loop.py"))
loop = importlib.util.module_from_spec(spec)
spec.loader.exec_module(loop)
loop.PORTS["product"] = 18081
loop.SCHEMAS["product"] = f"ddd19_product_{loop.RUN}"
V2_TOKEN = secrets.token_urlsafe()
CALLBACK = "/api/payments/callbacks/simulated/v2"
RESULTS = {}


def http(path, payload, header, token, expected=200):
    request = Request(f"http://127.0.0.1:{loop.PORTS['payment']}{path}",
                      json.dumps(payload).encode(), {"Content-Type": "application/json", header: token})
    try:
        response = urlopen(request, timeout=20)
    except HTTPError as error:
        response = error
    with response:
        raw = response.read()
        assert response.code == expected, (path, response.code, expected, raw[:500])
        return json.loads(raw)


def callback(report, expected=200, token=V2_TOKEN, path=CALLBACK):
    return http(path, report, "X-Simulated-Callback-Token", token, expected)


def query(payment_no, expected=200, token=loop.HANDOFF):
    return http(f"/api/payments/{payment_no}/simulated-query", {},
                "X-Payment-Handoff-Token", token, expected)


def catalogue_order(sku, original=loop.make_order):
    loop.http("product", "/api/catalog/skus", {
        "productId": sku, "skuId": sku, "name": "合成商品", "unitPrice": "50.00", "enabled": True,
    }, 201)
    return original(sku)


class SimulatedChannel:
    def __init__(self):
        self.report = None
        self.reply = Event()
        self.received = Event()
        self.paths = []
        self.post_count = 0
        channel = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_POST(self):
                channel.post_count += 1
                self.send_error(405)

            def do_GET(self):
                channel.paths.append(self.path)
                channel.received.set()
                channel.reply.wait(6)
                body = json.dumps(channel.report).encode()
                try:
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_exc):
        self.reply.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.server.server_port}"


def start_catalog():
    loop.sql(f"CREATE DATABASE {loop.SCHEMAS['product']} CHARACTER SET utf8mb4")
    loop.start("product", loop.jar("product"))
    loop.ready("product", "/api/catalog/skus/999", 404)


def start_payment(artifact, *settings):
    loop.start("payment", artifact, "--spring.cloud.nacos.discovery.register-enabled=false",
               f"--ddd.payment.order-base-url=http://127.0.0.1:{loop.PORTS['order']}",
               "--ddd.payment.relay-initial-delay-ms=200", "--ddd.payment.relay-interval-ms=250", *settings)
    loop.ready("payment", "/api/payments/lesson19-readiness", 401)


def report_for(p, o, **changes):
    return {"merchantRef": p, "merchantOrderRef": o, "amountMajor": "100.00",
            "currency": "CNY", "result": "CAPTURED", "receiptNo": "T-L19", **changes}


def explain_state(p, o):
    payment = loop.payment(p)
    order = loop.http("order", f"/api/orders/{o}")["data"]
    return dict(paymentStatus=payment["status"], orderStatus=order["status"],
                **loop.payment_counts(p, o))


def run(baseline_payment, baseline_probe):
    start_catalog()
    for role in ("inventory", "order", "payment"):
        loop.sql(f"CREATE DATABASE {loop.SCHEMAS[role]} CHARACTER SET utf8mb4")
    for role in ("inventory", "order"):
        migration = next((loop.ROOT / f"mall-{role}/src/main/resources/db/migration").glob("V1__*.sql"))
        loop.sql(migration.read_text(), role)
    subprocess.run(["docker", "exec", "ddd-rocketmq-broker", "sh", "mqadmin", "updateTopic",
                    "-n", "rocketmq-namesrv:9876", "-c", "DefaultCluster", "-t", loop.TOPIC],
                   check=True, stdout=subprocess.DEVNULL)
    loop.start("inventory", loop.jar("inventory"))
    loop.ready("inventory", "/api/inventories/SKU-1001", 200)
    loop.start("order", loop.jar("order"), "--spring.cloud.nacos.discovery.register-enabled=false",
               "--ddd.payment.consumer-enabled=true", "--ddd.payment.new-orders-enabled=true",
               f"--ddd.payment.consumer-group=ddd19-{loop.RUN}",
               f"--ddd.order.catalog-base-url=http://127.0.0.1:{loop.PORTS['product']}")
    loop.ready("order", "/api/orders/lesson19-readiness", 404)

    start_payment(baseline_payment)
    o, old_pending, old_confirmed = map(catalogue_order, (19001, 19002, 19003))
    p, p_pending, p_confirmed = map(loop.create_payment, (o, old_pending, old_confirmed))
    assert loop.callback(p_confirmed, old_confirmed, "V1-BEFORE-UPGRADE")["result"] == "CONFIRMED"
    assert loop.wait_status(p_confirmed, "ACCEPTED")
    start_key = loop.payment(p_confirmed)["confirmedAt"]
    accepted = report_for(p, o, result="SUCCESS", receiptNo=None, requestRef="R-L19")

    if baseline_probe:
        callback(accepted)  # On the old payment jar this must fail at the missing V2 route.
        return

    loop.stop("payment")
    with SimulatedChannel() as simulated:
        start_payment(loop.jar("payment"), f"--ddd.payment.simulated-v2-callback-token={V2_TOKEN}",
                      f"--ddd.payment.simulated-v2-query-base-url={simulated.url}",
                      "--ddd.payment.simulated-v2-query-timeout-ms=1000")

        assert loop.payment(p_pending)["status"] == "PENDING"
        assert loop.callback(p_pending, old_pending, "V1-AFTER-UPGRADE")["result"] == "CONFIRMED"
        assert loop.wait_status(p_pending, "ACCEPTED")
        assert loop.callback(p_confirmed, old_confirmed, "V1-BEFORE-UPGRADE") == {
            "result": "ALREADY_CONFIRMED", "reason": None, "paymentStatus": "SUCCEEDED"}
        assert loop.payment(p_confirmed)["confirmedAt"] == start_key
        assert loop.payment_counts(p_confirmed, old_confirmed) == {
            "paymentFacts": 1, "orderPaidFacts": 1, "orderAcceptanceRows": 1}
        RESULTS["v1Upgrade"] = {"pendingThroughUpgrade": "CONFIRMED",
                                "previousConfirmation": "ALREADY_CONFIRMED",
                                "originalConfirmationPreserved": True}

        accepted_receipt = callback(accepted)
        assert accepted_receipt == {"result": "NO_NEW_CONFIRMATION", "reason": "CHANNEL_ACCEPTED",
                                    "paymentStatus": "PENDING"}
        processing = callback(report_for(p, o, result="PROCESSING", receiptNo=None))
        assert processing["reason"] == "CHANNEL_PROCESSING" and processing["paymentStatus"] == "PENDING"
        assert loop.payment(p)["channelTxnNo"] is None
        query(p, 401, token="wrong")
        query("unknown-payment", 404)
        timeout = query(p)
        assert timeout == {"result": "NO_NEW_CONFIRMATION", "paymentStatus": "PENDING", "reason": "QUERY_TIMEOUT"}
        assert simulated.paths == [f"/payments/{p}"]
        before = explain_state(p, o)
        assert before == {"paymentStatus": "PENDING", "orderStatus": "PENDING_PAY",
                          "paymentFacts": 0, "orderPaidFacts": 0, "orderAcceptanceRows": 0}
        assert loop.stock(19001) == [10, 8, 2]
        RESULTS["beforeCapture"] = dict(before, accepted=accepted_receipt, processing=processing,
                                        timeout=timeout, queryPath=simulated.paths[0],
                                        callbackInput=accepted, stock=loop.stock(19001))
        simulated.reply.set()

        negatives = [
            ({"result": "DECLINED", "receiptNo": None}, 422, "CHANNEL_REQUEST_DECLINED"),
            ({"result": "VENDOR_NEW_STATUS"}, 422, "UNSUPPORTED_RESULT"),
            ({"merchantOrderRef": old_confirmed}, 422, "ORDER_MISMATCH"),
            ({"currency": "USD"}, 422, "AMOUNT_MISMATCH"),
            ({"receiptNo": None, "requestRef": "R-NOT-RECEIPT"}, 422, "TRANSACTION_REQUIRED"),
            ({"receiptNo": "V1-BEFORE-UPGRADE"}, 422, "CHANNEL_TRANSACTION_IN_USE"),
            ({"merchantRef": "p" * 49}, 400, "INVALID_MERCHANT_REF"),
            ({"result": "X" * 33}, 400, "INVALID_RESULT"),
            ({"amountMajor": "100.001"}, 400, "INVALID_AMOUNT"),
            ({"amountMajor": "92233720368547758.08"}, 400, "INVALID_AMOUNT"),
            ({"amountMajor": 100.00}, 400, "INVALID_AMOUNT"),
            ({"amountMajor": "100.01"}, 422, "AMOUNT_MISMATCH"),
        ]
        reasons = []
        for changes, status, reason in negatives:
            receipt = callback(report_for(p, o, **changes), status)
            assert receipt["reason"] == reason, (changes, receipt)
            reasons.append({"changes": changes, "status": status, "reason": reason})
            assert explain_state(p, o) == before
        callback(accepted, 401, token=loop.CHANNEL)
        callback(accepted, 400, token=loop.CHANNEL, path="/api/payments/callbacks/simulated")
        old_payload = {"paymentNo": p, "orderNo": o, "amountMinor": 10000,
                       "currency": "CNY", "channelTxnNo": "T-WRONG-VERSION", "result": "SUCCESS"}
        assert callback(old_payload, 400)["reason"] == "INVALID_MERCHANT_REF"
        reviews = loop.sql("SELECT reason FROM t_payment_callback_review"
                           f" WHERE payment_no='{p}' ORDER BY id", "payment").splitlines()
        assert reviews == [entry["reason"] for entry in reasons if entry["status"] == 422]
        RESULTS["negativeReports"] = reasons
        RESULTS["persistedReviewReasons"] = reviews

        simulated.report = report_for(p_confirmed, old_confirmed)
        assert query(p, 422)["reason"] == "QUERY_IDENTITY_MISMATCH"
        assert explain_state(p, o) == before
        assert loop.payment(p_confirmed)["confirmedAt"] == start_key

        captured = report_for(p, o)
        simulated.report = captured
        simulated.reply.clear()
        simulated.received.clear()
        with ThreadPoolExecutor(max_workers=2) as workers:
            lookup = workers.submit(query, p)
            assert simulated.received.wait(4)
            push = workers.submit(callback, captured)
            simulated.reply.set()
            decisions = [lookup.result(), push.result()]
        assert {value["result"] for value in decisions} == {"CONFIRMED", "ALREADY_CONFIRMED"}, decisions
        assert loop.wait_status(p, "ACCEPTED")
        confirmation = loop.payment(p)
        assert confirmation["channelTxnNo"] == "T-L19" and confirmation["amountMinor"] == 10000
        assert callback(report_for(p, o, result="UNKNOWN", receiptNo=None))["paymentStatus"] == "SUCCEEDED"
        cross_version_replay = loop.callback(p, o, "T-L19")
        assert cross_version_replay["result"] == "ALREADY_CONFIRMED"
        after = explain_state(p, o)
        assert after == {"paymentStatus": "SUCCEEDED", "orderStatus": "PAID",
                         "paymentFacts": 1, "orderPaidFacts": 1, "orderAcceptanceRows": 1}
        assert loop.payment(p)["confirmedAt"] == confirmation["confirmedAt"]
        assert loop.stock(19001) == [10, 8, 2]
        assert simulated.paths == [f"/payments/{p}"] * 3 and simulated.post_count == 0
        RESULTS["captureRace"] = dict(after, results=sorted(v["result"] for v in decisions),
                                      queryPaths=simulated.paths, channelWrites=simulated.post_count,
                                      amountMinor=10000, callbackInput=captured, stock=loop.stock(19001),
                                      v1ReplayAfterV2=cross_version_replay["result"])
        independently_paid = catalogue_order(19004)
        independent_p = loop.create_payment(independently_paid)
        assert callback(report_for(independent_p, independently_paid, receiptNo="T-L19-INDEPENDENT"))[
            "result"] == "CONFIRMED"
        assert loop.wait_status(independent_p, "ACCEPTED")
        RESULTS["independentSameAmount"] = explain_state(independent_p, independently_paid)


def regression(baseline_order):
    # Use the original L17 fixture with a catalogue entry for each product it used to pass inline.
    start_catalog()
    schema = loop.SCHEMAS.pop("product")
    loop.make_order = catalogue_order
    start = loop.start

    def start_with_current_catalog(role, artifact, *settings):
        if role == "order" and artifact != baseline_order:
            settings += (f"--ddd.order.catalog-base-url=http://127.0.0.1:{loop.PORTS['product']}",)
        start(role, artifact, *settings)

    loop.start = start_with_current_catalog
    try:
        loop.run(baseline_order)
        RESULTS.update(loop.RESULTS)
    finally:
        loop.SCHEMAS["product"] = schema


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--baseline-payment-jar", type=Path)
    target.add_argument("--lesson17-baseline-order-jar", type=Path)
    parser.add_argument("--baseline-probe", action="store_true")
    args = parser.parse_args()
    try:
        if args.baseline_payment_jar:
            run(args.baseline_payment_jar.resolve(), args.baseline_probe)
        else:
            regression(args.lesson17_baseline_order_jar.resolve())
        print(json.dumps(RESULTS, ensure_ascii=False, indent=2))
    finally:
        for role in list(loop.PROCESSES):
            loop.stop(role)
        print(f"Dedicated DBs: {', '.join(loop.SCHEMAS.values())}; logs: {loop.LOGS}", file=sys.stderr)
