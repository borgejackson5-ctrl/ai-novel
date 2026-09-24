package com.ainovel.module.oss.service;

import com.ainovel.module.oss.service.impl.OssServiceImpl;
import com.aliyun.oss.OSS;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.module.oss.config.OssProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * OSS 服务单测：覆盖 deleteByUrl 的 url→key 解析与删除幂等/异常路径。
 * OSS 客户端为懒加载，测试用反射注入 mock，绕开真实的 SDK 建连。
 */
@ExtendWith(MockitoExtension.class)
class OssServiceTest {

    @Mock
    private OSS ossClient;

    private OssService ossService;

    @BeforeEach
    void setUp() {
        OssProperties props = new OssProperties();
        props.setBucket("my-bucket");
        props.setEndpoint("oss-cn-beijing.aliyuncs.com");
        props.setAccessKeyId("ak");
        props.setAccessKeySecret("sk");
        props.setPublicBaseUrl("https://cdn.example.com");

        // props 走构造器；client 是内部懒加载缓存（不是 Spring 注入的依赖），仍用反射塞
        ossService = new OssServiceImpl(props);
        ReflectionTestUtils.setField(ossService, "client", ossClient);
    }

    @Test
    @DisplayName("按 URL 删除 → 解析出 key 并 deleteObject")
    void deleteByUrl_parsesKey() {
        ossService.deleteByUrl("https://cdn.example.com/ai-novel/covers/abc.png");

        verify(ossClient).deleteObject("my-bucket", "ai-novel/covers/abc.png");
    }

    @Test
    @DisplayName("空 URL → 静默跳过（不调 OSS）")
    void deleteByUrl_blankUrl_skips() {
        ossService.deleteByUrl(null);
        ossService.deleteByUrl("   ");

        verify(ossClient, never()).deleteObject(anyString(), anyString());
    }

    @Test
    @DisplayName("删除失败 → 抛 SYSTEM_ERROR")
    void deleteByUrl_failure_throws() {
        doThrow(new RuntimeException("oss down")).when(ossClient).deleteObject(anyString(), anyString());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ossService.deleteByUrl("https://cdn.example.com/ai-novel/covers/abc.png"));

        assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode());
    }
}
