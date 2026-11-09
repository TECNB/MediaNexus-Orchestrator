package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.config.WeComProperties;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Operations;
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

    @GetMapping("/api/v1/admin/wecom-ingest/operations")
    @Operation(summary = "分页查看当前管理员的微信操作记录")
    public ApiResponse<Operations> list(@RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(operations.listWeCom(page, pageSize));
    }
}
