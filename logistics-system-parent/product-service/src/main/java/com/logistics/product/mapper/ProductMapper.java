package com.logistics.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.logistics.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
