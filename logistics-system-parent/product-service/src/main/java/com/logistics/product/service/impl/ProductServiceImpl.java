package com.logistics.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.logistics.common.exception.BusinessException;
import com.logistics.common.result.ResultCode;
import com.logistics.product.entity.Product;
import com.logistics.product.mapper.ProductMapper;
import com.logistics.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;

    private static final String CACHE_KEY_PREFIX = "product:";
    private static final long CACHE_TTL = 30; // minutes
    private static final String LOCK_KEY_PREFIX = "lock:stock:";

    @Override
    public Page<Product> pageQuery(int pageNum, int pageSize) {
        Page<Product> page = new Page<>(pageNum, pageSize);
        return this.page(page, new LambdaQueryWrapper<Product>().orderByDesc(Product::getCreatedAt));
    }

    @Override
    public Product getProductById(Long id) {
        String cacheKey = CACHE_KEY_PREFIX + id;
        Product product = (Product) redisTemplate.opsForValue().get(cacheKey);
        if (product != null) {
            log.info("从缓存中查询商品: id={}", id);
            return product;
        }
        product = this.getById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        redisTemplate.opsForValue().set(cacheKey, product, CACHE_TTL, TimeUnit.MINUTES);
        log.info("从数据库查询商品并写入缓存: id={}", id);
        return product;
    }

    @Override
    public Product create(Product product) {
        this.save(product);
        return product;
    }

    @Override
    public Product update(Product product) {
        this.updateById(product);
        redisTemplate.delete(CACHE_KEY_PREFIX + product.getId());
        return product;
    }

    @Override
    public void delete(Long id) {
        this.removeById(id);
        redisTemplate.delete(CACHE_KEY_PREFIX + id);
    }

    @Override
    @Transactional
    public boolean deductStock(Long productId, Integer quantity) {
        String lockKey = LOCK_KEY_PREFIX + productId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(10, 30, TimeUnit.SECONDS)) {
                try {
                    Product product = this.getById(productId);
                    if (product == null) {
                        throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
                    }
                    if (product.getStock() < quantity) {
                        log.warn("库存不足: productId={}, currentStock={}, needQuantity={}",
                                productId, product.getStock(), quantity);
                        throw new BusinessException(ResultCode.STOCK_INSUFFICIENT);
                    }
                    product.setStock(product.getStock() - quantity);
                    this.updateById(product);
                    redisTemplate.delete(CACHE_KEY_PREFIX + productId);
                    log.info("扣减库存成功: productId={}, quantity={}, remainingStock={}",
                            productId, quantity, product.getStock());
                    return true;
                } finally {
                    lock.unlock();
                }
            } else {
                log.warn("获取分布式锁失败: productId={}", productId);
                throw new BusinessException(429, "系统繁忙，请稍后再试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(500, "扣减库存异常");
        }
    }

    @Override
    @Transactional
    public boolean restoreStock(Long productId, Integer quantity) {
        Product product = this.getById(productId);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        product.setStock(product.getStock() + quantity);
        this.updateById(product);
        redisTemplate.delete(CACHE_KEY_PREFIX + productId);
        log.info("库存恢复成功: productId={}, quantity={}, currentStock={}",
                productId, quantity, product.getStock());
        return true;
    }
}
