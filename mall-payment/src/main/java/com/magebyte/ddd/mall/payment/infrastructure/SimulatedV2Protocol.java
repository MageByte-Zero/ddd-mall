package com.magebyte.ddd.mall.payment.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.magebyte.ddd.mall.payment.application.PaymentChannelQuery.Report;
import com.magebyte.ddd.mall.payment.domain.Payment.ChannelResult;
import com.magebyte.ddd.mall.payment.domain.Payment.Observation;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/** V2 回调和查单共享同一份报文语义。 */
public final class SimulatedV2Protocol {
    private static final Pattern PAYMENT_REF = Pattern.compile("[A-Za-z0-9_-]{1,48}");
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern MAJOR_AMOUNT = Pattern.compile("[0-9]{1,17}\\.[0-9]{2}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern RESULT = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern RECEIPT = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    private SimulatedV2Protocol() {}

    public static Report translate(JsonNode body) {
        String paymentNo = merchantRef(body);
        if (paymentNo == null) throw new InvalidReport("INVALID_MERCHANT_REF");
        String orderNo = required(body, "merchantOrderRef", REFERENCE, "INVALID_ORDER_REF");
        String amount = required(body, "amountMajor", MAJOR_AMOUNT, "INVALID_AMOUNT");
        String currency = required(body, "currency", CURRENCY, "INVALID_CURRENCY");
        String reported = required(body, "result", RESULT, "INVALID_RESULT");
        JsonNode receipt = body.path("receiptNo");
        if (!receipt.isNull() && !receipt.isMissingNode()
                && (!receipt.isTextual() || !RECEIPT.matcher(receipt.textValue()).matches())) {
            throw new InvalidReport("INVALID_RECEIPT_NO");
        }
        ChannelResult result = switch (reported) {
            case "SUCCESS" -> ChannelResult.ACCEPTED;
            case "PROCESSING" -> ChannelResult.PROCESSING;
            case "CAPTURED" -> ChannelResult.CONFIRMED;
            case "DECLINED" -> ChannelResult.DECLINED;
            case "UNKNOWN" -> ChannelResult.UNKNOWN;
            default -> ChannelResult.UNRECOGNIZED;
        };
        // V2 的 requestRef 只标识受理，不能补作收款流水。
        String transactionNo = result == ChannelResult.CONFIRMED ? receipt.textValue() : null;
        return new Report(new Observation(paymentNo, orderNo, transactionNo, minorUnits(amount),
                currency, result), reported);
    }

    public static String merchantRef(JsonNode body) {
        String value = body == null ? null : body.path("merchantRef").textValue();
        return value != null && PAYMENT_REF.matcher(value).matches() ? value : null;
    }

    private static String required(JsonNode body, String field, Pattern pattern, String reason) {
        String value = body.path(field).textValue();
        if (value == null || !pattern.matcher(value).matches()) throw new InvalidReport(reason);
        return value;
    }

    private static long minorUnits(String major) {
        try {
            long minor = new BigDecimal(major).movePointRight(2).longValueExact();
            if (minor <= 0) throw new InvalidReport("INVALID_AMOUNT");
            return minor;
        } catch (ArithmeticException outOfRange) {
            throw new InvalidReport("INVALID_AMOUNT");
        }
    }

    public static final class InvalidReport extends IllegalArgumentException {
        private InvalidReport(String reason) {
            super(reason);
        }
    }
}
