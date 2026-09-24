package com.ainovel.module.rank.controller;

import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.common.enums.RankTypeEnum;
import com.ainovel.module.novel.domain.vo.NovelVO;
import com.ainovel.module.rank.service.RankService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 榜单接口
 */
@Tag(name = "榜单")
@RestController
@RequestMapping("/rank")
@RequiredArgsConstructor
public class RankController {

    private final RankService rankService;

    @Operation(summary = "热门榜 Top20")
    @GetMapping("/hot")
    public ResponseDTO<List<NovelVO>> hot() {
        return ResponseDTO.ok(rankService.hotRank());
    }

    @Operation(summary = "榜单 Top20（hot 热门 / new 新书 / finished 完本 / collect 收藏）")
    @GetMapping("/{type}")
    public ResponseDTO<List<NovelVO>> byType(@PathVariable String type) {
        return ResponseDTO.ok(rankService.rank(RankTypeEnum.of(type)));
    }
}
