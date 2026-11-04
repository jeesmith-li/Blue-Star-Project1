package com.example.meituan.web.service;

import com.example.meituan.web.entity.Shop;
import com.example.meituan.web.entity.User;
import com.example.meituan.web.repository.ShopRepository;
import com.example.meituan.web.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 初始化店铺测试数据
 */
@Component
@Order(2)
public class ShopDataInitializer implements CommandLineRunner {

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private UserRepository userRepository;

    @Override
    public void run(String... args) throws Exception {
        // Only initialize if no shops exist
        if (shopRepository.count() == 0) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            String currentTime = LocalDateTime.now().format(formatter);

            // Get users
            User admin = userRepository.findByUsername("admin").orElse(null);
            User zhangsan = userRepository.findByUsername("zhangsan").orElse(null);
            User lisi = userRepository.findByUsername("lisi").orElse(null);

            // Create shop for zhangsan (normal user)
            if (zhangsan != null) {
                Shop shop1 = new Shop();
                shop1.setName("张三餐饮店");
                shop1.setMeituanAccount("mt_zhangsan_001");
                shop1.setShopType("餐饮");
                shop1.setUserId(zhangsan.getId());
                shop1.setStatus("active");
                shop1.setLastSyncTime(currentTime);
                shop1.setTodayBadReviews(12);
                shop1.setUnrepliedCount(8);
                shopRepository.save(shop1);
                System.out.println("✓ 创建店铺: " + shop1.getName());
            }

            // Create shops for lisi (service provider - multiple shops)
            if (lisi != null) {
                Shop shop2 = new Shop();
                shop2.setName("李四北京店");
                shop2.setMeituanAccount("mt_lisi_bj");
                shop2.setShopType("餐饮");
                shop2.setUserId(lisi.getId());
                shop2.setStatus("active");
                shop2.setLastSyncTime(currentTime);
                shop2.setTodayBadReviews(5);
                shop2.setUnrepliedCount(3);
                shopRepository.save(shop2);
                System.out.println("✓ 创建店铺: " + shop2.getName());

                Shop shop3 = new Shop();
                shop3.setName("李四上海店");
                shop3.setMeituanAccount("mt_lisi_sh");
                shop3.setShopType("餐饮");
                shop3.setUserId(lisi.getId());
                shop3.setStatus("active");
                shop3.setLastSyncTime(currentTime);
                shop3.setTodayBadReviews(8);
                shop3.setUnrepliedCount(4);
                shopRepository.save(shop3);
                System.out.println("✓ 创建店铺: " + shop3.getName());

                Shop shop4 = new Shop();
                shop4.setName("李四广州店");
                shop4.setMeituanAccount("mt_lisi_gz");
                shop4.setShopType("餐饮");
                shop4.setUserId(lisi.getId());
                shop4.setStatus("active");
                shop4.setLastSyncTime(currentTime);
                shop4.setTodayBadReviews(6);
                shop4.setUnrepliedCount(2);
                shopRepository.save(shop4);
                System.out.println("✓ 创建店铺: " + shop4.getName());

                Shop shop5 = new Shop();
                shop5.setName("李四深圳店");
                shop5.setMeituanAccount("mt_lisi_sz");
                shop5.setShopType("餐饮");
                shop5.setUserId(lisi.getId());
                shop5.setStatus("active");
                shop5.setLastSyncTime(currentTime);
                shop5.setTodayBadReviews(10);
                shop5.setUnrepliedCount(5);
                shopRepository.save(shop5);
                System.out.println("✓ 创建店铺: " + shop5.getName());

                Shop shop6 = new Shop();
                shop6.setName("李四杭州店");
                shop6.setMeituanAccount("mt_lisi_hz");
                shop6.setShopType("餐饮");
                shop6.setUserId(lisi.getId());
                shop6.setStatus("active");
                shop6.setLastSyncTime(currentTime);
                shop6.setTodayBadReviews(7);
                shop6.setUnrepliedCount(6);
                shopRepository.save(shop6);
                System.out.println("✓ 创建店铺: " + shop6.getName());
            }
        }
    }
}
