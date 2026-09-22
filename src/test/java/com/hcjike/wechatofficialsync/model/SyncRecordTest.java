package com.hcjike.wechatofficialsync.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.hcjike.wechatofficialsync.util.SensitiveText;
import org.junit.jupiter.api.Test;

/**
 * {@link SyncRecord} 的行为验证：失败原因会持久化进任务记录、展示在文章列表，并可经 MCP 工具返回给
 * 调用方，因此落库前必须做凭据脱敏。
 */
class SyncRecordTest {

    @Test
    void failedMasksCredentialsInMessage() {
        SyncRecord record = SyncRecord.failed("403 Forbidden from POST "
            + "https://api.weixin.qq.com/cgi-bin/draft/add?access_token=TOKEN-VALUE");

        assertThat(record.getStatus()).isEqualTo(SyncRecord.STATUS_FAILED);
        assertThat(record.getMessage()).contains("access_token=" + SensitiveText.MASK)
            .doesNotContain("TOKEN-VALUE");
        assertThat(record.getTime()).isNotBlank();
    }

    @Test
    void failedFallsBackToGenericMessageWhenBlank() {
        assertThat(SyncRecord.failed(null).getMessage()).isEqualTo("同步失败，请查看服务端日志");
        assertThat(SyncRecord.failed("   ").getMessage()).isEqualTo("同步失败，请查看服务端日志");
    }

    @Test
    void successKeepsMediaIdWithoutMasking() {
        // media_id 是标识而非凭据，成功记录里保留原值（排查时需要）
        SyncRecord record = SyncRecord.success("MEDIA-ID-1");

        assertThat(record.getStatus()).isEqualTo(SyncRecord.STATUS_SUCCESS);
        assertThat(record.getMediaId()).isEqualTo("MEDIA-ID-1");
    }
}
