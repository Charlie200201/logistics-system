package com.logistics.product.controller;

import com.logistics.common.exception.BusinessException;
import com.logistics.common.result.ResultCode;
import com.logistics.product.entity.Product;
import com.logistics.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    // ==================== GET /api/products — 分页列表 ====================

    @Test
    void list_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/products")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void list_shouldUseDefaultParams() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== GET /api/products/{id} — 商品详情 ====================

    @Test
    void getById_shouldReturnProduct() throws Exception {
        Product product = new Product();
        product.setId(1L);
        product.setName("iPhone 15");
        product.setPrice(new BigDecimal("6999.00"));
        product.setStock(100);

        when(productService.getProductById(1L)).thenReturn(product);

        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("iPhone 15"))
                .andExpect(jsonPath("$.data.price").value(6999.00))
                .andExpect(jsonPath("$.data.stock").value(100));
    }

    @Test
    void getById_shouldReturnErrorCode_whenNotFound() throws Exception {
        when(productService.getProductById(999L))
                .thenThrow(new BusinessException(ResultCode.PRODUCT_NOT_FOUND));

        // GlobalExceptionHandler 捕获后返回 HTTP 200，body.code = 2001
        mockMvc.perform(get("/api/products/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2001));
    }

    // ==================== POST /api/products — 创建商品 ====================

    @Test
    void create_shouldReturnSavedProduct() throws Exception {
        Product saved = new Product();
        saved.setId(1L);
        saved.setName("iPhone 15");
        saved.setPrice(new BigDecimal("6999.00"));
        saved.setStock(100);

        when(productService.create(any(Product.class))).thenReturn(saved);

        mockMvc.perform(post("/api/products")
                        .contentType("application/json")
                        .content("""
                                {
                                    "name": "iPhone 15",
                                    "price": 6999.00,
                                    "stock": 100
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("iPhone 15"));
    }

    // ==================== PUT /api/products/{id} — 更新商品 ====================

    @Test
    void update_shouldReturnUpdatedProduct() throws Exception {
        Product updated = new Product();
        updated.setId(1L);
        updated.setName("updated name");
        updated.setPrice(new BigDecimal("5999.00"));
        updated.setStock(50);

        when(productService.update(any(Product.class))).thenReturn(updated);

        mockMvc.perform(put("/api/products/1")
                        .contentType("application/json")
                        .content("""
                                {
                                    "name": "updated name",
                                    "price": 5999.00,
                                    "stock": 50
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("updated name"))
                .andExpect(jsonPath("$.data.price").value(5999.00));
    }

    // ==================== DELETE /api/products/{id} — 删除商品 ====================

    @Test
    void delete_shouldReturn200() throws Exception {
        mockMvc.perform(delete("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== POST /api/products/{id}/deduct-stock ====================

    @Test
    void deductStock_shouldReturnTrue() throws Exception {
        when(productService.deductStock(1L, 3)).thenReturn(true);

        mockMvc.perform(post("/api/products/1/deduct-stock")
                        .contentType("application/json")
                        .content("{\"quantity\": 3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    void deductStock_shouldReturnError_whenInsufficient() throws Exception {
        when(productService.deductStock(1L, 100))
                .thenThrow(new BusinessException(ResultCode.STOCK_INSUFFICIENT));

        // GlobalExceptionHandler 捕获后返回 HTTP 200，body.code = 2002
        mockMvc.perform(post("/api/products/1/deduct-stock")
                        .contentType("application/json")
                        .content("{\"quantity\": 100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2002));
    }

    // ==================== POST /api/products/{id}/restore-stock ====================

    @Test
    void restoreStock_shouldReturnTrue() throws Exception {
        when(productService.restoreStock(1L, 5)).thenReturn(true);

        mockMvc.perform(post("/api/products/1/restore-stock")
                        .contentType("application/json")
                        .content("{\"quantity\": 5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
    }
}
