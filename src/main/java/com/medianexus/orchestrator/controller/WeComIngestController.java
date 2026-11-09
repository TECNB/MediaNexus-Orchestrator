package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.config.WeComProperties;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.*;
import jakarta.validation.Valid;
import com.medianexus.orchestrator.integration.wecom.WeComCallbackCipher;
import com.medianexus.orchestrator.service.BgmWishlistOperationService;
import com.medianexus.orchestrator.service.WeComIngestChat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "微信自动化")
public class WeComIngestController {
    private static final String CALLBACK = "/api/v1/wecom/ingest/callback";
    private final WeComProperties properties;
    private final WeComIngestChat chat;
    private final BgmWishlistOperationService operations;

    public WeComIngestController(WeComProperties properties, WeComIngestChat chat, BgmWishlistOperationService operations) {
        this.properties = properties; this.chat = chat; this.operations = operations;
    }

    @GetMapping(value = CALLBACK, produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "校验企业微信回调 URL，返回解密原文")
    public ResponseEntity<String> verify(HttpServletRequest request) {
        if (!chat.configured()) return ResponseEntity.notFound().build();
        try {
            byte[] echo = decrypt(request, request.getParameter("echostr"));
            return ResponseEntity.ok(new String(echo, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.status(403).build();
        }
    }

    @PostMapping(value = CALLBACK, produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "验签解密后持久受理授权成员的文本消息，异步处理")
    public ResponseEntity<String> receive(HttpServletRequest request) throws IOException {
        if (!chat.configured()) return ResponseEntity.notFound().build();
        byte[] body = request.getInputStream().readNBytes(16385);
        if (body.length > 16384) return ResponseEntity.status(413).build();
        java.util.Map<String, String> event;
        try {
            String encrypted = WeComCallbackCipher.fields(body).get("Encrypt");
            event = WeComCallbackCipher.fields(decrypt(request, encrypted));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.status(403).build();
        }
        if ("text".equals(event.get("MsgType")) && properties.getCorpId().trim().equals(event.get("ToUserName"))
                && properties.getAgentId().toString().equals(event.get("AgentID"))) {
            chat.accept(event.get("FromUserName"), event.get("MsgId"), event.get("Content"));
        }
        return ResponseEntity.ok("success");
    }

    private byte[] decrypt(HttpServletRequest request, String encrypted) {
        return WeComCallbackCipher.decrypt(request.getParameter("msg_signature"), request.getParameter("timestamp"),
                request.getParameter("nonce"), encrypted, properties.getIngestToken(), properties.getIngestAesKey(),
                properties.getCorpId().trim(), Instant.now());
    }

    private static final String RECORDS = "/api/v1/wecom-ingest/operations";

    @GetMapping({RECORDS, "/api/v1/admin/wecom-ingest/operations"})
    @Operation(summary = "分页查看微信操作记录；仅管理员可选择查看全部")
    public ApiResponse<Operations> list(@RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int pageSize,
                                        @RequestParam(defaultValue = "false") boolean all) {
        return ApiResponse.success(operations.listWeCom(page, pageSize, all));
    }

    @GetMapping(RECORDS + "/{id}")
    @Operation(summary = "查看微信操作详情；跨账号仅限管理员显式选择全部时")
    public ApiResponse<com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Operation> get(@PathVariable String id,
            @RequestParam(defaultValue = "false") boolean all) {
        return ApiResponse.success(operations.getWeCom(id, all));
    }

    @PostMapping(RECORDS + "/{id}/confirm")
    @Operation(summary = "确认本人微信操作的具体发布")
    public ApiResponse<com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Operation> confirm(
            @PathVariable String id, @Valid @RequestBody CandidateInput input) {
        operations.requireWeComRecord(id);
        return ApiResponse.success(operations.confirm(id, input));
    }

    @PostMapping(RECORDS + "/{id}/supply")
    @Operation(summary = "为本人微信操作补充来源")
    public ApiResponse<com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Operation> supply(
            @PathVariable String id, @Valid @RequestBody SupplyInput input) {
        operations.requireWeComRecord(id);
        return ApiResponse.success(operations.supply(id, input));
    }

    @PostMapping(RECORDS + "/{id}/search")
    @Operation(summary = "重新搜索本人微信操作的发布")
    public ApiResponse<com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Operation> retry(
            @PathVariable String id, @Valid @RequestBody SubjectInput input) {
        operations.requireWeComRecord(id);
        return ApiResponse.success(operations.retry(id, input.subjectId()));
    }

    @PostMapping(RECORDS + "/{id}/defer")
    @Operation(summary = "暂缓本人微信操作的内容例外")
    public ApiResponse<com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Operation> defer(
            @PathVariable String id, @Valid @RequestBody SubjectInput input) {
        operations.requireWeComRecord(id);
        return ApiResponse.success(operations.defer(id, input.subjectId()));
    }
}
