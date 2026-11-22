package com.magebyte.ddd.mall.inventory.interfaces.messaging;
import com.fasterxml.jackson.databind.*;
import com.magebyte.ddd.mall.inventory.application.ShipmentApplicationService;
import com.magebyte.ddd.mall.inventory.domain.ShipmentFact;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
@Component
@ConditionalOnProperty(name="ddd.inventory.shipment-consumer-enabled", havingValue="true", matchIfMissing=true)
@RocketMQMessageListener(topic="${ddd.inventory.shipment-topic:order-events}",
        consumerGroup="${ddd.inventory.shipment-group:inventory-shipment-v2}",selectorExpression="OrderShipped",
        maxReconsumeTimes=16)
public class InventoryShipmentConsumer implements RocketMQListener<String> {
    private static final Pattern LINE_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final ObjectMapper json;
    private final ShipmentApplicationService shipments;
    public InventoryShipmentConsumer(ObjectMapper json,ShipmentApplicationService shipments) {
        this.json=json; this.shipments=shipments;
    }
    public void onMessage(String body) {
        ShipmentFact fact = translate(body);
        boolean applied = shipments.process(fact); // 提交成功才允许回调成功，异常不能吞掉。
        org.slf4j.LoggerFactory.getLogger(getClass()).info("发货消费已提交 eventId={} applied={}", fact.eventId(), applied);
    }
    public ShipmentFact translate(String body) {
        try {
            JsonNode root=json.readTree(body);
            JsonNode versionField = root.path("schemaVersion");
            int version = versionField.intValue();
            if (!"OrderShipped".equals(root.path("eventName").asText()) || !versionField.isIntegralNumber()
                    || !versionField.canConvertToInt() || (version != 2 && version != 3)) {
                throw new IllegalArgumentException("库存只接受 OrderShipped v2/v3；v1 缺少明细");
            }
            for (String field : List.of("eventId", "orderNo", "operatedBy", "occurredOn"))
                if (!root.path(field).isTextual()) throw new IllegalArgumentException("发货身份字段必须是字符串");
            String id=root.path("eventId").asText(), order=root.path("orderNo").asText();
            if (root.path("operatedBy").asText().isBlank() || root.path("occurredOn").asText().isBlank()
                    || !root.path("lines").isArray()) throw new IllegalArgumentException("发货事实字段缺失");
            java.time.LocalDateTime.parse(root.path("occurredOn").textValue());
            List<ShipmentFact.Line> lines=new ArrayList<>();
            for (JsonNode line:root.path("lines")) {
                if (!line.path("quantity").isIntegralNumber() || !line.path("quantity").canConvertToInt()
                        || !line.path("reservationNo").isTextual() || !line.path("skuCode").isTextual())
                    throw new IllegalArgumentException("发货明细字段类型不合法");
                String lineId = null;
                if (version == 3) {
                    lineId = line.path("lineId").textValue();
                    if (lineId == null || !LINE_ID.matcher(lineId).matches()) {
                        throw new IllegalArgumentException("发货行身份格式不合法");
                    }
                }
                lines.add(new ShipmentFact.Line(line.path("reservationNo").textValue(),
                        line.path("skuCode").textValue(), line.path("quantity").intValue(), lineId));
            }
            // 标准化已知契约字段；JSON 字段顺序/空白不改变身份，未知扩展字段不进入业务指纹。
            var canonical=new TreeMap<String,Object>();
            canonical.put("eventName","OrderShipped"); canonical.put("schemaVersion",version);
            canonical.put("orderNo",order); canonical.put("operatedBy",root.path("operatedBy").asText());
            canonical.put("occurredOn",root.path("occurredOn").asText());
            var sortedLines = lines.stream().sorted(Comparator.comparing(ShipmentFact.Line::reservationNo)).toList();
            // v2 的 Inbox 已存指纹，必须哈希原有三字段形状。
            canonical.put("lines", version == 2 ? sortedLines.stream().map(LegacyLine::from).toList() : sortedLines);
            String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(canonical)));
            return new ShipmentFact(id,order,lines,fingerprint,version);
        } catch (Exception failure) { throw new IllegalArgumentException("发货事件无法处理",failure); }
    }

    private record LegacyLine(String reservationNo, String skuCode, int quantity) {
        private static LegacyLine from(ShipmentFact.Line line) {
            return new LegacyLine(line.reservationNo(), line.skuCode(), line.quantity());
        }
    }
}
