package com.example.meituan.web.service;

import com.example.meituan.web.entity.Shop;
import com.example.meituan.web.entity.User;
import com.example.meituan.web.repository.ShopRepository;
import com.example.meituan.web.repository.ReviewRepository;
import com.example.meituan.web.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ShopService {

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private UserRepository userRepository;

    public List<Shop> getShopsForUser(User user) {
        if (user.getRole() == User.UserRole.ADMIN) {
            return shopRepository.findAllByOrderBySortOrderAscIdAsc();
        } else {
            return shopRepository.findByUserIdOrderBySortOrderAscIdAsc(user.getId());
        }
    }

    public List<Shop> getAllShops() {
        return shopRepository.findAllByOrderBySortOrderAscIdAsc();
    }

    public Shop createShop(Shop shop) {
        // 新绑定的店铺排在当前顺序的最后
        int max = 0;
        for (Shop s : shopRepository.findAll()) {
            if (s.getSortOrder() != null && s.getSortOrder() > max) {
                max = s.getSortOrder();
            }
        }
        shop.setSortOrder(max + 1);
        Shop saved = shopRepository.save(shop);

        // 绑定成功后累加用户的店铺数
        if (shop.getUserId() != null) {
            userRepository.findById(shop.getUserId()).ifPresent(user -> {
                user.setShopCount((user.getShopCount() != null ? user.getShopCount() : 0) + 1);
                userRepository.save(user);
            });
        }
        return saved;
    }

    /**
     * 按传入的店铺ID顺序重排执行顺序（序号从1开始）
     * @return false 表示有店铺不存在或无权操作
     */
    public boolean reorderShops(User user, List<Long> ids) {
        List<Shop> shops = new java.util.ArrayList<>();
        for (Long id : ids) {
            Shop shop = shopRepository.findById(id).orElse(null);
            if (shop == null) {
                return false;
            }
            if (user.getRole() != User.UserRole.ADMIN && !shop.getUserId().equals(user.getId())) {
                return false;
            }
            shops.add(shop);
        }
        for (int i = 0; i < shops.size(); i++) {
            shops.get(i).setSortOrder(i + 1);
        }
        shopRepository.saveAll(shops);
        return true;
    }

    public Shop getShopById(Long id) {
        return shopRepository.findById(id).orElse(null);
    }

    public Shop updateShop(Shop shop) {
        return shopRepository.save(shop);
    }

    /**
     * 安全删除店铺
     * 如果存在关联的评价数据，则不允许删除
     *
     * @param id 店铺ID
     * @return true 删除成功，false 存在关联数据无法删除
     */
    public boolean deleteShop(Long id) {
        // 检查是否存在关联的评价数据
        long reviewCount = reviewRepository.countByShopId(id);
        if (reviewCount > 0) {
            return false;
        }

        // 没有关联数据，可以安全删除
        shopRepository.deleteById(id);
        return true;
    }
}
