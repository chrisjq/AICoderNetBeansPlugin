package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class AcpEnumsTest {

    @Test
    void acpMethodsRoundTrip() {
        for (AcpMethodEnum constant : AcpMethodEnum.values()) {
            assertEquals(constant, AcpMethodEnum.fromWire(constant.wireValue()));
        }
    }

    @Test
    void acpMethodFromWireReturnsNullForUnknown() {
        assertNull(AcpMethodEnum.fromWire(null));
        assertNull(AcpMethodEnum.fromWire("no_such_method"));
    }

    @Test
    void acpMethodWireStringsAreExact() {
        assertEquals("session/update", AcpMethodEnum.SESSION_UPDATE.wireValue());
        assertEquals("session/request_permission", AcpMethodEnum.SESSION_REQUEST_PERMISSION.wireValue());
        assertEquals("fs/write_text_file", AcpMethodEnum.FS_WRITE_TEXT_FILE.wireValue());
        assertEquals("session/set_config_option", AcpMethodEnum.SESSION_SET_CONFIG_OPTION.wireValue());
    }

    @Test
    void acpSessionUpdateRoundTrip() {
        for (AcpSessionUpdateEnum constant : AcpSessionUpdateEnum.values()) {
            assertEquals(constant, AcpSessionUpdateEnum.fromWire(constant.wireValue()));
        }
    }

    @Test
    void acpSessionUpdateFromWireReturnsNullForUnknown() {
        assertNull(AcpSessionUpdateEnum.fromWire(null));
        assertNull(AcpSessionUpdateEnum.fromWire("no_such_update"));
    }

    @Test
    void acpSessionUpdateWireStringsAreExact() {
        assertEquals("agent_thought_chunk", AcpSessionUpdateEnum.AGENT_THOUGHT_CHUNK.wireValue());
    }

    @Test
    void acpStopReasonRoundTrip() {
        for (AcpStopReasonEnum constant : AcpStopReasonEnum.values()) {
            assertEquals(constant, AcpStopReasonEnum.fromWire(constant.wireValue()));
        }
    }

    @Test
    void acpStopReasonFromWireReturnsNullForUnknown() {
        assertNull(AcpStopReasonEnum.fromWire(null));
        assertNull(AcpStopReasonEnum.fromWire("no_such_reason"));
    }

    @Test
    void acpStopReasonWireStringsAreExact() {
        assertEquals("max_turn_requests", AcpStopReasonEnum.MAX_TURN_REQUESTS.wireValue());
    }

    @Test
    void acpToolCallStatusRoundTrip() {
        for (AcpToolCallStatusEnum constant : AcpToolCallStatusEnum.values()) {
            assertEquals(constant, AcpToolCallStatusEnum.fromWire(constant.wireValue()));
        }
    }

    @Test
    void acpToolCallStatusFromWireReturnsNullForUnknown() {
        assertNull(AcpToolCallStatusEnum.fromWire(null));
        assertNull(AcpToolCallStatusEnum.fromWire("no_such_status"));
    }

    @Test
    void acpPermissionKindRoundTrip() {
        for (AcpPermissionKindEnum constant : AcpPermissionKindEnum.values()) {
            assertEquals(constant, AcpPermissionKindEnum.fromWire(constant.wireValue()));
        }
    }

    @Test
    void acpPermissionKindFromWireReturnsNullForUnknown() {
        assertNull(AcpPermissionKindEnum.fromWire(null));
        assertNull(AcpPermissionKindEnum.fromWire("no_such_kind"));
    }

    @Test
    void acpErrorCodeRoundTrip() {
        for (AcpErrorCodeEnum constant : AcpErrorCodeEnum.values()) {
            assertEquals(constant, AcpErrorCodeEnum.fromCode(constant.code()));
        }
    }

    @Test
    void acpErrorCodeFromCodeReturnsNullForUnknown() {
        assertNull(AcpErrorCodeEnum.fromCode(0));
        assertNull(AcpErrorCodeEnum.fromCode(9999));
    }

    @Test
    void acpErrorCodeRequestCancelledIsExact() {
        assertEquals(-32800, AcpErrorCodeEnum.REQUEST_CANCELLED.code());
    }

    @Test
    void acpPermissionOptionAccessorsRoundTrip() {
        AcpPermissionOption opt = new AcpPermissionOption("once", "Once", AcpPermissionKindEnum.ALLOW_ONCE);

        assertEquals("once", opt.optionId());
        assertEquals("Once", opt.name());
        assertEquals(AcpPermissionKindEnum.ALLOW_ONCE, opt.kind());

        opt.setOptionId("always");
        opt.setName("Always");
        opt.setKind(AcpPermissionKindEnum.ALLOW_ALWAYS);

        assertEquals("always", opt.optionId());
        assertEquals("Always", opt.name());
        assertEquals(AcpPermissionKindEnum.ALLOW_ALWAYS, opt.kind());
    }
}
