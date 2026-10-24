package com.huxizhen.travel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 行程调整草案。
 *
 * <p>两阶段写入的核心：AI 给出的改动先落成 {@code pending} 草案，用户逐条确认后才写进行程表。
 * {@code proposalJson} 里除了 operations 还留着当次的预算、冲突、逐日预览与总结，
 * 这样从列表里重新打开一个旧草案时，看到的是**当时**的样子。
 */
@TableName("itinerary_adjustments")
public class ItineraryAdjustment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tripId;
    private String instruction;
    private String proposalJson;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime appliedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }

    public String getProposalJson() {
        return proposalJson;
    }

    public void setProposalJson(String proposalJson) {
        this.proposalJson = proposalJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(LocalDateTime appliedAt) {
        this.appliedAt = appliedAt;
    }
}
