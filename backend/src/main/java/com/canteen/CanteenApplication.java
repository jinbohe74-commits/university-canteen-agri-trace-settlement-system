package com.canteen;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 面向高校食堂的农产品直供溯源与结算管理系统
 */
@SpringBootApplication
@MapperScan("com.canteen.mapper")
@EnableScheduling
public class CanteenApplication {

    public static void main(String[] args) {
        SpringApplication.run(CanteenApplication.class, args);
        System.out.println("""
                ============================================================
                  canteen-trace 已启动：http://127.0.0.1:8080/api
                  演示账号与初始口令请以部署文档为准，生产环境务必删除演示数据
                ============================================================""");
    }
}
