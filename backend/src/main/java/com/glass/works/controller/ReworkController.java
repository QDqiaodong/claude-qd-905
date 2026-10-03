package com.glass.works.controller;

import com.glass.works.common.ApiResponse;
import com.glass.works.dto.ReworkDtos.DispositionRequest;
import com.glass.works.dto.ReworkDtos.ReinspectRequest;
import com.glass.works.dto.Views.ReworkBatchVO;
import com.glass.works.dto.Views.ReworkTimelineVO;
import com.glass.works.service.ReworkService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 返修质量闭环：返修批次工作区（看板、时间线、处置、复检） */
@RestController
@RequestMapping("/api/rework")
public class ReworkController {

    private final ReworkService service;

    public ReworkController(ReworkService service) {
        this.service = service;
    }

    /** 看板：按状态查看待办（隔离中 / 处理中 / 待复检 / 已放行） */
    @GetMapping("/batches")
    public ApiResponse<List<ReworkBatchVO>> board(@RequestParam(required = false) String status) {
        return ApiResponse.success(service.board(status));
    }

    /** 批次时间线：来源工序、归并解释、处置进度、复检与冲突全程审计 */
    @GetMapping("/batches/{id}/timeline")
    public ApiResponse<ReworkTimelineVO> timeline(@PathVariable Long id) {
        return ApiResponse.success(service.timeline(id));
    }

    /** 隔离中 → 处理中（开工，登记处置方式） */
    @PostMapping("/batches/{id}/start")
    public ApiResponse<ReworkBatchVO> start(@PathVariable Long id,
                                            @RequestBody DispositionRequest request) {
        service.start(id, request);
        return ApiResponse.success("已开工处置", service.timeline(id).getBatch());
    }

    /** 处理中：登记处置进度；finish=true 流转待复检 */
    @PostMapping("/batches/{id}/dispose")
    public ApiResponse<ReworkBatchVO> dispose(@PathVariable Long id,
                                              @RequestBody DispositionRequest request) {
        service.dispose(id, request);
        return ApiResponse.success(Boolean.TRUE.equals(request.getFinish())
                ? "处置完成，进入待复检" : "处置进度已登记", service.timeline(id).getBatch());
    }

    /**
     * 待复检：提交复检结论。合格 → 已放行；返修 → 回隔离。
     * 并发相反结论返回 409（见 GlobalExceptionHandler），落败方见 CONFLICT 事件。
     */
    @PostMapping("/batches/{id}/reinspect")
    public ApiResponse<ReworkBatchVO> reinspect(@PathVariable Long id,
                                                @RequestBody ReinspectRequest request) {
        boolean pass = "PASS".equals(request.getVerdict());
        service.reinspect(id, request);
        return ApiResponse.success(pass ? "复检合格，批次已放行" : "复检再判返修，批次已回隔离",
                service.timeline(id).getBatch());
    }

    /** 深加工 / 检验 / 成品架共享的开放返修批次（状态标签） */
    @GetMapping("/open")
    public ApiResponse<List<ReworkBatchVO>> open() {
        return ApiResponse.success(service.openBatches());
    }
}
