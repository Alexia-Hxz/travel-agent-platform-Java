package com.huxizhen.travel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一次旅行。
 *
 * <p>{@code userId} 可空是**功能**：账号功能上线前创建的行程没有主人，第一个登录的账号会
 * 认领它们（{@code AuthService#claimUnownedTrips}），之后每趟旅行都有主且只对本人可见。
 */
@TableName("trips")
public class Trip {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String title;
    private String destination;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer budget;
    private Integer adultMaleCount;
    private Integer adultFemaleCount;
    private Integer childMaleCount;
    private Integer childFemaleCount;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public Integer getBudget() {
        return budget;
    }

    public void setBudget(Integer budget) {
        this.budget = budget;
    }

    public Integer getAdultMaleCount() {
        return adultMaleCount;
    }

    public void setAdultMaleCount(Integer adultMaleCount) {
        this.adultMaleCount = adultMaleCount;
    }

    public Integer getAdultFemaleCount() {
        return adultFemaleCount;
    }

    public void setAdultFemaleCount(Integer adultFemaleCount) {
        this.adultFemaleCount = adultFemaleCount;
    }

    public Integer getChildMaleCount() {
        return childMaleCount;
    }

    public void setChildMaleCount(Integer childMaleCount) {
        this.childMaleCount = childMaleCount;
    }

    public Integer getChildFemaleCount() {
        return childFemaleCount;
    }

    public void setChildFemaleCount(Integer childFemaleCount) {
        this.childFemaleCount = childFemaleCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
