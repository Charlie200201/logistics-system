package com.logistics.product.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.logistics.product.entity.Product;

public interface ProductService extends IService<Product> {
    Page<Product> pageQuery(int pageNum, int pageSize);
    Product getProductById(Long id);
    Product create(Product product);
    Product update(Product product);
    void delete(Long id);
    boolean deductStock(Long productId, Integer quantity);
    boolean restoreStock(Long productId, Integer quantity);
}
