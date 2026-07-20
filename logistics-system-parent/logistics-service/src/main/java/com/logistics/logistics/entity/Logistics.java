package com.logistics.logistics.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_logistics")
public class Logistics {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String logisticsNo;
    private Long orderId;
    private String status;
    private String currentLocation;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
