package com.logistics.product.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.logistics.common.result.Result;
import com.logistics.product.entity.Product;
import com.logistics.product.service.ProductService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Api(tags = "商品服务")
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @ApiOperation("分页查询商品列表")
    @GetMapping
    public Result<Page<Product>> list(@RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(productService.pageQuery(pageNum, pageSize));
    }

    @ApiOperation("查询商品详情")
    @GetMapping("/{id}")
    public Result<Product> getById(@PathVariable Long id) {
        return Result.ok(productService.getProductById(id));
    }

    @ApiOperation("创建商品")
    @PostMapping
    public Result<Product> create(@RequestBody Product product) {
        return Result.ok(productService.create(product));
    }

    @ApiOperation("更新商品")
    @PutMapping("/{id}")
    public Result<Product> update(@PathVariable Long id, @RequestBody Product product) {
        product.setId(id);
        return Result.ok(productService.update(product));
    }

    @ApiOperation("删除商品")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        productService.delete(id);
        return Result.ok();
    }

    @ApiOperation("扣减库存（内部接口）")
    @PostMapping("/{id}/deduct-stock")
    public Result<Boolean> deductStock(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Integer quantity = body.get("quantity");
        return Result.ok(productService.deductStock(id, quantity));
    }

    @ApiOperation("恢复库存（内部接口）")
    @PostMapping("/{id}/restore-stock")
    public Result<Boolean> restoreStock(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Integer quantity = body.get("quantity");
        return Result.ok(productService.restoreStock(id, quantity));
    }
}
