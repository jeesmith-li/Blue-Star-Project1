package com.example.meituan.web.dto;

import com.example.meituan.web.entity.User;
import java.time.LocalDateTime;

public class UserDTO {
    private Long id;
    private String username;
    private User.UserRole role;
    private User.UserType userType;
    private User.AccountStatus status;
    private LocalDateTime subscriptionStartTime;
    private LocalDateTime subscriptionEndTime;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
    private Integer shopCount;

    public static UserDTO fromEntity(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setRole(user.getRole());
        dto.setUserType(user.getUserType());
        dto.setStatus(user.getStatus());
        dto.setSubscriptionStartTime(user.getSubscriptionStartTime());
        dto.setSubscriptionEndTime(user.getSubscriptionEndTime());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setLastLoginAt(user.getLastLoginAt());
        dto.setShopCount(user.getShopCount());
        return dto;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public User.UserRole getRole() {
        return role;
    }

    public void setRole(User.UserRole role) {
        this.role = role;
    }

    public User.UserType getUserType() {
        return userType;
    }

    public void setUserType(User.UserType userType) {
        this.userType = userType;
    }

    public User.AccountStatus getStatus() {
        return status;
    }

    public void setStatus(User.AccountStatus status) {
        this.status = status;
    }

    public LocalDateTime getSubscriptionStartTime() {
        return subscriptionStartTime;
    }

    public void setSubscriptionStartTime(LocalDateTime subscriptionStartTime) {
        this.subscriptionStartTime = subscriptionStartTime;
    }

    public LocalDateTime getSubscriptionEndTime() {
        return subscriptionEndTime;
    }

    public void setSubscriptionEndTime(LocalDateTime subscriptionEndTime) {
        this.subscriptionEndTime = subscriptionEndTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public Integer getShopCount() {
        return shopCount;
    }

    public void setShopCount(Integer shopCount) {
        this.shopCount = shopCount;
    }
}
