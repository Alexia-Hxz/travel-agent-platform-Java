package com.huxizhen.travel.dto.place;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 收藏地点的部分更新。
 *
 * <p>PATCH 的语义要求区分"没传这个字段"和"显式传了 null 想清空"：
 * Python 用 {@code model_dump(exclude_unset=True)}，Java 没有等价物，所以每个字段配一个
 * presence 标记——只有 Jackson 真正调用过的 setter 才把标记置位。
 *
 * <p>这个 DTO 存在的原因：推荐卡片允许用户改"住几晚 / 吃几次"和随之变化的价格，
 * 但地点一旦收藏就没有别的入口能改，只能删了重加。
 */
public class PlaceUpdate {

    @Size(min = 1, max = 160)
    private String name;
    private boolean namePresent;

    private PlaceType type;
    private boolean typePresent;

    @Size(min = 1, max = 300)
    private String address;
    private boolean addressPresent;

    @Size(max = 500)
    private String imageUrl;
    private boolean imageUrlPresent;

    @DecimalMin("0.0")
    @DecimalMax("5.0")
    private Double rating;
    private boolean ratingPresent;

    @Min(0)
    @Max(5)
    private Integer priceLevel;
    private boolean priceLevelPresent;

    @Size(max = 300)
    private String openingHours;
    private boolean openingHoursPresent;

    @Size(max = 600)
    private String note;
    private boolean notePresent;

    @Min(0)
    private Integer estimatedCost;
    private boolean estimatedCostPresent;

    @Min(1)
    @Max(99)
    private Integer quantity;
    private boolean quantityPresent;

    private LocalDate preferredDate;
    private boolean preferredDatePresent;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.namePresent = true;
    }

    public boolean isNamePresent() {
        return namePresent;
    }

    public PlaceType getType() {
        return type;
    }

    public void setType(PlaceType type) {
        this.type = type;
        this.typePresent = true;
    }

    public boolean isTypePresent() {
        return typePresent;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
        this.addressPresent = true;
    }

    public boolean isAddressPresent() {
        return addressPresent;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
        this.imageUrlPresent = true;
    }

    public boolean isImageUrlPresent() {
        return imageUrlPresent;
    }

    public Double getRating() {
        return rating;
    }

    public void setRating(Double rating) {
        this.rating = rating;
        this.ratingPresent = true;
    }

    public boolean isRatingPresent() {
        return ratingPresent;
    }

    public Integer getPriceLevel() {
        return priceLevel;
    }

    public void setPriceLevel(Integer priceLevel) {
        this.priceLevel = priceLevel;
        this.priceLevelPresent = true;
    }

    public boolean isPriceLevelPresent() {
        return priceLevelPresent;
    }

    public String getOpeningHours() {
        return openingHours;
    }

    public void setOpeningHours(String openingHours) {
        this.openingHours = openingHours;
        this.openingHoursPresent = true;
    }

    public boolean isOpeningHoursPresent() {
        return openingHoursPresent;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
        this.notePresent = true;
    }

    public boolean isNotePresent() {
        return notePresent;
    }

    public Integer getEstimatedCost() {
        return estimatedCost;
    }

    public void setEstimatedCost(Integer estimatedCost) {
        this.estimatedCost = estimatedCost;
        this.estimatedCostPresent = true;
    }

    public boolean isEstimatedCostPresent() {
        return estimatedCostPresent;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
        this.quantityPresent = true;
    }

    public boolean isQuantityPresent() {
        return quantityPresent;
    }

    public LocalDate getPreferredDate() {
        return preferredDate;
    }

    public void setPreferredDate(LocalDate preferredDate) {
        this.preferredDate = preferredDate;
        this.preferredDatePresent = true;
    }

    public boolean isPreferredDatePresent() {
        return preferredDatePresent;
    }
}
