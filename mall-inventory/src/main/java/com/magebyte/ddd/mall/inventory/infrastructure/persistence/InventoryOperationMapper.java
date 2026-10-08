package com.magebyte.ddd.mall.inventory.infrastructure.persistence;

import com.magebyte.ddd.mall.inventory.domain.InventoryOperationResult;
import org.apache.ibatis.annotations.*;
import java.util.Map;

@Mapper
public interface InventoryOperationMapper {
    @Select("SELECT * FROM t_inventory WHERE sku_code=#{sku} AND deleted=0 FOR UPDATE")
    InventoryDO lockInventory(String sku);

    @Select("SELECT request_key,reservation_no,action,sku_code,quantity,total_stock,available_stock,reserved_stock FROM t_inventory_operation WHERE request_key=#{key} FOR UPDATE")
    InventoryOperationResult findRequest(String key);

    @Select("SELECT request_key,reservation_no,action,sku_code,quantity,total_stock,available_stock,reserved_stock FROM t_inventory_operation WHERE reservation_no=#{no} AND action=#{action} FOR UPDATE")
    InventoryOperationResult findAction(@Param("no") String no, @Param("action") String action);

    @Select("SELECT * FROM t_inventory_reservation WHERE reservation_no=#{no} FOR UPDATE")
    Map<String,Object> findReservation(String no);

    @Insert("INSERT INTO t_inventory_reservation(reservation_no,sku_code,quantity,state) VALUES(#{no},#{sku},#{qty},#{state})")
    void insertReservation(@Param("no") String no,@Param("sku") String sku,@Param("qty") int qty,@Param("state") String state);

    @Update("UPDATE t_inventory_reservation SET state=#{state} WHERE reservation_no=#{no}")
    void updateReservation(@Param("no") String no,@Param("state") String state);

    @Insert("INSERT INTO t_inventory_operation(request_key,reservation_no,action,sku_code,quantity,total_stock,available_stock,reserved_stock) VALUES(#{requestKey},#{reservationNo},#{action},#{skuCode},#{quantity},#{totalStock},#{availableStock},#{reservedStock})")
    void insertResult(InventoryOperationResult result);

    @Insert("INSERT INTO t_inventory_log(sku_code,change_type,quantity,total_before,total_after,available_before,available_after,reserved_before,reserved_after,reservation_no,request_key) VALUES(#{sku},#{action},#{qty},#{bt},#{at},#{ba},#{aa},#{br},#{ar},#{no},#{key})")
    void insertLog(@Param("sku") String sku,@Param("action") String action,@Param("qty") int qty,
                   @Param("bt") int bt,@Param("at") int at,@Param("ba") int ba,@Param("aa") int aa,
                   @Param("br") int br,@Param("ar") int ar,@Param("no") String no,@Param("key") String key);
}
