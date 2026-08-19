package com.logistics.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.logistics.common.exception.BusinessException;
import com.logistics.product.entity.Product;
import com.logistics.product.mapper.ProductMapper;
import com.logistics.product.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductMapper productMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    private ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        // spy：大部分方法走真实逻辑，个别依赖 MyBatis-Plus 内部的方法可以单独 stub
        productService = spy(new ProductServiceImpl(redisTemplate, redissonClient));
        ReflectionTestUtils.setField(productService, "baseMapper", productMapper);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ==================== 创建商品 ====================

    @Test
    void create_shouldReturnSavedProduct() {
        Product product = new Product();
        product.setName("iPhone 15");
        product.setPrice(new BigDecimal("6999.00"));
        product.setStock(100);

        when(productMapper.insert(product)).thenAnswer(inv -> {
            product.setId(1L);
            return 1;
        });

        Product result = productService.create(product);

        assertNotNull(result.getId());
        assertEquals(1L, result.getId());
        assertEquals("iPhone 15", result.getName());
    }

    // ==================== 查询商品 ====================

    @Test
    void getById_shouldReturnProduct_whenExists() {
        Product product = new Product();
        product.setId(1L);
        product.setName("iPhone 15");
        product.setPrice(new BigDecimal("6999.00"));
        product.setStock(100);

        when(valueOperations.get("product:1")).thenReturn(null);
        when(productMapper.selectById(1L)).thenReturn(product);

        Product result = productService.getProductById(1L);

        assertEquals("iPhone 15", result.getName());
        verify(valueOperations).set(eq("product:1"), eq(product), eq(30L), eq(TimeUnit.MINUTES));
    }

    @Test
    void getById_shouldReturnFromCache_whenCached() {
        Product cached = new Product();
        cached.setId(1L);
        cached.setName("cached product");

        when(valueOperations.get("product:1")).thenReturn(cached);

        Product result = productService.getProductById(1L);

        assertEquals("cached product", result.getName());
        verify(productMapper, never()).selectById(anyLong());
    }

    @Test
    void getById_shouldThrow_whenNotFound() {
        when(valueOperations.get("product:999")).thenReturn(null);
        when(productMapper.selectById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> productService.getProductById(999L));
    }

    // ==================== 更新商品 ====================

    @Test
    void update_shouldEvictCache_whenSuccess() {
        Product product = new Product();
        product.setId(1L);
        product.setName("updated name");
        product.setPrice(new BigDecimal("5999.00"));
        product.setStock(50);

        when(productMapper.updateById(product)).thenReturn(1);

        Product result = productService.update(product);

        assertEquals("updated name", result.getName());
        verify(redisTemplate).delete("product:1");
    }

    // ==================== 删除商品 ====================

    @Test
    void delete_shouldRemoveAndEvictCache() {
        // removeById 是 MyBatis-Plus ServiceImpl 的方法，会检查 tableInfo
        // 纯 Mockito 环境下 tableInfo 为 null，所以用 spy stub 掉
        doReturn(true).when(productService).removeById(1L);

        productService.delete(1L);

        verify(productService).removeById(1L);
        verify(redisTemplate).delete("product:1");
    }

    // ==================== 分页查询 ====================

    @Test
    void pageQuery_shouldReturnPage() {
        Product product = new Product();
        product.setId(1L);
        product.setName("test");

        Page<Product> expectedPage = new Page<>(1, 10);
        expectedPage.setRecords(java.util.Collections.singletonList(product));
        expectedPage.setTotal(1);

        // page() 是 MyBatis-Plus ServiceImpl 的方法，用 spy stub 掉内部实现
        doReturn(expectedPage).when(productService).page(any(Page.class), any(LambdaQueryWrapper.class));

        Page<Product> result = productService.pageQuery(1, 10);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
    }

    // ==================== 扣减库存 ====================

    @Test
    void deductStock_shouldSuccess_whenStockEnough() throws InterruptedException {
        Product product = new Product();
        product.setId(1L);
        product.setName("iPhone 15");
        product.setPrice(new BigDecimal("6999.00"));
        product.setStock(10);

        when(redissonClient.getLock("lock:stock:1")).thenReturn(rLock);
        when(rLock.tryLock(10, 30, TimeUnit.SECONDS)).thenReturn(true);
        when(productMapper.selectById(1L)).thenReturn(product);
        when(productMapper.updateById(any(Product.class))).thenReturn(1);

        boolean result = productService.deductStock(1L, 3);

        assertTrue(result);
        assertEquals(7, product.getStock());
        verify(rLock).unlock();
        verify(redisTemplate).delete("product:1");
    }

    @Test
    void deductStock_shouldThrow_whenStockInsufficient() throws InterruptedException {
        Product product = new Product();
        product.setId(1L);
        product.setStock(2);

        when(redissonClient.getLock("lock:stock:1")).thenReturn(rLock);
        when(rLock.tryLock(10, 30, TimeUnit.SECONDS)).thenReturn(true);
        when(productMapper.selectById(1L)).thenReturn(product);

        assertThrows(BusinessException.class, () -> productService.deductStock(1L, 10));
        verify(rLock).unlock();
        verify(productMapper, never()).updateById(any());
    }

    @Test
    void deductStock_shouldThrow_whenProductNotFound() throws InterruptedException {
        when(redissonClient.getLock("lock:stock:1")).thenReturn(rLock);
        when(rLock.tryLock(10, 30, TimeUnit.SECONDS)).thenReturn(true);
        when(productMapper.selectById(1L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> productService.deductStock(1L, 5));
        verify(rLock).unlock();
    }

    // ==================== 恢复库存 ====================

    @Test
    void restoreStock_shouldSuccess() {
        Product product = new Product();
        product.setId(1L);
        product.setStock(5);

        when(productMapper.selectById(1L)).thenReturn(product);
        when(productMapper.updateById(any(Product.class))).thenReturn(1);

        boolean result = productService.restoreStock(1L, 3);

        assertTrue(result);
        assertEquals(8, product.getStock());
        verify(redisTemplate).delete("product:1");
    }

    @Test
    void restoreStock_shouldThrow_whenProductNotFound() {
        when(productMapper.selectById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> productService.restoreStock(999L, 3));
    }
}
